package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.DocumentRowsSnapshotTest;
import com.deepseekharness.app.runtime.NativePluginLock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** Android FD 释放/失败控制流；真实跨进程 flock 另由 POSIX 工具验证。 */
@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    instrumentedPackages = {"com.deepseekharness.app.backup", "com.deepseekharness.app.runtime"},
    shadows = {
      AndroidPluginOperationLockTest.OwnedFiles.class,
      AndroidPluginOperationLockTest.Handles.class,
      AndroidPluginOperationLockTest.Kernel.class,
      AndroidPluginOperationLockTest.NativeLock.class
    })
public final class AndroidPluginOperationLockTest {
  private static final File ROOT = new File("/data-root"), PROFILE = new File("/profile");
  private final AndroidBackupFileSystem fs =
      new AndroidBackupFileSystem() {
        @Override
        public BackupFileSystem.Node stat(File path) {
          if (path.getName().equals(".dsha-data.lock"))
            return dataMissing
                ? missing()
                : AndroidBackupFileSystem.node(info(OsConstants.S_IFREG, 1));
          if (path.getName().equals(".dsha-restore-journal.json"))
            return restorePending
                ? AndroidBackupFileSystem.node(info(OsConstants.S_IFREG, 6))
                : missing();
          return AndroidBackupFileSystem.node(
              info(OsConstants.S_IFDIR, path.equals(PROFILE) ? 2 : 3));
        }
      };
  private static boolean dataMissing, restorePending;

  private static BackupFileSystem.Node missing() {
    return new BackupFileSystem.Node("MISSING", "", 0, 0, 0, 0);
  }

  private static StructStat info(int type, long inode) {
    return new StructStat(1, inode, type | 0600, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0);
  }

  @Implements(AndroidBackupFileSystem.class)
  public static final class OwnedFiles {
    @Implementation
    protected static ParcelFileDescriptor owned(File file, int flags, int mode) {
      assertTrue((flags & OsConstants.O_NOFOLLOW) != 0);
      boolean data = file.getName().equals(".dsha-data.lock");
      if (data) assertTrue((flags & OsConstants.O_NONBLOCK) != 0);
      FileDescriptor descriptor = new FileDescriptor();
      Kernel.files.put(
          descriptor, info(data ? OsConstants.S_IFREG : OsConstants.S_IFDIR, data ? 1 : 2));
      ParcelFileDescriptor handle = Shadow.newInstanceOf(ParcelFileDescriptor.class);
      Handles value = Shadow.extract(handle);
      value.descriptor = descriptor;
      value.number = ++Handles.opened;
      return handle;
    }
  }

  @Implements(ParcelFileDescriptor.class)
  public static final class Handles {
    static int opened, closed;
    private FileDescriptor descriptor;
    private int number;
    private boolean ended;

    @Implementation
    protected FileDescriptor getFileDescriptor() {
      return descriptor;
    }

    @Implementation
    protected int getFd() {
      return number;
    }

    @Implementation
    protected void close() {
      if (!ended) {
        ended = true;
        closed++;
      }
    }
  }

  @Implements(NativePluginLock.class)
  public static final class NativeLock {
    static int result, calls;

    @Implementation
    protected static void __staticInitializer__() {}

    @Implementation
    protected static int tryExclusive(int descriptor) {
      assertEquals(1, descriptor);
      calls++;
      return result;
    }
  }

  @Implements(Os.class)
  public static final class Kernel {
    static final Map<FileDescriptor, StructStat> files = new IdentityHashMap<>();
    static ByteArrayOutputStream content;
    static boolean existing, missing, failRemove;
    static long visibleInode;
    static int recordOpens, recordCloses, removes;

    @Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      assertTrue(path.startsWith("/proc/self/fd/"));
      assertTrue(path.endsWith("/package.json.lock"));
      assertTrue((flags & OsConstants.O_EXCL) != 0);
      assertTrue((flags & OsConstants.O_NOFOLLOW) != 0);
      assertEquals(1, NativeLock.calls);
      if (existing) throw new ErrnoException("open", OsConstants.EEXIST);
      FileDescriptor descriptor = new FileDescriptor();
      files.put(descriptor, info(OsConstants.S_IFREG, 4));
      visibleInode = 4;
      recordOpens++;
      return descriptor;
    }

    @Implementation
    protected static int write(FileDescriptor descriptor, byte[] bytes, int offset, int count) {
      int written = Math.min(2, count); // 真实代码必须处理短写。
      content.write(bytes, offset, written);
      return written;
    }

    @Implementation
    protected static StructStat fstat(FileDescriptor descriptor) {
      return files.get(descriptor);
    }

    @Implementation
    protected static StructStat lstat(String path) throws ErrnoException {
      if (missing) throw new ErrnoException("lstat", OsConstants.ENOENT);
      return info(OsConstants.S_IFREG, visibleInode);
    }

    @Implementation
    protected static void remove(String path) throws ErrnoException {
      if (failRemove) throw new ErrnoException("remove", OsConstants.EACCES);
      removes++;
      missing = true;
    }

    @Implementation
    protected static void close(FileDescriptor descriptor) {
      recordCloses++;
    }
  }

  @Before
  public void reset() {
    dataMissing = restorePending = false;
    Handles.opened = Handles.closed = NativeLock.calls = NativeLock.result = 0;
    Kernel.files.clear();
    Kernel.content = new ByteArrayOutputStream();
    Kernel.existing = Kernel.missing = Kernel.failRemove = false;
    Kernel.visibleInode = Kernel.recordOpens = Kernel.recordCloses = Kernel.removes = 0;
  }

  private AndroidPluginOperationLock acquire() throws IOException {
    return AndroidPluginOperationLock.tryAcquire(fs, ROOT, PROFILE);
  }

  private void closed() {
    assertEquals(Handles.opened, Handles.closed);
    assertEquals(Kernel.recordOpens, Kernel.recordCloses);
  }

  @Test
  public void dataBusyReturnsFallbackWithoutCreatingProfileLock() throws Exception {
    NativeLock.result = -OsConstants.EAGAIN;
    assertNull(acquire());
    assertEquals(0, Kernel.recordOpens);
    assertEquals(0, Kernel.removes);
    closed();
  }

  @Test
  public void existingProfileLockRemainsUntouchedAndDataFdCloses() throws Exception {
    Kernel.existing = true;
    assertNull(acquire());
    assertEquals(0, Kernel.removes);
    closed();
  }

  @Test
  public void missingDataLockIsNotCreatedByReadOnlyCheck() throws Exception {
    dataMissing = true;
    assertNull(acquire());
    assertEquals(0, Handles.opened);
    closed();
  }

  @Test
  public void pendingRestoreNeverCreatesProfileLock() throws Exception {
    restorePending = true;
    assertNull(acquire());
    assertEquals(0, Kernel.recordOpens);
    closed();
  }

  @Test
  public void normalReleaseWritesRealPidAndClosesEveryDescriptorOnce() throws Exception {
    var lease = acquire();
    assertNotNull(lease);
    assertEquals(
        android.os.Process.myPid() + "\n", Kernel.content.toString(StandardCharsets.US_ASCII));
    lease.verify();
    lease.close();
    lease.close();
    assertEquals(1, Kernel.removes);
    closed();
  }

  @Test
  public void deletedLockCannotReturnFallbackAndStillClosesAllDescriptors() throws Exception {
    var lease = acquire();
    Kernel.missing = true;
    assertEquals(
        "PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED",
        assertThrows(IOException.class, lease::close).getMessage());
    assertEquals(0, Kernel.removes);
    closed();
  }

  @Test
  public void replacementInodeIsPreservedAndFailsClosed() throws Exception {
    var lease = acquire();
    Kernel.visibleInode = 44;
    assertEquals(
        "PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED",
        assertThrows(IOException.class, lease::close).getMessage());
    assertEquals(0, Kernel.removes);
    closed();
  }

  @Test
  public void removalFailureStillReleasesDataFlockFd() throws Exception {
    var lease = acquire();
    Kernel.failRemove = true;
    assertEquals(
        "PLUGIN_HOST_LOCK_RELEASE_UNVERIFIED",
        assertThrows(IOException.class, lease::close).getMessage());
    closed();
  }
}
