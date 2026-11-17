package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Companion.immutableList
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.PolicyFailure

/** 已退休 review gate 的只读迁移计划；不获取 writer，不启用插件，也不写激活账本。 */
class PluginLegacyReviewPlan
private constructor(
    val snapshot: PluginPreparationSnapshot,
    rows: List<Row>,
    val unavailableReason: String? = null,
) {
    val rows: List<Row> = immutableList(rows)
    val steps: List<Step> =
        immutableList(
            rows.flatMap { row ->
                if (row.decision == Decision.ACTIVATE)
                    listOf(
                        Step(Action.QUEUE_ACTIVATION, row.name, row.fingerprint, row.version),
                        Step(Action.ENABLE_PLUGIN, row.name, row.fingerprint, row.version),
                    )
                else emptyList()
            }
        )

    enum class Decision {
        IGNORE,
        SKIP,
        BLOCKED,
        ACTIVATE,
        UNAVAILABLE,
    }

    enum class Action {
        QUEUE_ACTIVATION,
        ENABLE_PLUGIN,
    }

    data class Step(
        val action: Action,
        val name: String,
        val fingerprint: String?,
        val version: String?,
    )

    data class Row(
        val name: String,
        val decision: Decision,
        val reason: String,
        val reference: String? = null,
        val directory: String? = null,
        val fingerprint: String? = null,
        val version: String? = null,
        val policyFailure: PolicyFailure? = null,
    )

    /** 外部执行回执；queued=true 才能解释 enable 返回码，未知结果不得重放。 */
    data class Attempt(
        val queued: Boolean?,
        val enableExitCode: Int?,
        val policyFailure: PolicyFailure? = null,
    )

    class Outcome
    internal constructor(
        val status: String,
        val exitCode: Int?,
        activated: List<String>,
        skipped: List<String>,
        blocked: List<Pair<String, PolicyFailure>>,
    ) {
        val activated: List<String> = immutableList(activated)
        val skipped: List<String> = immutableList(skipped)
        val blocked: List<Pair<String, PolicyFailure>> = immutableList(blocked)
        val application: String?
            get() = if (blocked.isNotEmpty()) "failed" else null

        val stage: String?
            get() = if (blocked.isNotEmpty()) "enable" else null
    }

    /** 旧规则中普通原件损坏/enable失败只是 skipped，只有 PolicyError 导致 partial/1。 */
    fun classify(attempts: Map<String, Attempt> = emptyMap()): Outcome {
        val activated = ArrayList<String>()
        val skipped = ArrayList<String>()
        val blocked = ArrayList<Pair<String, PolicyFailure>>()
        var unknown = unavailableReason != null
        for (row in rows) {
            when (row.decision) {
                Decision.IGNORE -> Unit
                Decision.SKIP -> skipped.add(row.name)
                Decision.BLOCKED -> {
                    skipped.add(row.name)
                    blocked.add(row.name to row.policyFailure!!)
                }
                Decision.UNAVAILABLE -> unknown = true
                Decision.ACTIVATE -> {
                    val attempt = attempts[row.name]
                    if (attempt?.policyFailure != null) {
                        skipped.add(row.name)
                        blocked.add(row.name to attempt.policyFailure)
                    } else if (
                        attempt == null ||
                            attempt.queued == null ||
                            (attempt.queued && attempt.enableExitCode == null)
                    ) {
                        unknown = true
                    } else if (attempt.queued && attempt.enableExitCode == 0)
                        activated.add(row.name)
                    else skipped.add(row.name)
                }
            }
        }
        return Outcome(
            if (unknown) "unknown" else if (blocked.isNotEmpty()) "partial" else "ok",
            if (unknown) null else if (blocked.isNotEmpty()) 1 else 0,
            activated,
            skipped,
            blocked,
        )
    }

    companion object {
        @JvmStatic
        fun create(snapshot: PluginPreparationSnapshot): PluginLegacyReviewPlan {
            if (!snapshot.complete || snapshot.profile != "web" || snapshot.preparationId.isEmpty())
                return PluginLegacyReviewPlan(snapshot, emptyList(), "PREPARATION_UNAVAILABLE")
            return try {
                val references = snapshot.manifest()?.getAsJsonObject("dependencies")
                val reserved = snapshot.officialBundles + snapshot.builtins.map { it.name }
                val rows = ArrayList<Row>()
                for ((name, referenceValue) in references?.entrySet() ?: emptySet()) {
                    val reference =
                        if (
                            referenceValue.isJsonPrimitive &&
                                referenceValue.asJsonPrimitive.isString
                        )
                            referenceValue.asString
                        else null
                    val marker = snapshot.file(name)
                    val directory = snapshot.pluginSource.trimEnd('/') + "/" + name
                    val base = Row(name, Decision.IGNORE, "NOT_LEGACY_REVIEW", reference, directory)
                    if (!PluginPreparationSnapshot.validName(name) || name in reserved) {
                        rows.add(base.copy(reason = "RESERVED_OR_INVALID_NAME"))
                        continue
                    }
                    if (!marker.observed && marker.kind != NodeKind.SYMLINK) {
                        rows.add(
                            base.copy(decision = Decision.UNAVAILABLE, reason = "MARKER_UNOBSERVED")
                        )
                        continue
                    }
                    if (
                        marker.kind != NodeKind.REGULAR ||
                            marker.size !=
                                PluginPreparationSnapshot.LEGACY_REVIEW_MARKER.length.toLong() ||
                            !marker.exact(PluginPreparationSnapshot.LEGACY_REVIEW_MARKER)
                    ) {
                        rows.add(base)
                        continue
                    }
                    if (!marker.stable) {
                        rows.add(
                            base.copy(decision = Decision.UNAVAILABLE, reason = "MARKER_CHANGED")
                        )
                        continue
                    }
                    val source = snapshot.legacyPackages[name]
                    if (reference != "link:" + directory.replace('\\', '/')) {
                        rows.add(base.copy(decision = Decision.SKIP, reason = "SOURCE_MISMATCH"))
                        continue
                    }
                    if (source == null) {
                        rows.add(
                            base.copy(decision = Decision.UNAVAILABLE, reason = "SOURCE_UNOBSERVED")
                        )
                        continue
                    }
                    if (source.directoryKind == NodeKind.UNREADABLE) {
                        rows.add(
                            base.copy(decision = Decision.UNAVAILABLE, reason = "SOURCE_UNOBSERVED")
                        )
                        continue
                    }
                    if (source.directoryKind != NodeKind.DIRECTORY) {
                        rows.add(base.copy(decision = Decision.SKIP, reason = "SOURCE_MISMATCH"))
                        continue
                    }
                    if (
                        !source.metadataValid ||
                            source.metadataName != name ||
                            source.version == null
                    ) {
                        rows.add(base.copy(decision = Decision.SKIP, reason = "PACKAGE_INVALID"))
                        continue
                    }
                    val dependencies = source.dependencies
                    // 真实 current() 的 missing 会令 complete=false；已知缺项按旧规则 skipped。
                    if (dependencies != null && dependencies.missing.isNotEmpty()) {
                        rows.add(
                            base.copy(decision = Decision.SKIP, reason = "DEPENDENCIES_MISSING")
                        )
                        continue
                    }
                    if (dependencies != null && dependencies.missing.isNotEmpty()) {
                        rows.add(
                            base.copy(decision = Decision.SKIP, reason = "DEPENDENCIES_MISSING")
                        )
                        continue
                    }
                    if (
                        dependencies == null ||
                            !dependencies.complete ||
                            !Regex("[a-f0-9]{64}").matches(dependencies.sha256)
                    ) {
                        rows.add(
                            base.copy(
                                decision = Decision.UNAVAILABLE,
                                reason = "DEPENDENCIES_UNVERIFIED",
                            )
                        )
                        continue
                    }
                    if (!source.compatibilityChecked) {
                        rows.add(
                            base.copy(
                                decision = Decision.UNAVAILABLE,
                                reason = "COMPATIBILITY_UNVERIFIED",
                            )
                        )
                        continue
                    }
                    if (source.policyFailure != null) {
                        rows.add(
                            base.copy(
                                decision = Decision.BLOCKED,
                                reason = source.policyFailure.code,
                                policyFailure = source.policyFailure,
                            )
                        )
                        continue
                    }
                    rows.add(
                        base.copy(
                            decision = Decision.ACTIVATE,
                            reason = "EXACT_LEGACY_REVIEW",
                            fingerprint = dependencies.sha256,
                            version = source.version,
                        )
                    )
                }
                PluginLegacyReviewPlan(snapshot, rows)
            } catch (_: Exception) {
                PluginLegacyReviewPlan(snapshot, emptyList(), "PROFILE_MANIFEST_FORMAT")
            }
        }
    }
}
