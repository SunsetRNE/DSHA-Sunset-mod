package com.deepseekharness.app.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 一条已授权 tree 上的只读/写入操作。
 *
 * 路径是相对授权根的普通路径（`/files/config.json`），逐段用 [DocumentsContract.buildChildDocumentsUriUsingTree] +
 * 显示名解析成文档 id， 因此不依赖各家 Provider 自己的 id 规则（MT 注入的 Provider、网盘、系统文档源都能用）。解析失败不猜、不跨根、不允许 `..`。
 *
 * 一次公开操作共用一个截止时间：内部 `resolve()` / `entry()` 接受外层 [Deadline]，不再各自计时（嵌套截止会互相清除 callback，等于没有截止）。 每个
 * deadline 有独立 token，结束时只移除自己的 callback，不会清掉别的操作。
 *
 * 目标 Provider 可能很慢、离线甚至故意不响应；`query/openFileDescriptor` 都带 [CancellationSignal]。
 * `createDocument`/`deleteDocument` 在框架层没有 signal 参数，无法中断，只能在返回后检查截止并映射为 TIMEOUT。
 */
class SafTree {
    /** 探测返回的一次性确认凭据。 */
    class PendingDelete
    internal constructor(@JvmField val token: String?, @JvmField val hint: String?)

    private class Pending(
        @JvmField val tree: String,
        @JvmField val path: String,
        @JvmField val docId: String?,
        @JvmField val size: Long,
        @JvmField val modified: Long,
        @JvmField val expiresAt: Long,
    )

    /** 失败原因；`code` 是给桥与界面共用的大写短码，别直接展示给用户。 */
    class Failure(@JvmField val code: String?, detail: String?) :
        Exception(if (detail.isNullOrEmpty()) code else "$code: $detail")

    class Entry
    internal constructor(
        @JvmField val docId: String?,
        @JvmField val name: String?,
        @JvmField val mime: String?,
        @JvmField val size: Long,
        @JvmField val modified: Long,
    ) {
        @JvmField val directory: Boolean = DocumentsContract.Document.MIME_TYPE_DIR == mime
    }

    /** 一页目录：列表 + 是否因超过 limit 被截断。 */
    class Page
    internal constructor(
        @JvmField val entries: MutableList<Entry>?,
        @JvmField val truncated: Boolean,
    )

    /**
     * 一次公开操作共享的取消信号与截止时间；内层调用只复用，不再单独计时。
     *
     * 每个 deadline 持有独立的挂起任务标记（[ScheduledFuture]），结束时只取消自己那一个，等价于 `postDelayed(cancel, token,
     * DEADLINE_MS)` + `removeCallbacksAndMessages(token)`，不会像 `null` 那样清掉别的操作。
     */
    internal class Deadline(budgetMs: Long) : AutoCloseable {
        @JvmField internal val signal = CancellationSignal()
        private val timeout: ScheduledFuture<*> =
            WATCHDOG.schedule(signal::cancel, budgetMs, TimeUnit.MILLISECONDS)

        override fun close() {
            timeout.cancel(false)
        }

        /** 可中断调用返回后立刻复核：超过截止就按 TIMEOUT 处理，别把过期结果当成功。 */
        @JvmName("check")
        @Throws(OperationCanceledException::class)
        internal fun check() {
            if (signal.isCanceled) throw OperationCanceledException()
        }
    }

    private fun interface Timed<T> {
        @Throws(Failure::class) fun run(deadline: Deadline): T
    }

    private val resolver: ContentResolver
    private val tree: Uri
    private val rootId: String
    private val deadlineMs: Long

    @Throws(Failure::class)
    constructor(resolver: ContentResolver?, tree: Uri?) : this(resolver, tree, DEADLINE_MS)

    /** 包内测试入口：缩短截止时间，验证 TIMEOUT / 取消路径。 */
    @Throws(Failure::class)
    internal constructor(resolver: ContentResolver?, tree: Uri?, deadlineMs: Long) {
        if (resolver == null || tree == null) throw Failure("NOT_TREE", null)
        if ("content" != tree.scheme || !DocumentsContractCompat.isTreeUri(tree))
            throw Failure("NOT_TREE", null)
        if (deadlineMs <= 0) throw Failure("BAD_DEADLINE", deadlineMs.toString())
        this.resolver = resolver
        this.tree = tree
        this.deadlineMs = deadlineMs
        val id: String?
        try {
            id = DocumentsContract.getTreeDocumentId(tree)
        } catch (bad: Throwable) {
            throw Failure("NOT_TREE", null)
        }
        if (id.isNullOrEmpty()) throw Failure("NOT_TREE", null)
        this.rootId = id
    }

    fun uri(): Uri = tree

    /** 授权根自身的条目信息（应用名之外再拿目录名与可读性）。 */
    @Throws(Failure::class) fun root(): Entry = one("root") { deadline -> entry(deadline, rootId) }

    /** 列目录：目录在前、同类按名字排序；超过 limit 截断并在 [Page.truncated] 里报告。 */
    @Throws(Failure::class)
    fun list(path: String?, limit: Int): Page {
        val cap = Math.max(1, Math.min(if (limit <= 0) MAX_ENTRIES else limit, MAX_ENTRIES))
        return one("list:$path") { deadline ->
            val page = children(deadline, resolve(deadline, path), cap)
            page.entries!!.sortWith { left, right ->
                if (left.directory != right.directory) {
                    if (left.directory) -1 else 1
                } else {
                    left.name!!.compareTo(right.name!!, ignoreCase = true)
                }
            }
            page
        }
    }

    @Throws(Failure::class)
    fun stat(path: String?): Entry =
        one("stat:$path") { deadline ->
            entry(deadline, resolve(deadline, path))
        }

    /**
     * 读一段字节：`offset` 用于大文件分段读，`max` 上限 [MAX_READ]。 负偏移直接报参数错误；非可 seek 的 Provider 也能工作（按块丢弃前缀，不依赖
     * skip）。
     */
    @Throws(Failure::class)
    fun read(path: String?, offset: Long, max: Int): ByteArray {
        if (offset < 0) throw Failure("BAD_OFFSET", offset.toString())
        val cap = Math.max(1, Math.min(if (max <= 0) MAX_READ else max, MAX_READ))
        return one("read:$path") { deadline ->
            val entry = entry(deadline, resolve(deadline, path))
            if (entry.directory) throw Failure("IS_DIRECTORY", entry.name)
            // 不可 seek 的 Provider 只能从文件头读掉 offset 字节；先用 stat 的大小拒绝越界请求，
            // 大小未知时按桥支持的最大单文件（32 MiB）兜底，别让一个离谱的 offset 白读整份文件。
            if (if (entry.size >= 0) offset > entry.size else offset > MAX_WRITE)
                throw Failure("OFFSET_OUT_OF_RANGE", offset.toString())
            try {
                resolver.openFileDescriptor(documentUri(entry.docId), "r", deadline.signal).use {
                    descriptor ->
                    deadline.check()
                    if (descriptor == null) throw Failure("UNREADABLE", entry.name)
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        val scratch = ByteArray(8192)
                        var remaining = offset
                        while (remaining > 0) {
                            deadline.check()
                            val read =
                                input.read(
                                    scratch,
                                    0,
                                    Math.min(scratch.size.toLong(), remaining).toInt(),
                                )
                            if (read <= 0) throw Failure("OFFSET_OUT_OF_RANGE", offset.toString())
                            remaining -= read
                        }
                        val out = ByteArrayOutputStream(cap)
                        val buffer = ByteArray(Math.min(cap, 65536))
                        while (out.size() < cap) {
                            deadline.check()
                            val read =
                                input.read(buffer, 0, Math.min(buffer.size, cap - out.size()))
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                        }
                        deadline.check()
                        out.toByteArray()
                    }
                }
            } catch (canceled: OperationCanceledException) {
                throw canceled
            } catch (denied: SecurityException) {
                throw Failure("PERMISSION_DENIED", null)
            } catch (missing: FileNotFoundException) {
                deadline.check()
                throw Failure("NOT_FOUND", entry.name)
            } catch (error: IOException) {
                deadline.check()
                throw Failure("IO_ERROR", error.message)
            }
        }
    }

    /** 一次写入的目标：已有文档或刚创建的文档。 */
    private class WriteTarget(
        @JvmField val uri: Uri,
        @JvmField val docId: String?,
        @JvmField val created: Boolean,
    )

    /** 覆盖写；`create` 为真时目标不存在会按路径新建（父目录必须已存在）。写完核对长度。 */
    @Throws(Failure::class)
    fun write(path: String?, data: ByteArray?, create: Boolean): Long {
        val payload = data ?: ByteArray(0)
        if (payload.size > MAX_WRITE) throw Failure("TOO_LARGE", payload.size.toLong().toString())
        val clean = normalize(path)
        if (clean.isEmpty()) throw Failure("BAD_PATH", path)
        return one("write:$clean") { deadline ->
            val target = prepareWriteTarget(deadline, clean, create)
            try {
                val written =
                    writePayload(
                        deadline,
                        target,
                        ByteArrayInputStream(payload),
                        payload.size.toLong(),
                        clean,
                    )
                verifyWritten(deadline, target, clean, written)
                written
            } catch (error: Failure) {
                // 新建文档写到一半失败时清理半截文档；已有文档不在这里删除（原地覆盖，无法回滚）。
                if (target.created) deleteQuietly(target.uri)
                throw error
            } catch (error: RuntimeException) {
                if (target.created) deleteQuietly(target.uri)
                throw error
            }
        }
    }

    /**
     * 流式写入：push 用 64 KiB 缓冲边读边写，不再把 32 MiB 整份读进内存。 `expectedLength` 为 -1 表示未知（只受 [MAX_WRITE] 限制）。
     */
    @Throws(Failure::class)
    fun writeStream(
        path: String?,
        input: InputStream?,
        expectedLength: Long,
        create: Boolean,
    ): Long {
        if (input == null) throw Failure("NO_SOURCE", null)
        if (expectedLength > MAX_WRITE) throw Failure("TOO_LARGE", expectedLength.toString())
        val clean = normalize(path)
        if (clean.isEmpty()) throw Failure("BAD_PATH", path)
        return one("write:$clean") { deadline ->
            val target = prepareWriteTarget(deadline, clean, create)
            try {
                val written = writePayload(deadline, target, input, expectedLength, clean)
                verifyWritten(deadline, target, clean, written)
                written
            } catch (error: Failure) {
                if (target.created) deleteQuietly(target.uri)
                throw error
            } catch (error: RuntimeException) {
                if (target.created) deleteQuietly(target.uri)
                throw error
            }
        }
    }

    @Throws(Failure::class)
    private fun prepareWriteTarget(
        deadline: Deadline,
        clean: String,
        create: Boolean,
    ): WriteTarget {
        var docId: String? = null
        try {
            docId = resolve(deadline, clean)
        } catch (missing: Failure) {
            // 大目录里列表会被截断；create=true 时把它当作「未找到」，走 Provider 的 createDocument。
            val absent = "NOT_FOUND" == missing.code || "SCAN_LIMIT" == missing.code
            if (!absent || !create) throw missing
        }
        if (docId != null) {
            val existing = entry(deadline, docId)
            if (existing.directory) throw Failure("IS_DIRECTORY", existing.name)
            return WriteTarget(documentUri(docId), docId, false)
        }
        val cut = clean.lastIndexOf('/')
        val parentPath = if (cut <= 0) "" else clean.substring(0, cut)
        val name = if (cut < 0) clean else clean.substring(cut + 1)
        if (name.isEmpty() || name == "." || name == "..") throw Failure("BAD_PATH", clean)
        val parentId = resolve(deadline, parentPath)
        val parent = documentUri(parentId)
        val target: Uri?
        try {
            target = DocumentsContract.createDocument(resolver, parent, mimeOf(name), name)
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (missing: FileNotFoundException) {
            deadline.check()
            throw Failure("NOT_FOUND", parentPath)
        }
        try {
            // createDocument 在框架层没有 CancellationSignal：返回后立刻复核截止。
            deadline.check()
            if (target == null) throw Failure("CREATE_FAILED", name)
            // 新建文档直接校验返回 URI；不依赖父目录列表（列表可能截断或短暂延迟）。
            if (!createdChildOf(deadline, parent, target, name))
                throw Failure("BAD_CHILD_URI", name)
            return WriteTarget(target, DocumentsContract.getDocumentId(target), true)
        } catch (error: Failure) {
            // 返回 URI 未通过归属校验时可能是既有文档，不能删；宁可不清理也不能造成数据丢失。
            throw error
        } catch (error: RuntimeException) {
            throw error
        }
    }

    @Throws(Failure::class)
    private fun writePayload(
        deadline: Deadline,
        target: WriteTarget,
        input: InputStream,
        expectedLength: Long,
        clean: String,
    ): Long {
        var total = 0L
        try {
            return resolver.openFileDescriptor(target.uri, "wt", deadline.signal).use { descriptor
                ->
                deadline.check()
                if (descriptor == null) throw Failure("UNWRITABLE", null)
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        // 大块 I/O 分段写，每段之间复核取消/截止。
                        deadline.check()
                        if (total + read > MAX_WRITE)
                            throw Failure("TOO_LARGE", (total + read).toString())
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    deadline.check()
                    if (expectedLength >= 0 && total != expectedLength)
                        throw Failure("SIZE_MISMATCH", "$total/$expectedLength")
                    total
                }
            }
        } catch (canceled: OperationCanceledException) {
            throw canceled
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (missing: FileNotFoundException) {
            deadline.check()
            throw Failure("NOT_FOUND", clean)
        } catch (error: IOException) {
            deadline.check()
            throw Failure("IO_ERROR", error.message)
        }
    }

    /** 写后核对：Provider 可能中途失败或静默截断，落盘长度必须与请求一致。 */
    @Throws(Failure::class)
    private fun verifyWritten(
        deadline: Deadline,
        target: WriteTarget,
        clean: String,
        length: Long,
    ) {
        try {
            val after = entry(deadline, target.docId ?: resolve(deadline, clean))
            if (after.size >= 0 && after.size != length)
                throw Failure("WRITE_INCOMPLETE", "${after.size}/$length")
        } catch (verify: Failure) {
            if ("WRITE_INCOMPLETE" == verify.code) throw verify
            // 查不到新文档：当作 Provider 报告不一致，不让调用方误以为成功。
            throw Failure("WRITE_UNVERIFIED", verify.message)
        }
    }

    @Throws(Failure::class)
    fun mkdir(path: String?) {
        val clean = normalize(path)
        if (clean.isEmpty()) throw Failure("BAD_PATH", path)
        val cut = clean.lastIndexOf('/')
        val parentPath = if (cut <= 0) "" else clean.substring(0, cut)
        val name = if (cut < 0) clean else clean.substring(cut + 1)
        if (name.isEmpty() || name == "." || name == "..") throw Failure("BAD_PATH", clean)
        one("mkdir:$clean") { deadline ->
            val parentId = resolve(deadline, parentPath)
            val parent = documentUri(parentId)
            val created: Uri?
            try {
                created =
                    DocumentsContract.createDocument(
                        resolver,
                        parent,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        name,
                    )
            } catch (denied: SecurityException) {
                throw Failure("PERMISSION_DENIED", null)
            } catch (missing: FileNotFoundException) {
                deadline.check()
                throw Failure("NOT_FOUND", parentPath)
            }
            try {
                // createDocument 没有 signal：返回后复核截止。
                deadline.check()
                if (created == null) throw Failure("CREATE_FAILED", name)
                // 与 write 相同：直接校验 Provider 返回的 URI（不查父列表）。
                if (!createdChildOf(deadline, parent, created, name))
                    throw Failure("BAD_CHILD_URI", name)
                null
            } catch (error: Failure) {
                // 同 write：归属校验失败时返回 URI 可能是既有目录，不能删。
                throw error
            } catch (error: RuntimeException) {
                throw error
            }
        }
    }

    /**
     * 删除探测：不执行删除，登记一次性 token 并返回目标路径与（目录）子项数，由调用方展示后再用同一 token 调用 [deleteConfirmed]；单条 confirm
     * 参数不能放行。 空路径、根路径与解析回根目录的请求一律拒绝。
     */
    @Throws(Failure::class)
    fun delete(path: String?): PendingDelete {
        val clean = normalize(path)
        if (clean.isEmpty()) throw Failure("BAD_PATH", "empty path; root is never deletable")
        return one("delete-probe:$clean") { deadline ->
            val docId = resolve(deadline, clean)
            if (docId!!.equals(rootId))
                throw Failure("BAD_PATH", "root of the granted tree is not deletable")
            val entry = entry(deadline, docId)
            var hint = clean
            if (entry.directory) {
                val children = children(deadline, docId, MAX_ENTRIES + 1)
                hint +=
                    if (children.truncated) " (超过 $MAX_ENTRIES 个子项)"
                    else " (${children.entries!!.size} 个子项)"
            }
            val token = UUID.randomUUID().toString().replace("-", "")
            val now = System.nanoTime()
            prunePending(now)
            PENDING_DELETES[token] =
                Pending(
                    tree.toString(),
                    clean,
                    docId,
                    entry.size,
                    entry.modified,
                    now + CONFIRM_TTL_NANOS,
                )
            PendingDelete(token, hint)
        }
    }

    /** 消费一次性 token 并执行删除；目录会连同内容递归删除。 */
    @Throws(Failure::class)
    fun deleteConfirmed(path: String?, token: String?) {
        val clean = normalize(path)
        if (clean.isEmpty()) throw Failure("BAD_PATH", "empty path; root is never deletable")
        if (token.isNullOrEmpty()) throw Failure("CONFIRM_REQUIRED", "missing one-time token")
        val pending =
            PENDING_DELETES.remove(token)
                ?: throw Failure("CONFIRM_REQUIRED", "token not found or consumed")
        if (System.nanoTime() - pending.expiresAt > 0)
            throw Failure("CONFIRM_EXPIRED", "token expired; probe again")
        if (pending.tree != tree.toString() || pending.path != clean)
            throw Failure("CONFIRM_MISMATCH", "token is bound to another tree/path")
        one("delete:$clean") { deadline ->
            val docId = resolve(deadline, clean)
            if (docId!!.equals(rootId))
                throw Failure("BAD_PATH", "root of the granted tree is not deletable")
            val entry = entry(deadline, docId)
            if (
                !docId.equals(pending.docId) ||
                    entry.size != pending.size ||
                    entry.modified != pending.modified
            )
                throw Failure("CONFIRM_MISMATCH", "target changed since probe; probe again")
            // 先预检所有目录层；任何一层超过 500 子项就在删除前拒绝，避免半删。
            preflightTree(deadline, docId, clean)
            deleteTree(deadline, docId, clean)
            null
        }
    }

    /** 递归删除前的只读预检：任何一层子项超过分页上限就拒绝，不先删任何文件。 */
    @Throws(Failure::class)
    private fun preflightTree(deadline: Deadline, docId: String?, path: String) {
        val entry = entry(deadline, docId)
        if (!entry.directory) return
        val children = children(deadline, docId, MAX_ENTRIES)
        if (children.truncated) throw Failure("SCAN_LIMIT", path)
        for (child in children.entries!!) {
            if (child.directory) preflightTree(deadline, child.docId, path + "/" + child.name)
        }
    }

    /** 先完整检查一层子项再删除，避免 Provider 只支持有限分页时留下半删目录。 */
    @Throws(Failure::class)
    private fun deleteTree(deadline: Deadline, docId: String?, path: String) {
        val entry = entry(deadline, docId)
        if (entry.directory) {
            val children = children(deadline, docId, MAX_ENTRIES + 1)
            if (children.truncated) throw Failure("SCAN_LIMIT", path)
            for (child in children.entries!!) {
                val childPath = path + "/" + child.name
                if (child.directory) deleteTree(deadline, child.docId, childPath)
                else deleteDocument(deadline, child.docId, childPath)
            }
        }
        deleteDocument(deadline, docId, path)
    }

    @Throws(Failure::class)
    private fun deleteDocument(deadline: Deadline, docId: String?, path: String) {
        try {
            val deleted = DocumentsContract.deleteDocument(resolver, documentUri(docId))
            deadline.check()
            if (!deleted) throw Failure("DELETE_FAILED", path)
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (missing: FileNotFoundException) {
            deadline.check()
            throw Failure("NOT_FOUND", path)
        }
    }

    /**
     * 新建文档的归属校验：同一 authority/tree、非空 documentId、Provider 明确它是请求父项的直接子项，且返回文档的显示名与请求一致。 优先查
     * COLUMN_PARENT_DOCUMENT_ID + COLUMN_DISPLAY_NAME；Provider 不返回 parent 列时用
     * isChildDocument；两者都不支持则失败关闭。 不依赖父目录列表，因此大目录/列表延迟仍能新建。
     */
    private fun createdChildOf(
        deadline: Deadline,
        parent: Uri?,
        child: Uri?,
        expectedName: String,
    ): Boolean {
        if (parent == null || child == null) return false
        if (!tree.authority!!.equals(child.authority)) return false
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val childTreeId = DocumentsContract.getTreeDocumentId(child)
            val parentId = DocumentsContract.getDocumentId(parent)
            val childId = DocumentsContract.getDocumentId(child)
            if (treeId == null || childTreeId == null || treeId != childTreeId) return false
            if (parentId == null || childId == null || childId.isEmpty()) return false
            if (childId == parentId) return false
            var displayed: String? = null
            var actualParent: String? = null
            try {
                resolver
                    .query(
                        child,
                        arrayOf(
                            COLUMN_PARENT_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null,
                        null,
                        null,
                        deadline.signal,
                    )
                    .use { cursor ->
                        deadline.check()
                        if (cursor != null && cursor.moveToFirst()) {
                            val parentIndex = cursor.getColumnIndex(COLUMN_PARENT_DOCUMENT_ID)
                            val nameIndex =
                                cursor.getColumnIndex(
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                                )
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex))
                                displayed = cursor.getString(nameIndex)
                            if (parentIndex >= 0 && !cursor.isNull(parentIndex))
                                actualParent = cursor.getString(parentIndex)
                        }
                    }
            } catch (canceled: OperationCanceledException) {
                throw canceled
            } catch (unsupportedColumn: Throwable) {
                // 列不可用，继续用 isChildDocument。
            }
            // 显示名必须与请求名一致：防止 Provider 返回同一父项下的既有 sibling/任意文档。
            if (displayed == null || displayed != expectedName) return false
            // a) parent 列可用时以它为准（明确的 mismatch 不降级）。
            if (actualParent != null) return parentId == actualParent
            // b) Provider 不返回 parent 列时用 isChildDocument；false/异常失败关闭。
            return DocumentsContract.isChildDocument(resolver, parent, child)
        } catch (canceled: OperationCanceledException) {
            throw canceled
        } catch (malformedOrUnsupported: Throwable) {
            return false
        }
    }

    /** 公开操作入口：整次操作共用一个截止时间；取消统一映射成 TIMEOUT。 */
    @Throws(Failure::class)
    private fun <T> one(op: String, body: Timed<T>): T {
        val deadline = Deadline(deadlineMs)
        try {
            val value = body.run(deadline)
            deadline.check()
            return value
        } catch (canceled: OperationCanceledException) {
            throw Failure("TIMEOUT", op)
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (error: RuntimeException) {
            // Provider 可能把取消包装成别的运行时异常；截止已到就统一按 TIMEOUT 上报。
            try {
                deadline.check()
            } catch (canceled: OperationCanceledException) {
                throw Failure("TIMEOUT", op)
            }
            throw error
        } finally {
            deadline.close()
        }
    }

    @Throws(Failure::class)
    private fun resolve(deadline: Deadline, path: String?): String? {
        val clean = normalize(path)
        if (clean.isEmpty()) return rootId
        var docId: String? = rootId
        for (segment in clean.split("/")) docId = child(deadline, docId, segment)
        return docId
    }

    @Throws(Failure::class)
    private fun child(deadline: Deadline, parentId: String?, name: String): String? {
        val page = children(deadline, parentId, MAX_ENTRIES)
        for (entry in page.entries!!) if (entry.name!!.equals(name)) return entry.docId
        if (page.truncated) throw Failure("SCAN_LIMIT", name)
        throw Failure("NOT_FOUND", name)
    }

    @Throws(Failure::class)
    private fun entry(deadline: Deadline, docId: String?): Entry {
        deadline.check()
        try {
            return resolver
                .query(documentUri(docId), COLUMNS, null, null, null, deadline.signal)
                .use { cursor ->
                    deadline.check()
                    if (cursor == null || !cursor.moveToFirst()) throw Failure("NOT_FOUND", docId)
                    row(cursor)
                }
        } catch (canceled: OperationCanceledException) {
            throw canceled
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (unsupported: IllegalArgumentException) {
            throw Failure("UNSUPPORTED", unsupported.message)
        } catch (error: RuntimeException) {
            deadline.check()
            throw Failure("PROVIDER_ERROR", error.message)
        }
    }

    @Throws(Failure::class)
    private fun children(deadline: Deadline, parentId: String?, limit: Int): Page {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        deadline.check()
        try {
            return resolver.query(uri, COLUMNS, null, null, null, deadline.signal).use { cursor ->
                deadline.check()
                if (cursor == null) {
                    Page(ArrayList(), false)
                } else {
                    val entries: MutableList<Entry> = ArrayList()
                    var truncated = false
                    while (cursor.moveToNext()) {
                        if (entries.size >= limit) {
                            truncated = true
                            break
                        }
                        entries.add(row(cursor))
                    }
                    Page(entries, truncated)
                }
            }
        } catch (canceled: OperationCanceledException) {
            throw canceled
        } catch (denied: SecurityException) {
            throw Failure("PERMISSION_DENIED", null)
        } catch (unsupported: IllegalArgumentException) {
            throw Failure("UNSUPPORTED", unsupported.message)
        } catch (error: RuntimeException) {
            deadline.check()
            throw Failure("PROVIDER_ERROR", error.message)
        }
    }

    private fun row(cursor: Cursor): Entry {
        val docId = if (cursor.isNull(0)) "" else cursor.getString(0)
        val name = if (cursor.isNull(1)) "" else cursor.getString(1)
        val mime = if (cursor.isNull(2)) "" else cursor.getString(2)
        val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
        val modified = if (cursor.isNull(4)) -1L else cursor.getLong(4)
        return Entry(docId, name, mime, size, modified)
    }

    private fun documentUri(docId: String?): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** 拒绝时删掉刚建的文档；删除失败不掩盖原始拒绝原因。 */
    private fun deleteQuietly(document: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (ignored: Throwable) {
            // Provider 拒绝清理时也按失败返回，调用方不会拿到树外 URI。
        }
    }

    companion object {
        /** Provider 的父文档列（AOSP DocumentsContract.Document 中同名常量为 hidden API）。 */
        private const val COLUMN_PARENT_DOCUMENT_ID = "parent_document_id"

        /** 单次列目录上限；超过只给前 limit 条，并报告被截断。 */
        const val MAX_ENTRIES = SafCapabilityLimits.MAX_ENTRIES

        /** 单次读取上限（与 /app/readfile 的 256 KiB 对齐）。 */
        const val MAX_READ = SafCapabilityLimits.MAX_READ_BYTES

        /** 单次写入上限。 */
        const val MAX_WRITE = SafCapabilityLimits.MAX_WRITE_BYTES

        /**
         * 跨进程调用的统一截止时间：可中断调用在截止时取消并返回 TIMEOUT；createDocument 等不可中断调用只能在返回后复核截止，Provider
         * 完全忽略取消时后台线程仍会被占用到它返回为止。
         */
        const val DEADLINE_MS = SafCapabilityLimits.DEADLINE_MS

        /** 删除确认 token 登记：一次性、绑定 treeId/规范化路径/探测时的目标身份与 60 秒有效期。 */
        private val PENDING_DELETES = ConcurrentHashMap<String, Pending>()
        @Volatile private var CONFIRM_TTL_NANOS = TimeUnit.SECONDS.toNanos(60)

        private val COLUMNS =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

        /** 单个 deadline 的看门狗：真实时间计时，不占用主线程消息队列。 */
        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                val thread = Thread(runnable, "saf-deadline")
                thread.isDaemon = true
                thread
            }

        /** 包内复用（SafGrants 的 label 查询也走同一套截止机制）。 */
        @JvmStatic
        @JvmName("newDeadline")
        internal fun newDeadline(budgetMs: Long): Deadline = Deadline(budgetMs)

        @JvmStatic
        private fun prunePending(now: Long) {
            PENDING_DELETES.entries.removeIf { value -> now - value.value.expiresAt > 0 }
            // 满了按最早过期时间淘汰，不做「一刀切清空」——清空会让并发的确认请求被无差别打扰。
            while (PENDING_DELETES.size >= 128) {
                var oldestKey: String? = null
                var oldest = Long.MAX_VALUE
                for (entry in PENDING_DELETES.entries) {
                    if (entry.value.expiresAt < oldest) {
                        oldest = entry.value.expiresAt
                        oldestKey = entry.key
                    }
                }
                if (oldestKey == null) break
                PENDING_DELETES.remove(oldestKey)
            }
        }

        /** 测试观察 pending 数量（只读）。 */
        @JvmStatic
        @JvmName("pendingCountForTests")
        internal fun pendingCountForTests(): Int = PENDING_DELETES.size

        /** 测试清理：避免跨用例的待确认登记互相影响。 */
        @JvmStatic
        @JvmName("clearPendingDeletesForTests")
        internal fun clearPendingDeletesForTests() {
            PENDING_DELETES.clear()
        }

        /** 测试缩短/恢复 token TTL。 */
        @JvmStatic
        @JvmName("setConfirmTtlForTests")
        internal fun setConfirmTtlForTests(nanos: Long) {
            CONFIRM_TTL_NANOS = nanos
        }

        @JvmStatic
        @Throws(Failure::class)
        fun normalize(path: String?): String {
            try {
                return SafPaths.normalize(path)
            } catch (error: IllegalArgumentException) {
                val message = java.lang.String.valueOf(error.message)
                val code =
                    if (message != null && message.startsWith("PATH_TOO_DEEP")) "PATH_TOO_DEEP"
                    else "BAD_PATH"
                throw Failure(code, path)
            }
        }

        /** 委托 [SafPaths.mimeOf]。 */
        @JvmStatic fun mimeOf(name: String?): String = SafPaths.mimeOf(name)

        /** 委托 [SafPaths.looksText]。 */
        @JvmStatic fun looksText(data: ByteArray?): Boolean = SafPaths.looksText(data)
    }
}
