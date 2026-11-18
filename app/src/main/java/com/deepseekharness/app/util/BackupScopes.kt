package com.deepseekharness.app.util

import java.util.Collections
import java.util.LinkedHashSet

/** 宿主 v5 的范围名；旧整数 full/sessions 编码仍由 BackupScope 定义。 */
class BackupScopes private constructor() {
    companion object {
        const val APPLICATION: String = "application"
        const val SESSIONS: String = "sessions"
        const val SETTINGS: String = "settings"
        const val PLUGINS: String = "plugins"
        const val PROJECTS: String = "projects"

        // 保留 List.of 对 null 查询的拒绝及原 JDK 不可变集合行为。
        @JvmField
        val ORDERED: List<String> =
            java.util.List.of(APPLICATION, SESSIONS, SETTINGS, PLUGINS, PROJECTS)
        @JvmField val ALL: Set<String> = Collections.unmodifiableSet(LinkedHashSet(ORDERED))
    }
}
