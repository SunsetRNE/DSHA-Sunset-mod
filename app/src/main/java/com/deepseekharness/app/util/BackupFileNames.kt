package com.deepseekharness.app.util

import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/** 备份名描述应用归档；导入仍核验实际容器格式。 */
class BackupFileNames private constructor() {
    companion object {
        const val EXTENSION: String = ".tar.gz"
        const val MIME_TYPE: String = "application/gzip"

        @JvmStatic fun portableName(): String = "DSHA-data-v5-" + UUID.randomUUID() + EXTENSION

        @JvmStatic
        fun exportMimeType(name: String?): String {
            val lower = name?.lowercase(Locale.ROOT) ?: ""
            if (lower.endsWith(EXTENSION)) return MIME_TYPE
            if (lower.endsWith(".txt")) return "text/plain"
            if (lower.endsWith(".json")) return "application/json"
            if (lower.endsWith(".png")) return "image/png"
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg"
            if (lower.endsWith(".gif")) return "image/gif"
            if (lower.endsWith(".webp")) return "image/webp"
            if (lower.endsWith(".pdf")) return "application/pdf"
            if (lower.endsWith(".zip")) return "application/zip"
            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz"))
                return "application/gzip"
            if (lower.endsWith(".tar")) return "application/x-tar"
            return "application/octet-stream"
        }

        /** 只自动发现全量旧包；部分范围始终要求显式选择。 */
        @JvmStatic
        fun discoverable(name: String?): Boolean {
            if (name == null) return false
            val normalized =
                Pattern.compile("\\s*\\(\\d+\\)")
                    .matcher(name.lowercase(Locale.ROOT))
                    .replaceAll("")
            if (Pattern.matches("dsha-data-v5-[a-f0-9-]{36}\\.(?:tar\\.gz|dshbak)", normalized))
                return true
            if (!normalized.startsWith("dsha-backup-") && !normalized.startsWith("dsha-migration-"))
                return false
            return normalized.endsWith(".dshbak") ||
                normalized.endsWith(".tar.gz") ||
                normalized.endsWith(".tgz") ||
                normalized.endsWith(".tar")
        }
    }
}
