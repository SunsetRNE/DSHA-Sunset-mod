package com.deepseekharness.app.contract

import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Objects
import java.util.regex.Pattern

/**
 * 宿主布局快照：顺序 + 隐藏集合。未知但仍合法的键必须原样保留，避免降级后丢布局。
 *
 * 键格式固定为模块全局键 module-id#contribution-id；坏键由存储后端回默认布局。
 */
class LayoutSnapshot(order: List<String>?, hidden: Set<String>?) {
    private val order: List<String>
    private val hidden: Set<String>

    init {
        val checkedOrder: List<String> = Objects.requireNonNull(order, "order")!!
        val checkedHidden: Set<String> = Objects.requireNonNull(hidden, "hidden")!!
        val orderCopy = ArrayList<String>(checkedOrder.size)
        val seen = HashSet<String>()
        for (key: String? in checkedOrder) {
            val checkedKey = validateKey(key)
            if (!seen.add(checkedKey)) throw IllegalArgumentException("LAYOUT_ORDER_DUPLICATE")
            orderCopy.add(checkedKey)
        }
        val hiddenCopy = LinkedHashSet<String>()
        for (key: String? in checkedHidden) hiddenCopy.add(validateKey(key))
        this.order = java.util.List.copyOf(orderCopy)
        this.hidden = java.util.Set.copyOf(hiddenCopy)
    }

    fun order(): List<String> = order

    fun hidden(): Set<String> = hidden

    fun isHidden(key: String?): Boolean {
        val values: Set<String?> = hidden
        return values.contains(key)
    }

    fun withHidden(key: String?, hidden: Boolean): LayoutSnapshot {
        val checkedKey = validateKey(key)
        val next = LinkedHashSet(this.hidden)
        if (hidden) next.add(checkedKey) else next.remove(checkedKey)
        return LayoutSnapshot(order, next)
    }

    /** 用给定顺序替换，但把原有键追加在末尾，保留未知/暂不可达的布局记录。 */
    fun mergedOrder(requested: List<String>?): LayoutSnapshot {
        val checkedRequested: List<String> = Objects.requireNonNull(requested, "requested")!!
        val merged = ArrayList<String>(checkedRequested.size + order.size)
        val seen = HashSet<String>()
        for (key: String? in checkedRequested) {
            val checkedKey = validateKey(key)
            if (!seen.add(checkedKey)) throw IllegalArgumentException("LAYOUT_ORDER_DUPLICATE")
            merged.add(checkedKey)
        }
        for (key in order) if (seen.add(key)) merged.add(key)
        return LayoutSnapshot(merged, hidden)
    }

    override fun equals(other: Any?): Boolean =
        other is LayoutSnapshot && order == other.order && hidden == other.hidden

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + order.hashCode()
        result = 31 * result + hidden.hashCode()
        return result
    }

    override fun toString(): String = "LayoutSnapshot[order=$order, hidden=$hidden]"

    companion object {
        private val KEY = Pattern.compile("[a-z][a-z0-9._-]{0,63}#[a-z][a-z0-9._-]{0,63}")

        @JvmStatic
        fun empty(): LayoutSnapshot = LayoutSnapshot(java.util.List.of(), java.util.Set.of())

        @JvmStatic fun validKey(key: String?): Boolean = key != null && KEY.matcher(key).matches()

        /** final 类上的 protected static 保留 Java 同包调用，外包不能继承扩大入口。 */
        @JvmStatic
        protected fun validateKey(key: String?): String {
            if (!validKey(key)) throw IllegalArgumentException("LAYOUT_KEY_INVALID")
            return key!!
        }

        @JvmSynthetic internal fun validateKeyForHost(key: String?): String = validateKey(key)
    }
}
