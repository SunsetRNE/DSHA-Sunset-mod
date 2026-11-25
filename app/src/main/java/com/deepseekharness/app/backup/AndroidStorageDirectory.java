package com.deepseekharness.app.backup;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.FileKind;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Read-only storage traversal with the current directory and its ancestors pinned. */
final class AndroidStorageDirectory implements AutoCloseable {
  private static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000;
  private static final int DIRECTORY_FLAGS = O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW;
  private static final BackupFileSystem.Node MISSING =
      new BackupFileSystem.Node("MISSING", "", 0, 0, 0, 0);

  private final ParcelFileDescriptor handle;
  private final BackupFileSystem.Node identity;
  private final String directoryPath, originalPath;
  private boolean closed;

  private AndroidStorageDirectory(
      ParcelFileDescriptor handle, BackupFileSystem.Node identity, String originalPath) {
    this.handle = handle;
    this.identity = identity;
    this.originalPath = originalPath;
    directoryPath = "/proc/self/fd/" + handle.getFd();
  }

  static AndroidStorageDirectory open(File file, BackupFileSystem.Node expected)
      throws IOException {
    requireDirectory(expected);
    try {
      var handle = AndroidBackupFileSystem.owned(file, DIRECTORY_FLAGS, 0);
      return checked(handle, expected, file.getAbsolutePath());
    } catch (ErrnoException failure) {
      throw AndroidBackupFileSystem.failure(failure);
    }
  }

  AndroidStorageDirectory directory(String name, BackupFileSystem.Node expected)
      throws IOException {
    requireDirectory(expected);
    String original = childPath(name);
    try {
      var raw = Os.open(original, DIRECTORY_FLAGS, 0);
      ParcelFileDescriptor handle;
      try {
        handle = ParcelFileDescriptor.dup(raw);
      } finally {
        Os.close(raw);
      }
      return checked(handle, expected, original);
    } catch (ErrnoException failure) {
      throw AndroidBackupFileSystem.failure(failure);
    }
  }

  private static void requireDirectory(BackupFileSystem.Node expected) throws IOException {
    if (expected.kind != FileKind.DIRECTORY) throw new IOException("DIRECTORY_TYPE");
  }

  private static AndroidStorageDirectory checked(
      ParcelFileDescriptor handle, BackupFileSystem.Node expected, String originalPath)
      throws IOException {
    var directory = new AndroidStorageDirectory(handle, expected, originalPath);
    try {
      directory.verify();
      return directory;
    } catch (IOException failure) {
      try {
        handle.close();
      } catch (IOException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  private String childPath(String name) throws IOException {
    if (closed) throw new IOException("TREE_SCOPE_CLOSED");
    if (name.isEmpty()
        || name.indexOf('/') >= 0
        || name.indexOf('\0') >= 0
        || name.equals(".")
        || name.equals("..")) throw new IOException("INVALID_CHILD");
    return directoryPath + "/" + name;
  }

  private static BackupFileSystem.Node statPath(String path) throws IOException {
    try {
      return AndroidBackupFileSystem.node(Os.lstat(path));
    } catch (ErrnoException failure) {
      if (failure.errno == OsConstants.ENOENT) return MISSING;
      throw AndroidBackupFileSystem.failure(failure);
    }
  }

  BackupFileSystem.Node stat(String name) throws IOException {
    return statPath(childPath(name));
  }

  List<String> list() throws IOException {
    if (closed) throw new IOException("TREE_SCOPE_CLOSED");
    String[] names = new File(directoryPath).list();
    if (names == null) throw new IOException("DIRECTORY_UNREADABLE");
    if (names.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
    verify();
    Arrays.sort(names);
    return Arrays.asList(names);
  }

  private void verify() throws IOException {
    try {
      if (!identity.same(AndroidBackupFileSystem.node(Os.fstat(handle.getFileDescriptor())))
          || !identity.same(statPath(originalPath))) throw new IOException("SOURCE_CHANGED");
    } catch (ErrnoException failure) {
      throw AndroidBackupFileSystem.failure(failure);
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    try {
      verify();
    } finally {
      closed = true;
      handle.close();
    }
  }
}
