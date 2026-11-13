package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.Assert.*;

public class Rc1MigrationResultTest {
  private static Map<String, Object> current() {
    return Map.of(
        "resultVersion", 3L,
        "status", "prepared",
        "verificationScope", "current",
        "contentCopyStatus", "verified",
        "sourceObjectIdentity", "unknown",
        "settingsImportStatus", "pending");
  }

  private static Map<String, Object> historical() {
    return Map.of(
        "resultVersion", 3L,
        "status", "historical-complete",
        "verificationScope", "historical",
        "contentCopyStatus", "verified",
        "sourceObjectIdentity", "unknown",
        "settingsImportStatus", "verified-historical");
  }

  @Test
  public void parsesExactRecordWithBothLineEndings() throws Exception {
    assertTrue(
        Rc1MigrationResult.allowsStart(
            Rc1MigrationResult.parse(
                "notice\r\nDSHA_RC1_MIGRATION={\"resultVersion\":3,\"status\":\"prepared\","
                    + "\"verificationScope\":\"current\",\"contentCopyStatus\":\"verified\"}\r\n")));
    assertTrue(
        Rc1MigrationResult.allowsStart(
            Rc1MigrationResult.parse(
                "DSHA_RC1_MIGRATION={\"resultVersion\":3,\"status\":\"historical-complete\","
                    + "\"verificationScope\":\"historical\",\"contentCopyStatus\":\"verified\"}\n")));
  }

  @Test
  public void statusTextCannotReplaceCurrentVerification() throws Exception {
    assertFalse(Rc1MigrationResult.allowsStart(Map.of("status", "prepared")));
    assertFalse(Rc1MigrationResult.allowsStart(Map.of("resultVersion", 2L, "status", "prepared")));
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of(
                "resultVersion", 3L,
                "status", "prepared",
                "verificationScope", "current",
                "contentCopyStatus", "unknown")));
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of(
                "resultVersion", 3L,
                "status", "failed",
                "verificationScope", "current",
                "contentCopyStatus", "verified")));
    assertFalse(Rc1MigrationResult.allowsStart(Map.of("status", "skipped", "reason", "ERROR")));
    assertTrue(
        Rc1MigrationResult.allowsStart(
            Map.of("resultVersion", 3L, "status", "skipped", "reason", "DSH_MISSING")));
    assertThrows(
        IOException.class, () -> Rc1MigrationResult.parse("ERROR: {\"status\":\"prepared\"}"));
    assertThrows(
        IOException.class,
        () -> Rc1MigrationResult.parse("DSHA_RC1_MIGRATION={}\nDSHA_RC1_MIGRATION={}"));
  }

  @Test
  public void historicalCompletionAllowsOnlyOrdinaryStartWithoutMigrationWrites() {
    assertTrue(Rc1MigrationResult.allowsStart(historical()));
    // 历史完成不是本次核验，缺 scope/copy 或字段类型不对都不能授予启动。
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of("resultVersion", 3L, "status", "historical-complete")));
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of(
                "resultVersion", 3L,
                "status", "historical-complete",
                "verificationScope", "current",
                "contentCopyStatus", "verified",
                "settingsImportStatus", "verified-historical")));
    assertFalse(
        Rc1MigrationResult.allowsStart(
            Map.of(
                "resultVersion", "3",
                "status", "historical-complete",
                "verificationScope", "historical",
                "contentCopyStatus", "verified")));
  }

  @Test
  public void routineReceiptsAreQuietButUnknownOrHistoricalGapsStayVisible() {
    assertTrue(Rc1MigrationResult.isRoutineReceipt(current()));
    assertTrue(Rc1MigrationResult.isRoutineReceipt(historical()));
    assertTrue(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of(
                "resultVersion", 3L,
                "status", "committed",
                "verificationScope", "current",
                "contentCopyStatus", "verified",
                "settingsImportStatus", "verified-current")));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of(
                "resultVersion", 3L,
                "status", "historical-complete",
                "verificationScope", "historical",
                "contentCopyStatus", "verified",
                "settingsImportStatus", "unknown")));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("resultVersion", 3L, "status", "unknown", "reason", "STALE_STARTUP_BINDING")));
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of(
                "resultVersion", 3L,
                "status", "pending",
                "verificationScope", "current",
                "contentCopyStatus", "verified",
                "settingsImportStatus", "pending")));
    // 旧 v2 回执不再静默成功。
    assertFalse(
        Rc1MigrationResult.isRoutineReceipt(
            Map.of("status", "committed", "sourcePreserved", true, "settingsImported", true)));
  }
}
