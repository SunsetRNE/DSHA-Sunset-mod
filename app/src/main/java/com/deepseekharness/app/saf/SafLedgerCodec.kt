package com.deepseekharness.app.saf

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.TreeSet

/**
 * 授权簿持久化编解码（纯逻辑，不 import Android API）。
 *
 * 存储是一个扁平 key/value 命名空间（Android 侧为独立 SharedPreferences），布局：
 *
 * ```
 * schema = "1"
 * revision = 单调递增的十进制
 * nextEpoch = 下一个可分配的授权代次
 * ids = 记录短 id 的 StringSet
 * grant.<id>.uri/state/epoch/read/write/userWrite/acquired/takenAt/pendingBefore/pendingIntent
 * ```
 *
 * 坏标量按安全默认恢复并给出警告；未知 key 原样进入 [Decoded.preserved]，保存时不会删除。记录缺 URI、 id 与 URI 摘要不符、state
 * 不认识时整组保留但不激活，避免恢复一条可能已撤销的授权。
 */
class SafLedgerCodec private constructor() {
    /** 解码结果：返回空/降级授权簿、警告与需要原样保留的未知项。构造器在 Kotlin 中仅模块内可见。 */
    class Decoded
    internal constructor(
        @JvmField val ledger: SafGrantLedger?,
        preserved: Map<String, Any?>?,
        warnings: List<String>?,
    ) {
        @JvmField val preserved: Map<String, Any?> = java.util.Map.copyOf(preserved)
        @JvmField val warnings: List<String> = java.util.List.copyOf(warnings)
    }

    companion object {
        const val SCHEMA = 1

        const val PREF_SCHEMA = "schema"
        const val PREF_REVISION = "revision"
        const val PREF_NEXT_EPOCH = "nextEpoch"
        const val PREF_IDS = "ids"
        const val PREF_GRANT_PREFIX = "grant."

        const val FIELD_URI = "uri"
        const val FIELD_APP = "app"
        const val FIELD_LABEL = "label"
        const val FIELD_READ = "read"
        const val FIELD_WRITE = "write"
        const val FIELD_USER_WRITE = "userWrite"
        const val FIELD_ACQUIRED = "acquired"
        const val FIELD_STATE = "state"
        const val FIELD_EPOCH = "epoch"
        const val FIELD_TAKEN_AT = "takenAt"
        const val FIELD_PENDING_BEFORE = "pendingBefore"
        const val FIELD_PENDING_INTENT = "pendingIntent"
        const val FIELD_PREVIOUS = "prev"
        const val FIELD_PREV_APP = "prevApp"
        const val FIELD_PREV_LABEL = "prevLabel"
        const val FIELD_PREV_READ = "prevRead"
        const val FIELD_PREV_WRITE = "prevWrite"
        const val FIELD_PREV_ACQUIRED = "prevAcquired"
        const val FIELD_PREV_TAKEN_AT = "prevTakenAt"

        private val TOP_LEVEL =
            java.util.Set.of(PREF_SCHEMA, PREF_REVISION, PREF_NEXT_EPOCH, PREF_IDS)
        private val GRANT_FIELDS =
            java.util.Set.of(
                FIELD_URI,
                FIELD_APP,
                FIELD_LABEL,
                FIELD_READ,
                FIELD_WRITE,
                FIELD_USER_WRITE,
                FIELD_ACQUIRED,
                FIELD_STATE,
                FIELD_EPOCH,
                FIELD_TAKEN_AT,
                FIELD_PENDING_BEFORE,
                FIELD_PENDING_INTENT,
                FIELD_PREVIOUS,
                FIELD_PREV_APP,
                FIELD_PREV_LABEL,
                FIELD_PREV_READ,
                FIELD_PREV_WRITE,
                FIELD_PREV_ACQUIRED,
                FIELD_PREV_TAKEN_AT,
            )

        /** 从扁平 key/value 快照解码；不修改输入、不抛异常。 */
        @JvmStatic
        fun decode(raw: Map<String, *>?): Decoded {
            val preserved = LinkedHashMap<String, Any?>()
            val warnings = ArrayList<String>()
            if (raw == null || raw.isEmpty()) {
                return Decoded(SafGrantLedger.empty(), preserved, warnings)
            }
            val schema = text(raw[PREF_SCHEMA])
            if (SCHEMA.toString() != schema) {
                // 不认识的版本（或 schema 整段损坏）：整份原样保留，不激活任何记录，等新版本读回。
                preserved.putAll(raw)
                warnings.add("SCHEMA_UNSUPPORTED")
                return Decoded(SafGrantLedger.empty(), preserved, warnings)
            }

            var revision = 0L
            val revisionRaw = raw[PREF_REVISION]
            if (revisionRaw != null) {
                val parsed = optionalNumber(revisionRaw)
                if (parsed == null || parsed < 0) {
                    warnings.add("REVISION_DEFAULTED")
                } else {
                    revision = parsed
                }
            }
            var nextEpoch = 0L
            val nextEpochRaw = raw[PREF_NEXT_EPOCH]
            if (nextEpochRaw != null) {
                val parsed = optionalNumber(nextEpochRaw)
                if (parsed != null && parsed >= 0) nextEpoch = parsed
            }

            val ids = readIds(raw, warnings)
            val acceptedIds = LinkedHashSet<String>()
            val acceptedUris = LinkedHashSet<String?>()
            val records = ArrayList<SafGrantRecord>()
            for (id in ids) {
                val prefix = PREF_GRANT_PREFIX + id + "."
                var present = false
                for (key in raw.keys) {
                    if (key.startsWith(prefix)) {
                        present = true
                        break
                    }
                }
                if (!present) {
                    warnings.add("RECORD_MISSING:" + id)
                    continue
                }
                if (records.size >= SafCapabilityLimits.MAX_TREES) {
                    warnings.add("RECORD_LIMIT")
                    break
                }
                val record = decodeRecord(raw, id, prefix, warnings) ?: continue
                if (!acceptedUris.add(record.uri)) {
                    warnings.add("RECORD_DUPLICATE_URI:" + id)
                    continue
                }
                records.add(record)
                acceptedIds.add(id)
            }

            for ((key, value) in raw) {
                if (TOP_LEVEL.contains(key)) continue
                if (consumedGrantKey(key, acceptedIds)) continue
                preserved[key] = value
            }
            val ledger = SafGrantLedger.of(records, revision, nextEpoch)
            return Decoded(ledger, preserved, warnings)
        }

        /** 编码为可交给 SharedPreferences 的扁平快照；只写本版本认识的 key。 */
        @JvmStatic
        fun encode(ledger: SafGrantLedger?): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            out[PREF_SCHEMA] = SCHEMA.toString()
            out[PREF_REVISION] = ledger!!.revision().toString()
            out[PREF_NEXT_EPOCH] = ledger.nextEpoch().toString()
            val ids = LinkedHashSet<String?>()
            for (record in ledger.records()) {
                ids.add(record.id)
                val prefix = PREF_GRANT_PREFIX + record.id + "."
                out[prefix + FIELD_URI] = record.uri!!
                out[prefix + FIELD_APP] = record.app!!
                out[prefix + FIELD_LABEL] = record.label!!
                out[prefix + FIELD_READ] = if (record.readable) "1" else "0"
                out[prefix + FIELD_WRITE] = if (record.writable) "1" else "0"
                out[prefix + FIELD_USER_WRITE] = if (record.userWritable) "1" else "0"
                out[prefix + FIELD_ACQUIRED] = flags(record.acquired)
                out[prefix + FIELD_STATE] = record.state!!.name
                out[prefix + FIELD_EPOCH] = record.epoch.toString()
                out[prefix + FIELD_TAKEN_AT] = record.takenAt.toString()
                out[prefix + FIELD_PENDING_BEFORE] = record.pendingBefore.toString()
                out[prefix + FIELD_PENDING_INTENT] = record.pendingIntent.toString()
                val previous = record.previous
                if (previous != null) {
                    out[prefix + FIELD_PREVIOUS] = "1"
                    out[prefix + FIELD_PREV_APP] = previous.app!!
                    out[prefix + FIELD_PREV_LABEL] = previous.label!!
                    out[prefix + FIELD_PREV_READ] = if (previous.readable) "1" else "0"
                    out[prefix + FIELD_PREV_WRITE] = if (previous.writable) "1" else "0"
                    out[prefix + FIELD_PREV_ACQUIRED] = flags(previous.acquired)
                    out[prefix + FIELD_PREV_TAKEN_AT] = previous.takenAt.toString()
                }
            }
            out[PREF_IDS] = ids
            return out
        }

        /** 是否为当前版本拥有的 key；保存时只清理这些，未知字段原样保留。 */
        @JvmStatic
        fun knownKey(key: String?): Boolean {
            if (key == null) return false
            if (TOP_LEVEL.contains(key)) return true
            if (!key.startsWith(PREF_GRANT_PREFIX)) return false
            val rest = key.substring(PREF_GRANT_PREFIX.length)
            val separator = rest.indexOf('.')
            if (separator <= 0) return false
            if (!SafGrantRecord.validId(rest.substring(0, separator))) return false
            return GRANT_FIELDS.contains(rest.substring(separator + 1))
        }

        @JvmStatic
        private fun decodeRecord(
            raw: Map<String, *>,
            id: String,
            prefix: String,
            warnings: MutableList<String>,
        ): SafGrantRecord? {
            val uri = text(raw[prefix + FIELD_URI])
            if (SafCapabilityPolicy.treeUriProblem(uri, null) != SafCapabilityPolicy.TreeUri.OK) {
                warnings.add("RECORD_INVALID_URI:" + id)
                return null
            }
            if (id != SafGrantRecord.idOf(uri)) {
                warnings.add("RECORD_ID_MISMATCH:" + id)
                return null
            }
            val state = state(text(raw[prefix + FIELD_STATE]))
            if (state == null) {
                warnings.add("RECORD_INVALID_STATE:" + id)
                return null
            }
            var epoch = number(raw[prefix + FIELD_EPOCH], 0L)
            if (epoch < 0) {
                epoch = 0
                warnings.add("RECORD_EPOCH_DEFAULTED:" + id)
            }
            val readable = bool(raw[prefix + FIELD_READ], false)
            val writable = bool(raw[prefix + FIELD_WRITE], false)
            val userWritable = bool(raw[prefix + FIELD_USER_WRITE], true)
            val acquired = acquired(raw[prefix + FIELD_ACQUIRED])
            val takenAt = maxOf(0L, number(raw[prefix + FIELD_TAKEN_AT], 0L))
            val pendingBefore = mask(number(raw[prefix + FIELD_PENDING_BEFORE], 0L))
            var pendingIntent =
                mask(number(raw[prefix + FIELD_PENDING_INTENT], SafGrantRecord.READ.toLong()))
            if (pendingIntent == 0) pendingIntent = SafGrantRecord.READ
            var previous: SafGrantRecord.Previous? = null
            if (bool(raw[prefix + FIELD_PREVIOUS], false)) {
                previous =
                    SafGrantRecord.Previous(
                        text(raw[prefix + FIELD_PREV_APP]),
                        text(raw[prefix + FIELD_PREV_LABEL]),
                        bool(raw[prefix + FIELD_PREV_READ], false),
                        bool(raw[prefix + FIELD_PREV_WRITE], false),
                        acquired(raw[prefix + FIELD_PREV_ACQUIRED]),
                        maxOf(0L, number(raw[prefix + FIELD_PREV_TAKEN_AT], 0L)),
                    )
            }
            try {
                return SafGrantRecord(
                    id,
                    uri,
                    text(raw[prefix + FIELD_APP]),
                    text(raw[prefix + FIELD_LABEL]),
                    readable,
                    writable,
                    userWritable,
                    acquired,
                    state,
                    epoch,
                    takenAt,
                    pendingBefore,
                    pendingIntent,
                    previous,
                )
            } catch (invalid: IllegalArgumentException) {
                // 记录级不变量（id/URI/flags）在该版本里被破坏：整组保留，不激活。
                warnings.add("RECORD_INVALID:" + id)
                return null
            }
        }

        @JvmStatic
        private fun readIds(raw: Map<String, *>, warnings: MutableList<String>): List<String> {
            var indexed = strings(raw[PREF_IDS])
            if (indexed == null) {
                warnings.add("IDS_INVALID")
                indexed = scanIds(raw)
                if (indexed.isNotEmpty()) warnings.add("IDS_RECOVERED")
            }
            val ids = ArrayList<String>()
            for (id in TreeSet(indexed)) {
                if (SafGrantRecord.validId(id)) ids.add(id) else warnings.add("IDS_ENTRY_INVALID")
            }
            return ids
        }

        @JvmStatic
        private fun scanIds(raw: Map<String, *>): Set<String> {
            val ids = TreeSet<String>()
            for (key in raw.keys) {
                if (!key.startsWith(PREF_GRANT_PREFIX)) continue
                val rest = key.substring(PREF_GRANT_PREFIX.length)
                val separator = rest.indexOf('.')
                if (separator <= 0) continue
                val id = rest.substring(0, separator)
                if (SafGrantRecord.validId(id)) ids.add(id)
            }
            return ids
        }

        @JvmStatic
        private fun consumedGrantKey(key: String, acceptedIds: Set<String>): Boolean {
            if (!key.startsWith(PREF_GRANT_PREFIX)) return false
            val rest = key.substring(PREF_GRANT_PREFIX.length)
            val separator = rest.indexOf('.')
            if (separator <= 0) return false
            val id = rest.substring(0, separator)
            return acceptedIds.contains(id) && GRANT_FIELDS.contains(rest.substring(separator + 1))
        }

        @JvmStatic
        private fun state(value: String?): SafGrantRecord.State? {
            if (SafGrantRecord.State.ACTIVE.name == value) return SafGrantRecord.State.ACTIVE
            if (SafGrantRecord.State.PENDING.name == value) return SafGrantRecord.State.PENDING
            if (SafGrantRecord.State.RELEASING.name == value) return SafGrantRecord.State.RELEASING
            return null
        }

        @JvmStatic
        private fun text(value: Any?): String? {
            if (value is String) return value
            if (value is Number) return value.toString()
            return null
        }

        @JvmStatic
        private fun number(value: Any?, fallback: Long): Long {
            val parsed = optionalNumber(value)
            return parsed ?: fallback
        }

        @JvmStatic
        private fun optionalNumber(value: Any?): Long? {
            if (value is Number) return value.toLong()
            val text = text(value)
            if (text == null || text.codePoints().allMatch { Character.isWhitespace(it) })
                return null
            try {
                return java.lang.Long.parseLong(text.trim { it <= ' ' })
            } catch (invalid: NumberFormatException) {
                return null
            }
        }

        @JvmStatic
        private fun bool(value: Any?, fallback: Boolean): Boolean {
            val text = text(value)
            if ("1" == text || "true".equals(text, ignoreCase = true)) return true
            if ("0" == text || "false".equals(text, ignoreCase = true)) return false
            return fallback
        }

        @JvmStatic
        private fun acquired(value: Any?): Int {
            val rawFlags = text(value) ?: return 0
            if (rawFlags.all { it == 'r' || it == 'w' || it == ' ' }) {
                var flags = 0
                if (rawFlags.indexOf('r') >= 0) flags = flags or SafGrantRecord.READ
                if (rawFlags.indexOf('w') >= 0) flags = flags or SafGrantRecord.WRITE
                return flags
            }
            return mask(number(value, 0L))
        }

        @JvmStatic
        private fun mask(value: Long): Int {
            return (value and (SafGrantRecord.READ or SafGrantRecord.WRITE).toLong()).toInt()
        }

        @JvmStatic
        private fun flags(flags: Int): String {
            val out = StringBuilder(2)
            if ((flags and SafGrantRecord.READ) != 0) out.append('r')
            if ((flags and SafGrantRecord.WRITE) != 0) out.append('w')
            return out.toString()
        }

        @JvmStatic
        private fun strings(value: Any?): Set<String>? {
            if (value !is Set<*>) return null
            val result = LinkedHashSet<String>()
            for (item in value) {
                if (item !is String) return null
                result.add(item)
            }
            return result
        }
    }
}
