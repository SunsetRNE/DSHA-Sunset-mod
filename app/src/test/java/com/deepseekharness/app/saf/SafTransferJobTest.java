package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import com.deepseekharness.app.transfer.DownloadTask;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/** 模型使用的同一纯逻辑流程：目标确认、记录先行、写后收尾、错误不重放。 */
public final class SafTransferJobTest {
  private record Ticket(String path) {}

  private static final class Fake implements SafTransferJob.Ports<Ticket> {
    final List<String> events = new ArrayList<>();
    final SafTransferHistory history =
        new SafTransferHistory(new SafTestSupport.FakePreferences(), new HashSet<>());
    boolean enabled = true;
    String failAt;
    String failure = "HANDLE_REVOKED";
    boolean failFinish;
    boolean throwFinish;

    void stage(String name) throws IOException {
      events.add(name);
      if (name.equals(failAt)) throw new IOException(failure);
    }

    @Override
    public boolean enabled() {
      return enabled;
    }

    @Override
    public void openHistory() throws IOException {
      stage("open");
    }

    @Override
    public Ticket retarget(Ticket ticket, String path) throws IOException {
      stage("retarget");
      return new Ticket(path);
    }

    @Override
    public String path(Ticket ticket) {
      return ticket.path;
    }

    @Override
    public String beginRecord(String name, boolean importing, long time) throws IOException {
      stage("begin");
      return history.begin(name, importing, time);
    }

    @Override
    public long transfer(Ticket ticket, String guestPath, boolean importing) throws IOException {
      stage("transfer");
      return 7;
    }

    @Override
    public boolean finishRecord(String id, long bytes, String code) throws IOException {
      events.add("finish");
      if (throwFinish) throw new IOException("disk unavailable");
      return !failFinish && history.finish(id, bytes, code);
    }

    @Override
    public void cancel(Ticket ticket) {
      events.add("cancel:" + ticket.path);
    }
  }

  private static SafTransferJob.Result run(Fake fake, boolean importing) {
    return SafTransferJob.run(
        fake,
        new Ticket("folder/selected.txt"),
        "folder/export.txt",
        "/root/work.txt",
        importing,
        100);
  }

  @Test
  public void exportRetargetsBeforeDurableRecordThenWritesAndFinishes() {
    Fake fake = new Fake();
    SafTransferJob.Result result = run(fake, false);
    assertEquals("OK", result.code);
    assertEquals(7, result.bytes);
    assertEquals(
        List.of(
            "open",
            "retarget",
            "begin",
            "transfer",
            "cancel:folder/selected.txt",
            "cancel:folder/export.txt",
            "finish"),
        fake.events);
    SafTransferHistory.Record record = fake.history.list().get(0);
    assertEquals("export.txt", record.name);
    assertFalse(record.importing);
    assertEquals(DownloadTask.Status.COMPLETE, record.status);
  }

  @Test
  public void importKeepsSelectedTargetAndReleasesItsTicketOnce() {
    Fake fake = new Fake();
    assertEquals("OK", run(fake, true).code);
    assertEquals(
        List.of("open", "begin", "transfer", "cancel:folder/selected.txt", "finish"), fake.events);
    assertEquals("selected.txt", fake.history.list().get(0).name);
    assertTrue(fake.history.list().get(0).importing);
  }

  @Test
  public void disabledWorkbenchDoesNotOpenRecordOrTransfer() {
    Fake fake = new Fake();
    fake.enabled = false;
    assertEquals("WORKBENCH_DISABLED", run(fake, false).code);
    assertEquals(List.of("cancel:folder/selected.txt"), fake.events);
  }

  @Test
  public void historyOpenRetargetAndBeginFailuresNeverReachDataWriter() {
    for (String stage : List.of("open", "retarget", "begin")) {
      Fake fake = new Fake();
      fake.failAt = stage;
      fake.failure = stage.equals("retarget") ? "HANDLE_EXPIRED" : "HISTORY_UNAVAILABLE";
      assertEquals(fake.failure, run(fake, false).code);
      assertFalse(fake.events.contains("transfer"));
      assertFalse(fake.events.contains("finish"));
      assertTrue(fake.history.list().isEmpty());
      assertTrue(fake.events.contains("cancel:folder/selected.txt"));
    }
  }

  @Test
  public void transferFailureIsRecordedExactlyOnceAndNeverReplayed() {
    Fake fake = new Fake();
    fake.failAt = "transfer";
    assertEquals("HANDLE_REVOKED", run(fake, true).code);
    assertEquals(1, java.util.Collections.frequency(fake.events, "transfer"));
    assertEquals(1, java.util.Collections.frequency(fake.events, "finish"));
    assertEquals(DownloadTask.Status.FAILED, fake.history.list().get(0).status);
    assertEquals("HANDLE_REVOKED", fake.history.list().get(0).code);
  }

  @Test
  public void finishFailureDoesNotTurnSuccessfulCopyIntoAReplayableFailure() {
    for (boolean throwing : List.of(false, true)) {
      Fake fake = new Fake();
      fake.failFinish = true;
      fake.throwFinish = throwing;
      SafTransferJob.Result result = run(fake, true);
      assertEquals("OK_HISTORY_UNAVAILABLE", result.code);
      assertEquals(7, result.bytes);
      assertEquals(1, java.util.Collections.frequency(fake.events, "transfer"));
      assertEquals(1, java.util.Collections.frequency(fake.events, "finish"));
    }
  }

  @Test
  public void recordFinishErrorDoesNotHideOriginalTransferFailure() {
    Fake fake = new Fake();
    fake.failAt = "transfer";
    fake.throwFinish = true;
    assertEquals("HANDLE_REVOKED", run(fake, false).code);
  }

  @Test
  public void failureCodeKeepsOnlyCompleteShortCodesAndDropsPrivateText() {
    for (String code :
        List.of("TIMEOUT", "CANCELLED", "PERMISSION_DENIED", "HANDLE_REVOKED", "MAINTENANCE_BUSY"))
      assertEquals(code, SafTransferJob.failureCode(new IOException(code)));
    for (String message :
        List.of("content://private/tree/key", "IO_ERROR: /root/.ssh/key", "", "a".repeat(100)))
      assertEquals("IO_ERROR", SafTransferJob.failureCode(new IOException(message)));
    assertEquals("IO_ERROR", SafTransferJob.failureCode(new IOException()));
  }
}
