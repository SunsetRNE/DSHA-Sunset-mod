package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** 直接执行 TransferFiles；仅替换 Android 描述符事实，真实临时文件承载读写字节。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      TransferFilesBehaviorTest.Host.class,
      TransferFilesBehaviorTest.Calls.class,
      TransferFilesBehaviorTest.Owned.class
    })
public final class TransferFilesBehaviorTest {
  private File actual;
  private static final File LOGICAL = new File("/parent/leaf");

  @Before
  public void prepare() throws Exception {
    actual = Files.createTempFile("transfer-descriptor-", ".partial").toFile();
    Files.writeString(actual.toPath(), "prefix", StandardCharsets.UTF_8);
    Calls.actual = actual;
    Calls.handles.clear();
    Calls.opened = Calls.closed = Calls.truncated = Calls.seeks = Calls.syncs = 0;
    Calls.leafFlags = 0;
    Calls.leafType = OsConstants.S_IFREG;
    Calls.parentType = OsConstants.S_IFDIR;
    Calls.inode = 9;
    Calls.syncFailure = false;
    Calls.files.clear();
    Host.missing = false;
    Host.size = actual.length();
    Owned.duplicates = Owned.closed = 0;
  }

  @After
  public void release() throws Exception {
    for (RandomAccessFile file : Calls.files) file.close();
    Files.deleteIfExists(actual.toPath());
  }

  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static final class Host {
    static boolean missing;
    static long size;

    @Implementation
    protected BackupFileSystem.Node stat(File ignored) {
      return new BackupFileSystem.Node(missing ? "MISSING" : "FILE", "1:9", size, 0, 1, 0600);
    }
  }

  @Implements(Os.class)
  public static final class Calls {
    static File actual;
    static final IdentityHashMap<FileDescriptor, Boolean> handles = new IdentityHashMap<>();
    static final List<RandomAccessFile> files = new ArrayList<>();
    static int opened, closed, truncated, seeks, syncs, leafFlags, leafType, parentType;
    static long inode;
    static boolean syncFailure;

    @Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      opened++;
      boolean leaf = path.endsWith("/leaf");
      FileDescriptor descriptor;
      if (leaf) {
        leafFlags = flags;
        assertEquals(0, flags & OsConstants.O_TRUNC);
        assertTrue((flags & OsConstants.O_NONBLOCK) != 0);
        assertTrue((flags & OsConstants.O_NOFOLLOW) != 0);
        assertEquals(0600, mode);
        try {
          RandomAccessFile file = new RandomAccessFile(actual, "rw");
          files.add(file);
          descriptor = file.getFD();
        } catch (IOException failure) {
          throw new ErrnoException("open", OsConstants.EIO);
        }
      } else {
        if (!path.equals("/")) assertTrue((flags & OsConstants.O_NOFOLLOW) != 0);
        descriptor = new FileDescriptor();
      }
      handles.put(descriptor, leaf);
      return descriptor;
    }

    @Implementation
    protected static StructStat fstat(FileDescriptor descriptor) {
      boolean leaf = Boolean.TRUE.equals(handles.get(descriptor));
      return new StructStat(
          1,
          leaf ? inode : 1,
          (leaf ? leafType : parentType) | 0600,
          1,
          0,
          0,
          0,
          actual.length(),
          0,
          0,
          0,
          4096,
          0);
    }

    @Implementation
    protected static void ftruncate(FileDescriptor descriptor, long length) throws ErrnoException {
      truncated++;
      try {
        files.get(files.size() - 1).setLength(length);
      } catch (IOException failure) {
        throw new ErrnoException("truncate", OsConstants.EIO);
      }
    }

    @Implementation
    protected static long lseek(FileDescriptor descriptor, long offset, int whence)
        throws ErrnoException {
      seeks++;
      assertEquals(OsConstants.SEEK_SET, whence);
      try {
        files.get(files.size() - 1).seek(offset);
        return offset;
      } catch (IOException failure) {
        throw new ErrnoException("seek", OsConstants.EIO);
      }
    }

    @Implementation
    protected static void close(FileDescriptor descriptor) {
      assertNotNull(handles.remove(descriptor));
      closed++;
    }

    @Implementation
    protected static void fsync(FileDescriptor descriptor) throws ErrnoException {
      syncs++;
      if (syncFailure) throw new ErrnoException("sync", OsConstants.EIO);
    }
  }

  @Implements(ParcelFileDescriptor.class)
  public static final class Owned {
    static int duplicates, closed;
    private FileDescriptor descriptor;
    private boolean retired;

    @Implementation
    protected static ParcelFileDescriptor dup(FileDescriptor descriptor) {
      ParcelFileDescriptor owner = Shadow.newInstanceOf(ParcelFileDescriptor.class);
      Owned shadow = Shadow.extract(owner);
      shadow.descriptor = descriptor;
      duplicates++;
      return owner;
    }

    @Implementation
    protected FileDescriptor getFileDescriptor() {
      return descriptor;
    }

    @Implementation
    protected int getFd() {
      return System.identityHashCode(descriptor) & Integer.MAX_VALUE;
    }

    @Implementation
    protected void close() {
      if (!retired) {
        retired = true;
        closed++;
      }
    }
  }

  private void closedAll() {
    assertEquals(Calls.opened, Calls.closed);
    assertEquals(Owned.duplicates, Owned.closed);
  }

  @Test
  public void resumeSeeksWithoutTruncatingVerifiedPrefixAndSyncsBeforeOwnerCloses()
      throws Exception {
    try (OutputStream out = TransferFiles.write(LOGICAL, Host.size)) {
      out.write("tail".getBytes(StandardCharsets.UTF_8));
      assertEquals(0, Calls.truncated);
      assertEquals(1, Calls.seeks);
      assertEquals(0, Calls.syncs);
    }
    assertEquals("prefixtail", Files.readString(actual.toPath()));
    assertEquals(1, Calls.syncs);
    closedAll();
  }

  @Test
  public void zeroOffsetReplacesTheWholeOldPartialAfterIdentityCheck() throws Exception {
    try (OutputStream out = TransferFiles.write(LOGICAL, 0)) {
      out.write(new byte[] {'x', 'y'});
    }
    assertEquals("xy", Files.readString(actual.toPath()));
    assertEquals(1, Calls.truncated);
    assertEquals(0, Calls.seeks);
    closedAll();
  }

  @Test
  public void replacementInodeIsRejectedBeforeTruncation() {
    Calls.inode = 10;
    assertEquals(
        "FILE_PARTIAL_CHANGED",
        assertThrows(IOException.class, () -> TransferFiles.write(LOGICAL, 0)).getMessage());
    assertEquals(0, Calls.truncated);
    assertEquals(0, Calls.seeks);
    closedAll();
  }

  @Test
  public void lateFifoCannotBeOpenedAsAValidPartialOrTruncated() {
    Calls.leafType = OsConstants.S_IFIFO;
    assertEquals(
        "FILE_PARTIAL_CHANGED",
        assertThrows(IOException.class, () -> TransferFiles.write(LOGICAL, 0)).getMessage());
    assertEquals(0, Calls.truncated);
    assertTrue((Calls.leafFlags & OsConstants.O_NONBLOCK) != 0);
    closedAll();
  }

  @Test
  public void staleResumeOffsetFailsBeforeAnyDescriptorOpen() {
    assertEquals(
        "FILE_PARTIAL_CHANGED",
        assertThrows(IOException.class, () -> TransferFiles.write(LOGICAL, Host.size + 1))
            .getMessage());
    assertEquals(0, Calls.opened);
    closedAll();
  }

  @Test
  public void unsafeParentFailsBeforeOpeningLeafAndClosesBothDescriptors() {
    Calls.parentType = OsConstants.S_IFLNK;
    assertEquals(
        "FILE_PARENT_UNSAFE",
        assertThrows(IOException.class, () -> TransferFiles.write(LOGICAL, 0)).getMessage());
    assertEquals(0, Calls.leafFlags);
    assertEquals(0, Calls.truncated);
    closedAll();
  }

  @Test
  public void missingPartialRequiresExclusiveCreationAndNoFollow() throws Exception {
    Host.missing = true;
    try (OutputStream out = TransferFiles.write(LOGICAL, 0)) {
      out.flush();
    }
    assertTrue((Calls.leafFlags & OsConstants.O_CREAT) != 0);
    assertTrue((Calls.leafFlags & OsConstants.O_EXCL) != 0);
    assertTrue((Calls.leafFlags & OsConstants.O_NOFOLLOW) != 0);
    closedAll();
  }

  @Test
  public void syncFailurePropagatesWhileStillClosingTheStreamOwner() throws Exception {
    OutputStream out = TransferFiles.write(LOGICAL, 0);
    out.write('x');
    Calls.syncFailure = true;
    assertEquals("FILE_SYNC_FAILED", assertThrows(IOException.class, out::close).getMessage());
    assertEquals(1, Calls.syncs);
    closedAll();
  }
}
