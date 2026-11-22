package com.deepseekharness.app.saf

import java.nio.charset.StandardCharsets
import java.util.Locale

/** 跨应用文件访问的路径、MIME 与文本判定；不依赖 Android API。 */
class SafPaths private constructor() {
    companion object {
        const val MAX_SEGMENTS = SafCapabilityLimits.MAX_PATH_SEGMENTS

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun normalize(path: String?): String {
            var value = path?.trim { it <= ' ' } ?: ""
            while (value.startsWith("/")) value = value.substring(1)
            while (value.endsWith("/")) value = value.substring(0, value.length - 1)
            if (value.isEmpty()) return ""
            for (segment in value.split('/')) {
                if (segment.isEmpty() || segment == "." || segment == "..")
                    throw IllegalArgumentException("BAD_PATH: $path")
                if (segment.indexOf('\\') >= 0) throw IllegalArgumentException("BAD_PATH: $path")
                for (character in segment) if (character < ' ')
                    throw IllegalArgumentException("BAD_PATH: $path")
            }
            if (value.split('/').size > MAX_SEGMENTS)
                throw IllegalArgumentException("PATH_TOO_DEEP: $path")
            return value
        }

        @JvmStatic
        fun mimeOf(name: String?): String {
            val dot = name!!.lastIndexOf('.')
            val extension = if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
            return when (extension) {
                "txt",
                "log",
                "md" -> "text/plain"
                "json" -> "application/json"
                "xml" -> "text/xml"
                "html" -> "text/html"
                "js" -> "application/javascript"
                "csv" -> "text/csv"
                "png" -> "image/png"
                "jpg",
                "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "pdf" -> "application/pdf"
                "db",
                "sqlite",
                "sqlite3" -> "application/x-sqlite3"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
        }

        @JvmStatic
        fun looksText(data: ByteArray?): Boolean {
            if (data == null) return false
            if (data.isEmpty()) return true
            for (byte in data) if (byte == 0.toByte()) return false
            return try {
                String(data, StandardCharsets.UTF_8).indexOf('\uFFFD') < 0
            } catch (ignored: Throwable) {
                false
            }
        }
    }
}
