package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import com.deepseekharness.app.transfer.DownloadTask;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** 记录有界、失败诚实、重启不重放；持久字段不能成为另一张授权簿。 */
public final class SafTransferHistoryTest {
  @Test
  public void completedTransferKeepsOnlyPublicSummaryFields() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    SafTransferHistory history = new SafTransferHistory(prefs, new HashSet<>());
    String id = history.begin("report.md", true, 100);
    assertTrue(history.finish(id, 1234, "OK"));
    SafTransferHistory.Record record = history.list().get(0);
    assertEquals(DownloadTask.Status.COMPLETE, record.status);
    assertEquals(1234, record.bytes);
    assertEquals("report.md", record.name);
    assertTrue(record.importing);
    assertEquals("", record.code);
    for (String key : prefs.getAll().keySet()) {
      assertTrue(
          key.equals("records")
              || Set.of(":name", ":import", ":time", ":bytes", ":status", ":code")
                  .contains(key.substring(id.length())));
    }
  }

  @Test
  public void restartMarksUnfinishedRecordInterruptedWithoutCompletingOrReplaying() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    SafTransferHistory before = new SafTransferHistory(prefs, new HashSet<>());
    before.begin("report.md", false, 100);
    assertEquals(DownloadTask.Status.DOWNLOADING, before.list().get(0).status);
    SafTransferHistory after = new SafTransferHistory(prefs, new HashSet<>());
    assertEquals(DownloadTask.Status.FAILED, after.list().get(0).status);
    assertEquals("INTERRUPTED", after.list().get(0).code);
    assertEquals("DOWNLOADING", prefs.getString(after.list().get(0).id + ":status", ""));
  }

  @Test
  public void historyRotatesOldCompletedRecordsButKeepsActiveTransfer() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    SafTransferHistory history = new SafTransferHistory(prefs, new HashSet<>());
    String active = history.begin("active.txt", true, 0);
    String oldest = null;
    for (int index = 1; index <= SafTransferHistory.MAX_RECORDS; index++) {
      String id = history.begin("file-" + index + ".txt", false, index);
      if (oldest == null) oldest = id;
      assertTrue(history.finish(id, index, "OK"));
    }
    assertEquals(SafTransferHistory.MAX_RECORDS, history.list().size());
    assertEquals(SafTransferHistory.MAX_RECORDS, prefs.getStringSet("records", Set.of()).size());
    assertTrue(prefs.getStringSet("records", Set.of()).contains(active));
    assertFalse(prefs.getStringSet("records", Set.of()).contains(oldest));
    assertFalse(prefs.contains(oldest + ":name"));
    assertEquals("file-40.txt", history.list().get(0).name);
  }

  @Test
  public void fullActiveHistoryRefusesToDiscardInFlightRecords() {
    SafTransferHistory history =
        new SafTransferHistory(new SafTestSupport.FakePreferences(), new HashSet<>());
    for (int index = 0; index < SafTransferHistory.MAX_RECORDS; index++)
      history.begin("file.txt", true, index);
    try {
      history.begin("extra.txt", true, 100);
      fail("active records must not be evicted");
    } catch (IllegalStateException expected) {
      assertEquals("HISTORY_FULL", expected.getMessage());
    }
    assertEquals(SafTransferHistory.MAX_RECORDS, history.list().size());
  }

  @Test
  public void beginRequiresDurableRecordAndRejectsPathAsName() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    Set<String> active = new HashSet<>();
    SafTransferHistory history = new SafTransferHistory(prefs, active);
    prefs.commitResult = false;
    try {
      history.begin("report.md", true, 100);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("HISTORY_UNAVAILABLE", expected.getMessage());
    }
    assertTrue(active.isEmpty());
    assertTrue(history.list().isEmpty());
    try {
      history.begin("/root/report.md", true, 100);
      fail();
    } catch (IllegalArgumentException expected) {
      assertEquals("BAD_NAME", expected.getMessage());
    }
  }

  @Test
  public void rawExceptionTextIsNeverPersistedAsAnError() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    SafTransferHistory history = new SafTransferHistory(prefs, new HashSet<>());
    String id = history.begin("file.txt", false, 0);
    assertTrue(history.finish(id, 99, "content://secret.provider/token=secret"));
    assertEquals("IO_ERROR", history.list().get(0).code);
    assertEquals(DownloadTask.Status.FAILED, history.list().get(0).status);
    assertEquals(0, history.list().get(0).bytes);
    assertFalse(prefs.getAll().toString().contains("secret"));
  }

  @Test
  public void failedFinishIsReportedAndBecomesInterruptedAfterActiveOwnerLeaves() {
    SafTestSupport.FakePreferences prefs = new SafTestSupport.FakePreferences();
    Set<String> active = new HashSet<>();
    SafTransferHistory history = new SafTransferHistory(prefs, active);
    String id = history.begin("file.txt", true, 1);
    prefs.commitResult = false;
    assertFalse(history.finish(id, 12, "OK"));
    assertFalse(active.contains(id));
    assertEquals("INTERRUPTED", history.list().get(0).code);
    assertEquals(DownloadTask.Status.FAILED, history.list().get(0).status);
  }
}
