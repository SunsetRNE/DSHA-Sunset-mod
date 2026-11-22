package com.deepseekharness.app.contract

import android.content.Context
import android.content.SharedPreferences
import java.util.Objects

/** 独立布局命名空间；保留 v1: 格式、历史键与未知合法贡献。 */
class SharedPreferencesLayoutStore : LayoutStore {
    private val preferences: SharedPreferences
    private val diagnostics: DiagnosticsSink

    constructor(preferences: SharedPreferences?) : this(preferences, DiagnosticsSink.discarding())

    constructor(preferences: SharedPreferences?, diagnostics: DiagnosticsSink?) {
        this.preferences = Objects.requireNonNull(preferences, "preferences")!!
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    }

    override fun load(): LayoutSnapshot {
        try {
            val order = decodeOrder(preferences.getString(PREF_ORDER, ""))
            val hidden = decodeHidden(preferences.getStringSet(PREF_HIDDEN, java.util.Set.of()))
            return LayoutSnapshot(order, hidden)
        } catch (error: RuntimeException) {
            diagnostics.report(
                ContractCodes.LAYOUT_CORRUPT,
                "layout preferences unreadable: ${error.javaClass.simpleName}",
            )
            return LayoutSnapshot.empty()
        }
    }

    override fun save(snapshot: LayoutSnapshot?) {
        Objects.requireNonNull(snapshot, "snapshot")
        preferences
            .edit()
            .putString(PREF_ORDER, encodeOrder(snapshot!!.order()))
            .putStringSet(PREF_HIDDEN, LinkedHashSet(snapshot.hidden()))
            .apply()
    }

    companion object {
        const val NAMESPACE = "dsha_module_layout"
        const val PREF_ORDER = "order"
        const val PREF_HIDDEN = "hidden"
        private const val ORDER_PREFIX = "v1:"

        @JvmStatic
        fun open(context: Context?): SharedPreferencesLayoutStore {
            if (context == null) throw IllegalArgumentException("CONTEXT_REQUIRED")
            return SharedPreferencesLayoutStore(
                context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE)
            )
        }

        private fun decodeOrder(encoded: String?): List<String> {
            if (encoded == null || encoded.isEmpty()) return java.util.List.of()
            if (!encoded.startsWith(ORDER_PREFIX))
                throw IllegalArgumentException("LAYOUT_ORDER_INVALID")
            val body = encoded.substring(ORDER_PREFIX.length)
            if (body.isEmpty()) return java.util.List.of()
            // Kotlin split(limit=0) 保留尾空项，对应 Java split("\n", -1)。
            val parts = body.split('\n')
            val order = ArrayList<String>(parts.size)
            for (part in parts) order.add(LayoutSnapshot.validateKeyForHost(part))
            return order
        }

        private fun decodeHidden(stored: Set<String?>?): Set<String> {
            if (stored == null || stored.isEmpty()) return java.util.Set.of()
            val hidden = LinkedHashSet<String>()
            for (key in stored) hidden.add(LayoutSnapshot.validateKeyForHost(key))
            return hidden
        }

        private fun encodeOrder(order: List<String>): String {
            val encoded = StringBuilder(ORDER_PREFIX)
            for (index in order.indices) {
                if (index > 0) encoded.append('\n')
                encoded.append(order[index])
            }
            return encoded.toString()
        }
    }
}
