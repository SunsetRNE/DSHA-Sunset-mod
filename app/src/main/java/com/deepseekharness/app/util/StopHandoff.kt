package com.deepseekharness.app.util

/** 停止事实的纯判据；交接始终依附原票据与应用会话，不产生独立启动权限。 */
object StopHandoff {
    /** 只在既有 lifecycle 锁内操作；记录原票据从 gS 到 gN 的一次性交接。 */
    class Admission(val completion: StopCompletion, private val ticket: WebStartProtection.Ticket) {
        private var startGeneration = -1L
        private var consumed = false
        private var dispatched = false

        fun bindStart(
            owner: Any,
            latest: StopOperationId?,
            generation: Long,
            revision: Long,
        ): Boolean {
            val operation = completion.operation()
            if (
                startGeneration != -1L ||
                    !completion.complete() ||
                    !operation.belongsTo(owner) ||
                    latest !== operation ||
                    revision != operation.cancellationRevision() ||
                    generation != operation.stopGeneration() + 1 ||
                    ticket.generation() != generation
            )
                return false
            startGeneration = generation
            return true
        }

        private fun valid(
            owner: Any,
            latest: StopOperationId?,
            generation: Long,
            revision: Long,
            protection: WebStartProtection,
        ): Boolean =
            completion.operation().belongsTo(owner) &&
                latest === completion.operation() &&
                generation == startGeneration &&
                revision == completion.operation().cancellationRevision() &&
                protection.isClaimedCurrent(ticket, generation)

        fun consume(
            owner: Any,
            latest: StopOperationId?,
            generation: Long,
            revision: Long,
            protection: WebStartProtection,
        ): Boolean {
            if (consumed || dispatched || !valid(owner, latest, generation, revision, protection))
                return false
            consumed = true
            return true
        }

        fun current(
            owner: Any,
            latest: StopOperationId?,
            generation: Long,
            revision: Long,
            protection: WebStartProtection,
        ): Boolean =
            consumed && !dispatched && valid(owner, latest, generation, revision, protection)

        fun dispatch(
            owner: Any,
            latest: StopOperationId?,
            generation: Long,
            revision: Long,
            protection: WebStartProtection,
        ): Boolean {
            if (!current(owner, latest, generation, revision, protection)) return false
            dispatched = true
            return true
        }
    }

    enum class Decision {
        ELIGIBLE,
        INCOMPLETE,
        WRONG_OPERATION,
        WRONG_OWNER,
        STALE_INTENT,
        ENVIRONMENT_CHANGED,
        UNPROTECTED_PATH,
    }

    /** 只有本次全部原 guest 来源与终态 key 一致，才能保留该退出事实。 */
    @JvmStatic
    fun bindGuest(
        observed: StopCompletion.Fact?,
        required: List<StopCompletion.ProcessKey?>,
        instanceBound: Boolean,
        observedAt: Long,
    ): StopCompletion.Fact =
        if (
            observed?.scope == StopCompletion.Scope.GUEST &&
                observed.outcome == StopCompletion.Outcome.EXITED &&
                observed.target != null &&
                instanceBound &&
                required.isNotEmpty() &&
                required.all { it != null && it == observed.target }
        )
            observed
        else
            StopCompletion.Fact(
                StopCompletion.Scope.GUEST,
                StopCompletion.Outcome.UNKNOWN,
                observedAt,
                "captured-guest-unbound",
            )

    @JvmStatic
    fun evaluate(
        completion: StopCompletion,
        expected: StopOperationId,
        latest: StopOperationId?,
        owner: Any,
        generation: Long,
        cancellationRevision: Long,
        environment: StopCompletion.Environment,
        protectedRecovery: Boolean,
    ): Decision =
        when {
            completion.operation() !== expected || latest !== expected -> Decision.WRONG_OPERATION
            !expected.belongsTo(owner) -> Decision.WRONG_OWNER
            generation != expected.stopGeneration() ||
                cancellationRevision != expected.cancellationRevision() -> Decision.STALE_INTENT
            !protectedRecovery -> Decision.UNPROTECTED_PATH
            !completion.complete() -> Decision.INCOMPLETE
            !environment.known() || completion.environment() != environment ->
                Decision.ENVIRONMENT_CHANGED
            else -> Decision.ELIGIBLE
        }
}
