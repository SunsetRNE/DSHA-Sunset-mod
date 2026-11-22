package com.deepseekharness.app.transfer

import java.io.IOException
import java.util.regex.Pattern

/** 持久下载器与恶意响应夹具共用的纯校验。 */
class TransferProtocol private constructor() {
    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 15_000
        const val SOURCE_VERIFY_READ_TIMEOUT_MS: Int = 15 * 60 * 1000
        const val CONTENT_READ_TIMEOUT_MS: Int = 30_000
        const val RELEASE_TIMEOUT_MS: Int = 5_000
        private val RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")

        /** 返回真实偏移；服务器忽略 Range 时必须覆盖整个分段，不能追加。 */
        @JvmStatic
        @Throws(IOException::class)
        fun responseOffset(
            code: Int,
            requested: Long,
            size: Long,
            range: String?,
            length: String?,
            etag: String?,
            sha256: String?,
        ): Long {
            if (requested < 0 || requested > size || size < 0 || size > TransferArtifact.MAX_BYTES)
                throw IOException("FILE_RANGE_INVALID")
            if (code != 200 && code != 206) throw IOException("FILE_HTTP_$code")
            if ("\"$sha256\"" != etag) throw IOException("FILE_SOURCE_CHANGED")
            val offset: Long
            if (code == 206) {
                val match = RANGE.matcher(range ?: "")
                try {
                    if (
                        !match.matches() ||
                            java.lang.Long.parseLong(match.group(1)) != requested ||
                            java.lang.Long.parseLong(match.group(2)) != size - 1 ||
                            java.lang.Long.parseLong(match.group(3)) != size
                    )
                        throw IOException("FILE_RANGE_INVALID")
                } catch (overflow: NumberFormatException) {
                    throw IOException("FILE_RANGE_INVALID")
                }
                offset = requested
            } else if (code == 200) {
                offset = 0
            } else throw IOException("FILE_HTTP_$code")
            try {
                if (length == null || java.lang.Long.parseLong(length) != size - offset)
                    throw IOException("FILE_LENGTH_INVALID")
            } catch (invalid: NumberFormatException) {
                throw IOException("FILE_LENGTH_INVALID")
            }
            return offset
        }

        @JvmStatic
        @Throws(IOException::class)
        fun requiredSpace(size: Long, partial: Long, includesFinalCopy: Boolean): Long {
            if (size < 0 || size > TransferArtifact.MAX_BYTES || partial < 0 || partial > size)
                throw IOException("FILE_SIZE_INVALID")
            return size - partial + (if (includesFinalCopy) size else 0) + 16L * 1024 * 1024
        }
    }
}
