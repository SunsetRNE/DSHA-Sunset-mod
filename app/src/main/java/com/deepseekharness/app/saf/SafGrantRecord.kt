package com.deepseekharness.app.saf

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Objects
import java.util.regex.Pattern

/** 授权簿记录；系统持久 URI 授权仍是唯一真相，epoch 绑定本次授权代次。 */
@JvmRecord
data class SafGrantRecord(
    val id: String?,
    val uri: String?,
    val app: String?,
    val label: String?,
    val readable: Boolean,
    val writable: Boolean,
    val userWritable: Boolean,
    val acquired: Int,
    val state: State?,
    val epoch: Long,
    val takenAt: Long,
    val pendingBefore: Int,
    val pendingIntent: Int,
    val previous: Previous?,
) {
    enum class State {
        PENDING,
        ACTIVE,
        RELEASING,
    }

    /** 仅 PENDING 持有的旧 ACTIVE 快照；失败重新授权时原样恢复。 */
    @JvmRecord
    data class Previous(
        val app: String?,
        val label: String?,
        val readable: Boolean,
        val writable: Boolean,
        val acquired: Int,
        val takenAt: Long,
    ) {
        init {
            // 仅在 canonical constructor 内重新赋值：保留 Java record 的 final 字段与归一化顺序。
            @Suppress("VAL_REASSIGNMENT")
            this.app = app ?: ""
            @Suppress("VAL_REASSIGNMENT")
            this.label = label ?: ""
            if (acquired and FLAGS.inv() != 0) throw IllegalArgumentException("GRANT_FLAGS_INVALID")
            if (takenAt < 0) throw IllegalArgumentException("GRANT_TIME_INVALID")
        }

        final override fun equals(other: Any?): Boolean =
            this === other ||
                other is Previous &&
                    app == other.app &&
                    label == other.label &&
                    readable == other.readable &&
                    writable == other.writable &&
                    acquired == other.acquired &&
                    takenAt == other.takenAt

        final override fun hashCode(): Int {
            var result = app.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + java.lang.Boolean.hashCode(readable)
            result = 31 * result + java.lang.Boolean.hashCode(writable)
            result = 31 * result + acquired
            return 31 * result + java.lang.Long.hashCode(takenAt)
        }

        final override fun toString(): String =
            "Previous[app=$app, label=$label, readable=$readable, writable=$writable, acquired=$acquired, takenAt=$takenAt]"
    }

    init {
        Objects.requireNonNull(uri, "uri")
        Objects.requireNonNull(id, "id")
        Objects.requireNonNull(state, "state")
        if (!ID_PATTERN.matcher(id).matches()) throw IllegalArgumentException("GRANT_ID_INVALID")
        if (id != idOf(uri)) throw IllegalArgumentException("GRANT_ID_MISMATCH")
        // epoch=0 是损坏记录的失败关闭降级态：仍可展示/撤销，不能签发句柄。
        if (epoch < 0) throw IllegalArgumentException("GRANT_EPOCH_INVALID")
        if (takenAt < 0) throw IllegalArgumentException("GRANT_TIME_INVALID")
        if (acquired and FLAGS.inv() != 0) throw IllegalArgumentException("GRANT_FLAGS_INVALID")
        if (pendingBefore and FLAGS.inv() != 0 || pendingIntent and FLAGS.inv() != 0)
            throw IllegalArgumentException("GRANT_PENDING_FLAGS_INVALID")
        if (previous != null && state != State.PENDING)
            throw IllegalArgumentException("GRANT_PREVIOUS_WITHOUT_PENDING")
        // Kotlin 的 record canonical constructor 也必须先验证，再写入规范化值。
        @Suppress("VAL_REASSIGNMENT")
        this.app = clip(app)
        @Suppress("VAL_REASSIGNMENT")
        this.label = clip(label)
    }

    fun hasPrevious(): Boolean = previous != null

    fun canRead(): Boolean = state == State.ACTIVE && readable

    fun canWrite(): Boolean = state == State.ACTIVE && writable

    fun usableWrite(): Boolean = canWrite() && userWritable

    fun valid(): Boolean = canRead() || canWrite()

    fun summary(): String {
        val name = if (label!!.isEmpty()) lastSegment(uri!!) else label
        if (app!!.isEmpty()) return name
        return "$app · $name"
    }

    final override fun equals(other: Any?): Boolean =
        this === other ||
            other is SafGrantRecord &&
                id == other.id &&
                uri == other.uri &&
                app == other.app &&
                label == other.label &&
                readable == other.readable &&
                writable == other.writable &&
                userWritable == other.userWritable &&
                acquired == other.acquired &&
                state == other.state &&
                epoch == other.epoch &&
                takenAt == other.takenAt &&
                pendingBefore == other.pendingBefore &&
                pendingIntent == other.pendingIntent &&
                previous == other.previous

    final override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + app.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + java.lang.Boolean.hashCode(readable)
        result = 31 * result + java.lang.Boolean.hashCode(writable)
        result = 31 * result + java.lang.Boolean.hashCode(userWritable)
        result = 31 * result + acquired
        result = 31 * result + state.hashCode()
        result = 31 * result + java.lang.Long.hashCode(epoch)
        result = 31 * result + java.lang.Long.hashCode(takenAt)
        result = 31 * result + pendingBefore
        result = 31 * result + pendingIntent
        return 31 * result + previous.hashCode()
    }

    final override fun toString(): String =
        "SafGrantRecord[id=$id, uri=$uri, app=$app, label=$label, readable=$readable, writable=$writable, userWritable=$userWritable, acquired=$acquired, state=$state, epoch=$epoch, takenAt=$takenAt, pendingBefore=$pendingBefore, pendingIntent=$pendingIntent, previous=$previous]"

    companion object {
        const val READ = 1
        const val WRITE = 2
        const val ID_LENGTH = 12
        private const val FLAGS = READ or WRITE
        private const val MAX_TEXT = 300
        private val ID_PATTERN = Pattern.compile("[0-9a-f]{$ID_LENGTH}")

        @JvmStatic
        fun idOf(uri: String?): String {
            if (uri == null) return ""
            return try {
                val hash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(uri.toByteArray(StandardCharsets.UTF_8))
                val out = StringBuilder(ID_LENGTH)
                for (index in 0 until ID_LENGTH / 2) out.append(
                    String.format(Locale.ROOT, "%02x", hash[index])
                )
                out.toString()
            } catch (unavailable: Exception) {
                String.format(Locale.ROOT, "%08x", uri.hashCode())
            }
        }

        @JvmStatic
        fun validId(id: String?): Boolean = id != null && ID_PATTERN.matcher(id).matches()

        private fun clip(value: String?): String {
            if (value == null) return ""
            val trimmed = value.trim { it <= ' ' }
            return if (trimmed.length <= MAX_TEXT) trimmed else trimmed.substring(0, MAX_TEXT)
        }

        private fun lastSegment(uri: String): String {
            val value = if (uri.endsWith("/")) uri.substring(0, uri.length - 1) else uri
            val slash = value.lastIndexOf('/')
            return if (slash < 0 || slash == value.length - 1) value else value.substring(slash + 1)
        }
    }
}
