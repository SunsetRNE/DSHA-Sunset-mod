package com.deepseekharness.app.transfer

/** 一次显式前台目标操作；启动系统界面前先消费，旧请求不会自动重放。 */
class PendingFileAction(id: String?, action: String?) {
    private var id: String = if (id != null && id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) id else ""
    private val action: String = if (action == "share" || action == "saveas") action else ""

    fun id(): String = id

    fun action(): String = action

    fun retire() {
        id = ""
    }

    fun consume(
        taskId: String?,
        destination: String?,
        status: DownloadTask.Status?,
        resumed: Boolean,
    ): String {
        if (!resumed || id.isEmpty() || action.isEmpty() || id != taskId || action != destination)
            return ""
        if (
            status == DownloadTask.Status.PAUSED ||
                status == DownloadTask.Status.FAILED ||
                status == DownloadTask.Status.CANCELLED
        ) {
            id = "" // 手动重试失败传输不能重放之前的分享请求。
            return ""
        }
        if (
            action == "share" && status == DownloadTask.Status.COMPLETE ||
                action == "saveas" && status == DownloadTask.Status.WAITING_DESTINATION
        ) {
            id = ""
            return action
        }
        return ""
    }
}
