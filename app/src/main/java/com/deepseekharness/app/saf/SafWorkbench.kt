package com.deepseekharness.app.saf

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.deepseekharness.app.util.RuntimeWorkPort
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.UUID
import java.util.function.Supplier

/** 原生文件工作台；每次 IO 复核授权，面板只持有内存中的限时票据。 */
class SafWorkbench(context: Context?, private val rootfs: Supplier<File?>?) {
    class Ticket
    private constructor(
        @JvmField val treeId: String,
        @JvmField val path: String,
        @JvmField val operation: SafCapabilityHandles.Operation,
        private val token: String,
    ) {
        companion object {
            @JvmSynthetic
            internal fun create(
                treeId: String,
                path: String,
                operation: SafCapabilityHandles.Operation,
                token: String,
            ): Ticket = Ticket(treeId, path, operation, token)
        }

        @JvmSynthetic internal fun token(): String = token
    }

    private val context = context!!.applicationContext
    private val handles = SafCapabilityHandles()

    @Throws(SafTree.Failure::class)
    fun prepare(treeId: String?, path: String?, importing: Boolean): Ticket =
        issue(
            treeId,
            path,
            if (importing) SafCapabilityHandles.Operation.READ
            else SafCapabilityHandles.Operation.WRITE,
        )

    /** 更改目标仍复核旧选择的代次，不借重新授权续期。 */
    @Throws(SafTree.Failure::class)
    fun retarget(selection: Ticket?, path: String?): Ticket {
        val expected = check(selection, false)
        val ledger = SafGrants.capabilitySnapshot(context, selection!!.treeId)
        val current = ledger.findById(selection.treeId)
        if (current == null || current.epoch != expected.epoch)
            throw SafTree.Failure("HANDLE_REVOKED", null)
        val clean = SafTree.normalize(path)
        val issued =
            handles.issue(
                ledger,
                selection.treeId,
                selection.operation,
                clean,
                SystemClock.elapsedRealtime(),
            )
        if (!issued.ok()) throw SafTree.Failure("HANDLE_" + issued.result.name, null)
        val result = Ticket.create(selection.treeId, clean, selection.operation, issued.token)
        cancel(selection)
        return result
    }

    fun cancel(ticket: Ticket?) {
        if (ticket != null) handles.discard(ticket.token())
    }

    @Throws(SafTree.Failure::class)
    private fun issue(
        treeId: String?,
        path: String?,
        operation: SafCapabilityHandles.Operation,
    ): Ticket {
        val clean = SafTree.normalize(path)
        val issued =
            handles.issue(
                SafGrants.capabilitySnapshot(context, treeId),
                treeId,
                operation,
                clean,
                SystemClock.elapsedRealtime(),
            )
        if (!issued.ok()) throw capabilityFailure(issued.result, operation)
        return Ticket.create(treeId!!, clean, operation, issued.token)
    }

    @Throws(SafTree.Failure::class)
    private fun check(ticket: Ticket?, consume: Boolean): SafGrantRecord {
        if (ticket == null) throw SafTree.Failure("HANDLE_UNKNOWN", null)
        val ledger = SafGrants.capabilitySnapshot(context, ticket.treeId)
        val result =
            if (consume)
                handles.consume(
                    ledger,
                    ticket.token(),
                    ticket.operation,
                    ticket.path,
                    SystemClock.elapsedRealtime(),
                )
            else
                handles.check(
                    ledger,
                    ticket.token(),
                    ticket.operation,
                    ticket.path,
                    SystemClock.elapsedRealtime(),
                )
        if (result != SafCapabilityHandles.Result.OK)
            throw capabilityFailure(result, ticket.operation)
        return ledger.findById(ticket.treeId)!!
    }

    @Throws(SafTree.Failure::class)
    private fun tree(grant: SafGrantRecord): SafTree =
        SafTree(context.contentResolver, Uri.parse(grant.uri))

    @Throws(SafTree.Failure::class)
    fun list(treeId: String?, path: String?): SafTree.Page {
        val ticket = issue(treeId, path, SafCapabilityHandles.Operation.READ)
        try {
            val result =
                tree(check(ticket, false)).list(ticket.path, SafCapabilityLimits.MAX_ENTRIES)
            check(ticket, false)
            return result
        } finally {
            cancel(ticket)
        }
    }

    @Throws(SafTree.Failure::class)
    fun read(treeId: String?, path: String?, max: Int): ByteArray {
        val ticket = issue(treeId, path, SafCapabilityHandles.Operation.READ)
        try {
            val grant = tree(check(ticket, false))
            val data = grant.read(ticket.path, 0, max)
            check(ticket, false)
            return data
        } finally {
            cancel(ticket)
        }
    }

    /** 一次性消费；失败/中断后必须重新签发，不自动重放。 */
    @Throws(Exception::class)
    fun transfer(ticket: Ticket?, guestPath: String?, importing: Boolean): Long {
        if (
            ticket == null ||
                ticket.operation !=
                    if (importing) SafCapabilityHandles.Operation.READ
                    else SafCapabilityHandles.Operation.WRITE
        )
            throw SafTree.Failure("HANDLE_MISMATCH", null)
        if (!SafTransferGate.TRANSFERS.tryAcquire()) throw SafTree.Failure("BUSY", null)
        try {
            beginWork().use {
                val grant = check(ticket, true)
                val deadline = SystemClock.elapsedRealtime() + SafCapabilityLimits.DEADLINE_MS
                val local = guestFile(guestPath)
                return if (importing) pull(ticket, grant, local, deadline)
                else push(ticket, grant, local, deadline)
            }
        } finally {
            SafTransferGate.TRANSFERS.release()
        }
    }

    @Throws(IOException::class)
    private fun running(ticket: Ticket, expected: SafGrantRecord, deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw IOException("CANCELLED")
        if (SystemClock.elapsedRealtime() >= deadline) throw IOException("TIMEOUT")
        try {
            val actual =
                SafGrants.capabilitySnapshot(context, ticket.treeId).findById(ticket.treeId)
            if (actual == null || actual.epoch != expected.epoch)
                throw IOException("HANDLE_REVOKED")
            if (
                if (ticket.operation == SafCapabilityHandles.Operation.READ) !actual.canRead()
                else !actual.usableWrite()
            )
                throw IOException("PERMISSION_DENIED")
        } catch (failure: SafTree.Failure) {
            throw IOException(
                if (failure.code == "NO_GRANT") "HANDLE_REVOKED" else failure.code,
                failure,
            )
        }
    }

    @Throws(Exception::class)
    private fun push(ticket: Ticket, grant: SafGrantRecord, source: File, deadline: Long): Long {
        val size = source.length()
        if (size > SafCapabilityLimits.MAX_WRITE_BYTES) throw IOException("TOO_LARGE")
        running(ticket, grant, deadline)
        val guardFailure = arrayOfNulls<IOException>(1)
        try {
            object : FilterInputStream(SafLocalFiles.openRead(source, pathCheck())) {
                    @Throws(IOException::class)
                    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                        try {
                            running(ticket, grant, deadline)
                        } catch (stopped: IOException) {
                            guardFailure[0] = stopped
                            throw stopped
                        }
                        return super.read(bytes, offset, count)
                    }
                }
                .use { checked ->
                    val bytes = tree(grant).writeStream(ticket.path, checked, size, true)
                    running(ticket, grant, deadline)
                    return bytes
                }
        } catch (failure: SafTree.Failure) {
            // 保留本层守卫的确定短码，不被桥通用 IO_ERROR 遮蔽。
            if (guardFailure[0] != null) throw guardFailure[0]!!
            throw failure
        }
    }

    @Throws(Exception::class)
    private fun pull(ticket: Ticket, grant: SafGrantRecord, target: File, deadline: Long): Long {
        val tree = tree(grant)
        val source = tree.stat(ticket.path)
        if (source.directory) throw IOException("IS_DIRECTORY")
        if (source.size > SafCapabilityLimits.MAX_WRITE_BYTES) throw IOException("TOO_LARGE")
        val parent = target.parentFile
        if (parent == null || target.isDirectory) throw IOException("BAD_PATH")
        val temporary = ".saf-workbench-" + UUID.randomUUID() + ".part"
        var total = 0L
        SafLocalFiles.ensureAnchoredDirectory(parent, pathCheck()).use { directory ->
            pruneTemps(directory, System.currentTimeMillis())
            var previousMode = -1
            try {
                val original = Os.lstat("/proc/self/fd/" + directory.fd + "/" + target.name)
                if (OsConstants.S_ISREG(original.st_mode)) previousMode = original.st_mode and 0x1ff
            } catch (missing: ErrnoException) {
                // 新目标保持创建时的 0600，只复制普通既有文件的权限位。
            }
            var published = false
            try {
                SafLocalFiles.createInDirectory(directory, temporary, pathCheck()).use { output ->
                    while (true) {
                        running(ticket, grant, deadline)
                        val cap =
                            minOf(
                                    SafCapabilityLimits.MAX_READ_BYTES.toLong(),
                                    SafCapabilityLimits.MAX_WRITE_BYTES - total + 1,
                                )
                                .toInt()
                        val data = tree.read(ticket.path, total, cap)
                        if (data.isEmpty()) break
                        if (total + data.size > SafCapabilityLimits.MAX_WRITE_BYTES)
                            throw IOException("TOO_LARGE")
                        output.write(data)
                        total += data.size
                        if (data.size < cap) break
                    }
                    if (source.size >= 0 && total != source.size) throw IOException("SIZE_MISMATCH")
                    val after = tree.stat(ticket.path)
                    if (
                        source.docId!! != after.docId ||
                            source.size != after.size ||
                            source.modified != after.modified
                    )
                        throw IOException("SOURCE_CHANGED")
                    running(ticket, grant, deadline)
                    SafLocalFiles.publishInDirectory(
                        directory,
                        temporary,
                        target.name,
                        output,
                        previousMode,
                    ) {
                        running(ticket, grant, deadline)
                    }
                    published = true
                }
                return total
            } finally {
                if (!published) {
                    try {
                        SafLocalFiles.deleteInDirectory(directory, temporary)
                    } catch (ignored: IOException) {
                        // 只清理本次已锚定目录内的临时文件，保留原始失败。
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun guestFile(raw: String?): File {
        val path = raw?.trim { it <= ' ' } ?: ""
        var problem = SafCapabilityPolicy.localSourceProblem(path)
        if (problem != null) throw IOException(problem)
        val configured = rootfs?.get() ?: throw IOException("LOCAL_OUTSIDE_ROOTFS")
        val root = configured.canonicalFile
        val resolved = File(root, path.substring(1)).canonicalFile
        problem = SafCapabilityPolicy.localCanonicalProblem(resolved.path, root.path)
        if (problem != null) throw IOException(problem)
        if (SafLocalFiles.link2SymlinkPath(path) || SafLocalFiles.link2SymlinkPath(resolved.path))
            throw IOException("FORBIDDEN_LINK2SYMLINK")
        return resolved
    }

    @Throws(IOException::class)
    private fun pathCheck(): SafLocalFiles.PathCheck {
        val configured = rootfs?.get()
        val root = configured?.canonicalPath
        return SafLocalFiles.PathCheck { real ->
            SafCapabilityPolicy.localCanonicalProblem(real, root) != null
        }
    }

    companion object {
        private fun capabilityFailure(
            result: SafCapabilityHandles.Result,
            operation: SafCapabilityHandles.Operation,
        ): SafTree.Failure {
            val code =
                if (
                    result == SafCapabilityHandles.Result.NOT_PERMITTED &&
                        operation == SafCapabilityHandles.Operation.READ
                )
                    "PERMISSION_DENIED"
                else "HANDLE_" + result.name
            return SafTree.Failure(code, null)
        }

        @Throws(SafTree.Failure::class)
        private fun beginWork(): RuntimeWorkPort.Work =
            try {
                RuntimeWorkPort.begin("跨应用文件传输")
            } catch (maintenance: IllegalStateException) {
                throw SafTree.Failure("MAINTENANCE_BUSY", null)
            }

        /** 仅清理本功能过期 UUID 普通单链接 part，不跟随链接、不递归。 */
        @JvmStatic
        @JvmName("pruneTemps")
        internal fun pruneTemps(directory: SafLocalFiles.AnchoredDirectory?, now: Long) {
            val cutoff = now - 60L * 60 * 1000
            for (name in SafLocalFiles.listInDirectory(directory)!!) {
                if (!name!!.matches(Regex("\\.saf-workbench-[0-9a-f-]{36}\\.part"))) continue
                try {
                    val stat = Os.lstat("/proc/self/fd/" + directory!!.fd + "/" + name)
                    if (
                        OsConstants.S_ISREG(stat.st_mode) &&
                            stat.st_nlink == 1L &&
                            stat.st_mtime * 1000L < cutoff
                    )
                        SafLocalFiles.deleteInDirectory(directory, name)
                } catch (ignored: ErrnoException) {
                    // 清理失败不掩盖正在进行的操作，下次在同一目录再试。
                } catch (ignored: IOException) {
                    // 同上。
                }
            }
        }
    }
}
