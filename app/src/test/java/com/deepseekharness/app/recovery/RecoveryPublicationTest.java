package com.deepseekharness.app.recovery;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RecoveryPublicationTest {
  private final BackupControl control = new BackupControl(null);
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final String runtime = "1".repeat(64), id = "a".repeat(32);

  private static final class RecordingFs extends JvmBackupFileSystem {
    final List<String> boundaries = new ArrayList<>();
    File capsules, failSource;
    boolean interruptProofRead;
    BackupControl cancelOnBody, cancelAfterSeal, cancelAfterFirstMove;
    long bodyBytes;

    @Override
    public void move(File source, File target) throws IOException {
      if (source.equals(failSource)) throw new IOException("SIMULATED_PUBLICATION_FAILURE");
      super.move(source, target);
      if (source.getParentFile().equals(capsules))
        boundaries.add("move:" + source.getName() + ":" + target.getName());
      if (cancelAfterFirstMove != null) cancelAfterFirstMove.cancel();
    }

    @Override
    public void syncDirectory(File directory) throws IOException {
      super.syncDirectory(directory);
      if (directory.equals(capsules)) boundaries.add("sync");
    }

    @Override
    public InputStream read(File file, Node node) throws IOException {
      if (interruptProofRead && file.getName().equals("prepare-proof.json"))
        throw new InterruptedIOException("CANCELLED");
      InputStream opened = super.read(file, node);
      if (cancelOnBody == null || !file.getName().equals("signed-data")) return opened;
      return new FilterInputStream(opened) {
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
          int count = super.read(bytes, offset, length);
          if (count > 0) {
            bodyBytes += count;
            cancelOnBody.cancel();
          }
          return count;
        }
      };
    }

    @Override
    public void atomic(File directory, String name, byte[] bytes) throws IOException {
      super.atomic(directory, name, bytes);
      if (name.equals("verified.json") && cancelAfterSeal != null) cancelAfterSeal.cancel();
    }
  }

  private File staging(File capsules) throws Exception {
    File staging = new File(capsules, runtime + ".pending-" + id);
    Files.createDirectories(new File(staging, "linux/ubuntu").toPath());
    Files.writeString(new File(staging, "linux/ubuntu/signed-data").toPath(), "new signed bytes");
    return staging;
  }

  @Test
  public void publicationDurablyRetainsOriginalAndPublishesCompleteCandidate() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    Files.createDirectories(current.toPath());
    Files.writeString(new File(current, "original").toPath(), "old original");
    RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id, control);
    assertEquals(
        List.of(
            "move:" + runtime + ":" + runtime + ".retained-" + id,
            "sync",
            "move:" + staging.getName() + ":" + runtime,
            "sync"),
        fs.boundaries);
    assertEquals(
        "old original",
        Files.readString(new File(capsules, runtime + ".retained-" + id + "/original").toPath()));
    assertEquals(
        "new signed bytes",
        Files.readString(new File(current, "linux/ubuntu/signed-data").toPath()));
    assertEquals(
        runtime,
        BackupJson.read(Files.readAllBytes(new File(current, "verified.json").toPath()), 8192)
            .get("runtimeId"));
    assertFalse(staging.exists());
  }

  @Test
  public void failedPublicationKeepsBothOriginalAndCompleteCandidate() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    Files.createDirectories(current.toPath());
    Files.writeString(new File(current, "original").toPath(), "preserve me");
    fs.failSource = staging;
    try {
      RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id, control);
      fail("publication must fail");
    } catch (IOException expected) {
      assertEquals("SIMULATED_PUBLICATION_FAILURE", expected.getMessage());
    }
    assertEquals(
        "preserve me",
        Files.readString(new File(capsules, runtime + ".retained-" + id + "/original").toPath()));
    assertTrue(new File(staging, "verified.json").isFile());
    assertEquals(
        "new signed bytes",
        Files.readString(new File(staging, "linux/ubuntu/signed-data").toPath()));
  }

  @Test
  public void occupiedRetentionSlotCannotOverwriteOrMoveEitherOriginal() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File staging = staging(capsules), current = new File(capsules, runtime);
    File retained = new File(capsules, runtime + ".retained-" + id);
    Files.createDirectories(current.toPath());
    Files.writeString(retained.toPath(), "unknown historical record");
    try {
      RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id, control);
      fail("retained originals cannot be replaced");
    } catch (IOException expected) {
      assertEquals("RECOVERY_RETAIN_FAILED", expected.getMessage());
    }
    assertTrue(current.isDirectory());
    assertTrue(staging.isDirectory());
    assertEquals("unknown historical record", Files.readString(retained.toPath()));
    assertTrue(fs.boundaries.isEmpty());
  }

  @Test
  public void stagingOutsideCapsuleAuthorityIsRejectedBeforePublication() throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder();
    File external = staging(temporary.newFolder());
    try {
      RecoveryStoragePlan.publish(fs, capsules, external, runtime, id, control);
      fail("external staging must not publish");
    } catch (IOException expected) {
      assertEquals("RECOVERY_PUBLISH_IDENTITY", expected.getMessage());
    }
    assertTrue(external.isDirectory());
    assertTrue(fs.boundaries.isEmpty());
  }

  @Test
  public void stopDuringEitherTreeHashDoesNotConsumeTheRestOfTheFile() throws Exception {
    RecordingFs fs = new RecordingFs();
    File files = temporary.newFolder(), capsules = new File(files, "recovery-capsules");
    Files.createDirectories(capsules.toPath());
    Files.createDirectories(new File(files, "recovery-sessions/" + id).toPath());
    File staging = staging(capsules);
    File large = new File(staging, "linux/ubuntu/signed-data");
    Files.write(large.toPath(), new byte[1024 * 1024]);
    fs.cancelOnBody = control;
    assertThrows(
        InterruptedIOException.class,
        () -> RecoveryStoragePlan.prepared(fs, staging, runtime, id, control));
    assertTrue(fs.bodyBytes > 0 && fs.bodyBytes < large.length());
    assertFalse(new File(staging, "prepare-proof.json").exists());
    fs.cancelOnBody = null;
    RecoveryStoragePlan.prepared(fs, staging, runtime, id, new BackupControl(null));
    fs.bodyBytes = 0;
    BackupControl retry = new BackupControl(null);
    fs.cancelOnBody = retry;
    assertThrows(
        InterruptedIOException.class,
        () -> RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of(), retry));
    assertTrue(fs.bodyBytes > 0 && fs.bodyBytes < large.length());
    assertEquals(1024 * 1024L, large.length());
  }

  @Test
  public void stopAtLastBoundaryPreservesCurrentAndAfterFirstMoveFinishesPublishing()
      throws Exception {
    RecordingFs fs = new RecordingFs();
    File capsules = fs.capsules = temporary.newFolder(), staging = staging(capsules);
    File current = new File(capsules, runtime);
    Files.createDirectories(current.toPath());
    Files.writeString(new File(current, "original").toPath(), "old");
    fs.cancelAfterSeal = control;
    assertThrows(
        InterruptedIOException.class,
        () -> RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id, control));
    assertTrue(fs.boundaries.isEmpty());
    assertTrue(staging.isDirectory());
    assertEquals("old", Files.readString(new File(current, "original").toPath()));
    fs.cancelAfterSeal = null;
    fs.cancelAfterFirstMove = new BackupControl(null);
    RecoveryStoragePlan.publish(fs, capsules, staging, runtime, id, fs.cancelAfterFirstMove);
    assertTrue(fs.cancelAfterFirstMove.isCancelled());
    assertEquals(4, fs.boundaries.size());
    assertTrue(new File(current, "linux/ubuntu/signed-data").isFile());
  }

  @Test
  public void cancellationDuringCandidateVerificationIsPropagated() throws Exception {
    RecordingFs fs = new RecordingFs();
    File files = temporary.newFolder(), capsules = new File(files, "recovery-capsules");
    Files.createDirectories(capsules.toPath());
    File staging = staging(capsules);
    RecoveryStoragePlan.prepared(fs, staging, runtime, id, control);
    fs.interruptProofRead = true;
    try {
      RecoveryStoragePlan.reusable(fs, files, capsules, runtime, Set.of(), control);
      fail("cancellation must not become an unreadable candidate");
    } catch (InterruptedIOException expected) {
      assertEquals("CANCELLED", expected.getMessage());
    }
    assertTrue(staging.isDirectory());
  }
}
