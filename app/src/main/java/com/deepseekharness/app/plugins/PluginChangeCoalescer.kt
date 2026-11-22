package com.deepseekharness.app.plugins

/**
 * 文件失效信号的纯状态合并器；时间由调用者提供（单调毫秒），调度和扫描均由 Engine 负责。
 *
 * 首事件固定本批的最迟发起边界，事件风暴不会不断延后。完成时间还包含 Engine 的有界 扫描耗时；若上一轮扫描已超时，此类不会冒充扫描完成，也不会并发启动第二轮。
 */
class PluginChangeCoalescer
@JvmOverloads
constructor(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val minScanIntervalMs: Long = DEFAULT_MIN_SCAN_INTERVAL_MS,
) {
    private var firstDirtyAtMillis: Long = -1
    private var lastDirtyAtMillis: Long = -1
    private var lastScanStartedAtMillis: Long = -1
    private var lastNowMillis: Long = -1
    private var immediate: Boolean = false
    private var scanning: Boolean = false

    init {
        if (
            debounceMs < 0 ||
                minScanIntervalMs < 0 ||
                maxWaitMs < debounceMs ||
                maxWaitMs < minScanIntervalMs
        ) {
            throw IllegalArgumentException("合并截止不得小于防抖或最小扫描间隔")
        }
    }

    @Synchronized
    fun markDirty(now: Long) {
        val clock = time(now)
        if (firstDirtyAtMillis < 0) firstDirtyAtMillis = clock
        lastDirtyAtMillis = clock
    }

    /** 前台恢复、来源换代及操作结束的补偿请求仍遵守最小扫描间隔。 */
    @Synchronized
    fun requestNow(now: Long) {
        markDirty(now)
        immediate = true
    }

    /** -1 表示无待办或已有扫描；0 表示现在可开始。不可将 -1 当作零延迟重新轮询。 */
    @Synchronized
    fun nextDelayMillis(now: Long): Long {
        val clock = time(now)
        if (scanning || firstDirtyAtMillis < 0) return -1
        var due =
            if (immediate) firstDirtyAtMillis
            else minOf(add(lastDirtyAtMillis, debounceMs), add(firstDirtyAtMillis, maxWaitMs))
        if (lastScanStartedAtMillis >= 0)
            due = maxOf(due, add(lastScanStartedAtMillis, minScanIntervalMs))
        return if (due <= clock) 0 else due - clock
    }

    /** 仅消耗本轮开始前的信号；开始后的 markDirty 自动属于下一轮。 */
    @Synchronized
    fun beginScan(now: Long): Boolean {
        val clock = time(now)
        if (nextDelayMillis(clock) != 0L) return false
        scanning = true
        lastScanStartedAtMillis = clock
        firstDirtyAtMillis = -1
        lastDirtyAtMillis = -1
        immediate = false
        return true
    }

    /** 成功、失败、取消都须结束占用；不清除扫描期间到达的下一批信号。 */
    @Synchronized
    fun scanFinished(now: Long) {
        time(now)
        scanning = false
    }

    @Synchronized fun hasPending(): Boolean = firstDirtyAtMillis >= 0

    @Synchronized fun isScanning(): Boolean = scanning

    @Synchronized fun firstDirtyAt(): Long = firstDirtyAtMillis

    /** 下一批首事件的固定截止；没有下一批时返回 -1。 */
    @Synchronized
    fun deadlineAt(): Long = if (firstDirtyAtMillis < 0) -1 else add(firstDirtyAtMillis, maxWaitMs)

    private fun time(now: Long): Long {
        if (now < 0) throw IllegalArgumentException("需要非负单调毫秒时间")
        lastNowMillis = maxOf(lastNowMillis, now)
        return lastNowMillis
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
        const val DEFAULT_MAX_WAIT_MS = 2000L
        const val DEFAULT_MIN_SCAN_INTERVAL_MS = 750L

        private fun add(value: Long, delta: Long): Long =
            if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}
