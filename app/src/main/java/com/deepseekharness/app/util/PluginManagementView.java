package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 管理面、补丁声明与观察覆盖各自独立；这些只读事实不授予插件写入或退出确认能力。 */
public final class PluginManagementView {
  public enum Notice {
    MANAGEMENT_UNAVAILABLE,
    MANAGEMENT_UNKNOWN,
    PATCH_UNVERIFIED,
    PATCH_UNAVAILABLE,
    PATCH_MISMATCH,
    COVERAGE_LIMITED,
    COVERAGE_UNKNOWN
  }

  private static final Set<String> RUNTIME_REASONS =
      Set.of(
          "METADATA_UNAVAILABLE",
          "METADATA_LIMIT",
          "LOADER_UNAVAILABLE",
          "LOADER_LIMIT",
          "ENTRY_UNADDRESSABLE",
          "FRAME_BYTE_LIMIT",
          "DYNAMIC_BYTE_LIMIT",
          "SESSION_IDENTITY_UNVERIFIED",
          "RUNTIME_COVERAGE_UNKNOWN");
  private static final Set<String> WATCH_REASONS =
      Set.of(
          "WATCH_NOT_OBSERVING",
          "WATCH_PENDING",
          "WATCH_PATH_LIMIT",
          "WATCH_DIRECTORY_LIMIT",
          "WATCH_ENTRY_LIMIT",
          "WATCH_DEPTH_LIMIT",
          "WATCH_TIME_LIMIT",
          "WATCH_OBSERVER_FAILED",
          "WATCH_PATH_UNREADABLE",
          "WATCH_PATH_CHANGED",
          "WATCH_UNSAFE_PATH",
          "WATCH_EVENT_OVERFLOW",
          "WATCH_INVALIDATED",
          "WATCH_NO_PATHS",
          "WATCH_COVERAGE_UNKNOWN");

  public final String managementState, managementReason, patchState, patchReason, contentKey;
  public final List<String> runtimeReasons, watchReasons;
  public final boolean runtimeLimited, watchLimited;
  public final long observedAt;

  public PluginManagementView(
      String managementState,
      String managementReason,
      String managementEvidence,
      String patchState,
      String patchReason,
      String patchEvidence,
      Boolean runtimeLimited,
      List<String> runtimeReasons,
      long observedAt) {
    this(
        managementState,
        managementReason,
        managementEvidence,
        patchState,
        patchReason,
        patchEvidence,
        runtimeLimited,
        runtimeReasons,
        observedAt,
        true,
        List.of("WATCH_NOT_OBSERVING"));
  }

  private PluginManagementView(
      String managementState,
      String managementReason,
      String managementEvidence,
      String patchState,
      String patchReason,
      String patchEvidence,
      Boolean runtimeLimited,
      List<String> runtimeReasons,
      long observedAt,
      boolean watchLimited,
      List<String> watchReasons) {
    boolean serviceEvidence = "SERVICE_PRESENCE".equals(managementEvidence);
    boolean available =
        serviceEvidence
            && "available".equals(managementState)
            && "MANAGEMENT_AVAILABLE".equals(managementReason);
    boolean absent =
        serviceEvidence
            && "unavailable".equals(managementState)
            && "MANAGEMENT_SERVICE_ABSENT".equals(managementReason);
    this.managementState = available ? "available" : absent ? "unavailable" : "unknown";
    this.managementReason =
        available
            ? "MANAGEMENT_AVAILABLE"
            : absent
                ? "MANAGEMENT_SERVICE_ABSENT"
                : safeReason(managementReason, "MANAGEMENT_OBSERVATION_FAILED");
    boolean patchEvidenceKnown = "STARTUP_METADATA".equals(patchEvidence);
    this.patchState =
        patchEvidenceKnown
                && Set.of("present-unverified", "unavailable", "mismatch")
                    .contains(String.valueOf(patchState))
            ? patchState
            : "unknown";
    this.patchReason = safeReason(patchReason, "PATCH_PROVENANCE_UNVERIFIED");
    this.runtimeReasons = reasons(runtimeReasons, RUNTIME_REASONS, "RUNTIME_COVERAGE_UNKNOWN");
    this.runtimeLimited =
        runtimeLimited == null || runtimeLimited || !this.runtimeReasons.isEmpty();
    this.watchReasons = reasons(watchReasons, WATCH_REASONS, "WATCH_COVERAGE_UNKNOWN");
    this.watchLimited = watchLimited || !this.watchReasons.isEmpty();
    this.observedAt = Math.max(0, observedAt);
    contentKey =
        String.join(
            "|",
            this.managementState,
            this.managementReason,
            this.patchState,
            this.patchReason,
            Boolean.toString(this.runtimeLimited),
            this.runtimeReasons.toString(),
            Boolean.toString(this.watchLimited),
            this.watchReasons.toString());
  }

  public static PluginManagementView unknown(String reason) {
    return new PluginManagementView(
        "unknown", reason, "", "unknown", reason, "", null, List.of("RUNTIME_COVERAGE_UNKNOWN"), 0);
  }

  public PluginManagementView withWatch(boolean limited, List<String> reasons) {
    return new PluginManagementView(
        managementState,
        managementReason,
        "SERVICE_PRESENCE",
        patchState,
        patchReason,
        "unknown".equals(patchState) ? "" : "STARTUP_METADATA",
        runtimeLimited,
        runtimeReasons,
        observedAt,
        limited,
        reasons);
  }

  public List<Notice> notices() {
    List<Notice> result = new ArrayList<>();
    if ("unavailable".equals(managementState)) result.add(Notice.MANAGEMENT_UNAVAILABLE);
    else if (!"available".equals(managementState)) result.add(Notice.MANAGEMENT_UNKNOWN);
    result.add(
        switch (patchState) {
          case "unavailable" -> Notice.PATCH_UNAVAILABLE;
          case "mismatch" -> Notice.PATCH_MISMATCH;
          default -> Notice.PATCH_UNVERIFIED;
        });
    boolean unknownRuntime =
        runtimeReasons.contains("RUNTIME_COVERAGE_UNKNOWN")
            || runtimeReasons.contains("METADATA_UNAVAILABLE")
            || runtimeReasons.contains("SESSION_IDENTITY_UNVERIFIED");
    boolean unknownWatch =
        watchReasons.stream()
            .anyMatch(
                value ->
                    Set.of(
                            "WATCH_NOT_OBSERVING",
                            "WATCH_PENDING",
                            "WATCH_NO_PATHS",
                            "WATCH_COVERAGE_UNKNOWN")
                        .contains(value));
    if (runtimeLimited && !unknownRuntime || watchLimited && !unknownWatch)
      result.add(Notice.COVERAGE_LIMITED);
    else if (unknownRuntime || unknownWatch) result.add(Notice.COVERAGE_UNKNOWN);
    return Collections.unmodifiableList(result);
  }

  private static String safeReason(String value, String fallback) {
    return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : fallback;
  }

  private static List<String> reasons(List<String> supplied, Set<String> allowed, String fallback) {
    if (supplied == null) return List.of(fallback);
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String reason : supplied) {
      if (result.size() >= 8) {
        result.add(fallback);
        break;
      }
      result.add(allowed.contains(String.valueOf(reason)) ? reason : fallback);
    }
    if (result.size() > 8) {
      result = new LinkedHashSet<>(new ArrayList<>(result).subList(0, 7));
      result.add(fallback);
    }
    return List.copyOf(result);
  }
}
