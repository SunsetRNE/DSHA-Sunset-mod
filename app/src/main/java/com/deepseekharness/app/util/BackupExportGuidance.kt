// 保留原 Java 方法 flags；类本身仍为 final，不开放继承。
@file:Suppress("NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

/**
 * 导出被立即拒绝时的原因与下一步文案。
 *
 * 仅接受固定原因名；未知值按通用失败处理。救援导出只读，不要求维护锁。
 */
class BackupExportGuidance private constructor() {
    enum class Kind {
        NONE,
        BUSY,
        WORKERS_CLOSED,
        FACTORY_RESET_PENDING,
        MAINTENANCE_PENDING,
        INVALID_SELECTION,
        PASSWORD_INVALID,
        RETAINED_KEY,
        SOURCE_COPY,
        PRIVATE_STORAGE,
        WORKER_UNAVAILABLE,
        UNKNOWN,
    }

    companion object {
        @JvmStatic
        open fun classify(reason: String?): Kind {
            if (reason == null) return Kind.UNKNOWN
            return try {
                Kind.valueOf(reason)
            } catch (unavailable: IllegalArgumentException) {
                Kind.UNKNOWN
            }
        }

        /** 可行动说明不包含路径、凭据或异常原文，按当前语言即时呈现。 */
        @JvmStatic
        open fun message(reason: String?, rescue: Boolean): String =
            when (classify(reason)) {
                Kind.NONE -> UiText.text("导出已开始。")
                Kind.BUSY ->
                    UiText.choose(
                        "已有数据任务正在运行，请等待当前任务结束后重试。",
                        "Another data task is running. Wait for it to finish and retry.",
                    )
                Kind.WORKERS_CLOSED ->
                    UiText.choose(
                        "数据作业已关闭，请重启应用后再导出。",
                        "Data jobs are closed. Restart the app and export again.",
                    )
                Kind.FACTORY_RESET_PENDING ->
                    UiText.choose(
                        "正在等待格式化数据，暂时不能导出。请先完成或取消格式化。",
                        "Data reset is pending, so export is unavailable. Finish or cancel the reset first.",
                    )
                Kind.MAINTENANCE_PENDING ->
                    if (rescue)
                        UiText.choose(
                            "维护记录尚待恢复；救援导出仍可继续。",
                            "Maintenance records still need recovery; rescue export can continue.",
                        )
                    else
                        UiText.choose(
                            "上次环境维护未完成：请先到安装与修复页恢复，再导出备份。",
                            "The last environment maintenance did not finish. Recover it in Installation and repair before exporting.",
                        )
                Kind.INVALID_SELECTION ->
                    UiText.choose(
                        "导出范围或项目参数无效，请重新选择后再试。",
                        "The export scope or project selection is invalid. Select again and retry.",
                    )
                Kind.PASSWORD_INVALID ->
                    UiText.choose(
                        "内部自动副本的密码长度不符合要求；用户导出不需要密码，请直接重试。",
                        "The internal automatic-copy password length is invalid. User exports need no password; retry directly.",
                    )
                Kind.RETAINED_KEY ->
                    UiText.choose(
                        "该私有副本只用于内部核对，不能直接导出。请从副本列表选择「重新导出」。",
                        "This private copy is for internal verification only. Choose \"Export again\" from the copy list.",
                    )
                Kind.SOURCE_COPY ->
                    UiText.choose(
                        "所选项目目录的访问凭据不可用，请重新选择目录。",
                        "The selected project folder's permission is unavailable. Select the folder again.",
                    )
                Kind.PRIVATE_STORAGE ->
                    UiText.choose(
                        "无法在私有目录创建导出作业：请检查可用空间、前台服务权限后重试。",
                        "The private export job could not be created. Check free space and foreground-service permission, then retry.",
                    )
                Kind.WORKER_UNAVAILABLE ->
                    UiText.choose(
                        "无法启动数据作业线程，请稍后重试或重启应用。",
                        "The data job thread could not start. Retry shortly or restart the app.",
                    )
                Kind.UNKNOWN ->
                    UiText.choose(
                        "导出未开始。请查看状态后重试。",
                        "Export did not start. Review the state and retry.",
                    )
            }
    }
}
