package com.deepseekharness.app.plugins;

import com.deepseekharness.app.core.PluginRepository.Item;
import com.deepseekharness.app.util.PluginEntryView;
import com.deepseekharness.app.util.PluginRuntimeEvidence;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** 当前 Web 的只读运行证据；不把磁盘配置、历史激活记录或快照缺席当作加载结论。 */
public final class PluginRuntimeProjection {
  /**
   * Web 心跳每 2 秒写一次；允许事件循环被长任务占住时连续错过若干次心跳。代次与实例已由调用方
   * 核对，年龄只用于排除写方已停止的残留快照，不能把一次卡顿当成新的加载结论。
   */
  public static final long MAX_AGE_MILLIS = 30_000;

  private static final int MAX_INSTALLED = 512, MAX_DYNAMIC = 256;

  private PluginRuntimeProjection() {}

  /**
   * 运行证据快照的固定路径。Android 的 /data/user/0 是 /data/data 的系统别名，读取器逐段
   * NOFOLLOW 检查会把合法别名判成 PARENT_LINK；先规范化可信运行根，最终文件仍由读取器
   * NOFOLLOW 打开，不在这里解析最终文件。
   */
  public static File snapshotPath(File rootfs) throws IOException {
    if (rootfs == null) throw new IOException("RUNTIME_ROOT_MISSING");
    return new File(rootfs.getCanonicalFile(), "root/.dsha-web-activity.json");
  }

  public static List<Item> apply(
      List<Item> items, JSONObject activity, long generation, String instance, long now)
      throws Exception {
    boolean fresh = valid(activity, generation, instance, now);
    Map<String, JSONObject> installed = new HashMap<>();
    Set<String> ambiguous = new HashSet<>();
    JSONArray rows = fresh ? activity.optJSONArray("installedPlugins") : null;
    if (rows != null && rows.length() <= MAX_INSTALLED) {
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null || row.optString("name").isEmpty()) continue;
        String key = PluginIdentity.key(row.optString("profile", "web"), row.optString("name"));
        if (installed.put(key, row) != null) ambiguous.add(key);
      }
    }
    List<Item> result = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    JSONArray dynamic = fresh ? activity.optJSONArray("plugins") : null;
    boolean dynamicKnown = dynamic != null && dynamic.length() <= MAX_DYNAMIC;
    for (Item item : items) {
      if (!keys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
      if (item.dynamic) {
        if (!dynamicKnown && item.runtimeGeneration == generation)
          result.add(item.withRuntime(item.runtimeState, generation, true));
        continue;
      }
      Item next =
          stale(
              item,
              generation,
              fresh ? "NO_EVIDENCE" : rejection(activity, generation, instance, now));
      String key = PluginIdentity.key(item.profile, item.name);
      JSONObject observed = ambiguous.contains(key) ? null : installed.get(key);
      if (observed != null && "application".equals(item.scope))
        next = observed(item, observed, generation, instance, now);
      result.add(next);
    }
    if (dynamicKnown) {
      Set<String> dynamicKeys = new HashSet<>();
      List<Item> nextDynamic = new ArrayList<>();
      for (int i = 0; i < dynamic.length(); i++) {
        JSONObject row = dynamic.optJSONObject(i);
        if (row == null) continue;
        String session = row.optString("sessionId"), pluginId = row.optString("id");
        if (session.isEmpty() || pluginId.isEmpty()) continue;
        Item item =
            new Item(
                new JSONObject()
                    .put("name", row.optString("name"))
                    .put("dynamic", true)
                    .put("sessionId", session)
                    .put("pluginId", pluginId)
                    .put("runtimeGeneration", generation)
                    .put("runtimeStale", false)
                    .put(
                        "runtimeState",
                        row.optString("state", row.optBoolean("active") ? "active" : "inactive"))
                    .put("version", row.optString("version"))
                    .put("available", true)
                    .put("enabled", row.optBoolean("active"))
                    .put("location", "会话 " + session.substring(Math.max(0, session.length() - 8)))
                    .put("description", "会话内临时插件，请在 Web 页面管理；重启后需重新定义，不作为安装包备份。"));
        if (!dynamicKeys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
        nextDynamic.add(item);
      }
      result.addAll(nextDynamic);
    }
    return Collections.unmodifiableList(result);
  }

  public static com.deepseekharness.app.util.PluginManagementView management(
      JSONObject activity, long generation, String instance, long now) {
    String rejected = rejection(activity, generation, instance, now);
    if (!rejected.isEmpty())
      return com.deepseekharness.app.util.PluginManagementView.unknown(rejected);
    JSONObject service = activity.optJSONObject("management"),
        patch = activity.optJSONObject("patchChain"),
        coverage = activity.optJSONObject("coverage");
    if (service == null && patch == null && coverage == null)
      return com.deepseekharness.app.util.PluginManagementView.unknown("LEGACY_PROTOCOL");
    List<String> reasons = null;
    if (coverage != null && coverage.opt("reasons") instanceof JSONArray values) {
      reasons = new ArrayList<>();
      for (int index = 0; index < Math.min(9, values.length()); index++) {
        Object value = values.opt(index);
        reasons.add(value instanceof String ? (String) value : "RUNTIME_COVERAGE_UNKNOWN");
      }
    }
    Object limited = coverage == null ? null : coverage.opt("limited");
    return new com.deepseekharness.app.util.PluginManagementView(
        service == null ? "unknown" : service.optString("state"),
        service == null ? "LEGACY_PROTOCOL" : service.optString("reasonCode"),
        service == null ? "" : service.optString("evidence"),
        patch == null ? "unknown" : patch.optString("state"),
        patch == null ? "LEGACY_PROTOCOL" : patch.optString("reasonCode"),
        patch == null ? "" : patch.optString("evidence"),
        limited instanceof Boolean ? (Boolean) limited : null,
        reasons,
        activity.optLong("at", 0));
  }

  private static Item observed(
      Item item, JSONObject row, long generation, String instance, long now) throws Exception {
    String state = row.optString("state", "unknown");
    String version = row.optString("version");
    String reason = "";
    JSONObject identity = row.optJSONObject("identity");
    long at = identity == null ? 0 : identity.optLong("observedAt", 0);
    if (version.isEmpty() || !version.equals(item.version)) reason = "VERSION";
    else if (identity == null) reason = "LEGACY_EVIDENCE";
    else
      reason =
          PluginRuntimeEvidence.identityRejection(
              identity.optString("identitySource"),
              identity.optString("startupId"),
              identity.optString("generation"),
              identity.optString("instance"),
              Long.toString(generation),
              instance,
              at,
              now,
              MAX_AGE_MILLIS);
    boolean identityBound = reason.isEmpty();
    String content =
        PluginRuntimeEvidence.contentRejection(
            item.contentAlgorithm,
            item.contentFingerprint,
            row.optString("contentAlgorithm"),
            row.optString("contentFingerprint"));
    if (reason.isEmpty()) reason = content;
    JSONArray values = row.optJSONArray("entries");
    Map<String, JSONObject> entries = new HashMap<>();
    boolean complete = values != null && values.length() <= 2048 && !row.optBoolean("truncated");
    if (complete)
      for (int index = 0; index < values.length(); index++) {
        JSONObject entry = values.optJSONObject(index);
        if (entry == null
            || !(entry.opt("entryId") instanceof String)
            || entries.put(entry.optString("entryId"), entry) != null) complete = false;
      }
    if (!item.entryReadError.isEmpty()
        || item.entries.isEmpty()
        || entries.size() != item.entries.size()) complete = false;
    if (!complete && reason.isEmpty()) reason = "ENTRY_COVERAGE";
    // 身份/内容属于 bundle 级公共原因；某个 sibling 的条目故障不得传播到其它条目。
    String bundleReason = reason;
    List<PluginEntryView> projected = new ArrayList<>();
    for (PluginEntryView expected : item.entries) {
      JSONObject entry = complete ? entries.get(expected.entryId) : null;
      String entryReason = complete ? "" : "ENTRY_COVERAGE";
      boolean entryIdentityBound = false;
      String phase = "unknown", diagnostic = "";
      if (entry != null) {
        if (!(entry.opt("moduleHash") instanceof String)
            || !(entry.opt("parentId") instanceof String)
            || !(entry.opt("group") instanceof Boolean)) entryReason = "ENTRY_WIRE_INVALID";
        else
          entryReason =
              PluginRuntimeEvidence.entryRejection(
                  expected,
                  entry.optString("entryId"),
                  entry.optString("moduleHash"),
                  entry.optString("parentId"),
                  entry.optBoolean("group"));
        entryIdentityBound = entryReason.isEmpty();
        diagnostic = entry.optString("diagnostic");
        if (!diagnostic.isEmpty() && !diagnostic.matches("[A-Z0-9_]{1,64}"))
          diagnostic = "EVIDENCE_INVALID";
        if (entry.has("phase") && (entry.isNull("phase") || entry.opt("phase") instanceof String)) {
          phase = entry.isNull("phase") ? null : entry.optString("phase");
          if (!PluginRuntimeEvidence.knownPhase(phase)) entryReason = "ENTRY_PHASE_UNKNOWN";
          // null 只表达没有活根；没有匹配的 Loader 或入口缺席不证明已卸载。
          if (phase == null
              && !java.util.Set.of("NO_LIVE_ROOT", "ENTRY_DISABLED", "ENTRY_DISPOSED")
                  .contains(diagnostic)) {
            entryReason = diagnostic.isEmpty() ? "ENTRY_PHASE_UNKNOWN" : diagnostic;
            phase = "unknown";
          }
        } else entryReason = "ENTRY_PHASE_UNKNOWN";
      } else if (complete) entryReason = "ENTRY_MISSING";
      if (!entryReason.isEmpty() || !identityBound) phase = "unknown";
      if (entryReason.isEmpty()) entryReason = bundleReason;
      if (reason.isEmpty() && !entryReason.isEmpty()) reason = entryReason;
      projected.add(
          expected
              .withRuntime(phase, diagnostic, entryReason, at)
              .withManagement(
                  identityBound
                          && entryIdentityBound
                          && entry != null
                          && Boolean.TRUE.equals(entry.opt("managementReadOnly"))
                      ? Boolean.TRUE
                      : null,
                  identityBound && entryIdentityBound && entry != null
                      ? entry.optString("managementReadOnlyReason")
                      : "PROTECTION_UNVERIFIED"));
    }
    if (reason.isEmpty() && !confirmedState(state)) reason = "ENTRY_PHASE_UNKNOWN";
    // 保留原始观察供详情解释；未核验内容/身份时卡片仍显示待核验。
    String observedState = identityBound && confirmedState(state) ? state : item.runtimeState;
    Item next =
        item.withRuntimeEvidence(
            observedState, generation, !reason.isEmpty(), reason, instance, at, projected);
    next =
        next.withManagementEvidence(
            identityBound && Boolean.TRUE.equals(row.opt("managementReadOnly"))
                ? Boolean.TRUE
                : null,
            identityBound ? row.optString("managementReadOnlyReason") : "PROTECTION_UNVERIFIED",
            eligibility(
                item,
                identityBound ? row.optJSONObject("eligibility") : null,
                generation,
                instance,
                now));
    if ("VERSION".equals(reason) || "CONTENT_CHANGED".equals(reason))
      return next.withRuntime("unknown", generation, true).withRestartRequired(true);
    if (reason.isEmpty()
        && !"queued".equals(item.loadState)
        && ((item.enabled && ("loaded".equals(state) || "active".equals(state)))
            || (!item.enabled && ("unloaded".equals(state) || "disabled".equals(state)))))
      next = next.withRestartRequired(false);
    return next;
  }

  private static JSONObject eligibility(
      Item item, JSONObject value, long generation, String instance, long now) throws Exception {
    JSONObject result =
        new JSONObject().put("state", "unknown").put("reasonCode", "PREFLIGHT_UNAVAILABLE");
    if (value == null) return result;
    long observedAt = value.optLong("observedAt", 0);
    if (!"PREFLIGHT".equals(value.optString("evidence"))
        || !item.name.equals(value.optString("name"))
        || !item.version.equals(value.optString("version"))
        || !item.profile.equals(value.optString("profile"))
        || !Long.toString(generation).equals(value.optString("generation"))
        || !instance.equals(value.optString("instance"))
        || observedAt <= 0
        || observedAt > now
        || item.installedDsh.isEmpty()
        || !item.installedDsh.equals(value.optString("runtimeVersion"))
        || item.runtimePlatform.isEmpty()
        || !item.runtimePlatform.equals(value.optString("runtimePlatform")))
      return result.put("reasonCode", "PREFLIGHT_IDENTITY_MISMATCH");
    String state = value.optString("state");
    if (!java.util.Set.of(
            "unknown",
            "compatible",
            "unconstrained",
            "exempted",
            "incompatible",
            "unsupported-platform")
        .contains(state)) return result;
    if ("exempted".equals(state) && !Boolean.TRUE.equals(value.opt("versionExempted")))
      return result;
    String code = value.optString("reasonCode");
    return result
        .put("state", state)
        .put("reasonCode", code.matches("[A-Z0-9_]{1,64}") ? code : "PREFLIGHT_UNAVAILABLE")
        .put("observedAt", observedAt);
  }

  /** 同版本同代目录刷新只更新安装事实，不能抹掉上次实际运行证据。 */
  public static Item retainRuntime(Item fresh, Item previous, long generation) throws Exception {
    if (previous == null
        || !fresh.stableKey.equals(previous.stableKey)
        || !fresh.version.equals(previous.version)
        || !fresh.origin.equals(previous.origin)
        || !fresh.source.equals(previous.source)
        || !fresh.installedDsh.equals(previous.installedDsh)
        || !fresh.runtimePlatform.equals(previous.runtimePlatform)
        || !fresh.patchRevision.equals(previous.patchRevision)
        || !fresh.contentFingerprint.equals(previous.contentFingerprint)
        || !fresh.contentAlgorithm.equals(previous.contentAlgorithm)
        || previous.runtimeGeneration != generation)
      return fresh.withRuntime("unknown", generation, true);
    if (fresh.entries.size() != previous.entries.size())
      return fresh.withRuntime("unknown", generation, true);
    for (int index = 0; index < fresh.entries.size(); index++) {
      PluginEntryView before = previous.entries.get(index);
      PluginEntryView configuration =
          new PluginEntryView(
              before.entryId,
              before.moduleHash,
              before.parentId,
              before.group,
              before.patchDisabled,
              before.patchReason);
      if (!fresh.entries.get(index).contentKey.equals(configuration.contentKey))
        return fresh.withRuntime("unknown", generation, true);
    }
    return fresh.withRuntimeEvidence(
        previous.runtimeState,
        generation,
        previous.runtimeStale,
        previous.runtimeReason,
        previous.runtimeInstance,
        previous.runtimeObservedAt,
        previous.entries);
  }

  private static Item stale(Item item, long generation, String reason) throws Exception {
    List<PluginEntryView> entries = new ArrayList<>();
    for (var entry : item.entries)
      entries.add(
          entry
              .withRuntime(
                  item.runtimeGeneration == generation ? entry.runtimePhase : "unknown",
                  entry.runtimeDiagnostic,
                  reason,
                  entry.runtimeObservedAt)
              .withManagement(null, "PROTECTION_UNVERIFIED"));
    return item.withRuntimeEvidence(
            item.runtimeGeneration == generation ? item.runtimeState : "unknown",
            generation,
            true,
            reason,
            item.runtimeInstance,
            item.runtimeObservedAt,
            entries)
        .withRestartRequired(item.runtimeGeneration == generation && item.restartRequired)
        .withManagementEvidence(null, "PROTECTION_UNVERIFIED", null);
  }

  private static boolean valid(JSONObject activity, long generation, String instance, long now) {
    return rejection(activity, generation, instance, now).isEmpty();
  }

  /**
   * 返回拒绝原因码（空串表示可用）。调用方用它记录本轮为什么没有采纳运行证据；没有原因码时
   * 界面只能显示“未确认”，无法区分读失败、代次变化和心跳过期。
   */
  public static String rejection(JSONObject activity, long generation, String instance, long now) {
    if (activity == null) return "NO_ACTIVITY";
    if (instance == null || instance.isEmpty()) return "NO_INSTANCE";
    if (generation < 1) return "NO_GENERATION";
    long at = activity.optLong("at", -1);
    if (at <= 0) return "NO_TIME";
    if (now < at) return "FUTURE_TIME";
    if (now - at > MAX_AGE_MILLIS) return "EXPIRED";
    if (!Long.toString(generation).equals(activity.optString("generation"))) return "GENERATION";
    if (!instance.equals(activity.optString("instance"))) return "INSTANCE";
    return "";
  }

  private static boolean confirmedState(String value) {
    return switch (value) {
      case "loaded",
              "active",
              "failed",
              "loading",
              "unloading",
              "pending",
              "waiting",
              "unloaded",
              "disabled" ->
          true;
      default -> false;
    };
  }
}
