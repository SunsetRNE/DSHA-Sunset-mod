package com.deepseekharness.app.util;

/** 当前 profile 的 entry 配置投影；字面 override 不等同于最终启用或运行状态。 */
public final class PluginEntryView {
  public final String entryId, moduleHash, parentId, patchReason, contentKey;
  public final String runtimePhase, runtimeDiagnostic, runtimeReason;
  public final long runtimeObservedAt;
  public final boolean group, runtimeObserved;
  public final Boolean patchDisabled, managementReadOnly;
  public final String managementReadOnlyReason;

  public PluginEntryView(
      String entryId,
      String moduleHash,
      String parentId,
      boolean group,
      Boolean patchDisabled,
      String patchReason) {
    this(
        entryId,
        moduleHash,
        parentId,
        group,
        patchDisabled,
        patchReason,
        false,
        "unknown",
        "",
        "NO_EVIDENCE",
        0,
        null,
        "PROTECTION_UNVERIFIED");
  }

  private PluginEntryView(
      String entryId,
      String moduleHash,
      String parentId,
      boolean group,
      Boolean patchDisabled,
      String patchReason,
      boolean runtimeObserved,
      String runtimePhase,
      String runtimeDiagnostic,
      String runtimeReason,
      long runtimeObservedAt,
      Boolean managementReadOnly,
      String managementReadOnlyReason) {
    if (!validId(entryId, false)
        || !validId(parentId, true)
        || moduleHash == null
        || !moduleHash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("PLUGIN_ENTRY_IDENTITY");
    this.entryId = entryId;
    this.moduleHash = moduleHash;
    this.parentId = parentId;
    this.group = group;
    this.patchDisabled = patchDisabled;
    this.patchReason =
        patchReason != null && patchReason.matches("[A-Z0-9_]{1,64}") ? patchReason : "UNKNOWN";
    this.runtimeObserved = runtimeObserved;
    this.runtimePhase = runtimePhase;
    this.runtimeDiagnostic = code(runtimeDiagnostic, "UNKNOWN");
    this.runtimeReason = code(runtimeReason, "EVIDENCE_INVALID");
    this.runtimeObservedAt = runtimeObservedAt;
    this.managementReadOnly =
        Boolean.TRUE.equals(managementReadOnly)
                && "MANAGEMENT_REQUIRED".equals(managementReadOnlyReason)
            ? Boolean.TRUE
            : null;
    this.managementReadOnlyReason =
        this.managementReadOnly == Boolean.TRUE
                && "MANAGEMENT_REQUIRED".equals(managementReadOnlyReason)
            ? "MANAGEMENT_REQUIRED"
            : "MANAGEMENT_UNAVAILABLE".equals(managementReadOnlyReason)
                ? "MANAGEMENT_UNAVAILABLE"
                : "PROTECTION_UNVERIFIED";
    contentKey =
        key(
            entryId,
            moduleHash,
            parentId,
            Boolean.toString(group),
            String.valueOf(patchDisabled),
            this.patchReason,
            Boolean.toString(runtimeObserved),
            String.valueOf(runtimePhase),
            this.runtimeDiagnostic,
            this.runtimeReason,
            Long.toString(runtimeObservedAt),
            String.valueOf(this.managementReadOnly),
            this.managementReadOnlyReason);
  }

  public PluginEntryView withRuntime(
      String phase, String diagnostic, String reason, long observedAt) {
    boolean observed = PluginRuntimeEvidence.knownPhase(phase);
    return new PluginEntryView(
        entryId,
        moduleHash,
        parentId,
        group,
        patchDisabled,
        patchReason,
        observed,
        observed ? phase : "unknown",
        diagnostic,
        reason,
        observedAt,
        managementReadOnly,
        managementReadOnlyReason);
  }

  public PluginEntryView withManagement(Boolean readOnly, String reason) {
    return new PluginEntryView(
        entryId,
        moduleHash,
        parentId,
        group,
        patchDisabled,
        patchReason,
        runtimeObserved,
        runtimePhase,
        runtimeDiagnostic,
        runtimeReason,
        runtimeObservedAt,
        readOnly,
        reason);
  }

  public boolean runtimeVerified() {
    return runtimeObserved && runtimeReason.isEmpty();
  }

  private static String code(String value, String fallback) {
    return value != null && (value.isEmpty() || value.matches("[A-Z0-9_]{1,64}"))
        ? value
        : fallback;
  }

  public boolean ancestorDisabled() {
    return "ANCESTOR_DISABLED".equals(patchReason);
  }

  /** entryId 只在所属 profile/scope 内唯一；模块与父组另作匹配证据。 */
  public String stableKey(String profile, String scope) {
    if (!validId(profile, false) || !validId(scope, false))
      throw new IllegalArgumentException("PLUGIN_ENTRY_SCOPE");
    return key(profile, scope, entryId);
  }

  private static boolean validId(String value, boolean empty) {
    if (value == null || (!empty && value.isEmpty()) || value.length() > 256) return false;
    for (int index = 0; index < value.length(); index++)
      if (value.charAt(index) < 32 || value.charAt(index) == 127) return false;
    return true;
  }

  private static String key(String... values) {
    StringBuilder result = new StringBuilder();
    for (String value : values) result.append(value.length()).append(':').append(value);
    return result.toString();
  }
}
