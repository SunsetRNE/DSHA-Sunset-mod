package com.deepseekharness.app.saf

import java.util.LinkedHashMap
import java.util.Objects

/** SAF 授权簿不可变快照；坏参数不改变 revision、不消耗 epoch。 */
class SafGrantLedger
private constructor(
    private val byUri: Map<String, SafGrantRecord>,
    private val revision: Long,
    private val nextEpoch: Long,
) {
    /** 按短 id 的稳定升序返回只读快照，包含 PENDING 与系统位已归零的记录。 */
    fun records(): List<SafGrantRecord> {
        val list = ArrayList(byUri.values)
        list.sortWith(compareBy { it.id })
        return java.util.List.copyOf(list)
    }

    fun findById(id: String?): SafGrantRecord? {
        if (!SafGrantRecord.validId(id)) return null
        for (record in byUri.values) if (record.id == id) return record
        return null
    }

    fun findByUri(uri: String?): SafGrantRecord? = if (uri == null) null else byUri[uri]

    fun revision(): Long = revision

    fun nextEpoch(): Long = nextEpoch

    fun size(): Int = byUri.size

    /** 先落 PENDING；重新授权分配新代次，保留旧 ACTIVE 快照以供失败恢复。 */
    fun beginTake(
        uri: String?,
        app: String?,
        label: String?,
        beforeFlags: Int,
        intentFlags: Int,
        now: Long,
    ): Change {
        if (SafCapabilityPolicy.treeUriProblem(uri, null) != SafCapabilityPolicy.TreeUri.OK)
            return fail(INVALID_URI)
        if (
            beforeFlags and (SafGrantRecord.READ or SafGrantRecord.WRITE).inv() != 0 ||
                intentFlags and (SafGrantRecord.READ or SafGrantRecord.WRITE).inv() != 0 ||
                intentFlags == 0
        )
            return fail(INVALID_ARGUMENT)
        val existing = byUri[uri]
        if (existing != null && existing.state == SafGrantRecord.State.RELEASING)
            return fail(BAD_STATE)
        if (existing == null && byUri.size >= SafCapabilityLimits.MAX_TREES) return fail(LIMIT)
        val epoch = nextEpoch
        val userWritable = existing == null || existing.userWritable
        var previous: SafGrantRecord.Previous? = null
        if (existing != null) {
            val prior =
                if (existing.state == SafGrantRecord.State.PENDING) existing.previous else null
            if (prior != null) {
                previous = prior
            } else if (existing.state == SafGrantRecord.State.ACTIVE) {
                previous =
                    SafGrantRecord.Previous(
                        existing.app,
                        existing.label,
                        existing.readable,
                        existing.writable,
                        existing.acquired,
                        existing.takenAt,
                    )
            }
        }
        val pending =
            SafGrantRecord(
                SafGrantRecord.idOf(uri),
                uri,
                app,
                label,
                false,
                false,
                userWritable,
                0,
                SafGrantRecord.State.PENDING,
                epoch,
                maxOf(0, now),
                beforeFlags,
                intentFlags,
                previous,
            )
        val copy = LinkedHashMap(byUri)
        copy[uri!!] = pending
        return Change(SafGrantLedger(copy, revision + 1, epoch + 1), null)
    }

    fun commitTake(
        uri: String?,
        takenFlags: Int,
        acquiredFlags: Int,
        userWritable: Boolean,
        now: Long,
    ): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return fail(UNKNOWN_GRANT)
        if (record.state != SafGrantRecord.State.PENDING) return fail(BAD_STATE)
        if (
            takenFlags and (SafGrantRecord.READ or SafGrantRecord.WRITE).inv() != 0 ||
                acquiredFlags and takenFlags.inv() != 0
        )
            return fail(INVALID_ARGUMENT)
        val active =
            SafGrantRecord(
                record.id,
                record.uri,
                record.app,
                record.label,
                takenFlags and SafGrantRecord.READ != 0,
                takenFlags and SafGrantRecord.WRITE != 0,
                userWritable,
                acquiredFlags,
                SafGrantRecord.State.ACTIVE,
                record.epoch,
                if (now >= 0) now else record.takenAt,
                0,
                0,
                null,
            )
        return replace(active)
    }

    /** 重新授权失败恢复原显示名/权限归属，但保留本次已分配的 epoch。 */
    fun abortTake(uri: String?): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return Change(this, null)
        if (record.state != SafGrantRecord.State.PENDING) return fail(BAD_STATE)
        val previous = record.previous ?: return remove(uri!!)
        val restored =
            SafGrantRecord(
                record.id,
                record.uri,
                previous.app,
                previous.label,
                previous.readable,
                previous.writable,
                record.userWritable,
                previous.acquired,
                SafGrantRecord.State.ACTIVE,
                record.epoch,
                previous.takenAt,
                0,
                0,
                null,
            )
        return replace(restored)
    }

    /** 系统拒绝释放时留 RELEASING，不再签发能力，供下次清理重试。 */
    fun beginRelease(uri: String?): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return Change(this, null)
        if (record.state == SafGrantRecord.State.RELEASING) return Change(this, null)
        if (record.state != SafGrantRecord.State.ACTIVE) return fail(BAD_STATE)
        val releasing =
            SafGrantRecord(
                record.id,
                record.uri,
                record.app,
                record.label,
                false,
                false,
                record.userWritable,
                record.acquired,
                SafGrantRecord.State.RELEASING,
                record.epoch,
                record.takenAt,
                0,
                0,
                null,
            )
        return replace(releasing)
    }

    fun finishRelease(uri: String?): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return Change(this, null)
        if (record.state != SafGrantRecord.State.RELEASING) return fail(BAD_STATE)
        return remove(uri!!)
    }

    fun setMetadata(uri: String?, app: String?, label: String?): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return fail(UNKNOWN_GRANT)
        val nextApp = app?.trim { it <= ' ' } ?: ""
        val nextLabel = label?.trim { it <= ' ' } ?: ""
        if (record.app == nextApp && record.label == nextLabel) return Change(this, null)
        return replace(record.copy(app = nextApp, label = nextLabel))
    }

    fun forget(uri: String?): Change {
        if (uri == null || !byUri.containsKey(uri)) return Change(this, null)
        return remove(uri)
    }

    fun setUserWritable(uri: String?, writable: Boolean): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return fail(UNKNOWN_GRANT)
        if (record.userWritable == writable) return Change(this, null)
        return replace(record.copy(userWritable = writable))
    }

    /** 系统撤销后位归零但保留可展示/移除的记录，不更换代次。 */
    fun refreshSystemFlags(uri: String?, observedFlags: Int): Change {
        val record = if (uri == null) null else byUri[uri]
        if (record == null) return fail(UNKNOWN_GRANT)
        if (record.state != SafGrantRecord.State.ACTIVE) return fail(BAD_STATE)
        if (observedFlags and (SafGrantRecord.READ or SafGrantRecord.WRITE).inv() != 0)
            return fail(INVALID_ARGUMENT)
        val readable = observedFlags and SafGrantRecord.READ != 0
        val writable = observedFlags and SafGrantRecord.WRITE != 0
        if (record.readable == readable && record.writable == writable) return Change(this, null)
        return replace(record.copy(readable = readable, writable = writable, previous = null))
    }

    private fun replace(record: SafGrantRecord): Change {
        val copy = LinkedHashMap(byUri)
        copy[record.uri!!] = record
        return Change(SafGrantLedger(copy, revision + 1, nextEpoch), null)
    }

    private fun remove(uri: String): Change {
        val copy = LinkedHashMap(byUri)
        copy.remove(uri)
        return Change(SafGrantLedger(copy, revision + 1, nextEpoch), null)
    }

    private fun fail(failure: String): Change = Change(this, failure)

    class Change internal constructor(ledger: SafGrantLedger?, @JvmField val failure: String?) {
        @JvmField val ledger: SafGrantLedger = Objects.requireNonNull(ledger, "ledger")!!

        fun accepted(): Boolean = failure == null
    }

    companion object {
        const val INVALID_URI = "INVALID_URI"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
        const val LIMIT = "LIMIT"
        const val UNKNOWN_GRANT = "UNKNOWN_GRANT"
        const val BAD_STATE = "BAD_STATE"

        @JvmStatic fun empty(): SafGrantLedger = SafGrantLedger(java.util.Map.of(), 0, 1)

        @JvmStatic
        fun of(records: List<SafGrantRecord?>?, revision: Long, nextEpoch: Long): SafGrantLedger {
            Objects.requireNonNull(records, "records")
            val map = LinkedHashMap<String, SafGrantRecord>()
            var maxEpoch = 0L
            for (entry in records!!) {
                val record = entry ?: throw IllegalArgumentException("GRANT_NULL")
                if (map.putIfAbsent(record.uri!!, record) != null)
                    throw IllegalArgumentException("GRANT_DUPLICATE_URI")
                maxEpoch = maxOf(maxEpoch, record.epoch)
            }
            if (map.size > SafCapabilityLimits.MAX_TREES)
                throw IllegalArgumentException("GRANT_LIMIT")
            return SafGrantLedger(map, maxOf(0, revision), maxOf(maxOf(1, nextEpoch), maxEpoch + 1))
        }
    }
}
