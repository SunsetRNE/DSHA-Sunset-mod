package com.deepseekharness.app.saf

import android.content.Context
import android.content.SharedPreferences
import com.deepseekharness.app.transfer.DownloadTask
import com.deepseekharness.app.transfer.TransferArtifact
import java.util.LinkedHashSet
import java.util.UUID

/** 有界的本机传输记录；不保存 URI、路径、能力句柄或内容。 */
class SafTransferHistory
internal constructor(preferences: SharedPreferences?, active: MutableSet<String?>?) {
    private val preferences = preferences
    private val active = active

    class Record
    private constructor(
        @JvmField val id: String,
        @JvmField val name: String?,
        @JvmField val importing: Boolean,
        @JvmField val time: Long,
        @JvmField val bytes: Long,
        @JvmField val status: DownloadTask.Status,
        @JvmField val code: String,
    ) {
        companion object {
            @JvmSynthetic
            internal fun create(
                id: String,
                name: String?,
                importing: Boolean,
                time: Long,
                bytes: Long,
                status: DownloadTask.Status,
                code: String,
            ): Record = Record(id, name, importing, time, bytes, status, code)
        }
    }

    /** 写记录失败时不开始传输；活跃项不被轮换。 */
    fun begin(name: String?, importing: Boolean, time: Long): String {
        if (!TransferArtifact.safeName(name)) throw IllegalArgumentException("BAD_NAME")
        synchronized(LOCK) {
            val records = listLocked()
            if (records.size >= MAX_RECORDS) {
                var oldest: Record? = null
                for (index in records.size - 1 downTo 0) {
                    if (!active!!.contains(records[index].id)) {
                        oldest = records.removeAt(index)
                        break
                    }
                }
                if (oldest == null) throw IllegalStateException("HISTORY_FULL")
            }
            val id = UUID.randomUUID().toString()
            records.add(
                Record.create(
                    id,
                    name,
                    importing,
                    maxOf(0, time),
                    0,
                    DownloadTask.Status.DOWNLOADING,
                    "",
                )
            )
            if (!saveLocked(records)) throw IllegalStateException("HISTORY_UNAVAILABLE")
            active!!.add(id)
            return id
        }
    }

    fun finish(id: String?, bytes: Long, code: String?): Boolean {
        synchronized(LOCK) {
            val records = listLocked()
            var found = false
            for (index in records.indices) {
                val record = records[index]
                if (record.id != id) continue
                val safeCode =
                    if (code != null && code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) code
                    else "IO_ERROR"
                val ok = safeCode == "OK"
                records[index] =
                    Record.create(
                        record.id,
                        record.name,
                        record.importing,
                        record.time,
                        if (ok) maxOf(0, bytes) else 0,
                        if (ok) DownloadTask.Status.COMPLETE else DownloadTask.Status.FAILED,
                        if (ok) "" else safeCode,
                    )
                found = true
                break
            }
            val saved = found && saveLocked(records)
            active!!.remove(id)
            return saved
        }
    }

    fun list(): List<Record> = synchronized(LOCK) { java.util.List.copyOf(listLocked()) }

    private fun listLocked(): MutableList<Record> {
        var records: MutableList<Record> = ArrayList()
        val ids =
            try {
                preferences!!.getStringSet("records", java.util.Set.of())
            } catch (bad: RuntimeException) {
                return records
            }
        for (id in ids!!) {
            if (id == null || !id.matches(Regex("[0-9a-f-]{36}"))) continue
            try {
                val name = preferences!!.getString("$id:name", "")
                if (!TransferArtifact.safeName(name)) continue
                var status =
                    DownloadTask.Status.valueOf(preferences.getString("$id:status", "FAILED")!!)
                var code = preferences.getString("$id:code", "")!!
                if (
                    status != DownloadTask.Status.COMPLETE && status != DownloadTask.Status.FAILED
                ) {
                    if (active!!.contains(id)) status = DownloadTask.Status.DOWNLOADING
                    else {
                        status = DownloadTask.Status.FAILED
                        code = "INTERRUPTED"
                    }
                }
                if (code.isNotEmpty() && !code.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
                    code = "IO_ERROR"
                records.add(
                    Record.create(
                        id,
                        name,
                        preferences.getBoolean("$id:import", true),
                        maxOf(0, preferences.getLong("$id:time", 0)),
                        maxOf(0, preferences.getLong("$id:bytes", 0)),
                        status,
                        code,
                    )
                )
            } catch (bad: RuntimeException) {
                // 损坏记录跳过，不把外部字符串当错误文案展示。
            }
        }
        records.sortWith(compareByDescending<Record> { it.time }.thenBy { it.id })
        if (records.size > MAX_RECORDS) records = ArrayList(records.subList(0, MAX_RECORDS))
        return records
    }

    private fun saveLocked(records: List<Record>): Boolean {
        return try {
            val editor = preferences!!.edit()
            val next = LinkedHashSet<String>()
            for (record in records) {
                next.add(record.id)
                editor
                    .putString(record.id + ":name", record.name)
                    .putBoolean(record.id + ":import", record.importing)
                    .putLong(record.id + ":time", record.time)
                    .putLong(record.id + ":bytes", record.bytes)
                    .putString(record.id + ":status", record.status.name)
                    .putString(record.id + ":code", record.code)
            }
            for (previous in preferences.getStringSet("records", java.util.Set.of())!!) {
                if (next.contains(previous)) continue
                for (field in
                    listOf(":name", ":import", ":time", ":bytes", ":status", ":code")) editor
                    .remove(previous + field)
            }
            editor.putStringSet("records", next).commit()
        } catch (unavailable: RuntimeException) {
            false
        }
    }

    companion object {
        const val MAX_RECORDS = 40
        private val LOCK = Any()
        private val ACTIVE: MutableSet<String?> = HashSet()

        @JvmStatic
        fun open(context: Context?): SafTransferHistory =
            SafTransferHistory(
                context!!.getSharedPreferences("saf_workbench_transfers", Context.MODE_PRIVATE),
                ACTIVE,
            )
    }
}
