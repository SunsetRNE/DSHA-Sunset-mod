package com.deepseekharness.app.plugins

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects
import java.util.UUID

/** 插件操作的纯逻辑排队与结果模型；不申请环境票、不执行工作，也不决定是否重放。 */
class PluginOperationQueue private constructor() {
    enum class Type {
        SET_ENABLED,
        INSTALL,
        DELETE,
        UPDATE,
        CHECK_UPDATES,
        ROLLBACK,
        INSPECT,
        IMPORT,
        EXPORT,
        CONFIRM_PREVIEW,
        DISCARD_PREVIEW,
        REVIEW_RESTORED,
        REVIEW_LEGACY,
        SAFE_MODE,
    }

    enum class Phase {
        QUEUED,
        RUNNING,
        COMMITTING,
        SUCCEEDED,
        FAILED,
        TIMEOUT,
        CANCELLED,
        SUPERSEDED,
        INVALIDATED,
        UNCONFIRMED,
        REJECTED,
    }

    /** 某一时刻的不可变快照；展示名称和阶段文案由 UI 决定。 */
    class Operation
    internal constructor(
        @JvmField val operationId: String,
        @JvmField val type: Type,
        @JvmField val targetKey: String,
        @JvmField val desired: Boolean?,
        @JvmField val expectedVersion: String?,
        @JvmField val source: PluginSourceIdentity?,
        @JvmField val phase: Phase,
        message: String?,
        percent: Int,
        @JvmField val transactionId: String?,
        @JvmField val backendTaskId: String?,
        @JvmField val outcome: PluginOperationResult.Detail = PluginOperationResult.Detail.EMPTY,
    ) {
        @JvmField val message: String = message ?: ""

        /** -1 表示尚无可用进度，否则为 0 到 100。 */
        @JvmField val percent: Int = maxOf(-1, minOf(100, percent))

        fun pending(): Boolean =
            phase == Phase.QUEUED || phase == Phase.RUNNING || phase == Phase.COMMITTING

        internal fun withOutcome(detail: PluginOperationResult.Detail): Operation =
            Operation(
                operationId,
                type,
                targetKey,
                desired,
                expectedVersion,
                source,
                phase,
                message,
                percent,
                transactionId,
                backendTaskId,
                detail,
            )

        internal fun withSource(source: PluginSourceIdentity?): Operation =
            Operation(
                operationId,
                type,
                targetKey,
                desired,
                expectedVersion,
                source,
                phase,
                message,
                percent,
                transactionId,
                backendTaskId,
                outcome,
            )

        internal fun withState(
            phase: Phase,
            message: String?,
            percent: Int,
            transactionId: String?,
            backendTaskId: String?,
        ): Operation =
            Operation(
                operationId,
                type,
                targetKey,
                desired,
                expectedVersion,
                source,
                phase,
                message,
                percent,
                transactionId,
                backendTaskId,
                outcome,
            )
    }

    /** 入队时冻结工作引用及原请求；W 应由调用方准备为不可变工作快照，队列不复制或调用它。 即使历史已淘汰，持有者仍能从 operation() 读取这个条目的最终结果。 */
    class Entry<W>
    internal constructor(
        @JvmField val request: Operation,
        @JvmField val work: W,
    ) {
        @JvmField val operationId: String = request.operationId
        @Volatile internal var current: Operation = request

        fun operation(): Operation = current
    }

    /** 所有状态变更在同一监视器内完成；最多一个 active，等待容量不包括 active 和历史。 */
    class Queue<W>
    @JvmOverloads
    constructor(
        private val waitingCapacity: Int = DEFAULT_WAITING_CAPACITY,
        private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
    ) {
        private val waiting: ArrayDeque<Entry<W>> = ArrayDeque()
        // 只保存操作快照，不让有界历史继续持有工作或执行器引用。
        private val history: LinkedHashMap<String, Operation> = LinkedHashMap()
        private var active: Entry<W>? = null

        init {
            if (waitingCapacity < 1 || historyCapacity < 1)
                throw IllegalArgumentException("PLUGIN_QUEUE_CAPACITY_INVALID")
        }

        /**
         * 每次请求都有独立身份。满队列返回 REJECTED 条目并写入历史；不会抛弃请求结果。
         * 同目标最后一个等待请求为启停，且双方来源已知且相等、预期版本相等时，才允许替换并将新请求排到队尾。
         */
        @Synchronized
        fun enqueue(
            type: Type,
            targetKey: String,
            desired: Boolean?,
            expectedVersion: String?,
            source: PluginSourceIdentity?,
            work: W,
        ): Entry<W> {
            Objects.requireNonNull(type, "type")
            Objects.requireNonNull(targetKey, "targetKey")
            Objects.requireNonNull(work, "work")
            if (type == Type.SET_ENABLED && desired == null)
                throw IllegalArgumentException("PLUGIN_ENABLE_DESIRED_REQUIRED")

            val superseded =
                if (type == Type.SET_ENABLED) lastQueuedToggle(targetKey, expectedVersion, source)
                else null
            val blocked = active?.operation()?.phase == Phase.UNCONFIRMED
            val rejected = blocked || (waiting.size >= waitingCapacity && superseded == null)
            val operation =
                Operation(
                    UUID.randomUUID().toString(),
                    type,
                    targetKey,
                    desired,
                    expectedVersion,
                    source,
                    if (rejected) Phase.REJECTED else Phase.QUEUED,
                    when {
                        blocked -> "PLUGIN_OPERATION_BLOCKED_UNCONFIRMED"
                        rejected -> "PLUGIN_QUEUE_FULL"
                        else -> ""
                    },
                    -1,
                    null,
                    null,
                )
            val entry = Entry(operation, work)
            if (rejected) {
                remember(entry, operation)
                return entry
            }
            if (superseded != null) {
                waiting.remove(superseded)
                finish(superseded, Phase.SUPERSEDED, "PLUGIN_OPERATION_SUPERSEDED")
            }
            waiting.addLast(entry)
            return entry
        }

        private fun lastQueuedToggle(
            targetKey: String,
            expectedVersion: String?,
            source: PluginSourceIdentity?,
        ): Entry<W>? {
            if (source == null) return null
            val reverse = waiting.descendingIterator()
            while (reverse.hasNext()) {
                val entry = reverse.next()
                val request = entry.request
                if (request.targetKey == targetKey)
                    return if (
                        request.type == Type.SET_ENABLED &&
                            source == request.source &&
                            Objects.equals(expectedVersion, request.expectedVersion)
                    )
                        entry
                    else null
            }
            return null
        }

        /** 有 active 或没有等待项时返回 null；取得条目时原子地进入 RUNNING。 */
        @Synchronized
        fun poll(): Entry<W>? {
            if (active != null) return null
            val entry = waiting.pollFirst() ?: return null
            val before = entry.operation()
            entry.current =
                before.withState(
                    Phase.RUNNING,
                    before.message,
                    before.percent,
                    before.transactionId,
                    before.backendTaskId,
                )
            active = entry
            return entry
        }

        /** 仅将精确 RUNNING 请求绑定到执行器已核验的同一数据来源；原请求输入始终不变。 */
        @Synchronized
        fun bindExecutionSource(operationId: String?, source: PluginSourceIdentity?): Boolean {
            val current = active
            if (
                source == null ||
                    !isActive(operationId) ||
                    current!!.operation().phase != Phase.RUNNING
            )
                return false
            val before = current.operation()
            if (before.source != null && !before.source.sameDataSource(source)) return false
            current.current = before.withSource(source)
            return true
        }

        @Synchronized
        fun progress(
            operationId: String?,
            phase: Phase?,
            message: String?,
            percent: Int,
            transactionId: String?,
        ): Boolean = progress(operationId, phase, message, percent, transactionId, null)

        /** 只接受精确 active 身份的运行阶段；COMMITTING 不会被迟到的 RUNNING 回调退回。 可选事务或后端任务身份传 null 时保留之前已经观察到的值。 */
        @Synchronized
        fun progress(
            operationId: String?,
            phase: Phase?,
            message: String?,
            percent: Int,
            transactionId: String?,
            backendTaskId: String?,
        ): Boolean {
            val effectivePhase = phase ?: return false
            if (
                !isActive(operationId) ||
                    (effectivePhase != Phase.RUNNING && effectivePhase != Phase.COMMITTING)
            )
                return false
            val current = active ?: return false
            val before = current.operation()
            if (before.phase != Phase.RUNNING && before.phase != Phase.COMMITTING) return false
            if (before.phase == Phase.COMMITTING && effectivePhase == Phase.RUNNING) return false
            current.current =
                before.withState(
                    effectivePhase,
                    message,
                    percent,
                    transactionId ?: before.transactionId,
                    backendTaskId ?: before.backendTaskId,
                )
            return true
        }

        /** 仅附加到精确 active；阶段变化和终态历史仍保留同一份结构化回执。 */
        @Synchronized
        fun recordResultDetail(
            operationId: String?,
            detail: PluginOperationResult.Detail?,
        ): Boolean {
            val current = active
            if (
                !isActive(operationId) ||
                    detail == null ||
                    current!!.operation().phase == Phase.UNCONFIRMED
            )
                return false
            current.current = current.operation().withOutcome(detail)
            return true
        }

        /** 清理未确认时保留执行槽；绝不 poll 后继或用迟到进度覆盖未知态。 */
        @Synchronized
        fun retainUnconfirmed(operationId: String?, message: String?): Boolean {
            val current = active
            if (!isActive(operationId)) return false
            val before = current!!.operation()
            current.current =
                before.withState(
                    Phase.UNCONFIRMED,
                    message,
                    before.percent,
                    before.transactionId,
                    before.backendTaskId,
                )
            return true
        }

        /** 只完成精确 active 身份，终态快照存入历史后释放唯一执行槽；不自动启动后继。 */
        @Synchronized
        fun complete(operationId: String?, phase: Phase?, message: String?): Boolean {
            val effectivePhase = phase ?: return false
            if (!isActive(operationId) || !terminal(effectivePhase)) return false
            finish(active!!, effectivePhase, message)
            active = null
            return true
        }

        /** 只取消尚未 poll 的请求；运行、提交与终态条目均不受影响。 */
        @Synchronized
        fun cancelQueued(operationId: String?): Boolean {
            val entries = waiting.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.operationId == operationId) {
                    entries.remove()
                    finish(entry, Phase.CANCELLED, "PLUGIN_OPERATION_CANCELLED")
                    return true
                }
            }
            return false
        }

        /** 快照顺序为 active、等待 FIFO、最近完成的历史优先；列表与元素均不可变。 */
        @Synchronized
        fun snapshot(): List<Operation> {
            val result = ArrayList<Operation>()
            active?.let { result.add(it.operation()) }
            for (entry in waiting) result.add(entry.operation())
            val completed = ArrayList(history.values)
            Collections.reverse(completed)
            result.addAll(completed)
            return Collections.unmodifiableList(result)
        }

        /** 未知或已淘汰的终态身份返回 null；读取不会延长历史保留期。 */
        @Synchronized
        fun get(operationId: String?): Operation? {
            if (operationId == null) return null
            if (isActive(operationId)) return active!!.operation()
            for (entry in waiting) if (entry.operationId == operationId) return entry.operation()
            return history[operationId]
        }

        @Synchronized fun hasPending(): Boolean = active != null || waiting.isNotEmpty()

        @Synchronized fun active(): Entry<W>? = active

        /** 返回 active，否则返回首个等待项；纯历史不作为当前操作。 */
        @Synchronized
        fun current(): Operation? {
            val entry = active ?: waiting.peekFirst()
            return entry?.operation()
        }

        private fun isActive(operationId: String?): Boolean =
            active != null && active!!.operationId == operationId

        private fun terminal(phase: Phase?): Boolean =
            phase != null &&
                phase != Phase.QUEUED &&
                phase != Phase.RUNNING &&
                phase != Phase.COMMITTING

        private fun finish(entry: Entry<W>, phase: Phase, message: String?) {
            val before = entry.operation()
            remember(
                entry,
                before.withState(
                    phase,
                    message,
                    if (phase == Phase.SUCCEEDED) 100 else before.percent,
                    before.transactionId,
                    before.backendTaskId,
                ),
            )
        }

        private fun remember(entry: Entry<W>, operation: Operation) {
            entry.current = operation
            history[operation.operationId] = operation
            while (history.size > historyCapacity) {
                val oldest = history.keys.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_WAITING_CAPACITY = 32
        const val DEFAULT_HISTORY_CAPACITY = 64
    }
}
