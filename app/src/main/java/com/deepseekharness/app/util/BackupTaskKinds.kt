// 保留原 Java 方法 flags；类本身仍为 final，不开放继承。
@file:Suppress("NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

/** 固定持久化操作标识；历史中英文标签继续可读。 */
class BackupTaskKinds private constructor() {
    companion object {
        const val BACKUP: String = "DSHA_BACKUP_V1"
        const val RESTORE: String = "DSHA_RESTORE_V1"
        const val REBUILD: String = "DSHA_REBUILD_V1"
        const val UPDATE: String = "DSHA_UPDATE_RUNTIME_V1"
        const val RECOVER: String = "DSHA_RECOVER_MAINTENANCE_V1"
        const val FACTORY_RESET: String = "DSHA_FACTORY_RESET_V1"
        const val ROLLBACK: String = "DSHA_ROLLBACK_RUNTIME_V1"
        const val SELECT_HOME: String = "DSHA_SELECT_HOME_V1"
        const val RESET: String = "DSHA_RESET_CONFIG_V1"
        const val REPAIR: String = "DSHA_REPAIR_STARTUP_V1"
        const val REMOVE_PLUGIN: String = "DSHA_REMOVE_FAULTY_PLUGIN_V1"
        const val ISOLATE_PLUGIN_JOURNALS: String = "DSHA_ISOLATE_PLUGIN_JOURNALS_V1"
        const val RECORD: String = "DSHA_TASK_RECORD_V1"
        private val ROWS =
            arrayOf(
                arrayOf(BACKUP, "创建备份", "Create backup"),
                arrayOf(RESTORE, "恢复备份", "Restore backup"),
                arrayOf(REBUILD, "重建环境", "Rebuild environment"),
                arrayOf(UPDATE, "更新运行环境", "Update runtime environment"),
                arrayOf(RECOVER, "恢复中断维护", "Resume interrupted maintenance"),
                arrayOf(FACTORY_RESET, "格式化 DSHA", "Format DSHA"),
                arrayOf(ROLLBACK, "回退兼容运行时", "Roll back compatible runtime"),
                arrayOf(SELECT_HOME, "选择数据目录", "Select data directory"),
                arrayOf(RESET, "重置配置", "Reset settings"),
                arrayOf(REPAIR, "修复启动配置", "Repair startup configuration"),
                arrayOf(REMOVE_PLUGIN, "卸载故障插件", "Remove faulty plugin"),
                arrayOf(
                    ISOLATE_PLUGIN_JOURNALS,
                    "隔离无法读取的插件日志",
                    "Isolate unreadable plugin journals",
                ),
                arrayOf(RECORD, "任务记录", "Task record"),
            )

        @JvmStatic
        open fun normalize(value: String?): String {
            if (value == null) return ""
            for (row in ROWS) for (known in row) if (known == value) return row[0]
            return value
        }

        @JvmStatic
        open fun display(value: String?, english: Boolean): String? {
            val id = normalize(value)
            for (row in ROWS) if (row[0] == id) return row[if (english) 2 else 1]
            return null
        }

        @JvmStatic
        open fun `is`(expected: String?, value: String?): Boolean {
            // 与 expected.equals(normalize(value)) 一致：先归一化，再因 null 接收者失败。
            val normalized = normalize(value)
            return expected!!.equals(normalized)
        }
    }
}
