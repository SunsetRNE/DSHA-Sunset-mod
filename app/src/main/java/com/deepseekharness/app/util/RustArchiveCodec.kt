@file:Suppress("NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Rust gzip 旁路的常量、冻结错误码和宿主流工厂；不改变 Rust ABI 或压缩算法。
 *
 * 首次写入前选择一次后端。pack 的 OUTPUT_BOUND 与 unpack 的累计上限语义分开， 流式异常直接传播，不在已消费输入或已写入目标上换后端重放。
 */
class RustArchiveCodec private constructor() {
    companion object {
        const val OK = 0
        const val INVALID_ARGUMENT = -1
        const val INPUT_BOUND = -2
        const val OUTPUT_BOUND = -3
        const val STALE_HANDLE = -4
        const val SESSION_LIMIT = -5
        const val INTERNAL = -6
        const val PANIC = -7
        const val JNI_PENDING = -8
        const val SHORT_READ = -9
        const val UNSUPPORTED_ALGORITHM = -10
        const val GZIP_HEADER = -11
        const val GZIP_HEADER_CRC = -12
        const val GZIP_METADATA_LIMIT = -13
        const val GZIP_TRUNCATED = -14
        const val GZIP_DEFLATE = -15
        const val GZIP_DICTIONARY = -16
        const val GZIP_STALLED = -17
        const val GZIP_TRAILER = -18
        const val GZIP_TRAILING_DATA = -19
        const val NOT_IMPLEMENTED = -26

        /** 单次输入与解压输出上限均为 256 KiB。 */
        const val CHUNK = 262_144
        const val MAX_OUTPUT_CHUNK = 262_144
        const val ARCHIVE_LIMIT = "ARCHIVE_LIMIT"
        const val MAX_METADATA = 8_192
        const val MIN_LEVEL = 0
        const val MAX_LEVEL = 9
        const val DEFAULT_LEVEL = 6
        const val HEADER_HEX = "1f8b0800000000000000"
        const val TRAILER_BYTES = 8
        const val FINISH_BOUND = 128 * 1024
        @JvmField val MAX_PACK_OUTPUT = packBound(CHUNK)
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024 + 256L * 1024 * 1024
        const val FLAG_EOF = 0x1
        const val FLAG_NEED_INPUT = 0x2
        const val FLAG_NEED_OUTPUT = 0x4

        // 原包可见常量；Kotlin internal 在 JVM 上公开，名字和值保持不变。
        internal const val RUST_ERROR_PREFIX = "RUST_ERROR_"

        @JvmStatic
        open fun supportsStreamSize(bytes: Long): Boolean = bytes >= 0 && bytes <= MAX_TOTAL_BYTES

        /** 只借用 target；任何构造/编码异常均由调用方处理，不关闭或重放原目标。 */
        @JvmStatic
        @Throws(IOException::class)
        open fun packStream(target: OutputStream?, plaintextBytes: Long): OutputStream {
            if (target == null) throw NullPointerException("target")
            val rust =
                supportsStreamSize(plaintextBytes) && RustBackends.archiveSelection().useRust()
            val borrowed =
                object : FilterOutputStream(target) {
                    @Throws(IOException::class)
                    override fun write(bytes: ByteArray?, offset: Int, length: Int) {
                        out.write(bytes, offset, length)
                    }

                    override fun close() {}
                }
            return if (rust) RustGzipOutputStream(borrowed) else GZIPOutputStream(borrowed, 65536)
        }

        /** n + n/8 + 128；在 long 上计算后检查 int 上界，避免回绕。 */
        @JvmStatic
        open fun packBound(inputLength: Int): Int {
            if (inputLength < 0) throw IllegalArgumentException("inputLength < 0")
            val bound = inputLength.toLong() + inputLength.toLong() / 8 + 128L
            if (bound > Int.MAX_VALUE) {
                throw IllegalArgumentException("inputLength too large for a packed bound")
            }
            return bound.toInt()
        }

        @JvmStatic
        open fun levelValid(level: Int): Boolean = level >= MIN_LEVEL && level <= MAX_LEVEL

        @JvmStatic open fun consumed(value: Long): Int = (value ushr 32).toInt()

        @JvmStatic open fun produced(value: Long): Int = (value and 0xffff_ffffL).toInt()

        @JvmStatic open fun unpackProduced(value: Long): Int = (value ushr 32).toInt()

        @JvmStatic open fun unpackFlags(value: Long): Int = (value and 0xffff_ffffL).toInt()

        @JvmStatic
        open fun message(code: Int): String =
            when (code) {
                OK -> "OK"
                INVALID_ARGUMENT -> "INVALID_ARGUMENT"
                INPUT_BOUND -> "INPUT_BOUND"
                OUTPUT_BOUND -> "OUTPUT_BOUND"
                STALE_HANDLE -> "STALE_HANDLE"
                SESSION_LIMIT -> "SESSION_LIMIT"
                INTERNAL -> "INTERNAL"
                PANIC -> "PANIC"
                JNI_PENDING -> "JNI_PENDING"
                GZIP_HEADER -> "GZIP_HEADER"
                GZIP_HEADER_CRC -> "GZIP_HEADER_CRC"
                GZIP_METADATA_LIMIT -> "GZIP_METADATA_LIMIT"
                GZIP_TRUNCATED -> "GZIP_TRUNCATED"
                GZIP_DEFLATE -> "GZIP_DEFLATE"
                GZIP_DICTIONARY -> "GZIP_DICTIONARY"
                GZIP_STALLED -> "GZIP_STALLED"
                GZIP_TRAILER -> "GZIP_TRAILER"
                GZIP_TRAILING_DATA -> "GZIP_TRAILING_DATA"
                NOT_IMPLEMENTED -> "NOT_IMPLEMENTED"
                else -> RUST_ERROR_PREFIX + code
            }

        /** unpack 的 -3 表示累计明文超过硬上限，不能作为可重试缓冲不足。 */
        @JvmStatic
        open fun unpackMessage(code: Int): String =
            if (code == OUTPUT_BOUND) ARCHIVE_LIMIT else message(code)

        @JvmStatic
        open fun errorCodeForJvmMessage(jvmMessage: String?): Int =
            when (jvmMessage) {
                "GZIP_HEADER",
                "GZIP_FLAGS" -> GZIP_HEADER
                "GZIP_HEADER_CRC" -> GZIP_HEADER_CRC
                "GZIP_METADATA_LIMIT" -> GZIP_METADATA_LIMIT
                "GZIP_TRUNCATED" -> GZIP_TRUNCATED
                "GZIP_DEFLATE" -> GZIP_DEFLATE
                "GZIP_DICTIONARY" -> GZIP_DICTIONARY
                "GZIP_STALLED" -> GZIP_STALLED
                "GZIP_TRAILER" -> GZIP_TRAILER
                "GZIP_TRAILING_DATA" -> GZIP_TRAILING_DATA
                ARCHIVE_LIMIT -> OUTPUT_BOUND
                "STREAM_CLOSED" -> INVALID_ARGUMENT
                else -> Int.MIN_VALUE
            }

        @JvmStatic
        open fun canonicalJvmMessage(jvmMessage: String?): String? =
            if (jvmMessage == "GZIP_FLAGS") "GZIP_HEADER" else jvmMessage

        @JvmStatic open fun unavailableMessage(): String = "RUST_" + RustNative.statusCode()
    }
}
