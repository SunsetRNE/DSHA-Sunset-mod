package com.deepseekharness.app.contract

import com.deepseekharness.app.util.SensitiveData
import java.util.Objects
import java.util.function.Consumer
import java.util.regex.Pattern

/** 契约层唯一脱敏出口；默认丢弃事件，不新增日志通道。 */
class DiagnosticsSink private constructor(private val downstream: Consumer<Event>) {
    /** 保留 record 访问器和值语义；直接构造时仍允许空字段。 */
    class Event(private val code: String?, private val detail: String?) {
        fun code(): String? = code

        fun detail(): String? = detail

        override fun equals(other: Any?): Boolean =
            other is Event && code == other.code && detail == other.detail

        override fun hashCode(): Int = 31 * (code?.hashCode() ?: 0) + (detail?.hashCode() ?: 0)

        override fun toString(): String = "Event[code=$code, detail=$detail]"
    }

    fun report(code: String?) {
        report(code, "")
    }

    fun report(code: String?, detail: String?) {
        val safeCode =
            if (code != null && CODE.matcher(code).matches()) code
            else ContractCodes.INVALID_DIAGNOSTIC
        var safeDetail = SensitiveData.redact(detail ?: "") ?: ""
        if (safeDetail.length > MAX_DETAIL_LENGTH)
            safeDetail = safeDetail.substring(0, MAX_DETAIL_LENGTH)
        try {
            downstream.accept(Event(safeCode, safeDetail))
        } catch (_: RuntimeException) {
            // 下游 RuntimeException 不影响生命周期；Error 保持原来的向上抛出行为。
        }
    }

    companion object {
        private val CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}")
        private const val MAX_DETAIL_LENGTH = 2048

        @JvmStatic fun discarding(): DiagnosticsSink = DiagnosticsSink(Consumer {})

        @JvmStatic
        fun forwarding(downstream: Consumer<Event>?): DiagnosticsSink =
            DiagnosticsSink(Objects.requireNonNull(downstream, "downstream")!!)
    }
}
