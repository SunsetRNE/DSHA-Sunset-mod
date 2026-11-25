@file:Suppress("NON_FINAL_MEMBER_IN_FINAL_CLASS", "NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * 已核验 fd/range 的摘要桥；Rust 核心只读，JVM 绝对读不改变 channel position。
 *
 * 只有 Rust INTERNAL/PANIC 允许用同一只读来源重算一次并明确标记回退；确定性错误、 JNI pending、未知算法等不重试。abort/OOM/SIGSEGV
 * 不属于可恢复的返回码。
 */
class RustDigest private constructor() {
    enum class Backend {
        JVM,
        RUST,
    }

    enum class Failure {
        NONE,
        INVALID_ARGUMENT,
        OUTPUT_BOUND,
        INTERNAL,
        PANIC,
        JNI_PENDING,
        SHORT_READ,
        UNSUPPORTED_ALGORITHM,
        LIBRARY_MISSING,
        LIBRARY_LOAD_FAILED,
        ABI_MISMATCH,
        NOT_LOADED,
        UNKNOWN,
    }

    /** 只读结果保留 Object 身份相等与摘要副本，不使用 data class。 */
    class Result
    private constructor(
        private val backend: Backend,
        private val digest: ByteArray?,
        private val bytesRead: Long,
        private val errorCode: Int,
        private val failure: Failure?,
        private val fellBack: Boolean,
        private val fallbackReason: String,
    ) {
        open fun backend(): Backend = backend

        open fun ok(): Boolean = failure == Failure.NONE && digest != null

        open fun digest(): ByteArray? = digest?.clone()

        open fun digestHex(): String? = if (digest == null) null else hex(digest)

        open fun bytesRead(): Long = bytesRead

        open fun errorCode(): Int = errorCode

        open fun failure(): Failure? = failure

        open fun fellBack(): Boolean = fellBack

        open fun fallbackReason(): String = fallbackReason

        private fun explain(reason: String, didFallBack: Boolean): Result =
            Result(backend, digest, bytesRead, errorCode, failure, didFallBack, reason)

        companion object {
            /** 原包可见测试缝；保留 Java 名与 static 描述符。 */
            @JvmStatic
            @JvmName("simulatedFailure")
            internal open fun simulatedFailure(failure: Failure?, errorCode: Int): Result =
                Result(Backend.RUST, null, 0, errorCode, failure, false, "")

            // 外层不能直接访问 Kotlin nested class 的 private 构造器；只生成 synthetic 桥。
            @JvmSynthetic
            internal fun create(
                backend: Backend,
                digest: ByteArray?,
                bytesRead: Long,
                errorCode: Int,
                failure: Failure?,
                fellBack: Boolean,
                fallbackReason: String,
            ): Result =
                Result(backend, digest, bytesRead, errorCode, failure, fellBack, fallbackReason)

            @JvmSynthetic
            internal fun explained(result: Result, reason: String, didFallBack: Boolean): Result =
                result.explain(reason, didFallBack)
        }
    }

    companion object {
        const val ALGORITHM_SHA256 = 1
        const val ALGORITHM_SHA1 = 2
        const val MAX_LENGTH = 33_554_432L
        const val CHUNK = 262_144
        const val OK = 0
        const val INVALID_ARGUMENT = -1
        const val OUTPUT_BOUND = -3
        const val INTERNAL = -6
        const val PANIC = -7
        const val JNI_PENDING = -8
        const val SHORT_READ = -9
        const val UNSUPPORTED_ALGORITHM = -10

        @JvmStatic
        open fun validate(offset: Long, length: Long, algorithm: Int): Int {
            if (offset < 0) return INVALID_ARGUMENT
            if (length < 1 || length > MAX_LENGTH) return INVALID_ARGUMENT
            if (offset > Long.MAX_VALUE - length) return INVALID_ARGUMENT
            if (algorithm != ALGORITHM_SHA256 && algorithm != ALGORITHM_SHA1) {
                return UNSUPPORTED_ALGORITHM
            }
            return OK
        }

        @JvmStatic
        open fun digest(
            fd: Int,
            channel: FileChannel?,
            offset: Long,
            length: Long,
            algorithm: Int,
        ): Result {
            val selection = RustBackends.digestSelection()
            if (!selection.useRust()) {
                return Result.explained(
                    digestJvm(channel, offset, length, algorithm),
                    selection.reasonCode(),
                    false,
                )
            }
            val rust = digestRust(fd, offset, length, algorithm)
            return settleRustResult(rust, channel, offset, length, algorithm)
        }

        /** 只重算纯函数 INTERNAL/PANIC，其余结果包括对象身份均原样返回。 */
        @JvmStatic
        @JvmName("settleRustResult")
        internal open fun settleRustResult(
            rust: Result?,
            channel: FileChannel?,
            offset: Long,
            length: Long,
            algorithm: Int,
        ): Result {
            if (rust!!.ok()) return rust
            if (shouldRedoOnJvm(rust.failure())) {
                if (channel == null) return Result.explained(rust, "JVM_SOURCE_UNAVAILABLE", false)
                val jvm = digestJvm(channel, offset, length, algorithm)
                return Result.explained(jvm, "RUST_" + rust.failure()!!.name, true)
            }
            return rust
        }

        @JvmStatic
        @JvmName("shouldRedoOnJvm")
        internal open fun shouldRedoOnJvm(failure: Failure?): Boolean =
            failure == Failure.INTERNAL || failure == Failure.PANIC

        @JvmStatic
        open fun digestRust(fd: Int, offset: Long, length: Long, algorithm: Int): Result {
            if (!RustNative.isAvailable()) {
                return Result.create(
                    Backend.RUST,
                    null,
                    0,
                    0,
                    statusFailure(RustNative.status()),
                    false,
                    "NOT_LOADED",
                )
            }
            // 成功必须写入摘要且只写允许范围；其余哨兵字节不得改变。
            val sentinel: Byte = 0x5a
            val out = ByteBuffer.allocateDirect(32)
            for (index in 0 until 32) out.put(index, sentinel)
            val value: Long
            try {
                value = RustNative.digest(fd, offset, length, algorithm, out)
            } catch (error: UnsatisfiedLinkError) {
                return Result.create(
                    Backend.RUST,
                    null,
                    0,
                    0,
                    Failure.LIBRARY_LOAD_FAILED,
                    false,
                    "NATIVE_BINDING_FAILED",
                )
            }
            if (value < 0) {
                val code = value.toInt()
                return Result.create(Backend.RUST, null, 0, code, mapFailure(code), false, "")
            }
            if (value != length) {
                return Result.create(
                    Backend.RUST,
                    null,
                    0,
                    INTERNAL,
                    Failure.INTERNAL,
                    false,
                    "RUST_BYTES_READ_MISMATCH",
                )
            }
            val size = digestBytes(algorithm)
            if (size < 0) {
                return Result.create(
                    Backend.RUST,
                    null,
                    0,
                    UNSUPPORTED_ALGORITHM,
                    Failure.UNSUPPORTED_ALGORITHM,
                    false,
                    "",
                )
            }
            var untouchedTail = true
            for (index in size until 32) {
                if (out.get(index) != sentinel) {
                    untouchedTail = false
                    break
                }
            }
            var wroteDigest = false
            for (index in 0 until size) {
                if (out.get(index) != sentinel) {
                    wroteDigest = true
                    break
                }
            }
            if (!untouchedTail || !wroteDigest) {
                return Result.create(
                    Backend.RUST,
                    null,
                    0,
                    INTERNAL,
                    Failure.INTERNAL,
                    false,
                    "RUST_OUTPUT_ABI_MISMATCH",
                )
            }
            val digest = ByteArray(size)
            out.position(0)
            out.get(digest)
            return Result.create(Backend.RUST, digest, value, OK, Failure.NONE, false, "")
        }

        @JvmStatic
        open fun digestJvm(
            channel: FileChannel?,
            offset: Long,
            length: Long,
            algorithm: Int,
        ): Result {
            val validation = validate(offset, length, algorithm)
            if (validation != OK) {
                return Result.create(
                    Backend.JVM,
                    null,
                    0,
                    validation,
                    mapFailure(validation),
                    false,
                    "",
                )
            }
            if (channel == null) {
                return Result.create(
                    Backend.JVM,
                    null,
                    0,
                    INVALID_ARGUMENT,
                    Failure.INVALID_ARGUMENT,
                    false,
                    "",
                )
            }
            val hasher = newMessageDigest(algorithm)
            if (hasher == null) {
                return Result.create(
                    Backend.JVM,
                    null,
                    0,
                    UNSUPPORTED_ALGORITHM,
                    Failure.UNSUPPORTED_ALGORITHM,
                    false,
                    "",
                )
            }
            val buffer = ByteArray(minOf(CHUNK.toLong(), length).toInt())
            val view = ByteBuffer.wrap(buffer)
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                view.clear()
                view.limit(want)
                val read: Int
                try {
                    read = channel.read(view, position)
                } catch (error: IOException) {
                    return Result.create(
                        Backend.JVM,
                        null,
                        0,
                        INTERNAL,
                        Failure.INTERNAL,
                        false,
                        "",
                    )
                }
                if (read < 0) {
                    return Result.create(
                        Backend.JVM,
                        null,
                        0,
                        SHORT_READ,
                        Failure.SHORT_READ,
                        false,
                        "",
                    )
                }
                if (read == 0) {
                    return Result.create(
                        Backend.JVM,
                        null,
                        0,
                        INTERNAL,
                        Failure.INTERNAL,
                        false,
                        "",
                    )
                }
                hasher.update(buffer, 0, read)
                position += read
                remaining -= read
            }
            return Result.create(Backend.JVM, hasher.digest(), length, OK, Failure.NONE, false, "")
        }

        @JvmStatic
        open fun mapFailure(code: Int): Failure =
            when (code) {
                INVALID_ARGUMENT -> Failure.INVALID_ARGUMENT
                OUTPUT_BOUND -> Failure.OUTPUT_BOUND
                INTERNAL -> Failure.INTERNAL
                PANIC -> Failure.PANIC
                JNI_PENDING -> Failure.JNI_PENDING
                SHORT_READ -> Failure.SHORT_READ
                UNSUPPORTED_ALGORITHM -> Failure.UNSUPPORTED_ALGORITHM
                else -> Failure.UNKNOWN
            }

        @JvmStatic
        @JvmName("digestBytes")
        internal open fun digestBytes(algorithm: Int): Int {
            if (algorithm == ALGORITHM_SHA256) return 32
            if (algorithm == ALGORITHM_SHA1) return 20
            return -1
        }

        private fun newMessageDigest(algorithm: Int): MessageDigest? {
            val name =
                when (algorithm) {
                    ALGORITHM_SHA256 -> "SHA-256"
                    ALGORITHM_SHA1 -> "SHA-1"
                    else -> return null
                }
            return try {
                MessageDigest.getInstance(name)
            } catch (error: NoSuchAlgorithmException) {
                null
            }
        }

        private fun statusFailure(status: RustNative.Status): Failure =
            when (status) {
                RustNative.Status.LIBRARY_MISSING -> Failure.LIBRARY_MISSING
                RustNative.Status.LOAD_FAILED -> Failure.LIBRARY_LOAD_FAILED
                RustNative.Status.ABI_MISMATCH -> Failure.ABI_MISMATCH
                else -> Failure.NOT_LOADED
            }

        @JvmStatic
        open fun hex(bytes: ByteArray?): String {
            val out = StringBuilder(bytes!!.size * 2)
            for (value in bytes) {
                out.append(Character.forDigit((value.toInt() shr 4) and 0x0f, 16))
                out.append(Character.forDigit(value.toInt() and 0x0f, 16))
            }
            return out.toString()
        }
    }
}
