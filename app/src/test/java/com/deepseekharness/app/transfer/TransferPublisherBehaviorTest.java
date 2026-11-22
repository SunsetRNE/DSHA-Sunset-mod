package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.TransferBehaviorTestSupport.*;
import static org.junit.Assert.*;

import android.app.Application;
import android.net.Uri;
import android.provider.MediaStore;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 直接调用真实 Publisher，目标存储可控但复制、摘要、日志与提交分支均不替代。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      TransferBehaviorTestSupport.Controller.class,
      TransferBehaviorTestSupport.HostFiles.class,
      TransferBehaviorTestSupport.Documents.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class TransferPublisherBehaviorTest {
  private static final byte[] BYTES = "发布前必须读回实际字节".getBytes(StandardCharsets.UTF_8);
  private TransferBehaviorTestSupport fixture;
  private TransferArtifact artifact;
  private File source;
  private BackupControl control;
  private Object gate;
  private Journal journal;

  @Before
  public void prepare() throws Exception {
    fixture = new TransferBehaviorTestSupport();
    artifact = artifact("publication-fixture", "report.bin", BYTES);
    source = fixture.source(BYTES);
    control = new BackupControl(null);
    gate = new Object();
    fixture.media.requiredGate = gate;
    journal = new Journal();
  }

  @After
  public void release() throws Exception {
    fixture.close();
  }

  private final class Journal implements TransferPublisher.Journal {
    Uri pending;
    String kind;
    TransferPublisher.Result result;
    IOException pendingFailure, completeFailure;
    int completions;

    @Override
    public void pending(Uri uri, String pendingKind) throws Exception {
      fixture.media.events.add("pending");
      pending = uri;
      kind = pendingKind;
      if (pendingFailure != null) throw pendingFailure;
    }

    @Override
    public void complete(TransferPublisher.Result value) throws Exception {
      assertTrue("完成日志与公开目标必须在同一提交锁内", Thread.holdsLock(gate));
      fixture.media.events.add("complete");
      completions++;
      if (completeFailure != null) throw completeFailure;
      result = value;
    }
  }

  private void publish(String destination, Uri target) throws Exception {
    TransferPublisher.publish(
        fixture.app,
        source,
        artifact,
        destination,
        "11111111-1111-1111-1111-111111111111",
        target,
        control,
        gate,
        journal);
  }

  @Test
  public void mediaJournalPrecedesOpenAndReadbackPrecedesVisibilityAndLockedCompletion()
      throws Exception {
    fixture.media.actualName = "report (2).bin";
    publish("downloads", null);
    assertEquals(
        List.of(
            "insert",
            "pending",
            "output-open",
            "output-close",
            "input-open",
            "input-close",
            "query",
            "visible",
            "complete"),
        fixture.media.events);
    assertEquals(fixture.media.last, journal.pending);
    assertEquals("media", journal.kind);
    Row row = fixture.media.require(journal.pending);
    assertArrayEquals(BYTES, row.bytes);
    assertEquals(0, row.pending());
    assertEquals("Download/DSHA/", row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
    assertEquals(
        "application/octet-stream", row.values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
    assertEquals(journal.pending, journal.result.uri());
    assertEquals("report (2).bin", journal.result.name());
    assertEquals("Download/DSHA", journal.result.location());
    assertEquals(1, journal.completions);
  }

  @Test
  public void sameLengthCorruptReadbackCannotCommitAndLeavesExactPendingRow() throws Exception {
    byte[] corrupt = BYTES.clone();
    corrupt[corrupt.length - 1] ^= 1;
    fixture.media.readback = corrupt;
    IOException failure = assertThrows(IOException.class, () -> publish("downloads", null));
    assertEquals("FILE_SHA256_MISMATCH", failure.getMessage());
    assertEquals(1, fixture.media.require(journal.pending).pending());
    assertArrayEquals("写入成功不能代替回读摘要核验", BYTES, fixture.media.require(journal.pending).bytes);
    assertEquals(0, journal.completions);
    assertFalse(fixture.media.events.contains("visible"));
    assertTrue(fixture.media.deleted.isEmpty());
    assertArrayEquals(BYTES, Files.readAllBytes(source.toPath()));
  }

  @Test
  public void failedPendingJournalDeletesOnlyNewMediaRowBeforeOpeningAnyStream() throws Exception {
    Uri unrelated = fixture.media.document("keep-existing.bin", BYTES);
    journal.pendingFailure = new IOException("journal pending unavailable");
    IOException failure = assertThrows(IOException.class, () -> publish("downloads", null));
    assertSame(journal.pendingFailure, failure);
    assertEquals(List.of("insert", "pending", "delete"), fixture.media.events);
    assertEquals(List.of(journal.pending), fixture.media.deleted);
    assertTrue(fixture.media.rows.containsKey(unrelated));
    assertFalse(fixture.media.rows.containsKey(journal.pending));
    assertEquals(0, journal.completions);
  }

  @Test
  public void rejectedVisibilityUpdateKeepsPendingBytesWithoutCompletion() throws Exception {
    fixture.media.updateCount = 0;
    IOException failure = assertThrows(IOException.class, () -> publish("downloads", null));
    assertEquals("FILE_PUBLICATION_FAILED", failure.getMessage());
    assertEquals(1, fixture.media.require(journal.pending).pending());
    assertArrayEquals(BYTES, fixture.media.require(journal.pending).bytes);
    assertTrue(fixture.media.events.contains("input-close"));
    assertEquals(0, journal.completions);
    assertTrue(fixture.media.deleted.isEmpty());
  }

  @Test
  public void failedCompleteJournalPreservesAlreadyVisibleVerifiedDestination() throws Exception {
    journal.completeFailure = new IOException("journal complete unavailable");
    IOException failure = assertThrows(IOException.class, () -> publish("downloads", null));
    assertSame(journal.completeFailure, failure);
    assertEquals(1, journal.completions);
    assertEquals(0, fixture.media.require(journal.pending).pending());
    assertArrayEquals(BYTES, fixture.media.require(journal.pending).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
    assertTrue(source.exists());
    assertNull(journal.result);
  }

  @Test
  public void cancellationAfterReadbackBeforeGateCannotMakeDestinationVisible() throws Exception {
    Documents.beforeQueryReturn = uri -> control.cancel();
    assertThrows(InterruptedIOException.class, () -> publish("downloads", null));
    assertEquals(1, fixture.media.require(journal.pending).pending());
    assertTrue(fixture.media.events.contains("input-close"));
    assertTrue(fixture.media.events.contains("query"));
    assertFalse(fixture.media.events.contains("visible"));
    assertEquals(0, journal.completions);
    assertTrue(fixture.media.deleted.isEmpty());
  }

  @Test
  public void failedSaveAsJournalPreservesCallerDocumentWithoutOpeningOrDeletingIt()
      throws Exception {
    Uri target = fixture.media.document("chosen-document.bin", BYTES);
    journal.pendingFailure = new IOException("save-as journal unavailable");
    assertSame(
        journal.pendingFailure, assertThrows(IOException.class, () -> publish("saveas", target)));
    assertEquals(target, journal.pending);
    assertEquals("saf", journal.kind);
    assertEquals(List.of("pending"), fixture.media.events);
    assertArrayEquals(BYTES, fixture.media.require(target).bytes);
    assertTrue(fixture.media.deleted.isEmpty());
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertEquals(0, journal.completions);
  }
}
