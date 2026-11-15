package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 真实临时文件上的平台输入反例；不加载 Android，不执行停止或发送信号。 */
public final class WebStopInputObservationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private WebProcessManager.StopTrace capture(File home, File marker, File payload)
      throws IOException {
    var trace = new WebProcessManager.StopTrace("root", "boot");
    trace.rememberInput(home, fs.stat(home), null);
    trace.rememberTrialEntries(home, fs.list(home));
    trace.rememberInput(marker, fs.stat(marker), Files.readAllBytes(marker.toPath()));
    trace.rememberInput(payload, fs.stat(payload), null);
    return trace;
  }

  private File[] inputs() throws IOException {
    File home = temporary.newFolder("trials");
    File marker = new File(home, "launched");
    Files.writeString(marker.toPath(), "marker-1", StandardCharsets.US_ASCII);
    File payload = new File(home, "payload");
    assertTrue(payload.mkdir());
    return new File[] {home, marker, payload};
  }

  @Test
  public void unchangedActualInputsRemainComparable() throws Exception {
    var in = inputs();
    assertTrue(capture(in[0], in[1], in[2]).verifyInputs(fs));
  }

  @Test
  public void sameBytesAtomicMarkerReplacementStillChangesItsIdentity() throws Exception {
    var in = inputs();
    var trace = capture(in[0], in[1], in[2]);
    File replacement = new File(in[0], "replacement");
    Files.writeString(replacement.toPath(), "marker-1", StandardCharsets.US_ASCII);
    Files.move(replacement.toPath(), in[1].toPath(), StandardCopyOption.REPLACE_EXISTING);
    assertFalse(trace.verifyInputs(fs));
  }

  @Test
  public void sameInodeSameLengthAndRestoredTimeCannotHideChangedMarkerBytes() throws Exception {
    var in = inputs();
    var before = fs.stat(in[1]);
    var trace = capture(in[0], in[1], in[2]);
    Files.writeString(in[1].toPath(), "invalid!", StandardCharsets.US_ASCII);
    Files.setLastModifiedTime(in[1].toPath(), FileTime.fromMillis(before.modified));
    assertEquals(before.key, fs.stat(in[1]).key);
    assertEquals(before.size, fs.stat(in[1]).size);
    assertEquals(before.modified, fs.stat(in[1]).modified);
    assertFalse(trace.verifyInputs(fs));
  }

  @Test
  public void replacingPayloadAndAddingANewScopeBothInvalidateTheObservation() throws Exception {
    var in = inputs();
    var trace = capture(in[0], in[1], in[2]);
    Files.move(in[2].toPath(), new File(in[0], "previous-payload").toPath());
    assertTrue(in[2].mkdir());
    assertFalse(trace.verifyInputs(fs));
  }

  @Test
  public void newDirectoryMembershipCannotBeHiddenBehindAnOldDirectoryTimestamp() throws Exception {
    var in = inputs();
    var trace = capture(in[0], in[1], in[2]);
    long modified = in[0].lastModified();
    assertTrue(new File(in[0], "new-scope").mkdir());
    Files.setLastModifiedTime(in[0].toPath(), FileTime.fromMillis(modified));
    assertFalse(trace.verifyInputs(fs));
  }

  @Test
  public void replacementBetweenStatAndReadCannotAcquireANewExpectedIdentity() throws Exception {
    var in = inputs();
    var trace = capture(in[0], in[1], in[2]);
    assertFalse(
        trace.verifyInputs(
            new JvmBackupFileSystem() {
              @Override
              public java.io.InputStream read(File file, Node expected) throws IOException {
                if (file.equals(in[1])) {
                  File replacement = new File(in[0], "replace-during-read");
                  Files.writeString(replacement.toPath(), "marker-1", StandardCharsets.US_ASCII);
                  Files.move(
                      replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return super.read(file, expected);
              }
            }));
  }

  @Test
  public void finalReadFailureRemainsUnknown() throws Exception {
    var in = inputs();
    var trace = capture(in[0], in[1], in[2]);
    assertFalse(
        trace.verifyInputs(
            new JvmBackupFileSystem() {
              @Override
              public java.io.InputStream read(File file, Node expected) throws IOException {
                throw new IOException("DENIED");
              }
            }));
  }
}
