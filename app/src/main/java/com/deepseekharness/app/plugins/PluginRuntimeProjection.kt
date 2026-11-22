package com.deepseekharness.app.plugins

import com.deepseekharness.app.core.PluginRepository.Item
import com.deepseekharness.app.util.PluginEntryView
import com.deepseekharness.app.util.PluginManagementView
import com.deepseekharness.app.util.PluginRuntimeEvidence
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import org.json.JSONArray
import org.json.JSONObject

/** 当前 Web 的只读运行证据；不把磁盘配置、历史激活记录或快照缺席当作加载结论。 */
class PluginRuntimeProjection private constructor() {
    companion object {
        /**
         * Web 心跳每 2 秒写一次；允许事件循环被长任务占住时连续错过若干次心跳。代次与实例已由调用方 核对，年龄只用于排除写方已停止的残留快照，不能把一次卡顿当成新的加载结论。
         */
        const val MAX_AGE_MILLIS = 30_000L

        private const val MAX_INSTALLED = 512
        private const val MAX_DYNAMIC = 256

        /**
         * 运行证据快照的固定路径。Android 的 /data/user/0 是 /data/data 的系统别名，读取器逐段 NOFOLLOW 检查会把合法别名判成
         * PARENT_LINK；先规范化可信运行根，最终文件仍由读取器 NOFOLLOW 打开，不在这里解析最终文件。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun snapshotPath(rootfs: File?): File {
            if (rootfs == null) throw IOException("RUNTIME_ROOT_MISSING")
            return File(rootfs.canonicalFile, "root/.dsha-web-activity.json")
        }

        @JvmStatic
        @Throws(Exception::class)
        fun apply(
            items: List<Item>,
            activity: JSONObject?,
            generation: Long,
            instance: String?,
            now: Long,
        ): List<Item> {
            val fresh = valid(activity, generation, instance, now)
            val installed = HashMap<String, JSONObject>()
            val ambiguous = HashSet<String>()
            val rows = if (fresh) activity?.optJSONArray("installedPlugins") else null
            if (rows != null && rows.length() <= MAX_INSTALLED) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    if (row.optString("name").isEmpty()) continue
                    val key =
                        PluginIdentity.key(
                            row.optString("profile", "web"),
                            row.optString("name"),
                        )
                    if (installed.put(key, row) != null) ambiguous.add(key)
                }
            }
            val result = ArrayList<Item>()
            val keys = HashSet<String>()
            val dynamic = if (fresh) activity?.optJSONArray("plugins") else null
            val dynamicKnown = dynamic != null && dynamic.length() <= MAX_DYNAMIC
            for (item in items) {
                if (!keys.add(item.stableKey)) throw IOException("PLUGIN_DUPLICATE_IDENTITY")
                if (item.dynamic) {
                    if (!dynamicKnown && item.runtimeGeneration == generation)
                        result.add(item.withRuntime(item.runtimeState, generation, true))
                    continue
                }
                var next =
                    stale(
                        item,
                        generation,
                        if (fresh) "NO_EVIDENCE"
                        else rejection(activity, generation, instance, now),
                    )
                val key = PluginIdentity.key(item.profile, item.name)
                val observedRow = if (ambiguous.contains(key)) null else installed[key]
                if (observedRow != null && item.scope == "application" && instance != null)
                    next = observed(item, observedRow, generation, instance, now)
                result.add(next)
            }
            if (dynamicKnown) {
                val dynamicKeys = HashSet<String>()
                val nextDynamic = ArrayList<Item>()
                for (index in 0 until dynamic.length()) {
                    val row = dynamic.optJSONObject(index) ?: continue
                    val session = row.optString("sessionId")
                    val pluginId = row.optString("id")
                    if (session.isEmpty() || pluginId.isEmpty()) continue
                    val item =
                        Item(
                            JSONObject()
                                .put("name", row.optString("name"))
                                .put("dynamic", true)
                                .put("sessionId", session)
                                .put("pluginId", pluginId)
                                .put("runtimeGeneration", generation)
                                .put("runtimeStale", false)
                                .put(
                                    "runtimeState",
                                    row.optString(
                                        "state",
                                        if (row.optBoolean("active")) "active" else "inactive",
                                    ),
                                )
                                .put("version", row.optString("version"))
                                .put("available", true)
                                .put("enabled", row.optBoolean("active"))
                                .put(
                                    "location",
                                    "会话 " + session.substring(maxOf(0, session.length - 8)),
                                )
                                .put(
                                    "description",
                                    "会话内临时插件，请在 Web 页面管理；重启后需重新定义，不作为安装包备份。",
                                )
                        )
                    if (!dynamicKeys.add(item.stableKey))
                        throw IOException("PLUGIN_DUPLICATE_IDENTITY")
                    nextDynamic.add(item)
                }
                result.addAll(nextDynamic)
            }
            return Collections.unmodifiableList(result)
        }

        @JvmStatic
        fun management(
            activity: JSONObject?,
            generation: Long,
            instance: String?,
            now: Long,
        ): PluginManagementView {
            val rejected = rejection(activity, generation, instance, now)
            if (rejected.isNotEmpty()) return PluginManagementView.unknown(rejected)
            val exact = activity ?: return PluginManagementView.unknown("NO_ACTIVITY")
            val service = exact.optJSONObject("management")
            val patch = exact.optJSONObject("patchChain")
            val coverage = exact.optJSONObject("coverage")
            if (service == null && patch == null && coverage == null)
                return PluginManagementView.unknown("LEGACY_PROTOCOL")
            var reasons: List<String>? = null
            if (coverage != null) {
                val raw = coverage.opt("reasons")
                if (raw is JSONArray) {
                    reasons = ArrayList()
                    for (index in 0 until minOf(9, raw.length())) {
                        val value = raw.opt(index)
                        reasons.add(if (value is String) value else "RUNTIME_COVERAGE_UNKNOWN")
                    }
                }
            }
            val limited = coverage?.opt("limited")
            return PluginManagementView(
                if (service == null) "unknown" else service.optString("state"),
                if (service == null) "LEGACY_PROTOCOL" else service.optString("reasonCode"),
                if (service == null) "" else service.optString("evidence"),
                if (patch == null) "unknown" else patch.optString("state"),
                if (patch == null) "LEGACY_PROTOCOL" else patch.optString("reasonCode"),
                if (patch == null) "" else patch.optString("evidence"),
                if (limited is Boolean) limited else null,
                reasons,
                exact.optLong("at", 0),
            )
        }

        private fun observed(
            item: Item,
            row: JSONObject,
            generation: Long,
            instance: String,
            now: Long,
        ): Item {
            val state = row.optString("state", "unknown")
            val version = row.optString("version")
            var reason = ""
            val identity = row.optJSONObject("identity")
            val at = if (identity == null) 0L else identity.optLong("observedAt", 0)
            if (version.isEmpty() || !version.equals(item.version)) reason = "VERSION"
            else if (identity == null) reason = "LEGACY_EVIDENCE"
            else
                reason =
                    PluginRuntimeEvidence.identityRejection(
                        identity.optString("identitySource"),
                        identity.optString("startupId"),
                        identity.optString("generation"),
                        identity.optString("instance"),
                        generation.toString(),
                        instance,
                        at,
                        now,
                        MAX_AGE_MILLIS,
                    )
            val identityBound = reason.isEmpty()
            val content =
                PluginRuntimeEvidence.contentRejection(
                    item.contentAlgorithm,
                    item.contentFingerprint,
                    row.optString("contentAlgorithm"),
                    row.optString("contentFingerprint"),
                )
            if (reason.isEmpty()) reason = content
            val values = row.optJSONArray("entries")
            val entries = HashMap<String, JSONObject>()
            var complete = values != null && values.length() <= 2048 && !row.optBoolean("truncated")
            val rows = values
            if (complete && rows != null)
                for (index in 0 until rows.length()) {
                    val entry = rows.optJSONObject(index)
                    if (
                        entry == null ||
                            !(entry.opt("entryId") is String) ||
                            entries.put(entry.optString("entryId"), entry) != null
                    )
                        complete = false
                }
            if (
                !item.entryReadError.isEmpty() ||
                    item.entries.isEmpty() ||
                    entries.size != item.entries.size
            )
                complete = false
            if (!complete && reason.isEmpty()) reason = "ENTRY_COVERAGE"
            // 身份/内容属于 bundle 级公共原因；某个 sibling 的条目故障不得传播到其它条目。
            val bundleReason = reason
            val projected = ArrayList<PluginEntryView>()
            for (expected in item.entries) {
                val entry = if (complete) entries[expected.entryId] else null
                var entryReason = if (complete) "" else "ENTRY_COVERAGE"
                var entryIdentityBound = false
                var phase: String? = "unknown"
                var diagnostic = ""
                if (entry != null) {
                    if (
                        !(entry.opt("moduleHash") is String) ||
                            !(entry.opt("parentId") is String) ||
                            !(entry.opt("group") is Boolean)
                    )
                        entryReason = "ENTRY_WIRE_INVALID"
                    else
                        entryReason =
                            PluginRuntimeEvidence.entryRejection(
                                expected,
                                entry.optString("entryId"),
                                entry.optString("moduleHash"),
                                entry.optString("parentId"),
                                entry.optBoolean("group"),
                            )
                    entryIdentityBound = entryReason.isEmpty()
                    diagnostic = entry.optString("diagnostic")
                    if (!diagnostic.isEmpty() && !diagnostic.matches(Regex("[A-Z0-9_]{1,64}")))
                        diagnostic = "EVIDENCE_INVALID"
                    if (
                        entry.has("phase") &&
                            (entry.isNull("phase") || entry.opt("phase") is String)
                    ) {
                        phase = if (entry.isNull("phase")) null else entry.optString("phase")
                        if (!PluginRuntimeEvidence.knownPhase(phase))
                            entryReason = "ENTRY_PHASE_UNKNOWN"
                        // null 只表达没有活根；没有匹配的 Loader 或入口缺席不证明已卸载。
                        if (
                            phase == null &&
                                !java.util.Set.of(
                                        "NO_LIVE_ROOT",
                                        "ENTRY_DISABLED",
                                        "ENTRY_DISPOSED",
                                    )
                                    .contains(diagnostic)
                        ) {
                            entryReason =
                                if (diagnostic.isEmpty()) "ENTRY_PHASE_UNKNOWN" else diagnostic
                            phase = "unknown"
                        }
                    } else entryReason = "ENTRY_PHASE_UNKNOWN"
                } else if (complete) entryReason = "ENTRY_MISSING"
                if (!entryReason.isEmpty() || !entryIdentityBound) phase = "unknown"
                if (entryReason.isEmpty()) entryReason = bundleReason
                if (reason.isEmpty() && !entryReason.isEmpty()) reason = entryReason
                projected.add(
                    expected
                        .withRuntime(phase, diagnostic, entryReason, at)
                        .withManagement(
                            if (
                                identityBound &&
                                    entryIdentityBound &&
                                    entry != null &&
                                    java.lang.Boolean.TRUE.equals(entry.opt("managementReadOnly"))
                            )
                                java.lang.Boolean.TRUE
                            else null,
                            if (identityBound && entryIdentityBound && entry != null)
                                entry.optString("managementReadOnlyReason")
                            else "PROTECTION_UNVERIFIED",
                        )
                )
            }
            if (reason.isEmpty() && !confirmedState(state)) reason = "ENTRY_PHASE_UNKNOWN"
            // 保留原始观察供详情解释；未核验内容/身份时卡片仍显示待核验。
            val observedState =
                if (identityBound && confirmedState(state)) state else item.runtimeState
            var next =
                item.withRuntimeEvidence(
                    observedState,
                    generation,
                    !reason.isEmpty(),
                    reason,
                    instance,
                    at,
                    projected,
                )
            next =
                next.withManagementEvidence(
                    if (
                        identityBound &&
                            java.lang.Boolean.TRUE.equals(row.opt("managementReadOnly"))
                    )
                        java.lang.Boolean.TRUE
                    else null,
                    if (identityBound) row.optString("managementReadOnlyReason")
                    else "PROTECTION_UNVERIFIED",
                    eligibility(
                        item,
                        if (identityBound) row.optJSONObject("eligibility") else null,
                        generation,
                        instance,
                        now,
                    ),
                )
            if ("VERSION" == reason || "CONTENT_CHANGED" == reason)
                return next.withRuntime("unknown", generation, true).withRestartRequired(true)
            if (
                reason.isEmpty() &&
                    "queued" != item.loadState &&
                    ((item.enabled && ("loaded" == state || "active" == state)) ||
                        (!item.enabled && ("unloaded" == state || "disabled" == state)))
            )
                next = next.withRestartRequired(false)
            return next
        }

        private fun eligibility(
            item: Item,
            value: JSONObject?,
            generation: Long,
            instance: String,
            now: Long,
        ): JSONObject {
            val result: JSONObject =
                JSONObject().put("state", "unknown").put("reasonCode", "PREFLIGHT_UNAVAILABLE")
            if (value == null) return result
            val observedAt = value.optLong("observedAt", 0)
            if (
                "PREFLIGHT" != value.optString("evidence") ||
                    item.name != value.optString("name") ||
                    item.version != value.optString("version") ||
                    item.profile != value.optString("profile") ||
                    generation.toString() != value.optString("generation") ||
                    instance != value.optString("instance") ||
                    observedAt <= 0 ||
                    observedAt > now ||
                    item.installedDsh.isEmpty() ||
                    item.installedDsh != value.optString("runtimeVersion") ||
                    item.runtimePlatform.isEmpty() ||
                    item.runtimePlatform != value.optString("runtimePlatform")
            ) {
                result.put("reasonCode", "PREFLIGHT_IDENTITY_MISMATCH")
                return result
            }
            val state = value.optString("state")
            if (
                !java.util.Set.of(
                        "unknown",
                        "compatible",
                        "unconstrained",
                        "exempted",
                        "incompatible",
                        "unsupported-platform",
                    )
                    .contains(state)
            )
                return result
            if ("exempted" == state && !java.lang.Boolean.TRUE.equals(value.opt("versionExempted")))
                return result
            val code = value.optString("reasonCode")
            result
                .put("state", state)
                .put(
                    "reasonCode",
                    if (code.matches(Regex("[A-Z0-9_]{1,64}"))) code else "PREFLIGHT_UNAVAILABLE",
                )
                .put("observedAt", observedAt)
            return result
        }

        /** 同版本同代目录刷新只更新安装事实，不能抹掉上次实际运行证据。 */
        @JvmStatic
        @Throws(Exception::class)
        fun retainRuntime(fresh: Item, previous: Item?, generation: Long): Item {
            if (
                previous == null ||
                    !fresh.stableKey.equals(previous.stableKey) ||
                    !fresh.version.equals(previous.version) ||
                    !fresh.origin.equals(previous.origin) ||
                    !fresh.source.equals(previous.source) ||
                    !fresh.installedDsh.equals(previous.installedDsh) ||
                    !fresh.runtimePlatform.equals(previous.runtimePlatform) ||
                    !fresh.patchRevision.equals(previous.patchRevision) ||
                    !fresh.contentFingerprint.equals(previous.contentFingerprint) ||
                    !fresh.contentAlgorithm.equals(previous.contentAlgorithm) ||
                    previous.runtimeGeneration != generation
            )
                return fresh.withRuntime("unknown", generation, true)
            if (fresh.entries.size != previous.entries.size)
                return fresh.withRuntime("unknown", generation, true)
            for (index in fresh.entries.indices) {
                val before = previous.entries[index]
                val configuration =
                    PluginEntryView(
                        before.entryId,
                        before.moduleHash,
                        before.parentId,
                        before.group,
                        before.patchDisabled,
                        before.patchReason,
                    )
                if (!fresh.entries[index].contentKey.equals(configuration.contentKey))
                    return fresh.withRuntime("unknown", generation, true)
            }
            return fresh.withRuntimeEvidence(
                previous.runtimeState,
                generation,
                previous.runtimeStale,
                previous.runtimeReason,
                previous.runtimeInstance,
                previous.runtimeObservedAt,
                previous.entries,
            )
        }

        private fun stale(item: Item, generation: Long, reason: String): Item {
            val entries = ArrayList<PluginEntryView>()
            for (entry in item.entries) entries.add(
                entry
                    .withRuntime(
                        if (item.runtimeGeneration == generation) entry.runtimePhase else "unknown",
                        entry.runtimeDiagnostic,
                        reason,
                        entry.runtimeObservedAt,
                    )
                    .withManagement(null, "PROTECTION_UNVERIFIED")
            )
            return item
                .withRuntimeEvidence(
                    if (item.runtimeGeneration == generation) item.runtimeState else "unknown",
                    generation,
                    true,
                    reason,
                    item.runtimeInstance,
                    item.runtimeObservedAt,
                    entries,
                )
                .withRestartRequired(item.runtimeGeneration == generation && item.restartRequired)
                .withManagementEvidence(null, "PROTECTION_UNVERIFIED", null)
        }

        private fun valid(
            activity: JSONObject?,
            generation: Long,
            instance: String?,
            now: Long,
        ): Boolean = rejection(activity, generation, instance, now).isEmpty()

        /** 返回拒绝原因码（空串表示可用）。调用方用它记录本轮为什么没有采纳运行证据；没有原因码时 界面只能显示“未确认”，无法区分读失败、代次变化和心跳过期。 */
        @JvmStatic
        fun rejection(
            activity: JSONObject?,
            generation: Long,
            instance: String?,
            now: Long,
        ): String {
            if (activity == null) return "NO_ACTIVITY"
            if (instance == null || instance.isEmpty()) return "NO_INSTANCE"
            if (generation < 1) return "NO_GENERATION"
            val at = activity.optLong("at", -1)
            if (at <= 0) return "NO_TIME"
            if (now < at) return "FUTURE_TIME"
            if (now - at > MAX_AGE_MILLIS) return "EXPIRED"
            if (generation.toString() != activity.optString("generation")) return "GENERATION"
            if (instance != activity.optString("instance")) return "INSTANCE"
            return ""
        }

        private fun confirmedState(value: String): Boolean =
            when (value) {
                "loaded",
                "active",
                "failed",
                "loading",
                "unloading",
                "pending",
                "waiting",
                "unloaded",
                "disabled" -> true
                else -> false
            }
    }
}
