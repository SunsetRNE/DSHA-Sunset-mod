package com.deepseekharness.app.plugins

import java.util.IdentityHashMap
import java.util.Objects
import java.util.UUID

private val OPERATION_ID = Regex("[a-f0-9]{32}")
private val BACKEND_TASK_ID = Regex("[a-f0-9]{32}")

/** A5 退出证明的消费者；默认没有生产者，不从自由文本、PID 或界面确认创建证明。 */
class PluginExitProofGate(private val owner: Owner) {
    enum class Outcome {
        EXITED_CONFIRMED,
        UNKNOWN,
    }

    @JvmRecord
    data class Binding(
        val operationId: String?,
        val backendTaskId: String?,
        val generation: Long?,
        val instance: String?,
    ) {
        init {
            val operationValid: Boolean =
                try {
                    operationId != null &&
                        (OPERATION_ID.matches(operationId) ||
                            UUID.fromString(operationId).toString() == operationId)
                } catch (invalid: IllegalArgumentException) {
                    false
                } catch (invalid: NullPointerException) {
                    false
                }
            if (!operationValid) throw IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING")
            if (backendTaskId == null || !BACKEND_TASK_ID.matches(backendTaskId))
                throw IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING")
            if (generation != null && generation < 0)
                throw IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING")
            if (instance != null && (instance.isEmpty() || instance.length > 128))
                throw IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING")
        }

        final override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Binding &&
                    operationId == other.operationId &&
                    backendTaskId == other.backendTaskId &&
                    generation == other.generation &&
                    instance == other.instance)

        final override fun hashCode(): Int =
            Objects.hash(operationId, backendTaskId, generation, instance)

        final override fun toString(): String =
            "Binding[operationId=$operationId, backendTaskId=$backendTaskId, " +
                "generation=$generation, instance=$instance]"
    }

    /** 数据只是可信 owner 解码的结果，本类型的公开构造器本身不授予证明效力。 */
    @JvmRecord
    data class Evidence(
        val binding: Binding?,
        val outcome: Outcome?,
        val evidenceSource: String?,
        val observedAt: Long,
    ) {
        final override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Evidence &&
                    binding == other.binding &&
                    outcome == other.outcome &&
                    evidenceSource == other.evidenceSource &&
                    observedAt == other.observedAt)

        final override fun hashCode(): Int =
            Objects.hash(binding, outcome, evidenceSource, observedAt)

        final override fun toString(): String =
            "Evidence[binding=$binding, outcome=$outcome, evidenceSource=$evidenceSource, " +
                "observedAt=$observedAt]"
    }

    fun interface Owner {
        /** A5 须核对真实执行代次并撤销同一任务先前投递的 token；错配/未知返回 null。 */
        fun verify(opaqueToken: Any?): Evidence?
    }

    private val consumed = IdentityHashMap<Any, Long>()

    constructor() : this(Owner { null })

    @Synchronized
    fun consume(token: Any?, expected: Binding?, retainedAt: Long, now: Long): Boolean {
        if (
            token == null ||
                token is CharSequence ||
                token is Map<*, *> ||
                token is Evidence ||
                expected == null ||
                // 未接齐 A5 执行上下文时保持拒绝；null==null 不能变成精确绑定。
                expected.generation == null ||
                expected.instance == null ||
                retainedAt <= 0 ||
                now < retainedAt
        )
            return false
        consumed.entries.removeAll { now >= it.value && now - it.value > MAX_AGE_MILLIS }
        if (consumed.containsKey(token) || consumed.size >= MAX_CONSUMED) return false
        val evidence: Evidence? =
            try {
                owner.verify(token)
            } catch (unavailable: RuntimeException) {
                return false
            }
        if (
            evidence == null ||
                evidence.outcome != Outcome.EXITED_CONFIRMED ||
                evidence.binding == null ||
                evidence.observedAt < retainedAt ||
                evidence.observedAt > now ||
                now - evidence.observedAt > MAX_AGE_MILLIS ||
                evidence.evidenceSource == null ||
                !EVIDENCE_SOURCE.matches(evidence.evidenceSource)
        )
            return false
        val actual = evidence.binding
        if (actual.generation == null || actual.instance == null) return false
        if (
            expected.operationId != actual.operationId ||
                expected.backendTaskId != actual.backendTaskId ||
                expected.generation != actual.generation ||
                expected.instance != actual.instance
        )
            return false
        consumed[token] = evidence.observedAt
        return true
    }

    companion object {
        const val MAX_AGE_MILLIS = 30_000L
        private const val MAX_CONSUMED = 64
        private val EVIDENCE_SOURCE = Regex("[A-Z0-9_]{1,64}")
    }
}
