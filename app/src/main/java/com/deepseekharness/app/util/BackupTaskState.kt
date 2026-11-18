// 保留原 Java 方法 flags；类本身仍为 final，不开放继承。
@file:Suppress("NON_FINAL_MEMBER_IN_FINAL_CLASS")

package com.deepseekharness.app.util

/** 页面不拥有任务；等待确认仍占槽位，旧页面不能确认下一次任务。 */
class BackupTaskState {
    enum class Status {
        IDLE,
        RUNNING,
        PREVIEW,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        INTERRUPTED,
    }

    private var id: Long = 0
    private var kind: String? = ""
    private var detail: String? = ""
    private var lastStage: String? = ""
    private var status: Status? = Status.IDLE

    @Synchronized
    open fun start(kind: String?): Long {
        if (busy()) return -1
        id = Math.max(System.currentTimeMillis(), id + 1)
        this.kind = kind
        status = Status.RUNNING
        detail = UiText.text("正在准备…")
        lastStage = detail
        return id
    }

    @Synchronized open fun busy(): Boolean = status == Status.RUNNING || status == Status.PREVIEW

    @Synchronized
    open fun update(expected: Long, next: Status?, message: String?) {
        if (expected != id || !busy()) return
        status = next
        detail = SensitiveData.redact(message ?: "")
        if (next == Status.RUNNING) lastStage = detail
    }

    @Synchronized
    open fun confirm(expected: Long): Boolean {
        if (id != expected || status != Status.PREVIEW) return false
        status = Status.RUNNING
        detail = UiText.text("正在恢复…")
        return true
    }

    @Synchronized open fun snapshot(): Snapshot = Snapshot(id, kind, status, detail, lastStage)

    @Synchronized
    open fun restoreStage(stage: String?) {
        lastStage = SensitiveData.redact(stage ?: "")
    }

    @Synchronized
    open fun restore(id: Long, kind: String?, status: Status?, detail: String?) {
        this.id = id
        this.kind = kind
        this.status = status
        this.detail = SensitiveData.redact(detail)
        if (busy()) {
            this.status = Status.INTERRUPTED
            this.detail = UiText.format("上次任务因进程退出而中断，未自动重试。请先处理未完成的维护或重新选择备份。\n%s", this.detail)
        }
    }

    /** 完成的应用内格式化交给欢迎页后，移除旧结果。 */
    @Synchronized
    open fun reset() {
        id = 0
        kind = ""
        detail = ""
        lastStage = ""
        status = Status.IDLE
    }

    // Java 原包级构造器对应 internal，JVM public 的单项可见性差异已单独登记。
    class Snapshot
    internal constructor(
        @JvmField val id: Long,
        @JvmField val kind: String?,
        @JvmField val status: Status?,
        @JvmField val detail: String?,
        @JvmField val lastStage: String?,
    ) {
        open fun busy(): Boolean = status == Status.RUNNING || status == Status.PREVIEW
    }
}
