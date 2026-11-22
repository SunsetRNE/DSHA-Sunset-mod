package com.deepseekharness.app.saf

import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects

/** 只在进程内存中保留的限时能力；绑定树、epoch、操作和规范化路径。 */
class SafCapabilityHandles(tokenSource: TokenSource?) {
    enum class Operation {
        READ,
        WRITE,
        CREATE,
        DELETE,
    }

    enum class Result {
        OK,
        UNKNOWN,
        EXPIRED,
        REVOKED,
        NOT_PERMITTED,
        MISMATCH,
        BAD_PATH,
        LIMIT,
        UNAVAILABLE,
    }

    fun interface TokenSource {
        fun next(): String?
    }

    class Issued
    private constructor(
        @JvmField val token: String,
        @JvmField val expiresAt: Long,
        @JvmField val result: Result,
    ) {
        fun ok(): Boolean = result == Result.OK

        companion object {
            // 嵌套类私有构造只由本域签发路径使用。
            @JvmSynthetic
            internal fun create(token: String, expiresAt: Long, result: Result): Issued =
                Issued(token, expiresAt, result)
        }
    }

    private class Entry(
        val treeId: String,
        val epoch: Long,
        val operation: Operation,
        val path: String,
        val expiresAt: Long,
    )

    private val tokens = LinkedHashMap<String, Entry>()
    private val tokenSource: TokenSource = Objects.requireNonNull(tokenSource, "tokenSource")!!

    constructor() : this(TokenSource { secureToken() })

    @Synchronized
    fun issue(
        ledger: SafGrantLedger?,
        treeId: String?,
        operation: Operation?,
        path: String?,
        nowMs: Long,
    ): Issued {
        val now = maxOf(0, nowMs)
        purgeExpiredLocked(now)
        val normalized = normalizePath(path) ?: return failed(Result.BAD_PATH)
        if (ledger == null) return failed(Result.UNKNOWN)
        val record = ledger.findById(treeId) ?: return failed(Result.UNKNOWN)
        if (record.state != SafGrantRecord.State.ACTIVE || record.epoch < 1)
            return failed(Result.REVOKED)
        if (!permitted(record, operation)) return failed(Result.NOT_PERMITTED)
        if (tokens.size >= MAX_OUTSTANDING) return failed(Result.LIMIT)
        val perTree = countForTree(treeId!!)
        if (perTree >= MAX_PER_TREE) return failed(Result.LIMIT)
        val token = nextToken() ?: return failed(Result.UNAVAILABLE)
        val expiresAt = if (now > Long.MAX_VALUE - TTL_MS) Long.MAX_VALUE else now + TTL_MS
        tokens[token] = Entry(treeId, record.epoch, operation!!, normalized, expiresAt)
        return Issued.create(token, expiresAt, Result.OK)
    }

    @Synchronized
    fun check(
        ledger: SafGrantLedger?,
        token: String?,
        operation: Operation?,
        path: String?,
        nowMs: Long,
    ): Result {
        val normalized = normalizePath(path) ?: return Result.BAD_PATH
        val entry = if (token == null) null else tokens[token]
        if (entry == null) return Result.UNKNOWN
        return validate(entry, ledger, operation, normalized, maxOf(0, nowMs))
    }

    /** 只在 OK 时消费；过期或撤销顺带清理，错路径/操作保留票据。 */
    @Synchronized
    fun consume(
        ledger: SafGrantLedger?,
        token: String?,
        operation: Operation?,
        path: String?,
        nowMs: Long,
    ): Result {
        val normalized = normalizePath(path) ?: return Result.BAD_PATH
        val entry = if (token == null) null else tokens[token]
        if (entry == null) return Result.UNKNOWN
        val result = validate(entry, ledger, operation, normalized, maxOf(0, nowMs))
        if (result == Result.OK || result == Result.EXPIRED || result == Result.REVOKED)
            tokens.remove(token)
        return result
    }

    @Synchronized
    fun discard(token: String?) {
        if (token != null) tokens.remove(token)
    }

    @Synchronized
    fun revokeTree(treeId: String?): Int {
        if (treeId == null) return 0
        var removed = 0
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            if (treeId == iterator.next().value.treeId) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    @Synchronized fun purgeExpired(nowMs: Long): Int = purgeExpiredLocked(maxOf(0, nowMs))

    @Synchronized fun size(): Int = tokens.size

    private fun validate(
        entry: Entry,
        ledger: SafGrantLedger?,
        operation: Operation?,
        normalizedPath: String,
        now: Long,
    ): Result {
        if (entry.expiresAt <= now) return Result.EXPIRED
        val record = ledger?.findById(entry.treeId)
        if (
            record == null ||
                record.state != SafGrantRecord.State.ACTIVE ||
                record.epoch != entry.epoch ||
                record.epoch < 1
        )
            return Result.REVOKED
        if (!permitted(record, operation)) return Result.NOT_PERMITTED
        if (entry.operation != operation) return Result.MISMATCH
        if (entry.path != normalizedPath) return Result.MISMATCH
        return Result.OK
    }

    private fun purgeExpiredLocked(now: Long): Int {
        var removed = 0
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= now) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun countForTree(treeId: String): Int {
        var count = 0
        for (entry in tokens.values) if (treeId == entry.treeId) count++
        return count
    }

    private fun nextToken(): String? {
        for (attempt in 0 until MAX_TOKEN_ATTEMPTS) {
            val candidate =
                try {
                    tokenSource.next()
                } catch (unavailable: RuntimeException) {
                    return null
                }
            if (validToken(candidate) && !tokens.containsKey(candidate)) return candidate
        }
        return null
    }

    companion object {
        const val TTL_MS = SafCapabilityLimits.HANDLE_TTL_MS
        const val MAX_OUTSTANDING = SafCapabilityLimits.MAX_OUTSTANDING_HANDLES
        const val MAX_PER_TREE = SafCapabilityLimits.MAX_HANDLES_PER_TREE
        private const val MAX_TOKEN_ATTEMPTS = 8
        private const val TOKEN_BYTES = 16
        private val RANDOM = SecureRandom()

        private fun permitted(record: SafGrantRecord, operation: Operation?): Boolean =
            when (operation) {
                null -> false
                Operation.READ -> record.canRead()
                Operation.WRITE,
                Operation.CREATE,
                Operation.DELETE -> record.usableWrite()
            }

        private fun validToken(token: String?): Boolean {
            if (token == null || token.isEmpty() || token.length > 128) return false
            for (value in token) if (value <= ' ' || value.code == 127) return false
            return true
        }

        private fun normalizePath(path: String?): String? =
            try {
                SafPaths.normalize(path)
            } catch (invalid: IllegalArgumentException) {
                null
            }

        private fun secureToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            RANDOM.nextBytes(bytes)
            val out = StringBuilder(TOKEN_BYTES * 2)
            for (value in bytes) out.append(String.format(Locale.ROOT, "%02x", value))
            return out.toString()
        }

        private fun failed(result: Result): Issued = Issued.create("", 0, result)
    }
}
