package com.deepseekharness.app.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.UriPermission
import android.net.Uri
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 跨应用文件访问的授权簿。
 *
 * 系统 [ContentResolver.getPersistedUriPermissions] 是唯一真相；本地账本（A2 slice 2 起为独立命名空间
 * [SafLedgerStore.NAMESPACE]）记录本功能添加的 tree、助手写入偏好、本功能新拿到的读/写位及应用名/目录名缓存。 系统持久 URI
 * 授权不区分功能，撤销时只释放本功能新拿到的位，保留其它功能原有授权。
 *
 * take 前先落 PENDING，成功登记后转 ACTIVE。进程在 take 与 remember 之间被杀时，下次访问按原有位释放新增位， 重新授权失败则恢复旧 ACTIVE。释放失败留下
 * RELEASING，后续访问重试。
 *
 * 首次访问只读导入历史 saf_grants；保存失败时本次调用回退旧路径。初始化以 schema 标记，避免移除全部授权后重复导入。
 *
 * 锁内只做 prefs/系统券快照，不查询目标 Provider。label 在 add 时缓存，列表缺失时在锁外以 CancellationSignal 和 15 秒截止补查，避免慢
 * Provider 持锁拖住桥请求。
 */
class SafGrants private constructor() {
    /** 已授权目录；valid=false 表示系统里的授权已经没了。构造器在 Kotlin 中仅模块内可见。 */
    class Grant
    internal constructor(
        @JvmField val id: String?,
        @JvmField val uri: Uri?,
        @JvmField val app: String?,
        @JvmField val label: String?,
        /** 用户允许助手写入，且系统确有写权限。 */
        @JvmField val writable: Boolean,
        /** 系统确有写权限，与用户偏好无关。 */
        @JvmField val canWrite: Boolean,
        /** 系统确有读权限。 */
        @JvmField val readable: Boolean,
        /** 系统里读或写至少还剩一个。 */
        @JvmField val valid: Boolean,
    ) {
        /** 应用 · 目录；查不到时退回 URI 尾段。 */
        fun summary(): String? {
            val name = if (label == null || label.isEmpty()) uri!!.lastPathSegment else label
            return if (app == null || app.isEmpty()) name else app + " · " + name
        }
    }

    /** 申请持久授权的结局；added=true 时已登记且 failure 为空。构造器在 Kotlin 中仅模块内可见。 */
    class AddResult
    internal constructor(
        @JvmField val added: Boolean,
        @JvmField val readable: Boolean,
        @JvmField val writable: Boolean,
        @JvmField val failure: String?,
    ) {
        companion object {
            /** 原包内静态工厂映射为模块可见；JVM 保留原名供 Java 调用。 */
            @JvmStatic
            @JvmName("failed")
            internal fun failed(failure: String?): AddResult {
                return AddResult(false, false, false, failure)
            }
        }
    }

    /** 桥接查找结果：区分没有授权与系统授权状态不可读。构造器在 Kotlin 中仅模块内可见。 */
    class Lookup internal constructor(@JvmField val grant: Grant?, @JvmField val failure: String?)

    companion object {
        /** 授权数量上限。 */
        const val MAX_TREES = SafCapabilityLimits.MAX_TREES

        private const val PREFS = "saf_grants"
        private const val KEY_URIS = "uris"
        private const val KEY_WRITABLE = "writable:"

        /** 本功能为这个 tree 新拿到的位（r/w/rw）；Kotlin 中仅模块内可见，JVM 字段名保持。 */
        internal const val KEY_ACQUIRED = "acquired:"

        /** 授权时缓存的应用名/目录名。 */
        private const val KEY_APP = "app:"

        internal const val KEY_LABEL = "label:"

        /** take 之前写入、remember 成功后删除。 */
        internal const val KEY_PENDING = "pending"

        /** take 前已有位与本次可能拿到的位，只用于释放新增部分。 */
        internal const val KEY_PENDING_BEFORE = "pending-before:"

        internal const val KEY_PENDING_INTENT = "pending-intent:"
        internal const val READ = 1
        internal const val WRITE = 2

        /** 原包内测试入口在 Kotlin 中仅模块内可见；保持真正的可变静态字段。 */
        @JvmField internal var labelDeadlineMs: Long = SafTree.DEADLINE_MS

        private val LOCK = Any()

        /** 稳定短 id：授权 URI 的 SHA-256 摘要前 12 位。 */
        @JvmStatic
        fun id(uri: Uri?): String {
            if (uri == null) return ""
            try {
                val hash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(uri.toString().toByteArray(StandardCharsets.UTF_8))
                val out = StringBuilder(12)
                for (i in 0 until 6) out.append(String.format(Locale.ROOT, "%02x", hash[i]))
                return out.toString()
            } catch (unavailable: Exception) {
                return String.format(Locale.ROOT, "%08x", uri.toString().hashCode())
            }
        }

        /** 界面列表：允许在锁外为缺 label 的条目做一次带截止的补查。 */
        @JvmStatic
        fun list(context: Context?): List<Grant> {
            return listInternal(context, true)
        }

        /** 桥接列表只读缓存，绝不查询目标 Provider。 */
        @JvmStatic
        fun listLight(context: Context?): List<Grant> {
            return listInternal(context, false)
        }

        // ---------------------------------------------------------------- 账本模式（A2 slice 2）

        /** 模式、记录代次与系统位在同一临界区取快照；Provider 补查始终在锁外。 */
        private class ListSnapshot(
            val ledger: SafGrantLedger?,
            val legacyGeneration: Long,
            val grants: List<Grant>,
        )

        private var legacyGeneration = 0L

        @JvmStatic
        private fun listInternal(context: Context?, resolveMissingLabels: Boolean): List<Grant> {
            val snapshot = synchronized(LOCK) { listSnapshotLocked(context) }
            var queried = false
            if (resolveMissingLabels) {
                for (grant in snapshot.grants) {
                    if (!grant.app.isNullOrEmpty() && !grant.label.isNullOrEmpty()) continue
                    queried = true
                    val uri = grant.uri!!
                    cacheMetadata(
                        context,
                        uri.toString(),
                        snapshot.ledger?.findByUri(uri.toString())?.epoch,
                        snapshot.legacyGeneration,
                        appLabel(context, uri),
                        documentLabel(context, uri),
                    )
                }
            }
            // 慢查询期间可能已迁移/撤销/重授；整体重取快照，不拼接旧系统位与新偏好。
            val grants =
                ArrayList(
                    if (queried) synchronized(LOCK) { listSnapshotLocked(context).grants }
                    else snapshot.grants
                )
            grants.sortWith(compareBy { it.summary()!!.lowercase(Locale.ROOT) })
            return grants
        }

        @JvmStatic
        private fun listSnapshotLocked(context: Context?): ListSnapshot {
            if (!ledgerReadyLocked(context)) {
                val grants = legacyListLocked(context)
                return ListSnapshot(null, legacyGeneration, grants)
            }
            cleanupLedgerLocked(context)
            val store = ledgerStore(context)
            val ledger = store.load()
            val persisted = persisted(context)
            val grants = ArrayList<Grant>()
            var updated = ledger
            for (record in ledger.records()) {
                if (record.state != SafGrantRecord.State.ACTIVE) continue
                val uri = safeParseUri(record.uri) ?: continue
                val flags = if (persisted == null) 0 else flagsOf(persisted, uri)
                val refreshed = updated.refreshSystemFlags(record.uri, flags)
                if (refreshed.accepted()) updated = refreshed.ledger
                grants.add(grantFromRecord(updated.findByUri(record.uri)!!, flags))
            }
            if (updated !== ledger) store.save(updated)
            return ListSnapshot(updated, legacyGeneration, grants)
        }

        /** 轻量查找：不查应用名与目录名，只验系统券。 */
        @JvmStatic
        fun lookup(context: Context?, id: String?): Lookup {
            if (id == null || id.isEmpty()) return Lookup(null, "NO_GRANT")
            synchronized(LOCK) {
                if (!ledgerReadyLocked(context)) return legacyLookup(context, id)
                cleanupLedgerLocked(context)
                val record = ledgerStore(context).load().findById(id)
                if (record == null || record.state != SafGrantRecord.State.ACTIVE) {
                    return Lookup(null, "NO_GRANT")
                }
                val uri = safeParseUri(record.uri)
                if (uri == null || !documentsTree(uri)) return Lookup(null, "NO_GRANT")
                val persisted = persisted(context)
                if (persisted == null) return Lookup(null, "PERMISSION_STATE_UNAVAILABLE")
                return Lookup(grantFromRecord(record, flagsOf(persisted, uri)), null)
            }
        }

        /** 取持久授权并登记。只把本次新拿到的位记成本功能所有，满额、拒绝与中途死亡时只释放这部分， 保留备份等其它功能对同一 URI 已有的授权。 */
        @JvmStatic
        fun add(context: Context?, tree: Uri?, wantWrite: Boolean): AddResult {
            if (!documentsTree(tree)) return AddResult.failed("NOT_TREE")
            if (ownProvider(context, tree)) return AddResult.failed("OWN_PROVIDER")
            val result: AddResult
            val epoch: Long?
            val generation: Long
            synchronized(LOCK) {
                val ledgerMode = ledgerReadyLocked(context)
                result =
                    if (ledgerMode) ledgerAddLocked(context, tree, wantWrite)
                    else legacyAddLocked(context, tree, wantWrite)
                epoch =
                    if (ledgerMode && result.added)
                        ledgerStore(context).load().findByUri(tree.toString())?.epoch
                    else null
                generation = legacyGeneration
            }
            if (result.added) {
                cacheMetadata(
                    context,
                    tree.toString(),
                    epoch,
                    generation,
                    appLabel(context, tree),
                    documentLabel(context, tree),
                )
            }
            return result
        }

        @JvmStatic
        private fun ledgerAddLocked(context: Context?, tree: Uri?, wantWrite: Boolean): AddResult {
            synchronized(LOCK) {
                cleanupLedgerLocked(context)
                val store = ledgerStore(context)
                var ledger = store.load()
                val value = tree.toString()
                val resolver = context!!.contentResolver
                val persisted = persisted(context)
                if (persisted == null) return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                if (store.hasPendingRestriction(value)) return AddResult.failed("STATE_UNAVAILABLE")
                var existing = ledger.findByUri(value)
                // 清理未完成时不得用新的 before/intent 覆盖原 PENDING 的权限归属证据。
                if (existing?.state == SafGrantRecord.State.PENDING)
                    return AddResult.failed("STATE_UNAVAILABLE")
                if (existing != null && existing.state == SafGrantRecord.State.RELEASING) {
                    // 系统已没有可释放位时完成撤销后重加；仍有位残留则失败关闭，等 cleanup 重试。
                    val held = flagsOf(persisted, tree!!)
                    if ((existing.acquired and held) != 0)
                        return AddResult.failed("STATE_UNAVAILABLE")
                    val finished = ledger.finishRelease(value).ledger
                    if (!store.save(finished)) return AddResult.failed("STATE_UNAVAILABLE")
                    ledger = finished
                    existing = null
                }
                if (existing == null && ledger.size() >= MAX_TREES) return AddResult.failed("LIMIT")
                val before = flagsOf(persisted, tree!!)
                val intent = READ or if (wantWrite) WRITE else 0
                val now = System.currentTimeMillis()
                // take 前先把原有位与可能新增位落盘；保存失败不能继续 take。
                val begun =
                    ledger.beginTake(
                        value,
                        if (existing == null) "" else existing.app,
                        if (existing == null) "" else existing.label,
                        before,
                        intent,
                        now,
                    )
                if (!begun.accepted()) return AddResult.failed(begun.failure)
                if (!store.save(begun.ledger)) return AddResult.failed("STATE_UNAVAILABLE")
                var taken =
                    takePersistable(resolver, tree, wantWrite)
                        ?: return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                if (taken == 0) {
                    val nowList = persistedList(resolver)
                    if (nowList == null) return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                    taken = flagsOf(nowList, tree)
                }
                if (taken == 0) {
                    store.save(begun.ledger.abortTake(value).ledger)
                    return AddResult.failed("NO_PERMISSION")
                }
                val acquired =
                    (taken and before.inv()) or if (existing == null) 0 else existing.acquired
                if ((taken and READ) == 0) {
                    // 路径解析依赖子项 query；只写授权不能 push/mkdir/rm，添加时直接拒绝。
                    val rollback = taken and before.inv() and intent
                    if (releaseFlags(resolver, tree, rollback)) {
                        store.save(begun.ledger.abortTake(value).ledger)
                        return AddResult.failed("WRITE_ONLY")
                    }
                    // 释放失败保留 PENDING，下次访问继续重试。
                    return AddResult.failed("WRITE_ONLY_PENDING")
                }
                val writable = (taken and WRITE) != 0
                val committed = begun.ledger.commitTake(value, taken, acquired, writable, now)
                if (!committed.accepted()) {
                    store.save(begun.ledger.abortTake(value).ledger)
                    return AddResult.failed(committed.failure)
                }
                if (!store.save(committed.ledger)) {
                    // PENDING 已在 take 前落盘，下次清理释放新增位并恢复旧记录。
                    return AddResult.failed("STATE_UNAVAILABLE")
                }
                return AddResult(true, (taken and READ) != 0, writable, null)
            }
        }

        /** 只退本功能新增位；释放失败进入不可用的 RELEASING，下次访问重试。 */
        @JvmStatic
        fun forget(context: Context?, tree: Uri?) {
            if (tree == null) return
            synchronized(LOCK) {
                if (!ledgerReadyLocked(context)) {
                    legacyForget(context, tree)
                    return
                }
                cleanupLedgerLocked(context)
                val store = ledgerStore(context)
                var ledger = store.load()
                val value = tree.toString()
                var record = ledger.findByUri(value) ?: return
                if (record.state == SafGrantRecord.State.PENDING) {
                    // 先按 before/intent 释放新增位，再 abort；不丢清理信息或用户移除意图（N2）。
                    val pendingState = persisted(context) ?: return
                    val rollback =
                        flagsOf(pendingState, tree) and
                            record.pendingBefore.inv() and
                            record.pendingIntent
                    if (rollback != 0 && !releaseFlags(context!!.contentResolver, tree, rollback))
                        return
                    val aborted = ledger.abortTake(value).ledger
                    if (!store.save(aborted)) return
                    ledger = aborted
                    record = ledger.findByUri(value) ?: return
                    if (record.state != SafGrantRecord.State.ACTIVE) return
                    // 失败重授恢复了旧 ACTIVE；用户仍要移除，继续正常 forget。
                }
                // 先登记当前 tree/epoch 的撤销意图，再释放或保存；commit(false) 不能复活旧 ACTIVE。
                val restriction = store.beginRevocation(record)
                val persisted = persisted(context)
                if (persisted == null) {
                    // 未知不能当作已释放，围栏和 RELEASING 意图一起保留，等实际系统位可读再重试。
                    store.save(ledger.beginRelease(value).ledger)
                    return
                }
                val remaining = record.acquired and flagsOf(persisted, tree)
                val released =
                    remaining == 0 || releaseFlags(context!!.contentResolver, tree, remaining)
                val desired =
                    if (released) ledger.forget(value).ledger else ledger.beginRelease(value).ledger
                if (released) store.saveAfterRelease(desired, listOf(record to restriction))
                else store.save(desired)
            }
        }

        @JvmStatic
        fun setWritable(context: Context?, tree: Uri?, writable: Boolean) {
            if (tree == null) return
            synchronized(LOCK) {
                if (!ledgerReadyLocked(context)) {
                    legacyGeneration++
                    legacySetWritable(context, tree, writable)
                    return
                }
                val store = ledgerStore(context)
                store.retryWriteDenials()
                val ledger = store.load()
                val record = ledger.findByUri(tree.toString()) ?: return
                if (record.state == SafGrantRecord.State.RELEASING) return
                // 未完成关闭前不接受新的开启；无关保存也不能撤销用户的缩权意图。
                if (writable && store.hasPendingRestriction(record.uri)) return
                val restriction = if (writable) null else store.denyWrite(record)
                val change = ledger.setUserWritable(tree.toString(), writable)
                if (change.accepted() && store.save(change.ledger) && !writable)
                    store.finishWriteDenial(record, restriction)
            }
        }

        /** 清理 take/remember 间死亡或释放失败留下的记录；旧命名空间只读迁移。 */
        @JvmStatic
        fun cleanupPending(context: Context?) {
            synchronized(LOCK) {
                if (ledgerReadyLocked(context)) cleanupLedgerLocked(context)
                else legacyCleanupPendingLocked(context)
            }
        }

        /** 工作台沿用 lookup 的授权判据，系统位只用于本次校验，不写新的凭据字段。Kotlin 中仅模块内可见。 */
        @JvmStatic
        @JvmName("capabilitySnapshot")
        @Throws(SafTree.Failure::class)
        internal fun capabilitySnapshot(context: Context?, id: String?): SafGrantLedger {
            synchronized(LOCK) {
                if (!ledgerReadyLocked(context)) {
                    val lookup = legacyLookup(context, id)
                    throw SafTree.Failure(lookup.failure ?: "STATE_UNAVAILABLE", null)
                }
                cleanupLedgerLocked(context)
                val ledger = ledgerStore(context).load()
                val record = ledger.findById(id)
                if (record == null || record.state != SafGrantRecord.State.ACTIVE) {
                    throw SafTree.Failure("NO_GRANT", null)
                }
                val uri = safeParseUri(record.uri)
                if (uri == null || !documentsTree(uri) || ownProvider(context, uri)) {
                    throw SafTree.Failure("NO_GRANT", null)
                }
                val persisted =
                    persisted(context)
                        ?: throw SafTree.Failure("PERMISSION_STATE_UNAVAILABLE", null)
                return ledger.refreshSystemFlags(record.uri, flagsOf(persisted, uri)).ledger
            }
        }

        @JvmStatic
        private fun ledgerStore(context: Context?): SafLedgerStore {
            return SafLedgerStore(
                context!!.getSharedPreferences(SafLedgerStore.NAMESPACE, Context.MODE_PRIVATE)
            )
        }

        /** 已初始化则直接用，否则只读导入旧 saf_grants；失败回退旧路径。 */
        @JvmStatic
        private fun ledgerReadyLocked(context: Context?): Boolean {
            val store = ledgerStore(context)
            if (store.initialized()) return true
            // commit 的同步监听器可能已重入并完成迁移；失败回退前核对同一存储的已确认状态。
            return store.save(migrateLegacyLocked(context)) || store.initialized()
        }

        /** 历史命名空间只读导入；超过上限的尾巴不迁移，旧数据保持原样。 */
        @JvmStatic
        private fun migrateLegacyLocked(context: Context?): SafGrantLedger {
            val legacy = prefs(context)
            val saved = LinkedHashSet(legacy.getStringSet(KEY_URIS, HashSet<String>())!!)
            val pending = LinkedHashSet(legacy.getStringSet(KEY_PENDING, HashSet<String>())!!)
            val persisted = persisted(context)
            val now = System.currentTimeMillis()
            val records = LinkedHashMap<String, SafGrantRecord>()
            var epoch = 1L
            for (value in saved) {
                val uri = safeParseUri(value)
                if (uri == null || !documentsTree(uri) || ownProvider(context, uri)) continue
                if (records.size >= MAX_TREES) break
                val flags = if (persisted == null) 0 else flagsOf(persisted, uri)
                records[value] =
                    SafGrantRecord(
                        id(uri),
                        value,
                        legacy.getString(KEY_APP + value, ""),
                        legacy.getString(KEY_LABEL + value, ""),
                        (flags and READ) != 0,
                        (flags and WRITE) != 0,
                        legacy.getBoolean(KEY_WRITABLE + value, true),
                        parse(legacy.getString(KEY_ACQUIRED + value, "")),
                        SafGrantRecord.State.ACTIVE,
                        epoch++,
                        now,
                        0,
                        READ,
                        null,
                    )
            }
            for (value in pending) {
                val uri = safeParseUri(value)
                if (uri == null || !documentsTree(uri) || ownProvider(context, uri)) continue
                val base = records[value]
                if (base == null && records.size >= MAX_TREES) continue
                var before = number(legacy.getString(KEY_PENDING_BEFORE + value, "0"), 0)
                var intent = number(legacy.getString(KEY_PENDING_INTENT + value, "1"), READ)
                before = before and (READ or WRITE)
                intent = intent and (READ or WRITE)
                if (intent == 0) intent = READ
                records[value] =
                    SafGrantRecord(
                        id(uri),
                        value,
                        if (base == null) "" else base.app,
                        if (base == null) "" else base.label,
                        false,
                        false,
                        base == null || base.userWritable,
                        0,
                        SafGrantRecord.State.PENDING,
                        epoch++,
                        now,
                        before,
                        intent,
                        if (base == null) null
                        else
                            SafGrantRecord.Previous(
                                base.app,
                                base.label,
                                base.readable,
                                base.writable,
                                base.acquired,
                                base.takenAt,
                            ),
                    )
            }
            return SafGrantLedger.of(ArrayList(records.values), 1, epoch)
        }

        /** PENDING 释放新增位后恢复/删除；RELEASING 重试成功后删除，释放失败保留到下次。 */
        @JvmStatic
        private fun cleanupLedgerLocked(context: Context?) {
            val store = ledgerStore(context)
            store.retryWriteDenials()
            var ledger = store.load()
            val persisted = persisted(context) ?: return
            val resolver = context!!.contentResolver
            var changed = false
            val releasedRestrictions = ArrayList<Pair<SafGrantRecord, Long?>>()
            for (record in ledger.records()) {
                val uri = safeParseUri(record.uri) ?: continue
                if (record.state == SafGrantRecord.State.PENDING) {
                    val release =
                        flagsOf(persisted, uri) and
                            record.pendingBefore.inv() and
                            record.pendingIntent
                    if (!releaseFlags(resolver, uri, release)) continue
                    ledger = ledger.abortTake(record.uri).ledger
                    changed = true
                } else if (record.state == SafGrantRecord.State.RELEASING) {
                    // 只重试本功能位与系统仍持有位的交集；交集为空直接完成撤销（P1）。
                    val restriction = store.revocationToken(record)
                    val remaining = record.acquired and flagsOf(persisted, uri)
                    if (remaining != 0 && !releaseFlags(resolver, uri, remaining)) continue
                    ledger = ledger.finishRelease(record.uri).ledger
                    releasedRestrictions.add(record to restriction)
                    changed = true
                }
            }
            if (changed) store.saveAfterRelease(ledger, releasedRestrictions)
        }

        @JvmStatic
        private fun grantFromRecord(record: SafGrantRecord, flags: Int): Grant {
            val readable = (flags and READ) != 0
            val canWrite = (flags and WRITE) != 0
            return Grant(
                record.id,
                Uri.parse(record.uri!!),
                record.app,
                record.label,
                canWrite && record.userWritable,
                canWrite,
                readable,
                canWrite || readable,
            )
        }

        @JvmStatic
        private fun cacheMetadata(
            context: Context?,
            value: String,
            expectedEpoch: Long?,
            expectedLegacyGeneration: Long,
            app: String?,
            label: String?,
        ) {
            if (app.isNullOrEmpty() && label.isNullOrEmpty()) return
            synchronized(LOCK) {
                val store = ledgerStore(context)
                if (expectedEpoch == null) {
                    // 未迁移的同一旧代次才可补缓存；迁移后绝不回写已退休命名空间。
                    if (store.initialized() || legacyGeneration != expectedLegacyGeneration) return
                    cacheLabels(prefs(context), Uri.parse(value), app, label)
                    return
                }
                if (!store.initialized()) return
                val ledger = store.load()
                val record = ledger.findByUri(value) ?: return
                if (record.state != SafGrantRecord.State.ACTIVE || record.epoch != expectedEpoch)
                    return
                val change = ledger.setMetadata(value, app, label)
                if (change.accepted()) store.save(change.ledger)
            }
        }

        @JvmStatic
        private fun safeParseUri(value: String?): Uri? {
            if (value == null) return null
            try {
                return Uri.parse(value)
            } catch (bad: Throwable) {
                return null
            }
        }

        @JvmStatic
        private fun legacyListLocked(context: Context?): List<Grant> {
            val persisted: List<UriPermission>?
            val saved: Set<String>
            val prefs = prefs(context)
            synchronized(LOCK) {
                legacyCleanupPendingLocked(context)
                saved =
                    HashSet(prefs.getStringSet(KEY_URIS, HashSet<String>())!!).apply {
                        removeAll(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
                    }
                persisted = persisted(context)
            }
            val grants = ArrayList<Grant>()
            for (value in saved) {
                val uri: Uri
                try {
                    uri = Uri.parse(value)
                } catch (bad: Throwable) {
                    continue
                }
                if (!documentsTree(uri)) continue
                val flags = if (persisted == null) 0 else flagsOf(persisted, uri)
                val app = prefs.getString(KEY_APP + uri, "")
                val label = prefs.getString(KEY_LABEL + uri, "")
                grants.add(grant(prefs, uri, flags, app, label))
            }
            grants.sortWith(compareBy { it.summary()!!.lowercase(Locale.ROOT) })
            return grants
        }

        @JvmStatic
        fun find(context: Context?, id: String?): Grant? {
            if (id == null || id.isEmpty()) return null
            for (grant in list(context)) if (grant.id!! == id) return grant
            return null
        }

        /** 轻量旧路径只验系统券，不查询应用名/目录名。 */
        @JvmStatic
        private fun legacyLookup(context: Context?, id: String?): Lookup {
            if (id == null || id.isEmpty()) return Lookup(null, "NO_GRANT")
            val prefs = prefs(context)
            val persisted: List<UriPermission>?
            val saved: Set<String>
            synchronized(LOCK) {
                legacyCleanupPendingLocked(context)
                saved =
                    HashSet(prefs.getStringSet(KEY_URIS, HashSet<String>())!!).apply {
                        removeAll(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
                    }
                persisted = persisted(context)
            }
            if (persisted == null) return Lookup(null, "PERMISSION_STATE_UNAVAILABLE")
            if (saved.isEmpty()) return Lookup(null, "NO_GRANT")
            for (value in saved) {
                val uri: Uri
                try {
                    uri = Uri.parse(value)
                } catch (bad: Throwable) {
                    continue
                }
                if (!documentsTree(uri) || id(uri) != id) continue
                return Lookup(
                    grant(
                        prefs,
                        uri,
                        flagsOf(persisted, uri),
                        prefs.getString(KEY_APP + uri, ""),
                        prefs.getString(KEY_LABEL + uri, ""),
                    ),
                    null,
                )
            }
            return Lookup(null, "NO_GRANT")
        }

        /** 兼容旧调用，只取授权本身；需要区分系统状态时用 lookup。 */
        @JvmStatic
        fun pick(context: Context?, id: String?): Grant? {
            val lookup = lookup(context, id)
            return lookup.grant
        }

        /** 旧路径取持久授权，只记录和释放本功能新增的位。 */
        @JvmStatic
        private fun legacyAddLocked(context: Context?, tree: Uri?, wantWrite: Boolean): AddResult {
            if (!documentsTree(tree)) return AddResult.failed("NOT_TREE")
            if (ownProvider(context, tree)) return AddResult.failed("OWN_PROVIDER")
            val result: AddResult
            synchronized(LOCK) {
                legacyGeneration++
                legacyCleanupPendingLocked(context)
                val prefs = prefs(context)
                if (prefs.getStringSet(KEY_PENDING, HashSet<String>())!!.contains(tree.toString()))
                    return AddResult.failed("STATE_UNAVAILABLE")
                val saved = HashSet(prefs.getStringSet(KEY_URIS, HashSet<String>())!!)
                if (!saved.contains(tree.toString()) && saved.size >= MAX_TREES) {
                    return AddResult.failed("LIMIT")
                }
                val resolver = context!!.contentResolver
                val persisted = persisted(context)
                if (persisted == null) return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                val before = flagsOf(persisted, tree!!)
                val intent = READ or if (wantWrite) WRITE else 0
                // take 前先把原有位与可能新增位落盘；保存失败不能继续 take。
                val pending = HashSet(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
                if (
                    !prefs
                        .edit()
                        .putStringSet(KEY_PENDING, with(pending, tree.toString()))
                        .putString(KEY_PENDING_BEFORE + tree, before.toString())
                        .putString(KEY_PENDING_INTENT + tree, intent.toString())
                        .commit()
                ) {
                    return AddResult.failed("STATE_UNAVAILABLE")
                }
                var taken =
                    takePersistable(resolver, tree, wantWrite)
                        ?: return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                if (taken == 0) {
                    val now = persistedList(resolver)
                    if (now == null) return AddResult.failed("PERMISSION_STATE_UNAVAILABLE")
                    taken = flagsOf(now, tree)
                }
                if (taken == 0) {
                    clearPendingLocked(prefs, tree)
                    return AddResult.failed("NO_PERMISSION")
                }
                val acquired =
                    (taken and before.inv()) or parse(prefs.getString(KEY_ACQUIRED + tree, ""))
                if ((taken and READ) == 0) {
                    val rollback = taken and before.inv() and intent
                    if (releaseFlags(resolver, tree, rollback)) {
                        clearPendingLocked(prefs, tree)
                        return AddResult.failed("WRITE_ONLY")
                    }
                    // 释放失败保留 pending 与归属记录，下次访问继续重试。
                    prefs
                        .edit()
                        .putString(KEY_ACQUIRED + tree, flags(rollback))
                        .putString(KEY_PENDING_BEFORE + tree, before.toString())
                        .putString(KEY_PENDING_INTENT + tree, intent.toString())
                        .commit()
                    return AddResult.failed("WRITE_ONLY_PENDING")
                }
                val writable = (taken and WRITE) != 0
                saved.add(tree.toString())
                val committed =
                    prefs
                        .edit()
                        .putStringSet(KEY_URIS, saved)
                        .putBoolean(KEY_WRITABLE + tree, writable)
                        .putString(KEY_ACQUIRED + tree, flags(acquired))
                        .putStringSet(KEY_PENDING, without(pending, tree.toString()))
                        .remove(KEY_PENDING_BEFORE + tree)
                        .remove(KEY_PENDING_INTENT + tree)
                        .commit()
                if (!committed) return AddResult.failed("STATE_UNAVAILABLE")
                result = AddResult(true, (taken and READ) != 0, writable, null)
            }
            return result
        }

        /** 旧路径撤销只释放新增位；失败保留 acquired 与 pending 等下次重试。 */
        @JvmStatic
        private fun legacyForget(context: Context?, tree: Uri?) {
            if (tree == null) return
            synchronized(LOCK) {
                legacyGeneration++
                legacyCleanupPendingLocked(context)
                val prefs = prefs(context)
                // 清理失败保留原 before/intent，不用另一次移除覆盖在途授权的归属事实。
                if (prefs.getStringSet(KEY_PENDING, HashSet<String>())!!.contains(tree.toString()))
                    return
                val acquired = parse(prefs.getString(KEY_ACQUIRED + tree, ""))
                val released = releaseFlags(context!!.contentResolver, tree, acquired)
                val saved = HashSet(prefs.getStringSet(KEY_URIS, HashSet<String>())!!)
                saved.remove(tree.toString())
                val pending = HashSet(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
                val editor =
                    prefs
                        .edit()
                        .putStringSet(KEY_URIS, saved)
                        .remove(KEY_WRITABLE + tree)
                        .remove(KEY_APP + tree)
                        .remove(KEY_LABEL + tree)
                if (!released && acquired != 0) {
                    editor
                        .putStringSet(KEY_PENDING, with(pending, tree.toString()))
                        .putString(KEY_PENDING_BEFORE + tree, "0")
                        .putString(KEY_PENDING_INTENT + tree, acquired.toString())
                } else {
                    editor
                        .remove(KEY_ACQUIRED + tree)
                        .putStringSet(KEY_PENDING, without(pending, tree.toString()))
                        .remove(KEY_PENDING_BEFORE + tree)
                        .remove(KEY_PENDING_INTENT + tree)
                }
                editor.commit()
            }
        }

        @JvmStatic
        private fun legacySetWritable(context: Context?, tree: Uri?, writable: Boolean) {
            if (tree == null) return
            prefs(context).edit().putBoolean(KEY_WRITABLE + tree, writable).apply()
        }

        /** DSHA 自己的 documents 根会形成自环，拒绝它。 */
        @JvmStatic
        fun ownProvider(context: Context?, tree: Uri?): Boolean {
            return tree != null && context!!.packageName + ".documents" == tree.authority
        }

        @JvmStatic
        fun documentsTree(uri: Uri?): Boolean {
            return uri != null && "content" == uri.scheme && DocumentsContractCompat.isTreeUri(uri)
        }

        // ---------------------------------------------------------------- 授权位与待确认记录

        /** 只释放本功能新拿到的位。组合释放失败后仍逐位重试，两个单独尝试都必须执行。Kotlin 中仅模块内可见。 */
        @JvmStatic
        @JvmName("releaseFlags")
        internal fun releaseFlags(resolver: ContentResolver?, tree: Uri?, flags: Int): Boolean {
            if (resolver == null || tree == null || flags == 0) return true
            if (strictRelease(resolver, tree, flags)) return true
            var released = true
            if ((flags and READ) != 0) released = released and strictRelease(resolver, tree, READ)
            if ((flags and WRITE) != 0) released = released and strictRelease(resolver, tree, WRITE)
            return released
        }

        @JvmStatic
        private fun strictRelease(resolver: ContentResolver, tree: Uri, flags: Int): Boolean {
            try {
                resolver.releasePersistableUriPermission(tree, intentFlags(flags))
                return true
            } catch (rejected: Throwable) {
                return false
            }
        }

        /** 依次尝试读写一起、只读、只写，返回 take 后系统实际持有的位。 */
        @JvmStatic
        private fun takePersistable(
            resolver: ContentResolver,
            tree: Uri,
            wantWrite: Boolean,
        ): Int? {
            val attempts =
                if (wantWrite) intArrayOf(READ or WRITE, READ, WRITE) else intArrayOf(READ)
            for (attempt in attempts) {
                try {
                    resolver.takePersistableUriPermission(tree, intentFlags(attempt))
                } catch (rejected: Throwable) {
                    continue
                }
                val persisted = persistedList(resolver)
                // 请求位不是系统事实；复核不可读时保留 PENDING，既不激活也不重放 take。
                return if (persisted == null) null else flagsOf(persisted, tree)
            }
            return 0
        }

        /** 旧路径只释放当前持有、take 前没有、且本次尝试可能拿到的位；失败保留记录。 */
        @JvmStatic
        private fun legacyCleanupPendingLocked(context: Context?) {
            val prefs = prefs(context)
            val pending = HashSet(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
            if (pending.isEmpty()) return
            legacyGeneration++
            val persisted = persisted(context) ?: return
            val resolver = context!!.contentResolver
            val saved = HashSet(prefs.getStringSet(KEY_URIS, HashSet<String>())!!)
            for (value in ArrayList(pending)) {
                val uri: Uri
                try {
                    uri = Uri.parse(value)
                } catch (bad: Throwable) {
                    pending.remove(value)
                    prefs.edit().putStringSet(KEY_PENDING, pending).commit()
                    continue
                }
                // 位掩码用十进制，不是 acquired 的 r/w 缩写。
                val before = number(prefs.getString(KEY_PENDING_BEFORE + uri, "0"), 0)
                val intent = number(prefs.getString(KEY_PENDING_INTENT + uri, "1"), READ)
                val release = flagsOf(persisted, uri) and before.inv() and intent
                if (!releaseFlags(resolver, uri, release)) continue
                pending.remove(value)
                val editor =
                    prefs
                        .edit()
                        .putStringSet(KEY_PENDING, pending)
                        .remove(KEY_PENDING_BEFORE + uri)
                        .remove(KEY_PENDING_INTENT + uri)
                if (!saved.contains(value)) editor.remove(KEY_ACQUIRED + uri)
                editor.commit()
            }
        }

        @JvmStatic
        private fun clearPendingLocked(prefs: SharedPreferences, tree: Uri) {
            val pending = HashSet(prefs.getStringSet(KEY_PENDING, HashSet<String>())!!)
            prefs
                .edit()
                .putStringSet(KEY_PENDING, without(pending, tree.toString()))
                .remove(KEY_PENDING_BEFORE + tree)
                .remove(KEY_PENDING_INTENT + tree)
                .commit()
        }

        @JvmStatic
        private fun with(values: Set<String>, value: String): Set<String> {
            val copy = HashSet(values)
            copy.add(value)
            return copy
        }

        @JvmStatic
        private fun without(values: Set<String>, value: String): Set<String> {
            val copy = HashSet(values)
            copy.remove(value)
            return copy
        }

        @JvmStatic
        private fun flags(flags: Int): String {
            val out = StringBuilder()
            if ((flags and READ) != 0) out.append('r')
            if ((flags and WRITE) != 0) out.append('w')
            return out.toString()
        }

        @JvmStatic
        private fun parse(flags: String?): Int {
            var value = 0
            if (flags != null) {
                if (flags.indexOf('r') >= 0) value = value or READ
                if (flags.indexOf('w') >= 0) value = value or WRITE
            }
            return value
        }

        /** pending 位掩码为十进制，坏数据按 fallback 处理。 */
        @JvmStatic
        private fun number(value: String?, fallback: Int): Int {
            try {
                return Integer.parseInt(value!!.trim { it <= ' ' })
            } catch (bad: Throwable) {
                return fallback
            }
        }

        @JvmStatic
        private fun intentFlags(flags: Int): Int {
            var value = 0
            if ((flags and READ) != 0) value = value or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if ((flags and WRITE) != 0) value = value or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            return value
        }

        @JvmStatic
        private fun flagsOf(persisted: List<UriPermission>, uri: Uri): Int {
            var flags = 0
            for (permission in persisted) {
                if (uri != permission.uri) continue
                if (permission.isReadPermission) flags = flags or READ
                if (permission.isWritePermission) flags = flags or WRITE
            }
            return flags
        }

        @JvmStatic
        private fun grant(
            prefs: SharedPreferences,
            uri: Uri,
            flags: Int,
            app: String?,
            label: String?,
        ): Grant {
            val canWrite = (flags and WRITE) != 0
            val readable = (flags and READ) != 0
            return Grant(
                id(uri),
                uri,
                app,
                label,
                canWrite && prefs.getBoolean(KEY_WRITABLE + uri, true),
                canWrite,
                readable,
                canWrite || readable,
            )
        }

        @JvmStatic
        private fun persisted(context: Context?): List<UriPermission>? {
            try {
                return context!!.contentResolver.persistedUriPermissions
            } catch (unavailable: Throwable) {
                // 系统状态不可读按未知处理，调用方不能据此释放其它功能授权。
                return null
            }
        }

        /** take 后复核失败时按未知返回 null。 */
        @JvmStatic
        private fun persistedList(resolver: ContentResolver): List<UriPermission>? {
            try {
                return resolver.persistedUriPermissions
            } catch (unavailable: Throwable) {
                return null
            }
        }

        @JvmStatic
        private fun prefs(context: Context?): SharedPreferences {
            return context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        @JvmStatic
        private fun cacheLabels(prefs: SharedPreferences, uri: Uri, app: String?, label: String?) {
            val editor = prefs.edit()
            var changed = false
            if (app != null && app.isNotEmpty()) {
                editor.putString(KEY_APP + uri, app)
                changed = true
            }
            if (label != null && label.isNotEmpty()) {
                editor.putString(KEY_LABEL + uri, label)
                changed = true
            }
            if (changed) editor.apply()
        }

        /** 解析 Provider 所属应用；只用于显示名，不改变授权范围。 */
        @JvmStatic
        private fun appLabel(context: Context?, uri: Uri?): String {
            try {
                val manager = context!!.packageManager
                val provider = manager.resolveContentProvider(uri!!.authority!!, 0)
                if (provider != null && provider.applicationInfo != null) {
                    return java.lang.String.valueOf(
                        manager.getApplicationLabel(provider.applicationInfo)
                    )
                }
                // resolveContentProvider 看不到无声明 Provider 时，退回按 action 查询。
                val documents = Intent("android.content.action.DOCUMENTS_PROVIDER")
                for (resolved in manager.queryIntentContentProviders(documents, 0)) {
                    if (
                        resolved.providerInfo != null &&
                            uri.authority!!.equals(resolved.providerInfo.authority) &&
                            resolved.providerInfo.applicationInfo != null
                    ) {
                        return java.lang.String.valueOf(
                            manager.getApplicationLabel(resolved.providerInfo.applicationInfo)
                        )
                    }
                }
            } catch (ignored: Throwable) {
                return ""
            }
            return ""
        }

        /**
         * 目录名查询带 CancellationSignal 与截止。完全忽略取消的 Provider 仍可能拖住调用线程， 因而只用于 add/list 后台线程，桥 listLight
         * 从不查询。Kotlin 中仅模块内可见，JVM 保留原名供 Java 调用。
         */
        @JvmStatic
        @JvmName("documentLabel")
        internal fun documentLabel(context: Context?, uri: Uri?): String? {
            val deadline = SafTree.newDeadline(labelDeadlineMs)
            try {
                val docId = DocumentsContract.getTreeDocumentId(uri!!)
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                context!!
                    .contentResolver
                    .query(
                        document,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                        deadline.signal,
                    )
                    .use { cursor ->
                        deadline.check()
                        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                            return cursor.getString(0)
                        }
                    }
            } catch (canceled: OperationCanceledException) {
                return ""
            } catch (ignored: Throwable) {
                // 目标应用不在或未启动时退回 URI 尾段。
            } finally {
                deadline.close()
            }
            return ""
        }
    }
}
