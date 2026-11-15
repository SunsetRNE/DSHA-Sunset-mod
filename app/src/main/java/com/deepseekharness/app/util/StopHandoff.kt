package com.deepseekharness.app.util

/** S1a 纯交接判据。调用者只能观察结果；S1b 才能在既有票据流程中消费交接。 */
object StopHandoff {
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
