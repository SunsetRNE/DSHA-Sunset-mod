package com.deepseekharness.app.backup;

import android.system.*;
import android.os.ParcelFileDescriptor;
import com.deepseekharness.app.util.LinuxOpenFlags;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** 不使用 root/ADB，不把 EACCES 当成不存在；描述符打开后再次检查类型、设备和 inode。 */
public class AndroidBackupFileSystem implements BackupFileSystem {
  private static final int O_PATH = 0x200000; // Linux UAPI；仅持有目录，不要求列出 /data 等父目录。
  // NDK r26 asm-generic/fcntl.h: 02000000；内核早已支持，Android Java 字段到 API 27 才公开。
  private static final int O_CLOEXEC = 0x80000;

  private static FileDescriptor anchored(File file, int flags, int mode)
      throws IOException, ErrnoException {
    File absolute = file.getAbsoluteFile();
    String path = absolute.getPath();
    if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/.."))
      throw new IOException("UNSAFE_HOST_PATH");
    if (path.equals("/")) return Os.open("/", flags, mode);
    String[] parts = path.substring(1).split("/");
    FileDescriptor directory = Os.open("/", O_PATH | O_CLOEXEC, 0);
    try {
      for (int i = 0; i < parts.length; i++) {
        if (parts[i].isEmpty() || parts[i].equals(".")) throw new IOException("UNSAFE_HOST_PATH");
        FileDescriptor next;
        try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(directory)) {
          next =
              Os.open(
                  "/proc/self/fd/" + handle.getFd() + "/" + parts[i],
                  i == parts.length - 1 ? flags : O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW,
                  i == parts.length - 1 ? mode : 0);
        }
        if (i < parts.length - 1 && !OsConstants.S_ISDIR(Os.fstat(next).st_mode)) {
          Os.close(next);
          throw new IOException("PARENT_LINK");
        }
        Os.close(directory);
        directory = next;
      }
      FileDescriptor result = directory;
      directory = null;
      return result;
    } finally {
      if (directory != null) Os.close(directory);
    }
  }

  static IOException failure(ErrnoException error) {
    return new IOException(
        error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM
            ? "PERMISSION_DENIED"
            : error.errno == OsConstants.ENOSPC ? "NO_SPACE" : "FILESYSTEM_" + error.errno,
        error);
  }

  /** Android 的 FileInput/OutputStream(FileDescriptor) 不拥有传入的描述符。明确转交给自动关闭流。 */
  static ParcelFileDescriptor owned(File file, int flags, int mode)
      throws IOException, ErrnoException {
    return duplicateOwned(anchored(file, flags, mode));
  }

  private static ParcelFileDescriptor duplicateOwned(FileDescriptor raw)
      throws IOException, ErrnoException {
    try {
      return ParcelFileDescriptor.dup(raw);
    } finally {
      Os.close(raw);
    }
  }

  static Node node(StructStat stat) {
    String type =
        OsConstants.S_ISREG(stat.st_mode)
            ? "FILE"
            : OsConstants.S_ISDIR(stat.st_mode)
                ? "DIRECTORY"
                : OsConstants.S_ISLNK(stat.st_mode) ? "LINK" : "SPECIAL";
    return new Node(
        type,
        stat.st_dev + ":" + stat.st_ino,
        stat.st_size,
        stat.st_mtime,
        stat.st_dev,
        stat.st_mode & 0777,
        stat.st_blocks >= 0 && stat.st_blocks <= Long.MAX_VALUE / 512 ? stat.st_blocks * 512 : -1,
        stat.st_nlink);
  }

  @Override
  public Node stat(File file) throws IOException {
    try {
      return node(Os.lstat(file.getPath()));
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT) return new Node("MISSING", "", 0, 0, 0, 0);
      throw failure(error);
    }
  }

  private interface AtParent<T> {
    T run(String path) throws IOException, ErrnoException;
  }

  private static <T> T atParent(File file, AtParent<T> action) throws IOException, ErrnoException {
    FileDescriptor parent =
        anchored(
            file.getAbsoluteFile().getParentFile(), O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
    try {
      if (!OsConstants.S_ISDIR(Os.fstat(parent).st_mode)) throw new IOException("PARENT_LINK");
      try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(parent)) {
        return action.run("/proc/self/fd/" + handle.getFd() + "/" + file.getName());
      }
    } finally {
      Os.close(parent);
    }
  }

  @Override
  public String readLink(File file) throws IOException {
    try {
      return atParent(file, Os::readlink);
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public List<String> list(File file) throws IOException {
    Node before = stat(file);
    if (!before.type.equals("DIRECTORY")) throw new IOException("DIRECTORY_TYPE");
    try {
      FileDescriptor fd =
          anchored(
              file,
              OsConstants.O_RDONLY
                  | LinuxOpenFlags.directoryFlag(OsConstants.O_NOFOLLOW)
                  | OsConstants.O_NONBLOCK
                  | O_CLOEXEC
                  | OsConstants.O_NOFOLLOW,
              0);
      try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(fd)) {
        if (!before.same(node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
        String[] names = directoryNames("/proc/self/fd/" + handle.getFd());
        if (!before.same(node(Os.fstat(fd))) || !before.same(stat(file)))
          throw new IOException("SOURCE_CHANGED");
        List<String> result = Arrays.asList(names);
        Collections.sort(result);
        return result;
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  private static String[] directoryNames(String path) throws IOException {
    String[] names = new File(path).list();
    if (names == null) throw new IOException("DIRECTORY_UNREADABLE");
    if (names.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
    return names;
  }

  @Override
  public InputStream read(File file, Node expected) throws IOException {
    if (!expected.type.equals("FILE")) throw new IOException("FILE_TYPE");
    try {
      ParcelFileDescriptor handle =
          // A regular file can become a FIFO after stat(). Never wait for a
          // writer before fstat can reject the opened descriptor's identity.
          owned(
              file,
              OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | O_CLOEXEC | OsConstants.O_NOFOLLOW,
              0);
      FileDescriptor fd = handle.getFileDescriptor();
      InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(handle);
      try {
        if (!expected.same(node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
      } catch (IOException | ErrnoException error) {
        try {
          stream.close();
        } catch (IOException closing) {
          error.addSuppressed(closing);
        }
        throw error;
      }
      return new FilterInputStream(stream) {
        boolean closed;

        @Override
        public void close() throws IOException {
          if (closed) return;
          closed = true;
          IOException error = null;
          try {
            if (!expected.same(node(Os.fstat(fd))) || !expected.same(stat(file)))
              error = new IOException("SOURCE_CHANGED");
          } catch (ErrnoException e) {
            error = failure(e);
          } finally {
            super.close();
          }
          if (error != null) throw error;
        }
      };
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public byte[] currentSnapshot(File file, int limit) throws IOException {
    if (limit < 1) throw new IOException("METADATA_LIMIT");
    try {
      ParcelFileDescriptor handle =
          owned(
              file,
              OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | O_CLOEXEC | OsConstants.O_NOFOLLOW,
              0);
      try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(handle);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        var stat = Os.fstat(handle.getFileDescriptor());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size > limit)
          throw new IOException("METADATA_LIMIT");
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > limit) throw new IOException("METADATA_LIMIT");
          out.write(buffer, 0, count);
        }
        return out.toByteArray();
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public OutputStream create(File file) throws IOException {
    try {
      ParcelFileDescriptor handle =
          owned(
              file,
              OsConstants.O_WRONLY
                  | OsConstants.O_CREAT
                  | OsConstants.O_EXCL
                  | O_CLOEXEC
                  | OsConstants.O_NOFOLLOW,
              0600);
      FileDescriptor fd = handle.getFileDescriptor();
      OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(handle);
      try {
        if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw new IOException("FILE_TYPE");
      } catch (IOException | ErrnoException error) {
        try {
          stream.close();
        } catch (IOException closing) {
          error.addSuppressed(closing);
        }
        throw error;
      }
      return new FilterOutputStream(stream) {
        boolean closed;

        @Override
        public void write(byte[] data, int off, int len) throws IOException {
          out.write(data, off, len);
        }

        @Override
        public void close() throws IOException {
          if (closed) return;
          closed = true;
          try {
            flush();
            Os.fsync(fd);
          } catch (ErrnoException e) {
            throw failure(e);
          } finally {
            super.close();
          }
        }
      };
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public void directory(File file) throws IOException {
    try {
      atParent(
          file,
          path -> {
            Os.mkdir(path, 0700);
            return null;
          });
      syncDirectory(file.getParentFile());
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public void move(File source, File target) throws IOException {
    Node a = stat(source.getParentFile()), b = stat(target.getParentFile());
    if (!a.type.equals("DIRECTORY")
        || !b.type.equals("DIRECTORY")
        || a.device != b.device
        || !stat(target).type.equals("MISSING")) throw new IOException("UNSAFE_MOVE");
    // 调用者持有维护屏障；两侧父目录描述符固定，不能在校验后沿被替换的链接改名。
    try {
      atParent(
          source,
          from ->
              atParent(
                  target,
                  to -> {
                    try {
                      Os.lstat(to);
                      throw new IOException("MOVE_TARGET_EXISTS");
                    } catch (ErrnoException missing) {
                      if (missing.errno != OsConstants.ENOENT) throw missing;
                    }
                    Os.rename(from, to);
                    return null;
                  }));
      syncDirectory(source.getParentFile());
      if (!source.getParentFile().equals(target.getParentFile()))
        syncDirectory(target.getParentFile());
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public void delete(File file) throws IOException {
    try {
      atParent(
          file,
          path -> {
            Os.remove(path);
            return null;
          });
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }

  @Override
  public void deleteChecked(File file, Node expected) throws IOException {
    try {
      atParent(
          file,
          path -> {
            if (!expected.same(node(Os.lstat(path)))) throw new IOException("SOURCE_CHANGED");
            Os.remove(path);
            return null;
          });
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  /** 仅遍历维护租约核准的缓存和已退役载荷；每层固定一个父目录描述符。 */
  final void removeStorageTree(
      File file,
      long device,
      Consumer<Node> visited,
      Consumer<Node> removed,
      BackupControl control,
      int depth)
      throws IOException {
    if (control != null) control.check();
    if (depth > BackupLimits.DEPTH) throw new IOException("STORAGE_DEPTH");
    File target = file.getAbsoluteFile();
    File parent = target.getParentFile();
    if (parent == null) throw new IOException("REFUSE_ROOT_DELETE");
    Node expected = stat(target);
    if (expected.type.equals("MISSING")) return;
    try (ParcelFileDescriptor held =
        owned(parent, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0)) {
      StructStat actual = Os.fstat(held.getFileDescriptor());
      if (!OsConstants.S_ISDIR(actual.st_mode)) throw new IOException("PARENT_LINK");
      if (actual.st_dev != device) throw new IOException("STORAGE_VOLUME_BOUNDARY");
      new StorageRemoval(device, visited, removed, control)
          .remove(target, expected, new RemovalDirectory(held, parent, node(actual)), depth);
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  private final class RemovalDirectory {
    final File file;
    final String fdPath;
    final Node identity;

    RemovalDirectory(ParcelFileDescriptor handle, File file, Node identity) {
      this.file = file;
      this.fdPath = "/proc/self/fd/" + handle.getFd();
      this.identity = identity;
    }

    void verifyLocation() throws IOException, ErrnoException {
      // An FD can outlive its old pathname. Stop if a held directory was moved or covered.
      Node named = stat(file);
      if (!named.type.equals("DIRECTORY")
          || !identity.key.equals(named.key)
          || identity.device != named.device
          || !file.getPath().equals(Os.readlink(fdPath))) throw new IOException("SOURCE_CHANGED");
    }
  }

  private final class StorageRemoval {
    final long device;
    final Consumer<Node> visited, removed;
    final BackupControl control;

    StorageRemoval(
        long device, Consumer<Node> visited, Consumer<Node> removed, BackupControl control) {
      this.device = device;
      this.visited = visited;
      this.removed = removed;
      this.control = control;
    }

    void remove(File file, Node expected, RemovalDirectory parent, int depth)
        throws IOException, ErrnoException {
      if (control != null) control.check();
      if (depth > BackupLimits.DEPTH) throw new IOException("STORAGE_DEPTH");
      if (expected.type.equals("MISSING")) return;
      if (expected.device != device) throw new IOException("STORAGE_VOLUME_BOUNDARY");
      String path = parent.fdPath + "/" + file.getName();
      parent.verifyLocation();
      if (!expected.same(node(Os.lstat(path)))) throw new IOException("SOURCE_CHANGED");
      visited.accept(expected);
      if (control != null) control.check();
      if (expected.type.equals("DIRECTORY")) expected = empty(file, path, expected, depth);
      parent.verifyLocation();
      if (!expected.same(node(Os.lstat(path)))) throw new IOException("SOURCE_CHANGED");
      if (control != null) control.check();
      Os.remove(path);
      removed.accept(expected);
    }

    Node empty(File file, String path, Node expected, int depth)
        throws IOException, ErrnoException {
      try (ParcelFileDescriptor held =
          duplicateOwned(Os.open(path, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0))) {
        var directory = new RemovalDirectory(held, file, expected);
        FileDescriptor fd = held.getFileDescriptor();
        StructStat opened = Os.fstat(fd);
        if (!expected.same(node(opened))) throw new IOException("SOURCE_CHANGED");
        if (opened.st_uid != android.os.Process.myUid())
          throw new IOException("RETIRED_DIRECTORY_NOT_OWNED");
        directory.verifyLocation();
        if ((opened.st_mode & 0700) != 0700)
          Os.chmod(directory.fdPath, (opened.st_mode & 0777) | 0700);
        Node before = node(Os.fstat(fd));
        String[] names = directoryNames(directory.fdPath);
        if (!before.same(node(Os.fstat(fd))) || !before.same(stat(file)))
          throw new IOException("SOURCE_CHANGED");
        Arrays.sort(names);
        for (String name : names) {
          if (control != null) control.check();
          if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0)
            throw new IOException("INVALID_CHILD");
          File child = new File(file, name);
          remove(child, stat(child), directory, depth + 1);
        }
        directory.verifyLocation();
        Node current = node(Os.fstat(fd));
        if (!current.same(stat(file))) throw new IOException("SOURCE_CHANGED");
        return current;
      }
    }
  }

  @Override
  public void prepareOwnedRemoval(File file) throws IOException {
    try {
      FileDescriptor fd = anchored(file, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
      try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(fd)) {
        StructStat node = Os.fstat(fd);
        if (!OsConstants.S_ISDIR(node.st_mode) || node.st_uid != android.os.Process.myUid())
          throw new IOException("RETIRED_DIRECTORY_NOT_OWNED");
        if ((node.st_mode & 0700) != 0700)
          Os.chmod("/proc/self/fd/" + handle.getFd(), (node.st_mode & 0777) | 0700);
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void mode(File file, int mode) throws IOException {
    Node before = stat(file);
    if (!before.type.equals("FILE") && !before.type.equals("DIRECTORY"))
      throw new IOException("MODE_TYPE");
    try {
      FileDescriptor fd =
          anchored(
              file,
              OsConstants.O_RDONLY | O_CLOEXEC | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK,
              0);
      try {
        if (!before.same(node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
        Os.fchmod(fd, mode & 0777);
        Os.fsync(fd);
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void symlink(String target, File link) throws IOException {
    try {
      atParent(
          link,
          path -> {
            Os.symlink(target, path);
            return null;
          });
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void syncDirectory(File file) throws IOException {
    try {
      FileDescriptor fd =
          anchored(
              file,
              OsConstants.O_RDONLY
                  | LinuxOpenFlags.directoryFlag(OsConstants.O_NOFOLLOW)
                  | OsConstants.O_NONBLOCK
                  | O_CLOEXEC
                  | OsConstants.O_NOFOLLOW,
              0);
      try {
        if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("DIRECTORY_TYPE");
        Os.fsync(fd);
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException e) {
      throw failure(e);
    }
  }
}
