package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PluginRuntimeEvidenceTest {
  private static final String A = "a".repeat(64), B = "b".repeat(64);
  private static final PluginEntryView ENTRY =
      new PluginEntryView("child", A, "group", false, null, "PROFILE_UNSET");

  @Test
  public void moduleNameHashCannotSubstituteForDeploymentContentProof() {
    assertEquals("CONTENT_UNVERIFIED", PluginRuntimeEvidence.contentRejection("", A, "", A));
    assertEquals(
        "CONTENT_UNVERIFIED",
        PluginRuntimeEvidence.contentRejection(
            PluginRuntimeEvidence.CONTENT_ALGORITHM, A, "module-name-sha256", A));
    assertEquals(
        "",
        PluginRuntimeEvidence.contentRejection(
            PluginRuntimeEvidence.CONTENT_ALGORITHM,
            A,
            PluginRuntimeEvidence.CONTENT_ALGORITHM,
            A));
    assertEquals(
        "CONTENT_CHANGED",
        PluginRuntimeEvidence.contentRejection(
            PluginRuntimeEvidence.CONTENT_ALGORITHM,
            A,
            PluginRuntimeEvidence.CONTENT_ALGORITHM,
            B));
  }

  @Test
  public void sameVersionEntryStillNeedsExactModuleParentAndGroup() {
    assertEquals("", PluginRuntimeEvidence.entryRejection(ENTRY, "child", A, "group", false));
    assertEquals(
        "ENTRY_ID", PluginRuntimeEvidence.entryRejection(ENTRY, "other", A, "group", false));
    assertEquals(
        "ENTRY_MODULE", PluginRuntimeEvidence.entryRejection(ENTRY, "child", B, "group", false));
    assertEquals(
        "ENTRY_PARENT", PluginRuntimeEvidence.entryRejection(ENTRY, "child", A, "other", false));
    assertEquals(
        "ENTRY_GROUP", PluginRuntimeEvidence.entryRejection(ENTRY, "child", A, "group", true));
  }

  @Test
  public void sessionEvidenceNeverInventsHostTicketAndExpiresAtTheBoundary() {
    assertEquals("", identity("OBSERVER_SESSION", "unknown", "7", "instance", 70_000));
    assertEquals("EXPIRED", identity("OBSERVER_SESSION", "unknown", "7", "instance", 69_999));
    assertEquals("FUTURE_TIME", identity("OBSERVER_SESSION", "unknown", "7", "instance", 100_001));
    assertEquals(
        "IDENTITY_UNBOUND",
        identity("OBSERVER_SESSION", "pretend-startup", "7", "instance", 100_000));
    assertEquals(
        "IDENTITY_UNBOUND", identity("HOST_START_TICKET", "unknown", "7", "instance", 100_000));
    assertEquals(
        "",
        identity(
            "HOST_START_TICKET", "12345678-1234-1234-1234-123456789abc", "7", "instance", 100_000));
    assertEquals("GENERATION", identity("OBSERVER_SESSION", "unknown", "6", "instance", 100_000));
    assertEquals("INSTANCE", identity("OBSERVER_SESSION", "unknown", "7", "older", 100_000));
    assertEquals("IDENTITY_UNBOUND", identity("UNATTRIBUTED", "unknown", "7", "instance", 100_000));
  }

  @Test
  public void absentUnloadingAndUnverifiedObservationRemainDifferentFromIntent() {
    PluginEntryView absent = ENTRY.withRuntime(null, "NO_LIVE_ROOT", "", 10);
    assertTrue(absent.runtimeObserved);
    assertNull(absent.runtimePhase);
    assertTrue(absent.runtimeVerified());
    assertNull(absent.patchDisabled);
    PluginEntryView unloading = ENTRY.withRuntime("unloading", "", "CONTENT_UNVERIFIED", 11);
    assertTrue(unloading.runtimeObserved);
    assertFalse(unloading.runtimeVerified());
    assertEquals("unloading", unloading.runtimePhase);
    assertNotEquals(absent.contentKey, unloading.contentKey);
    assertFalse(ENTRY.withRuntime("new-state", "", "", 12).runtimeObserved);
  }

  @Test
  public void finalHeartbeatExpiresOnceAtItsOwnDeadlineEvenAfterEarlierActivityCheck() {
    assertEquals(30_001, PluginRuntimeEvidence.remainingLifetime(100_000, 100_000, 30_000));
    assertEquals(20_000, PluginRuntimeEvidence.remainingLifetime(100_000, 110_001, 30_000));
    assertEquals(1, PluginRuntimeEvidence.remainingLifetime(100_000, 130_000, 30_000));
    assertEquals(-1, PluginRuntimeEvidence.remainingLifetime(100_000, 130_001, 30_000));
    assertEquals(-1, PluginRuntimeEvidence.remainingLifetime(0, 100_000, 30_000));
    assertEquals(-1, PluginRuntimeEvidence.remainingLifetime(100_001, 100_000, 30_000));
  }

  private String identity(
      String source, String startup, String generation, String instance, long at) {
    return PluginRuntimeEvidence.identityRejection(
        source, startup, generation, instance, "7", "instance", at, 100_000, 30_000);
  }
}
