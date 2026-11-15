package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PluginManagementViewTest {
  private PluginManagementView available(Boolean limited, List<String> reasons) {
    return new PluginManagementView(
        "available",
        "MANAGEMENT_AVAILABLE",
        "SERVICE_PRESENCE",
        "present-unverified",
        "PATCH_PROVENANCE_UNVERIFIED",
        "STARTUP_METADATA",
        limited,
        reasons,
        10);
  }

  @Test
  public void servicePresenceDoesNotProvePatchChainOrEmptyInventory() {
    PluginManagementView view = available(false, List.of()).withWatch(false, List.of());
    assertEquals(List.of(PluginManagementView.Notice.PATCH_UNVERIFIED), view.notices());
    assertEquals("available", view.managementState);
    assertEquals("present-unverified", view.patchState);
  }

  @Test
  public void unavailableAndUnknownAreDifferentAndNeitherMeansZeroPlugins() {
    var absent =
        new PluginManagementView(
            "unavailable",
            "MANAGEMENT_SERVICE_ABSENT",
            "SERVICE_PRESENCE",
            "unknown",
            "LEGACY_PROTOCOL",
            "",
            null,
            null,
            0);
    assertTrue(absent.notices().contains(PluginManagementView.Notice.MANAGEMENT_UNAVAILABLE));
    var unknown = PluginManagementView.unknown("LEGACY_PROTOCOL");
    assertTrue(unknown.notices().contains(PluginManagementView.Notice.MANAGEMENT_UNKNOWN));
    assertNotEquals(absent.contentKey, unknown.contentKey);
    assertEquals(
        "unknown",
        new PluginManagementView(
                "available",
                "MANAGEMENT_AVAILABLE",
                "",
                "verified",
                "PATCH_OK",
                "STARTUP_METADATA",
                false,
                List.of(),
                1)
            .managementState);
  }

  @Test
  public void backgroundAndPendingWatcherCannotClaimCompleteCoverage() {
    for (String reason : List.of("WATCH_NOT_OBSERVING", "WATCH_PENDING", "WATCH_NO_PATHS")) {
      var view = available(false, List.of()).withWatch(true, List.of(reason));
      assertTrue(view.notices().contains(PluginManagementView.Notice.COVERAGE_UNKNOWN));
      assertFalse(view.notices().contains(PluginManagementView.Notice.COVERAGE_LIMITED));
    }
  }

  @Test
  public void nativeCoverageCannotBeOverriddenByGuestCompleteClaim() {
    var limited = available(false, List.of()).withWatch(true, List.of("WATCH_DIRECTORY_LIMIT"));
    assertTrue(limited.notices().contains(PluginManagementView.Notice.COVERAGE_LIMITED));
    var runtimeLimited = available(true, List.of("FRAME_BYTE_LIMIT")).withWatch(false, List.of());
    assertTrue(runtimeLimited.notices().contains(PluginManagementView.Notice.COVERAGE_LIMITED));
    assertFalse(available(null, null).withWatch(false, List.of()).notices().isEmpty());
  }

  @Test
  public void reasonsAreBoundedDetachedAndDoNotExposePaths() {
    List<String> reasons = new ArrayList<>(List.of("WATCH_ENTRY_LIMIT", "/root/private"));
    var view = available(false, List.of()).withWatch(true, reasons);
    reasons.clear();
    assertEquals(List.of("WATCH_ENTRY_LIMIT", "WATCH_COVERAGE_UNKNOWN"), view.watchReasons);
    assertFalse(view.contentKey.contains("/root"));
    assertThrows(UnsupportedOperationException.class, () -> view.watchReasons.add("WATCH_PENDING"));
    assertThrows(UnsupportedOperationException.class, () -> view.notices().clear());
  }

  @Test
  public void actualWatcherInvalidationCodeSurvivesConsumerProjection() {
    var value = available(false, List.of()).withWatch(true, List.of("WATCH_INVALIDATED"));
    assertEquals(List.of("WATCH_INVALIDATED"), value.watchReasons);
    assertTrue(value.notices().contains(PluginManagementView.Notice.COVERAGE_LIMITED));
  }

  @Test
  public void patchMismatchIsNotHiddenByAvailableManager() {
    var mismatch =
        new PluginManagementView(
            "available",
            "MANAGEMENT_AVAILABLE",
            "SERVICE_PRESENCE",
            "mismatch",
            "PATCH_VERSION_MISMATCH",
            "STARTUP_METADATA",
            false,
            List.of(),
            10);
    assertTrue(mismatch.notices().contains(PluginManagementView.Notice.PATCH_MISMATCH));
    assertFalse(mismatch.notices().contains(PluginManagementView.Notice.MANAGEMENT_UNAVAILABLE));
  }
}
