// 保留原 Java 方法 flags；类本身仍为 final，不开放继承。
@file:Suppress("NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

import java.util.regex.Pattern

/**
 * 备份范围的唯一定义：归档路径、恢复子树与文件名前缀由同一范围派生。
 *
 * 部分备份不能使用 DSHA-backup-，否则老版本会将它当成全量恢复并替换全部数据。 本类只处理字符串和数组，不依赖 Android API。
 */
class BackupScope private constructor() {
    companion object {
        const val FULL: Int = 0
        const val SESSIONS: Int = 1
        const val PLUGINS: Int = 2
        const val SETTINGS: Int = 3

        /** 保留 UI 顺序，以及原 public final 数组的共享对象与元素可变性。 */
        @JvmField val ALL: IntArray = intArrayOf(FULL, SESSIONS, SETTINGS, PLUGINS)

        /** 包内承载公开数据快照的目录名，相对 /root。 */
        const val PUB_SNAPSHOT_DIR: String = ".dsha-pub"

        /** 原生授权只属于本机，不随用户归档导出或恢复。 */
        @JvmStatic
        open fun excludesNativeGrantState(path: String?): Boolean {
            if (path == null) return false
            val normalized = path.replace('\\', '/')
            return normalized == "dsha_device_grants" ||
                normalized.endsWith("/dsha_device_grants") ||
                normalized == "clipboard_read_mode_v1" ||
                normalized.endsWith("/clipboard_read_mode_v1")
        }

        /** 清单标识保持字面量；未知整数沿用原 full 投影。 */
        @JvmStatic
        open fun id(scope: Int): String =
            when (scope) {
                SESSIONS -> "sessions"
                PLUGINS -> "plugins"
                SETTINGS -> "settings"
                else -> "full"
            }

        /** 未知清单范围必须拒绝；只按 Java trim 的 <= U+0020 判据去边缘字符。 */
        @JvmStatic
        open fun fromId(id: String?): Int {
            if (id == null) throw IllegalArgumentException("UNKNOWN_BACKUP_SCOPE")
            val s = id.trim { it <= ' ' }
            if (s == "sessions") return SESSIONS
            if (s == "plugins") return PLUGINS
            if (s == "settings") return SETTINGS
            if (s == "full") return FULL
            throw IllegalArgumentException("UNKNOWN_BACKUP_SCOPE")
        }

        @JvmStatic
        open fun fileNamePrefix(scope: Int): String =
            when (scope) {
                SESSIONS -> "DSHA-sessions-"
                PLUGINS -> "DSHA-plugins-"
                SETTINGS -> "DSHA-settings-"
                else -> "DSHA-backup-"
            }

        @JvmStatic
        open fun archiveName(scope: Int, suffix: String?): String {
            if (suffix == null || !Pattern.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}", suffix)) {
                throw IllegalArgumentException(UiText.text("备份文件标识无效"))
            }
            return fileNamePrefix(scope) + suffix + BackupFileNames.EXTENSION
        }

        /** 文件名仅作手选包的范围提示，清单中的范围优先。 */
        @JvmStatic
        open fun fromFileName(name: String?): Int {
            if (name == null) return FULL
            var n = name.trim { it <= ' ' }
            val slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'))
            if (slash >= 0 && slash + 1 < n.length) n = n.substring(slash + 1)
            if (n.startsWith("DSHA-sessions-")) return SESSIONS
            if (n.startsWith("DSHA-plugins-")) return PLUGINS
            if (n.startsWith("DSHA-settings-")) return SETTINGS
            return FULL
        }

        @JvmStatic
        open fun visibleToLegacyScan(scope: Int): Boolean = fileNamePrefix(scope) == "DSHA-backup-"

        @JvmStatic
        open fun label(scope: Int): String =
            when (scope) {
                SESSIONS -> UiText.text("仅备份对话记录")
                PLUGINS -> UiText.text("仅备份插件")
                SETTINGS -> UiText.text("仅备份设置")
                else -> UiText.text("备份全部数据")
            }

        @JvmStatic
        open fun describe(scope: Int): String =
            when (scope) {
                SESSIONS -> UiText.text("只打包会话、索引、附件与工作区结构，恢复时不动设置与插件")
                PLUGINS -> UiText.text("只打包插件清单、配置和已安装插件数据")
                SETTINGS -> UiText.text("打包设置文件、0.1.7 profile 配置和运行参数；原生 API Key 遵循备份密钥开关")
                else -> UiText.text("配置、对话、附件、插件与工作区 .env/日志，换机或重装用这个")
            }

        @JvmStatic
        open fun restoreImpact(scope: Int): String =
            when (scope) {
                SESSIONS -> UiText.text("覆盖聊天记录、会话索引与附件，不改设置和插件")
                SETTINGS -> UiText.text("覆盖设置文件与 profile 配置，不改聊天记录和插件")
                PLUGINS -> UiText.text("覆盖插件 profile 和插件源码，不改聊天记录和设置")
                else -> UiText.text("覆盖配置、聊天记录、附件、插件与工作区 .env/日志")
            }

        /** 每次分配独立可变数组；空数组表示整个 .dsh。 */
        @JvmStatic
        open fun dshPaths(scope: Int): Array<String> =
            when (scope) {
                // 会话与注册表、附件必须同行，避免文件已恢复但 Web UI 无索引。
                SESSIONS -> arrayOf(".dsh/sessions", ".dsh/storages", ".dsh/attachments")
                PLUGINS ->
                    arrayOf(
                        ".dsh/profiles",
                        ".dsh/plugin-src",
                        ".dsh/plugin-sources.json",
                        ".dsh/plugin-history",
                        ".dsh/plugin-safe-mode.json",
                    )
                SETTINGS -> arrayOf(".dsh/settings.yaml")
                else -> emptyArray()
            }

        /** 与 dshPaths 的路径一一对应，保留每次调用的新数组身份。 */
        @JvmStatic
        open fun mergeSubdirs(scope: Int): Array<String> =
            when (scope) {
                SESSIONS -> arrayOf("sessions", "storages", "attachments")
                PLUGINS ->
                    arrayOf(
                        "profiles",
                        "plugin-src",
                        "plugin-sources.json",
                        "plugin-history",
                        "plugin-safe-mode.json",
                    )
                SETTINGS -> arrayOf("settings.yaml")
                else -> emptyArray()
            }

        @JvmStatic open fun includesWorkdirFiles(scope: Int): Boolean = scope == FULL

        @JvmStatic
        open fun includesPluginSrc(scope: Int): Boolean = scope == FULL || scope == PLUGINS

        @JvmStatic
        open fun needsPublicDataSnapshot(scope: Int): Boolean =
            scope == FULL || scope == SESSIONS || scope == SETTINGS
    }
}
