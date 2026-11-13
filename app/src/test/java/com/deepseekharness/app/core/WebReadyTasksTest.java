package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class WebReadyTasksTest {
  static final class Fake implements WebReadyTasks.Ports {
    boolean current = true, bound, cancelOnCookie, cancelOnDelay;
    int exchanges, notices, ready;
    String result =
        "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"status\":\"historical-complete\","
            + "\"verificationScope\":\"historical\",\"contentCopyStatus\":\"verified\","
            + "\"settingsImportStatus\":\"verified-historical\"}";
    Runnable async;
    String startupId;
    java.util.List<String> logs = new java.util.ArrayList<>();
    java.util.List<Long> delays = new java.util.ArrayList<>();

    public boolean current() {
      return current;
    }

    public void asynchronous(String name, Runnable work) {
      async = work;
    }

    public void delay(long ms) {
      delays.add(ms);
      if (cancelOnDelay) current = false;
    }

    public String finalizeMigration() {
      return result;
    }

    public String startupId() {
      return startupId;
    }

    public void migrationLog(String value) {
      logs.add(value);
    }

    public void migrationNeedsAttention() {
      notices++;
    }

    public boolean exchangeCookie() {
      exchanges++;
      if (cancelOnCookie) current = false;
      return false;
    }

    public boolean lanBound() {
      return bound;
    }

    public void lanReady() {
      ready++;
    }
  }

  @Test
  public void successfulFinalizationIsQuietAndIncompleteCommitIsVisible() {
    Fake ok = new Fake();
    WebReadyTasks.run(false, false, ok);
    ok.async.run();
    assertEquals(java.util.List.of(3000L), ok.delays);
    assertEquals(0, ok.notices);
    assertTrue(ok.logs.isEmpty());
    Fake bad = new Fake();
    bad.result =
        "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"status\":\"pending\","
            + "\"verificationScope\":\"current\",\"contentCopyStatus\":\"verified\","
            + "\"settingsImportStatus\":\"pending\"}";
    WebReadyTasks.run(false, false, bad);
    bad.async.run();
    assertEquals(1, bad.notices);
    assertEquals(1, bad.logs.size());
  }

  @Test
  public void unknownOrHistoricalGapIsVisibleAndNotRetried() {
    Fake unknown = new Fake();
    unknown.result =
        "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"phase\":\"finalize\","
            + "\"status\":\"unknown\",\"reason\":\"STALE_STARTUP_BINDING\","
            + "\"verificationScope\":\"none\",\"contentCopyStatus\":\"unknown\"}";
    WebReadyTasks.run(false, false, unknown);
    unknown.async.run();
    assertEquals(1, unknown.notices);
    assertEquals(1, unknown.logs.size());
    // historical 完成但设置账本未知：可见待处理，不能静默。
    Fake gap = new Fake();
    gap.result =
        "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"status\":\"historical-complete\","
            + "\"verificationScope\":\"historical\",\"contentCopyStatus\":\"verified\","
            + "\"settingsImportStatus\":\"unknown\"}";
    WebReadyTasks.run(false, false, gap);
    gap.async.run();
    assertEquals(1, gap.notices);
    assertEquals(1, gap.logs.size());
  }

  @Test
  public void receiptMustBindTheCurrentStartupTicket() {
    Fake mismatched = new Fake();
    mismatched.startupId = "current-startup";
    WebReadyTasks.run(false, false, mismatched);
    mismatched.async.run();
    assertEquals(1, mismatched.logs.size());
    assertEquals("RC1_MIGRATION_STARTUP_BINDING", mismatched.logs.get(0));
    assertEquals(1, mismatched.notices);
    Fake bound = new Fake();
    bound.startupId = "current-startup";
    bound.result =
        "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"startupId\":\"current-startup\","
            + "\"status\":\"historical-complete\",\"verificationScope\":\"historical\","
            + "\"contentCopyStatus\":\"verified\","
            + "\"settingsImportStatus\":\"verified-historical\"}";
    WebReadyTasks.run(false, false, bound);
    bound.async.run();
    assertTrue(bound.logs.isEmpty());
    assertEquals(0, bound.notices);
  }

  @Test
  public void unparseableOrDuplicateReceiptIsVisibleUnknownNotSilent() {
    for (String broken :
        java.util.List.of(
            "RC1_MIGRATION_ERROR: timeout", "DSHA_RC1_MIGRATION={}\nDSHA_RC1_MIGRATION={}")) {
      Fake fake = new Fake();
      fake.result = broken;
      WebReadyTasks.run(false, false, fake);
      fake.async.run();
      assertEquals(broken, 1, fake.logs.size());
      assertEquals(broken, 1, fake.notices);
    }
  }

  @Test
  public void staleMigrationCannotLogOrWarn() {
    Fake fake = new Fake();
    WebReadyTasks.run(false, false, fake);
    fake.cancelOnDelay = true;
    fake.async.run();
    assertTrue(fake.logs.isEmpty());
    assertEquals(0, fake.notices);
  }

  @Test
  public void lanRetriesAndPollingAreBoundedAndSafeModeSkipsMigration() {
    Fake fake = new Fake();
    WebReadyTasks.run(true, true, fake);
    assertNull(fake.async);
    assertEquals(3, fake.exchanges);
    assertEquals(15, fake.delays.size());
    assertEquals(0, fake.ready);
    Fake bound = new Fake();
    bound.bound = true;
    WebReadyTasks.run(true, true, bound);
    assertEquals(1, bound.ready);
  }

  @Test
  public void generationLossDoesNotPublishLanReadiness() {
    Fake fake = new Fake();
    fake.bound = true;
    fake.cancelOnCookie = true;
    WebReadyTasks.run(true, true, fake);
    assertEquals(1, fake.exchanges);
    assertEquals(0, fake.ready);
  }
}
