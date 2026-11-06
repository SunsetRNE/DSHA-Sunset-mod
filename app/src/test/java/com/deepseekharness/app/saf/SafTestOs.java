package com.deepseekharness.app.saf;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared Robolectric fakes: a small real-file-backed {@link Os} and a lifecycle-correct
 * {@link ParcelFileDescriptor}.
 *
 * <p>关键点：PFD 的 close 必须让编号失效——这是 t10 P0-1（fdNumber 返回已关闭编号）能漏过
 * 旧测试的根因。任何在 close 之后读取 getFd()/readlink 的代码都会在这里直接失败。
 */
public final class SafTestOs {
  private SafTestOs() {}

  static final int O_PATH = 0x200000;
  static final Map<FileDescriptor, File> RAW_FILES = new IdentityHashMap<>();
  static final Map<FileDescriptor, Boolean> RAW_DIRS = new IdentityHashMap<>();
  static final Map<FileDescriptor, RandomAccessFile> RAW_HANDLES = new IdentityHashMap<>();
  static final Map<Integer, File> FD_FILES = new HashMap<>();
  static final Set<Integer> OPEN_FDS = new HashSet<>();
  static final List<String> RENAMES = new ArrayList<>();
  static final List<String> REMOVES = new ArrayList<>();
  static final List<String> MKDIRS = new ArrayList<>();
  static int nextFd = 500;
  static int closedPfd;

  static void reset() {
    RAW_FILES.clear();
    RAW_DIRS.clear();
    for (RandomAccessFile handle : RAW_HANDLES.values()) {
      try {
        handle.close();
      } catch (IOException ignored) {
      }
    }
    RAW_HANDLES.clear();
    FD_FILES.clear();
    OPEN_FDS.clear();
    RENAMES.clear();
    REMOVES.clear();
    MKDIRS.clear();
    nextFd = 500;
    closedPfd = 0;
  }

  static StructStat stat(File file, boolean directory) {
    int mode = (directory ? OsConstants.S_IFDIR : OsConstants.S_IFREG) | 0600;
    long links = 1;
    try {
      int unixMode = (Integer) Files.getAttribute(file.toPath(), "unix:mode");
      mode = unixMode & 07777;
      if (OsConstants.S_ISDIR(unixMode)) mode |= OsConstants.S_IFDIR;
      else if (OsConstants.S_ISFIFO(unixMode)) mode |= OsConstants.S_IFIFO;
      else if (OsConstants.S_ISLNK(unixMode)) mode |= OsConstants.S_IFLNK;
      else mode |= OsConstants.S_IFREG;
      links = (Integer) Files.getAttribute(file.toPath(), "unix:nlink");
    } catch (Throwable ignored) {
      // 非 POSIX 文件系统：保持默认普通文件。
    }
    if (directory) {
      mode = OsConstants.S_IFDIR | (mode & 07777);
    }
    long size = directory ? 0 : file.length();
    int inode = Math.abs(file.getAbsolutePath().hashCode());
    long mtime = file.lastModified() / 1000L;
    return new StructStat(1, inode, mode, links, 0, 0, 0, size, mtime, mtime, mtime, 4096, 0);
  }

  static File resolve(String path) throws ErrnoException {
    if (path == null) throw new ErrnoException("resolve", OsConstants.ENOENT);
    if (!path.startsWith("/proc/self/fd/")) return new File(path);
    String rest = path.substring("/proc/self/fd/".length());
    int slash = rest.indexOf('/');
    String numberText = slash < 0 ? rest : rest.substring(0, slash);
    int number;
    try {
      number = Integer.parseInt(numberText);
    } catch (NumberFormatException bad) {
      throw new ErrnoException("resolve", OsConstants.ENOENT);
    }
    File base = FD_FILES.get(number);
    if (base == null || !OPEN_FDS.contains(number))
      throw new ErrnoException("resolve", OsConstants.ENOENT);
    if (slash < 0) return base;
    return new File(base, rest.substring(slash + 1));
  }

  @org.robolectric.annotation.Implements(Os.class)
  @android.annotation.SuppressLint("NewApi")
  public static final class FakeOs {
    @org.robolectric.annotation.Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      File file = resolve(path);
      boolean directory = (flags & O_PATH) != 0 || file.isDirectory();
      if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(file.toPath()))
        throw new ErrnoException("open", OsConstants.ELOOP);
      if ((flags & O_PATH) != 0 && !file.isDirectory()) {
        // 模拟「O_PATH 打开了非目录」：production 的 S_ISDIR 失败分支必须关闭这个 fd。
        try {
          RandomAccessFile handle = new RandomAccessFile(file, "r");
          FileDescriptor fd = handle.getFD();
          RAW_FILES.put(fd, file);
          RAW_DIRS.put(fd, false);
          RAW_HANDLES.put(fd, handle);
          return fd;
        } catch (IOException error) {
          throw new ErrnoException("open", OsConstants.EACCES);
        }
      }
      if (directory) {
        if (!file.isDirectory()) throw new ErrnoException("open", OsConstants.ENOTDIR);
        FileDescriptor fd = new FileDescriptor();
        RAW_FILES.put(fd, file);
        RAW_DIRS.put(fd, true);
        return fd;
      }
      boolean write = (flags & OsConstants.O_WRONLY) != 0 || (flags & OsConstants.O_RDWR) != 0;
      try {
        if (!file.exists()) {
          if ((flags & OsConstants.O_CREAT) == 0)
            throw new ErrnoException("open", OsConstants.ENOENT);
          File parent = file.getParentFile();
          if (parent == null || !parent.isDirectory())
            throw new ErrnoException("open", OsConstants.ENOENT);
          if (!file.createNewFile()) throw new ErrnoException("open", OsConstants.EACCES);
        }
        RandomAccessFile handle = new RandomAccessFile(file, write ? "rw" : "r");
        FileDescriptor fd = handle.getFD();
        RAW_FILES.put(fd, file);
        RAW_DIRS.put(fd, false);
        RAW_HANDLES.put(fd, handle);
        return fd;
      } catch (IOException error) {
        throw new ErrnoException("open", OsConstants.EACCES);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat lstat(String path) throws ErrnoException {
      File file = resolve(path);
      if (!file.exists() && !Files.isSymbolicLink(file.toPath()))
        throw new ErrnoException("lstat", OsConstants.ENOENT);
      return SafTestOs.stat(file, file.isDirectory());
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat stat(String path) throws ErrnoException {
      return lstat(path);
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat fstat(FileDescriptor fd) throws ErrnoException {
      File file = RAW_FILES.get(fd);
      if (file == null) throw new ErrnoException("fstat", OsConstants.EBADF);
      return SafTestOs.stat(file, Boolean.TRUE.equals(RAW_DIRS.get(fd)));
    }

    @org.robolectric.annotation.Implementation
    protected static String readlink(String path) throws ErrnoException {
      File file = resolve(path);
      try {
        return file.getCanonicalPath();
      } catch (IOException error) {
        throw new ErrnoException("readlink", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static int fcntlInt(FileDescriptor fd, int command, int argument)
        throws ErrnoException {
      return command == OsConstants.F_GETFL ? 0 : argument;
    }

    @org.robolectric.annotation.Implementation
    protected static void ftruncate(FileDescriptor fd, long length) throws ErrnoException {
      RandomAccessFile handle = RAW_HANDLES.get(fd);
      if (handle == null) throw new ErrnoException("ftruncate", OsConstants.EBADF);
      try {
        handle.setLength(length);
      } catch (IOException error) {
        throw new ErrnoException("ftruncate", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void close(FileDescriptor fd) {
      RAW_FILES.remove(fd);
      RAW_DIRS.remove(fd);
      RandomAccessFile handle = RAW_HANDLES.remove(fd);
      if (handle != null) {
        try {
          handle.close();
        } catch (IOException ignored) {
        }
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void mkdir(String path, int mode) throws ErrnoException {
      File file = resolve(path);
      if (!file.mkdir()) throw new ErrnoException("mkdir", OsConstants.EEXIST);
      MKDIRS.add(file.getAbsolutePath());
    }

    @org.robolectric.annotation.Implementation
    protected static void rename(String from, String to) throws ErrnoException {
      File source = resolve(from);
      File target = resolve(to);
      try {
        Files.move(
            source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        RENAMES.add(source.getAbsolutePath() + " -> " + target.getAbsolutePath());
      } catch (IOException error) {
        throw new ErrnoException("rename", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void remove(String path) throws ErrnoException {
      File file = resolve(path);
      if (!file.delete()) throw new ErrnoException("remove", OsConstants.ENOENT);
      REMOVES.add(file.getAbsolutePath());
    }

    @org.robolectric.annotation.Implementation
    protected static void chmod(String path, int mode) {
      // no-op：只验证调用发生在真实路径上
    }
  }

  /** PFD 生命周期正确的影子：close 后编号不可再用于 dup/readlink。 */
  @org.robolectric.annotation.Implements(ParcelFileDescriptor.class)
  public static final class FakePfd extends org.robolectric.shadows.ShadowParcelFileDescriptor {
    private int number;
    private FileDescriptor raw;
    private File file;

    @org.robolectric.annotation.Implementation
    protected static ParcelFileDescriptor dup(FileDescriptor descriptor) throws IOException {
      File mapped = RAW_FILES.get(descriptor);
      if (mapped == null) throw new IOException("EBADF");
      ParcelFileDescriptor handle =
          org.robolectric.shadow.api.Shadow.newInstanceOf(ParcelFileDescriptor.class);
      FakePfd shadow = org.robolectric.shadow.api.Shadow.extract(handle);
      shadow.number = nextFd++;
      shadow.raw = descriptor;
      shadow.file = mapped;
      FD_FILES.put(shadow.number, mapped);
      OPEN_FDS.add(shadow.number);
      return handle;
    }

    @org.robolectric.annotation.Implementation
    protected int getFd() {
      if (number == 0) return super.getFd();
      if (!OPEN_FDS.contains(number))
        throw new IllegalStateException("closed ParcelFileDescriptor");
      return number;
    }

    @org.robolectric.annotation.Implementation
    protected void close() throws IOException {
      if (number == 0) {
        super.close();
        return;
      }
      if (OPEN_FDS.remove(number)) closedPfd++;
      FD_FILES.remove(number);
    }
  }
}
