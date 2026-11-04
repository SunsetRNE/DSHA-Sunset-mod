package com.deepseekharness.app.backup;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.runtime.NativePluginLock;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 窄只读插件检查锁：先既有 data flock，再原子创建 profile 锁；不接管旧锁。 */
public final class AndroidPluginOperationLock implements AutoCloseable {
  private static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000;
  private final AndroidBackupFileSystem fs;
  private final File dataRoot, profile;
  private ParcelFileDescriptor data, parent;
  private FileDescriptor record;
  private BackupFileSystem.Node dataIdentity, parentIdentity;
  private boolean closed;

  private AndroidPluginOperationLock(AndroidBackupFileSystem fs, File dataRoot, File profile) {
    this.fs = fs;
    this.dataRoot = dataRoot;
    this.profile = profile;
  }

  /** 普通不确定/锁忙返回 null；释放不明必须抛出，不能随后启动 guest。 */
  public static AndroidPluginOperationLock tryAcquire(
      AndroidBackupFileSystem fs, File dataRoot, File profile) throws IOException {
    var lease = new AndroidPluginOperationLock(fs, dataRoot, profile);
    boolean acquired = false;
    try {
      acquired = lease.acquire();
    } catch (IOException | ErrnoException | RuntimeException | LinkageError unavailable) {
      // 未取得完整锁时只回原入口；close 若不能证明释放则覆盖此回退。
    } finally {
      if (!acquired) lease.close();
    }
    return acquired ? lease : null;
  }

  private boolean acquire() throws IOException, ErrnoException {
    if (Thread.currentThread().isInterrupted()) return false;
    File lock = fs.child(dataRoot, ".dsha-data.lock");
    dataIdentity = fs.stat(lock);
    if (!dataIdentity.type.equals("FILE")) return false;
    data =
        AndroidBackupFileSystem.owned(
            lock,
            OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | O_CLOEXEC,
            0);
    if (!dataIdentity.same(AndroidBackupFileSystem.node(Os.fstat(data.getFileDescriptor())))
        || NativePluginLock.tryExclusive(data.getFd()) != 0) return false;
    if (!fs.stat(fs.child(dataRoot, ".dsha-restore-journal.json")).type.equals("MISSING"))
      return false;
    parentIdentity = fs.stat(profile);
    if (!parentIdentity.type.equals("DIRECTORY")) return false;
    parent = AndroidBackupFileSystem.owned(profile, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
    if (!sameDirectory(
        parentIdentity, AndroidBackupFileSystem.node(Os.fstat(parent.getFileDescriptor()))))
      return false;
    record =
        Os.open(
            recordPath(),
            OsConstants.O_WRONLY
                | OsConstants.O_CREAT
                | OsConstants.O_EXCL
                | OsConstants.O_NOFOLLOW
                | O_CLOEXEC,
            0600);
    byte[] bytes = (android.os.Process.myPid() + "\n").getBytes(StandardCharsets.US_ASCII);
    int offset = 0;
    while (offset < bytes.length) {
      int count = Os.write(record, bytes, offset, bytes.length - offset);
      if (count <= 0) throw new IOException("PLUGIN_HOST_LOCK_WRITE");
      offset += count;
    }
    verify();
    return true;
  }

  private String recordPath() {
    return "/proc/self/fd/" + parent.getFd() + "/package.json.lock";
  }

  private static boolean sameDirectory(BackupFileSystem.Node before, BackupFileSystem.Node after) {
    return before.type.equals("DIRECTORY")
        && after.type.equals("DIRECTORY")
        && before.device == after.device
        && before.key.equals(after.key);
  }

  public void verify() throws IOException {
    if (closed || data == null || parent == null || record == null)
      throw new IOException("PLUGIN_HOST_LOCK_NOT_HELD");
    try {
      if (!dataIdentity.same(fs.stat(fs.child(dataRoot, ".dsha-data.lock")))
          || !dataIdentity.same(AndroidBackupFileSystem.node(Os.fstat(data.getFileDescriptor())))
          || !sameDirectory(parentIdentity, fs.stat(profile))
          || !sameDirectory(
              parentIdentity, AndroidBackupFileSystem.node(Os.fstat(parent.getFileDescriptor())))
          || !fs.stat(fs.child(dataRoot, ".dsha-restore-journal.json")).type.equals("MISSING"))
        throw new IOException("PLUGIN_HOST_LOCK_CHANGED");
      var held = Os.fstat(record);
      var visible = Os.lstat(recordPath());
      if (!OsConstants.S_ISREG(held.st_mode)
          || !OsConstants.S_ISREG(visible.st_mode)
          || held.st_dev != visible.st_dev
          || held.st_ino != visible.st_ino)
        throw new IOException("PLUGIN_HOST_PROFILE_LOCK_CHANGED");
    } catch (ErrnoException failure) {
      throw AndroidBackupFileSystem.failure(failure);
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    if (record != null) {
      try {
        var held = Os.fstat(record);
        var visible = Os.lstat(recordPath());
        if (!OsConstants.S_ISREG(held.st_mode)
            || !OsConstants.S_ISREG(visible.st_mode)
            || held.st_dev != visible.st_dev
            || held.st_ino != visible.st_ino)
          throw new IOException("PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED");
        // 在始终持有的父 FD 内只删本次创建的锁，祖先改名不能转向另一 profile。
        Os.remove(recordPath());
      } catch (IOException | ErrnoException | RuntimeException error) {
        failure = new IOException("PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED", error);
      } finally {
        try {
          Os.close(record);
        } catch (ErrnoException error) {
          if (failure == null)
            failure = new IOException("PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED", error);
          else failure.addSuppressed(error);
        }
      }
    }
    for (ParcelFileDescriptor handle : new ParcelFileDescriptor[] {parent, data}) {
      if (handle == null) continue;
      try {
        handle.close();
      } catch (IOException error) {
        if (failure == null)
          failure = new IOException("PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED", error);
        else failure.addSuppressed(error);
      }
    }
    if (failure != null) throw failure;
  }
}
