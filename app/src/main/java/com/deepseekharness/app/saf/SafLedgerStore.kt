package com.deepseekharness.app.saf

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Objects
import java.util.WeakHashMap

/**
 * 授权簿的 Android 持久化适配器。
 *
 * [NAMESPACE] 只属于 SAF 能力层，不写 ConfigStore，不读写历史 saf_grants 键，也不碰模块布局的
 * dsha_module_layout。保存时只删除本版本认识的 key；未知 key 与结构损坏但需保留的字段原样留在 prefs。
 *
 * 本类保留已确认的持久化事实，并叠加调用方登记的拒绝意图；系统权限与撤销完成仍由授权簿核验。调用方负责串行化业务操作，同一 SharedPreferences
 * 的提交隔离和拒绝围栏跨适配器实例共享。
 */
class SafLedgerStore(preferences: SharedPreferences?) {
    private val preferences: SharedPreferences =
        Objects.requireNonNull(preferences, "preferences")!!

    /** 读取授权簿；prefs 不可读或全为坏值时返回空簿，不抛异常、不误删磁盘内容。 */
    fun load(): SafGrantLedger = loadDecoded().ledger!!

    /** 读取并保留解码警告与未知条目，供迁移/诊断使用（诊断只输出短码，不输出 URI）。 */
    fun loadDecoded(): SafLedgerCodec.Decoded {
        try {
            val decoded = SafLedgerCodec.decode(confirmedValues())
            return SafLedgerCodec.Decoded(
                applyRestrictions(decoded.ledger!!),
                decoded.preserved,
                decoded.warnings,
            )
        } catch (unreadable: RuntimeException) {
            return SafLedgerCodec.Decoded(
                SafGrantLedger.empty(),
                java.util.Map.of(),
                java.util.List.of("STORE_UNREADABLE"),
            )
        }
    }

    /** 用已确认提交的 schema 判定初始化；不能把 commit(false) 留在内存中的 schema 当作迁移成功。 */
    fun initialized(): Boolean {
        try {
            return confirmedValues().containsKey(SafLedgerCodec.PREF_SCHEMA)
        } catch (unreadable: RuntimeException) {
            return false
        }
    }

    /** 原子保存整份授权簿；false 表示读取或提交失败，调用方不得假装已保存。 */
    fun save(ledger: SafGrantLedger?): Boolean =
        saveWithReleases(Objects.requireNonNull(ledger, "ledger")!!, emptyList())

    /** 仅供已核实系统增量释放的调用方提交删除；其它保存都必须携带拒绝投影。 */
    @JvmSynthetic
    internal fun saveAfterRelease(
        ledger: SafGrantLedger,
        released: List<Pair<SafGrantRecord, Long?>>,
    ): Boolean {
        val committed = saveWithReleases(ledger, released)
        if (committed) for ((record, token) in released) acknowledgeRestriction(record, token, true)
        return committed
    }

    private fun saveWithReleases(
        ledger: SafGrantLedger,
        released: List<Pair<SafGrantRecord, Long?>>,
    ): Boolean {
        // 无关保存也要把当前缩权/撤销写入磁盘，但不能因此确认或清除原意图。
        val desired = SafLedgerCodec.encode(applyRestrictions(ledger, released))
        val current: Map<String, *>
        val before: Map<String, *>
        try {
            current = preferences.all
            before = copyValues(confirmedValues())
        } catch (unreadable: RuntimeException) {
            return false
        }
        val currentSchema = current[SafLedgerCodec.PREF_SCHEMA]
        if (currentSchema != null && SafLedgerCodec.SCHEMA.toString() != currentSchema.toString()) {
            // 磁盘上是更新/损坏的 schema：本版本读不懂，也不能覆盖成 1 后删掉未来字段。
            return false
        }
        // decode 标记为结构损坏但需原样保留的已知 key 不能在保存时被清掉。
        val preserved = SafLedgerCodec.decode(current).preserved.keys
        val state = state()
        val attempt =
            synchronized(state) {
                // commit 通知监听器时可能尚未返回；重入读也只能看已确认的提交前快照。
                state.beforeFailure = before
                ++state.sequence
            }
        val committed =
            try {
                val editor = preferences.edit()
                for (key in current.keys) {
                    if (desired.containsKey(key) || preserved.contains(key)) continue
                    if (SafLedgerCodec.knownKey(key)) editor.remove(key)
                }
                for ((key, value) in desired) {
                    if (same(current[key], value)) continue
                    if (value is Set<*>) {
                        val strings = LinkedHashSet<String>()
                        for (item in value) {
                            if (item !is String) return false
                            strings.add(item)
                        }
                        editor.putStringSet(key, strings)
                    } else if (value is String) {
                        editor.putString(key, value)
                    } else {
                        return false
                    }
                }
                editor.commit()
            } catch (rejected: RuntimeException) {
                false
            }
        synchronized(state) {
            // 同步 prefs 监听器可能重入并完成更新的提交；旧失败不能覆盖较新的已确认结果。
            if (state.sequence == attempt) state.beforeFailure = if (committed) null else before
        }
        return committed
    }

    private fun confirmedValues(): Map<String, *> {
        val state = state()
        val confirmed = synchronized(state) { state.beforeFailure }
        return confirmed ?: preferences.all
    }

    private fun state(): CommitState =
        synchronized(states) {
            states.getOrPut(preferences) { CommitState() }
        }

    /** 缩权先形成内存拒绝围栏；失败 raw map 和旧确认快照都不能重开写许可。 */
    @JvmSynthetic internal fun denyWrite(record: SafGrantRecord): Long? = restrict(record, false)

    /** 撤销保留原代次和 acquired；只在调用方确认系统增量释放且删除已提交后解除。 */
    @JvmSynthetic
    internal fun beginRevocation(record: SafGrantRecord): Long = restrict(record, true)!!

    @JvmSynthetic
    internal fun hasPendingRestriction(uri: String?): Boolean {
        val state = state()
        return synchronized(state) { state.restrictions.containsKey(uri) }
    }

    @JvmSynthetic
    internal fun revocationToken(record: SafGrantRecord): Long? {
        val state = state()
        return synchronized(state) {
            state.restrictions[record.uri]
                ?.takeIf { it.releasing && it.record.epoch == record.epoch }
                ?.token
        }
    }

    @JvmSynthetic
    internal fun finishWriteDenial(record: SafGrantRecord, token: Long?) {
        acknowledgeRestriction(record, token, false)
    }

    /** 只有专门的缩权重试可以确认意图；通用 save 成功只确认磁盘，不清除围栏。 */
    @JvmSynthetic
    internal fun retryWriteDenials() {
        val state = state()
        val pending = synchronized(state) { state.restrictions.values.filter { !it.releasing } }
        for (restriction in pending) {
            val ledger = load()
            val current = ledger.findByUri(restriction.record.uri)
            val desired =
                if (current == null) ledger else ledger.setUserWritable(current.uri, false).ledger
            if (save(desired)) finishWriteDenial(restriction.record, restriction.token)
        }
    }

    private fun restrict(record: SafGrantRecord, releasing: Boolean): Long? {
        val state = state()
        return synchronized(state) {
            val current = state.restrictions[record.uri]
            if (current != null && current.record.epoch == record.epoch) {
                if (current.releasing || !releasing) return@synchronized current.token
            }
            val next = Restriction(record, releasing, ++state.restrictionSequence)
            state.restrictions[record.uri!!] = next
            next.token
        }
    }

    private fun acknowledgeRestriction(record: SafGrantRecord, token: Long?, releasing: Boolean) {
        if (token == null) return
        val persisted =
            try {
                SafLedgerCodec.decode(confirmedValues()).ledger!!.findByUri(record.uri)
            } catch (unreadable: RuntimeException) {
                return
            }
        // 缩权必须确实保存为 false（或已移除）；撤销还必须由调用方先确认系统权限增量已释放。
        if (releasing && persisted != null) return
        if (
            !releasing &&
                persisted != null &&
                (persisted.epoch != record.epoch || persisted.userWritable)
        )
            return
        val state = state()
        synchronized(state) {
            val current = state.restrictions[record.uri]
            if (
                current != null &&
                    current.token == token &&
                    current.releasing == releasing &&
                    current.record.epoch == record.epoch
            ) {
                state.restrictions.remove(record.uri)
            }
        }
    }

    private fun applyRestrictions(
        ledger: SafGrantLedger,
        released: List<Pair<SafGrantRecord, Long?>> = emptyList(),
    ): SafGrantLedger {
        val state = state()
        val pending = synchronized(state) { state.restrictions.values.toList() }
        if (pending.isEmpty()) return ledger
        val records = LinkedHashMap<String, SafGrantRecord>()
        for (record in ledger.records()) records[record.uri!!] = record
        var changed = false
        var nextEpoch = ledger.nextEpoch()
        for (restriction in pending) {
            val original = restriction.record
            if (
                restriction.releasing &&
                    ledger.findByUri(original.uri) == null &&
                    released.any { (record, token) ->
                        record.uri == original.uri &&
                            record.epoch == original.epoch &&
                            token == restriction.token
                    }
            )
                continue
            val current = records[original.uri]
            val restricted =
                if (restriction.releasing) {
                    // 缺失/被无关写替换时也保留原撤销代次与归属，供受控 cleanup 继续释放本功能增量。
                    val source =
                        if (current != null && current.epoch == original.epoch) current
                        else original
                    source.copy(
                        readable = false,
                        writable = false,
                        acquired = original.acquired,
                        state = SafGrantRecord.State.RELEASING,
                        pendingBefore = 0,
                        pendingIntent = 0,
                        previous = null,
                    )
                } else current?.copy(userWritable = false)
            if (restricted != null && restricted != current) {
                records[original.uri!!] = restricted
                nextEpoch = maxOf(nextEpoch, restricted.epoch + 1)
                changed = true
            }
        }
        return if (!changed) ledger
        else SafGrantLedger.of(ArrayList(records.values), ledger.revision() + 1, nextEpoch)
    }

    private class Restriction(val record: SafGrantRecord, val releasing: Boolean, val token: Long)

    private class CommitState {
        var sequence = 0L
        var beforeFailure: Map<String, *>? = null
        var restrictionSequence = 0L
        val restrictions = LinkedHashMap<String, Restriction>()
    }

    companion object {
        /** 独立命名空间；与 saf_grants（旧授权页）和 dsha_module_layout 分开。 */
        const val NAMESPACE = "saf_capability_ledger"

        // Android commit(false) 也可能已替换进程内 map；只在成功重试后重新信任该 map。
        private val states = WeakHashMap<SharedPreferences, CommitState>()

        @JvmStatic
        fun open(context: Context?): SafLedgerStore {
            if (context == null) throw IllegalArgumentException("CONTEXT_REQUIRED")
            return SafLedgerStore(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE))
        }

        @JvmStatic
        private fun copyValues(values: Map<String, *>): Map<String, *> {
            val copy = LinkedHashMap<String, Any?>()
            for ((key, value) in values) copy[key] =
                if (value is Set<*>) LinkedHashSet(value) else value
            return copy
        }

        @JvmStatic private fun same(left: Any?, right: Any?): Boolean = left == right
    }
}
