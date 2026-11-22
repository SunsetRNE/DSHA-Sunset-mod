package com.deepseekharness.app.saf

/** 跨应用文件访问的单一限额；本类不做 IO、不持有状态。 */
class SafCapabilityLimits private constructor() {
    companion object {
        const val MAX_TREES = 8
        const val MAX_ENTRIES = 500
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_WRITE_BYTES = 32L * 1024 * 1024
        const val MAX_TEXT_BYTES = 16 * 1024
        const val MAX_PATH_SEGMENTS = 32
        const val MAX_CONCURRENT_TRANSFERS = 2
        const val DEADLINE_MS = 15_000L
        const val HANDLE_TTL_MS = 60_000L
        const val MAX_OUTSTANDING_HANDLES = 64
        const val MAX_HANDLES_PER_TREE = 8
        const val MAX_URI_LENGTH = 4096

        const val OFFSET_INVALID = "OFFSET_INVALID"
        const val MAX_INVALID = "MAX_INVALID"
        const val MAX_TOO_LARGE = "MAX_TOO_LARGE"
        const val SIZE_INVALID = "SIZE_INVALID"
        const val TOO_LARGE = "TOO_LARGE"
        const val LIMIT_INVALID = "LIMIT_INVALID"
        const val LIMIT_TOO_LARGE = "LIMIT_TOO_LARGE"
        const val PATH_TOO_DEEP = "PATH_TOO_DEEP"
        const val URI_MISSING = "URI_MISSING"
        const val URI_TOO_LONG = "URI_TOO_LONG"

        @JvmStatic
        fun readProblem(offset: Long, max: Int): String? {
            if (offset < 0) return OFFSET_INVALID
            if (max <= 0) return MAX_INVALID
            if (max > MAX_READ_BYTES) return MAX_TOO_LARGE
            return null
        }

        @JvmStatic
        fun writeProblem(bytes: Long): String? {
            if (bytes < 0) return SIZE_INVALID
            if (bytes > MAX_WRITE_BYTES) return TOO_LARGE
            return null
        }

        @JvmStatic
        fun textProblem(bytes: Int): String? {
            if (bytes < 0) return SIZE_INVALID
            if (bytes > MAX_TEXT_BYTES) return TOO_LARGE
            return null
        }

        @JvmStatic
        fun listProblem(limit: Int): String? {
            if (limit <= 0) return LIMIT_INVALID
            if (limit > MAX_ENTRIES) return LIMIT_TOO_LARGE
            return null
        }

        @JvmStatic
        fun pathSegmentProblem(segments: Int): String? {
            if (segments < 0) return "PATH_INVALID"
            if (segments > MAX_PATH_SEGMENTS) return PATH_TOO_DEEP
            return null
        }

        @JvmStatic
        fun uriProblem(uri: String?): String? {
            if (uri == null || uri.codePoints().allMatch(Character::isWhitespace))
                return URI_MISSING
            if (uri.length > MAX_URI_LENGTH) return URI_TOO_LONG
            return null
        }
    }
}
