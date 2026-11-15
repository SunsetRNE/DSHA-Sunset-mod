package com.deepseekharness.app.util

/** 本宿主进程的一次停止身份；由既有会话在 lifecycle 锁内分配，不授予执行权限。 */
class StopOperationId(
    private val owner: Any,
    private val sequence: Long,
    private val stoppedGeneration: Long,
    private val stoppedInstance: String,
    private val stopGeneration: Long,
    private val cancellationRevision: Long,
) {
    init {
        require(sequence > 0 && stoppedGeneration >= 0 && stopGeneration > stoppedGeneration)
        require(cancellationRevision >= 0)
    }

    fun belongsTo(candidate: Any): Boolean = owner === candidate

    fun sequence(): Long = sequence

    fun stoppedGeneration(): Long = stoppedGeneration

    fun stoppedInstance(): String = stoppedInstance

    fun stopGeneration(): Long = stopGeneration

    fun cancellationRevision(): Long = cancellationRevision
}
