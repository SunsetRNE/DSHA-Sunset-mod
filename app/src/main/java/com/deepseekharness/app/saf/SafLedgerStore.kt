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
 * 本类不做业务判定，调用方负责串行化自己的写操作。同一 SharedPreferences 的失败提交隔离跨适配器实例共享。
 */
class SafLedgerStore(preferences: SharedPreferences?) {
    private val preferences: SharedPreferences =
        Objects.requireNonNull(preferences, "preferences")!!

    /** 读取授权簿；prefs 不可读或全为坏值时返回空簿，不抛异常、不误删磁盘内容。 */
    fun load(): SafGrantLedger = loadDecoded().ledger!!

    /** 读取并保留解码警告与未知条目，供迁移/诊断使用（诊断只输出短码，不输出 URI）。 */
    fun loadDecoded(): SafLedgerCodec.Decoded {
        try {
            return SafLedgerCodec.decode(confirmedValues())
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
    fun save(ledger: SafGrantLedger?): Boolean {
        Objects.requireNonNull(ledger, "ledger")
        val desired = SafLedgerCodec.encode(ledger)
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

    private class CommitState {
        var sequence = 0L
        var beforeFailure: Map<String, *>? = null
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
