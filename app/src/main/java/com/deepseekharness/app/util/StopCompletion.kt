package com.deepseekharness.app.util

import java.util.Collections
import java.util.EnumMap

/** 只冻结本次停止的历史事实；不是启动票据，也不代表未来仍无进程。 */
class StopCompletion(
    private val operation: StopOperationId,
    private val environment: Environment,
    facts: List<Fact>,
    private val finishedAt: Long,
    private val finish: Finish,
    private val objectsUnchanged: Boolean,
    private val legacyStopped: Boolean,
) {
    enum class Scope {
        GUEST,
        OWNED_LAUNCHERS,
        RECOVERED_LAUNCHERS,
        TRIALS,
        RESIDUALS,
    }

    enum class Outcome {
        EXITED,
        UNCONFIRMED,
        UNKNOWN,
    }

    enum class Finish {
        SUCCEEDED,
        FAILED,
        UNKNOWN,
    }

    /** root 使用设备/inode 身份，inputs 使用范围摘要；不放用户路径、命令或凭据。 */
    data class Environment(val root: String, val boot: String, val inputs: String) {
        fun known(): Boolean = root.isNotEmpty() && boot.isNotEmpty() && inputs.isNotEmpty()
    }

    /** 原 guest 的内核身份；不携带 token、命令或用户路径。 */
    data class ProcessKey(val boot: String, val pid: Int, val started: Long) {
        init {
            require(boot.isNotEmpty() && pid > 1 && started > 0)
        }
    }

    /** EXITED 包含同一范围经核验本来为空；未检查必须保留 UNKNOWN。 */
    data class Fact
    @JvmOverloads
    constructor(
        val scope: Scope,
        val outcome: Outcome,
        val observedAt: Long,
        val evidence: String,
        val target: ProcessKey? = null,
    ) {
        init {
            require(observedAt >= 0)
            require(outcome != Outcome.EXITED || evidence.isNotEmpty()) {
                "STOP_EXIT_EVIDENCE_MISSING"
            }
        }
    }

    private val facts: Map<Scope, Fact>

    init {
        require(finishedAt >= 0)
        val copy = EnumMap<Scope, Fact>(Scope::class.java)
        for (fact in facts) {
            require(copy.put(fact.scope, fact) == null) { "STOP_SCOPE_DUPLICATE" }
            require(fact.observedAt <= finishedAt) { "STOP_OBSERVATION_AFTER_FINISH" }
        }
        this.facts = Collections.unmodifiableMap(copy)
    }

    fun operation(): StopOperationId = operation

    fun environment(): Environment = environment

    fun facts(): Map<Scope, Fact> = facts

    fun finishedAt(): Long = finishedAt

    fun finish(): Finish = finish

    fun objectsUnchanged(): Boolean = objectsUnchanged

    fun legacyStopped(): Boolean = legacyStopped

    fun complete(): Boolean =
        environment.known() &&
            legacyStopped &&
            finish == Finish.SUCCEEDED &&
            objectsUnchanged &&
            Scope.entries.all { facts[it]?.outcome == Outcome.EXITED }
}
