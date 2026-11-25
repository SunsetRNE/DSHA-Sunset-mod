package com.deepseekharness.app.plugins

import com.deepseekharness.app.core.PluginRepository.Item
import com.deepseekharness.app.core.PluginRepository.PendingReview
import com.deepseekharness.app.util.PluginMutationTickets
import java.util.ArrayList
import java.util.Collections
import java.util.Objects

/** 目录状态的唯一版本快照；不执行文件、guest 或 UI 操作。 */
class PluginRegistry {
    class Snapshot
    internal constructor(
        @JvmField val version: Long,
        @JvmField val invalidationSeq: Long,
        @JvmField val verifiedAtMillis: Long,
        @JvmField val source: PluginSourceIdentity?,
        @JvmField val known: Boolean,
        @JvmField val loading: Boolean,
        @JvmField val stale: Boolean,
        @JvmField val error: String,
        @JvmField val items: List<Item>,
        @JvmField val pendingReviews: List<PendingReview>,
        @JvmField val safeMode: Boolean,
    )

    /** 只允许原 Registry 消耗一次；来源 A→B→A 也不能复活旧票。 */
    class Query
    internal constructor(
        @JvmField val source: PluginSourceIdentity,
        @JvmField val sourceEpoch: Long,
        @JvmField val invalidationSeq: Long,
        @JvmField val queryId: Long,
    )

    private var sourceEpoch: Long = 0L
    private var nextQueryId: Long = 0L
    private val mutations: PluginMutationTickets<PluginSourceIdentity> = PluginMutationTickets()
    private var activeQuery: Query? = null

    @Volatile
    private var current: Snapshot =
        Snapshot(
            0L,
            0L,
            0L,
            null,
            false,
            false,
            true,
            "",
            Collections.emptyList(),
            Collections.emptyList(),
            false,
        )

    fun snapshot(): Snapshot = current

    /** 同一用户数据根保留陈旧展示；运行根或 Web 换代仍撤销旧运行证据和查询票据。 */
    @Synchronized
    fun updateSource(source: PluginSourceIdentity?): Boolean {
        val next: PluginSourceIdentity = Objects.requireNonNull(source)!!
        val old = current
        if (next.equals(old.source)) return false
        val sameData = next.sameUserDataRoot(old.source)
        var retained: List<Item> = Collections.emptyList()
        if (sameData) {
            val values = ArrayList<Item>()
            for (item in old.items) if (!item.dynamic)
                values.add(withRuntime(item, "unknown", -1L, true, false))
            retained = Collections.unmodifiableList(values)
        }
        sourceEpoch++
        activeQuery = null
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq + 1,
                if (sameData) old.verifiedAtMillis else 0L,
                next,
                sameData && old.known,
                false,
                true,
                "",
                retained,
                if (sameData) old.pendingReviews else Collections.emptyList(),
                sameData && old.safeMode,
            )
        mutations.observeSource(next, sourceEpoch)
        return true
    }

    /** 生命周期已确认换代时立即撤销旧运行证据；不把已有根身份当作重新探测成功。 */
    @Synchronized
    fun updateWebGeneration(generation: Long): Boolean {
        val source = current.source
        if (source == null || source.webGeneration == generation) return false
        return updateSource(
            PluginSourceIdentity(
                source.dataRoot,
                source.dataRootKey,
                source.dataRootDevice,
                source.rootfs,
                source.rootfsKey,
                source.rootfsDevice,
                source.runtimeMarkerSha256,
                generation,
            )
        )
    }

    /** 入队时只使用已观察来源；停止过渡中不为其他请求补发凭据。 */
    @Synchronized
    fun issueMutation(
        web: PluginMutationTickets.Web
    ): PluginMutationTickets.Ticket<PluginSourceIdentity>? {
        if (mutations.isOlderWebObservation(web)) return null
        mutations.observeWeb(web)
        if (mutations.stopping()) return null
        val source = current.source
        if (source == null || source.webGeneration != web.generation) return null
        return mutations.issue()
    }

    @Synchronized
    fun observeMutationWeb(web: PluginMutationTickets.Web) {
        mutations.observeWeb(web)
    }

    @Synchronized
    fun observeMutationWebEvent(web: PluginMutationTickets.Web) {
        mutations.observeWebEvent(web)
    }

    @Synchronized
    fun claimMutation(
        ticket: PluginMutationTickets.Ticket<PluginSourceIdentity>?,
        source: PluginSourceIdentity?,
        web: PluginMutationTickets.Web,
    ): Boolean {
        if (mutations.isOlderWebObservation(web)) return false
        mutations.observeWeb(web)
        return source != null &&
            Objects.equals(current.source, source) &&
            source.webGeneration == web.generation &&
            mutations.claim(ticket)
    }

    @Synchronized
    fun validateMutation(
        ticket: PluginMutationTickets.Ticket<PluginSourceIdentity>?,
        source: PluginSourceIdentity?,
        web: PluginMutationTickets.Web,
    ): Boolean {
        if (mutations.isOlderWebObservation(web)) return false
        mutations.observeWeb(web)
        return source != null &&
            Objects.equals(current.source, source) &&
            source.webGeneration == web.generation &&
            mutations.active(ticket)
    }

    @Synchronized
    fun armMutationStop(
        ticket: PluginMutationTickets.Ticket<PluginSourceIdentity>?,
        source: PluginSourceIdentity?,
        web: PluginMutationTickets.Web,
        cancellationRevision: Long,
        ownsExclusive: Boolean,
    ): PluginMutationTickets.Stop<PluginSourceIdentity>? {
        if (!validateMutation(ticket, source, web)) return null
        val exact = source ?: return null
        if (exact.webGeneration == Long.MAX_VALUE) return null
        return mutations.armStop(
            ticket,
            exact.withWebGeneration(exact.webGeneration + 1),
            cancellationRevision,
            ownsExclusive,
        )
    }

    @Synchronized
    fun finishMutationStop(
        stop: PluginMutationTickets.Stop<PluginSourceIdentity>?,
        source: PluginSourceIdentity?,
        web: PluginMutationTickets.Web,
        cancellationRevision: Long,
        maintenanceOwner: Boolean,
    ): PluginMutationTickets.Ticket<PluginSourceIdentity>? {
        if (!mutations.canFinish(stop, source, web, cancellationRevision, maintenanceOwner))
            return null
        val exact = source ?: return null
        updateSource(exact)
        return mutations.finishStop(stop, exact, web, cancellationRevision, maintenanceOwner)
    }

    @Synchronized
    fun abortMutationStop(stop: PluginMutationTickets.Stop<PluginSourceIdentity>?) {
        mutations.abortStop(stop)
    }

    @Synchronized
    fun closeMutation(ticket: PluginMutationTickets.Ticket<PluginSourceIdentity>?) {
        mutations.close(ticket)
    }

    @Synchronized
    fun invalidate(): Long {
        val old = current
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq + 1,
                old.verifiedAtMillis,
                old.source,
                old.known,
                old.loading,
                true,
                old.error,
                old.items,
                old.pendingReviews,
                old.safeMode,
            )
        return current.invalidationSeq
    }

    /** 同一来源的在途读取复用同一票据；调度合并仍由上层负责。 */
    @Synchronized
    fun beginQuery(source: PluginSourceIdentity?): Query {
        updateSource(source)
        val active = activeQuery
        if (active != null) return active
        val exact: PluginSourceIdentity = Objects.requireNonNull(source)!!
        val old = current
        val query = Query(exact, sourceEpoch, old.invalidationSeq, ++nextQueryId)
        activeQuery = query
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq,
                old.verifiedAtMillis,
                exact,
                old.known,
                true,
                old.stale,
                "",
                old.items,
                old.pendingReviews,
                old.safeMode,
            )
        return query
    }

    @Synchronized
    fun completeQuery(
        query: Query?,
        items: List<Item>?,
        pendingReviews: List<PendingReview>?,
        safeMode: Boolean,
    ): Boolean = completeQuery(query, items, pendingReviews, safeMode, System.currentTimeMillis())

    @Synchronized
    fun completeQuery(
        query: Query?,
        items: List<Item>?,
        pendingReviews: List<PendingReview>?,
        safeMode: Boolean,
        verifiedAtMillis: Long,
    ): Boolean {
        if (!accepts(query)) return false
        val accepted = query ?: return false
        val nextItems = immutable(items)
        val nextPending = immutable(pendingReviews)
        val old = current
        activeQuery = null
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq,
                verifiedAtMillis,
                old.source,
                true,
                false,
                old.invalidationSeq != accepted.invalidationSeq,
                "",
                nextItems,
                nextPending,
                safeMode,
            )
        return true
    }

    /** 失败不把未知写成零项，也不抹掉同来源上次完整结果。 */
    @Synchronized
    fun failQuery(query: Query?, error: String?): Boolean {
        if (!accepts(query)) return false
        failSource(error)
        return true
    }

    /** 来源读取本身失败时使用；退休在途查询，保留旧结果并明确陈旧。 */
    @Synchronized
    fun failSource(error: String?) {
        val old = current
        activeQuery = null
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq + 1,
                old.verifiedAtMillis,
                old.source,
                old.known,
                false,
                true,
                if (error == null || error.isEmpty()) "PLUGIN_SOURCE_UNAVAILABLE" else error,
                old.items,
                old.pendingReviews,
                old.safeMode,
            )
    }

    /** delete-list 等已确认回执只更新目录项；完整扫描仍需确认其他状态。 */
    @Synchronized
    fun recordConfirmedItems(source: PluginSourceIdentity?, items: List<Item>?): Boolean {
        if (source == null || !Objects.equals(current.source, source)) return false
        val next = immutable(items)
        val old = current
        activeQuery = null
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq + 1,
                old.verifiedAtMillis,
                source,
                true,
                false,
                true,
                "",
                next,
                old.pendingReviews,
                old.safeMode,
            )
        return true
    }

    /** 仅在执行口确认启停后更新精确目标，绝不按同名覆盖动态插件。 */
    @Synchronized
    fun updateConfirmedTarget(
        source: PluginSourceIdentity?,
        stableKey: String?,
        confirmed: Item?,
    ): Boolean {
        if (
            source == null ||
                !source.equals(current.source) ||
                !current.known ||
                stableKey == null ||
                stableKey.isEmpty() ||
                confirmed == null ||
                confirmed.dynamic ||
                !stableKey.equals(confirmed.stableKey)
        )
            return false
        val next = ArrayList<Item>(current.items)
        var selected = -1
        for (index in next.indices) {
            val existing = next[index]
            if (!existing.dynamic && stableKey.equals(existing.stableKey)) {
                if (selected >= 0) return false
                selected = index
            }
        }
        if (selected < 0) return false
        next[selected] = confirmed
        return recordConfirmedItems(source, next)
    }

    /**
     * 心跳只更新当前来源的运行投影，不提供持久目录证明，也不取消正在进行的完整扫描。 持久条目沿当前快照保留，仅采纳其运行字段，防止较早投影覆盖后来的启停回执。 返回 true
     * 仅表示条目内容发生变化；相同投影不递增版本。
     */
    @Synchronized
    fun updateRuntimeItems(source: PluginSourceIdentity?, items: List<Item>?): Boolean {
        if (source == null || !source.equals(current.source)) return false
        val projection = immutable(items)
        val persistent = java.util.HashMap<String, Item>()
        val keys = java.util.HashSet<String>()
        for (item in projection) {
            if (!keys.add(item.stableKey)) return false
            if (item.dynamic) {
                if (item.runtimeGeneration != source.webGeneration) return false
            } else persistent.put(item.stableKey, item)
        }
        val old = current
        val next = ArrayList<Item>()
        for (item in old.items) {
            if (item.dynamic) continue
            val runtime = persistent.remove(item.stableKey)
            if (runtime != null) {
                if (
                    runtime.runtimeGeneration != -1L &&
                        runtime.runtimeGeneration != source.webGeneration
                )
                    return false
                if (
                    !item.version.equals(runtime.version) ||
                        !item.origin.equals(runtime.origin) ||
                        !item.source.equals(runtime.source) ||
                        !item.patchRevision.equals(runtime.patchRevision) ||
                        !item.contentFingerprint.equals(runtime.contentFingerprint) ||
                        !item.contentAlgorithm.equals(runtime.contentAlgorithm) ||
                        !item.installedDsh.equals(runtime.installedDsh) ||
                        !item.runtimePlatform.equals(runtime.runtimePlatform) ||
                        !item.sameEntryConfiguration(runtime)
                ) {
                    next.add(item)
                    continue
                }
                var restartRequired = item.restartRequired
                if (item.enabled == runtime.enabled) {
                    if (runtime.restartRequired) restartRequired = true
                    else if (
                        !runtime.runtimeStale &&
                            runtime.runtimeGeneration == source.webGeneration &&
                            (item.enabled && "loaded" == runtime.runtimeState ||
                                !item.enabled &&
                                    ("unloaded" == runtime.runtimeState ||
                                        "disabled" == runtime.runtimeState))
                    )
                        restartRequired = false
                }
                next.add(withRuntimeEvidence(item, runtime, restartRequired))
            } else next.add(item)
        }
        // 心跳不能凭空发现新的持久安装；仍须完整目录查询确认。
        if (!persistent.isEmpty()) return false
        for (item in projection) if (item.dynamic) next.add(item)
        if (sameItems(old.items, next)) return false
        current =
            Snapshot(
                old.version + 1,
                old.invalidationSeq,
                old.verifiedAtMillis,
                old.source,
                old.known,
                old.loading,
                old.stale,
                old.error,
                Collections.unmodifiableList(next),
                old.pendingReviews,
                old.safeMode,
            )
        return true
    }

    private fun withRuntimeEvidence(item: Item, runtime: Item, restartRequired: Boolean): Item =
        try {
            item
                .withRuntimeEvidence(
                    runtime.runtimeState,
                    runtime.runtimeGeneration,
                    runtime.runtimeStale,
                    runtime.runtimeReason,
                    runtime.runtimeInstance,
                    runtime.runtimeObservedAt,
                    runtime.entries,
                )
                .withRestartRequired(restartRequired)
                .withManagementEvidence(
                    runtime.managementReadOnly,
                    runtime.managementReadOnlyReason,
                    runtime.startupEligibilitySnapshot(),
                )
        } catch (invalid: Exception) {
            throw IllegalStateException("PLUGIN_RUNTIME_PROJECTION", invalid)
        }

    private fun withRuntime(
        item: Item,
        state: String,
        generation: Long,
        stale: Boolean,
        restartRequired: Boolean,
    ): Item {
        if (
            state.equals(item.runtimeState) &&
                generation == item.runtimeGeneration &&
                stale == item.runtimeStale &&
                restartRequired == item.restartRequired &&
                item.managementReadOnly == null &&
                item.eligibilityObservedAt == 0L &&
                item.entries.stream().noneMatch { entry ->
                    java.lang.Boolean.TRUE.equals(entry.managementReadOnly)
                }
        )
            return item
        return try {
            item.withRuntime(state, generation, stale).withRestartRequired(restartRequired)
        } catch (invalid: Exception) {
            throw IllegalStateException("PLUGIN_RUNTIME_PROJECTION", invalid)
        }
    }

    private fun sameItems(left: List<Item>, right: List<Item>): Boolean {
        if (left.size != right.size) return false
        for (index in left.indices) {
            val before = left[index]
            val after = right[index]
            if (
                !before.stableKey.equals(after.stableKey) ||
                    !before.contentKey.equals(after.contentKey)
            )
                return false
        }
        return true
    }

    private fun accepts(query: Query?): Boolean =
        query != null &&
            query === activeQuery &&
            query.sourceEpoch == sourceEpoch &&
            query.source == current.source

    private fun <T> immutable(values: List<T>?): List<T> {
        val exact = Objects.requireNonNull(values)!!
        val copy = ArrayList<T>(exact.size)
        for (value in exact) copy.add(Objects.requireNonNull(value))
        return Collections.unmodifiableList(copy)
    }
}
