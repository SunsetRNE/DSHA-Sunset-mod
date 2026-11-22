package com.deepseekharness.app.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.deepseekharness.app.backup.BackupControl
import com.deepseekharness.app.backup.DocumentStreams
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.transfer.DownloadTask.Status
import com.deepseekharness.app.util.FileIntegrity
import com.deepseekharness.app.util.PreviewPageSession
import com.deepseekharness.app.util.WebPreviewPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** 应用持有的串行、可恢复下载引擎；不持有网页，也不另建执行 owner。 */
class DownloadEngine private constructor(private val context: Context) {
    class State
    internal constructor(
        tasks: List<DownloadTask>?,
        @JvmField val busy: Boolean,
        @JvmField val activeId: String?,
    ) {
        @JvmField val tasks: List<DownloadTask> = Collections.unmodifiableList(tasks!!)
    }

    private val privateFiles: File
    private val root: File
    private val gate = Any()
    private val records = LinkedHashMap<String, Record>()
    private val pendingReleases = LinkedHashSet<String>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val releaseWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val state = MutableLiveData<State>()
    private var activeId = ""
    private var draining = false
    private var releasing = false
    private var admissionGeneration = 0L
    private var activeControl: BackupControl? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    private class Record private constructor() {
        var id = ""
        var destination = ""
        var message: String? = ""
        var uri = ""
        var displayName: String? = null
        var location: String? = ""
        var mime: String? = null
        var pendingUri = ""
        var pendingKind = ""
        var targetUri = ""
        lateinit var artifact: TransferArtifact
        var status = Status.QUEUED
        var downloaded = 0L
        var speed = 0L
        var createdAt = System.currentTimeMillis()
        var local = false

        fun snapshot(): DownloadTask =
            DownloadTask(
                id,
                artifact,
                destination,
                status,
                downloaded,
                speed,
                message,
                uri,
                displayName,
                location,
                mime,
                createdAt,
            )

        companion object {
            fun create(): Record = Record()
        }
    }

    init {
        privateFiles =
            try {
                context.filesDir.canonicalFile
            } catch (failure: IOException) {
                context.filesDir.absoluteFile
            }
        root = File(privateFiles, "transfers")
        try {
            TransferFiles.directory(privateFiles, "transfers")
        } catch (failure: IOException) {
            // 入队时报告受控存储错误。
        }
        restore()
        restoreReleases()
        HarnessController.get(context).addReadyWebPageListener(this::flushReleases)
        emit()
    }

    fun state(): LiveData<State> = state

    fun isBusy(): Boolean = synchronized(gate) { busy() }

    fun onRuntimeAvailable() {
        flushReleases()
    }

    @Throws(Exception::class)
    fun enqueue(artifact: TransferArtifact?, destination: String?): String {
        val required = artifact ?: throw NullPointerException()
        val record = create(required, destination, false)
        val ticket: Long
        synchronized(gate) {
            if (records.size >= 200) throw IOException("FILE_TASK_LIMIT")
            for (other in records.values) {
                if (other.artifact.id == required.id) throw IOException("FILE_DUPLICATE_ARTIFACT")
            }
            save(record)
            records[record.id] = record
            ticket = ++admissionGeneration
            emit()
        }
        startService(ticket)
        return record.id
    }

    /** Blob worker 完整私有复制并核验 SHA 后才移交所有权。 */
    @Throws(Exception::class)
    fun enqueueLocal(source: File?, name: String?, mime: String?, destination: String?): String {
        val allowed = TransferFiles.FS.child(context.cacheDir.canonicalFile, "web-downloads")
        val canonical = source!!.canonicalFile
        if (
            File(allowed, source.name) != canonical ||
                allowed != canonical.parentFile ||
                TransferFiles.FS.stat(allowed).type != "DIRECTORY" ||
                TransferFiles.FS.stat(canonical).type != "FILE"
        )
            throw IOException("FILE_LOCAL_SOURCE_REJECTED")
        val size = canonical.length()
        if (size < 0 || size > TransferArtifact.MAX_BYTES || !TransferArtifact.safeName(name))
            throw IOException("FILE_LIMIT_5GB")
        val id = UUID.randomUUID().toString()
        val dir = ensureTaskDir(id)
        if (root.usableSpace < TransferProtocol.requiredSpace(size, 0, true))
            throw IOException("FILE_SPACE_INSUFFICIENT")
        val partial = File(dir, "partial")
        val integrity =
            TransferFiles.read(canonical).use { input ->
                TransferFiles.write(partial, 0).use { output ->
                    FileIntegrity.copy(input, output, size)
                }
            }
        if (integrity.size != size || canonical.length() != size)
            throw IOException("FILE_LOCAL_SOURCE_CHANGED")
        val artifact =
            TransferArtifact(
                "local-$id",
                name,
                if (mime == null || mime.codePoints().allMatch { Character.isWhitespace(it) })
                    "application/octet-stream"
                else mime,
                size,
                integrity.sha256,
                "local-${integrity.sha256}",
            )
        val record = create(artifact, destination, true)
        record.id = id
        record.downloaded = size
        val ticket: Long
        synchronized(gate) {
            if (records.size >= 200) throw IOException("FILE_TASK_LIMIT")
            save(record)
            records[id] = record
            ticket = ++admissionGeneration
            emit()
        }
        startService(ticket)
        return id
    }

    @Throws(IOException::class)
    private fun create(artifact: TransferArtifact, destination: String?, local: Boolean): Record {
        if (!java.util.Set.of("downloads", "gallery", "saveas", "share").contains(destination))
            throw IOException("FILE_DESTINATION_INVALID")
        val record = Record.create()
        record.id = UUID.randomUUID().toString()
        record.artifact = artifact
        record.displayName = artifact.name
        record.mime = artifact.mime
        record.destination = destination!!
        record.message = "等待下载"
        record.local = local
        return record
    }

    @Throws(Exception::class)
    fun resume(id: String?) {
        val ticket: Long
        synchronized(gate) {
            val record = require(id)
            if (record.status == Status.CANCELLED) throw IOException("FILE_TASK_CANCELLED")
            if (record.status == Status.COMPLETE || running(record.status) || record.id == activeId)
                return
            if (record.status == Status.WAITING_DESTINATION && record.targetUri.isEmpty()) return
            record.status = Status.QUEUED
            record.message = "等待继续"
            save(record)
            ticket = ++admissionGeneration
            emit()
        }
        startService(ticket)
    }

    /** 仅接受本次 ACTION_CREATE_DOCUMENT 新建的 URI。 */
    @Throws(Exception::class)
    fun provideSaveTarget(id: String?, uri: Uri?) {
        if (uri == null || uri.scheme != "content") throw IOException("FILE_SAVE_TARGET_INVALID")
        val ticket: Long
        synchronized(gate) {
            val record = require(id)
            if (record.destination != "saveas" || record.status != Status.WAITING_DESTINATION)
                throw IOException("FILE_SAVE_TARGET_STATE")
            record.targetUri = uri.toString()
            record.status = Status.QUEUED
            record.message = "等待另存为"
            save(record)
            ticket = ++admissionGeneration
            emit()
        }
        startService(ticket)
    }

    fun pause(id: String?) {
        stop(id, false, "已暂停，下载进度已保留")
    }

    fun cancel(id: String?) {
        stop(id, true, "已取消。需要再次下载请从网页重新发起")
    }

    private fun stop(id: String?, cancelled: Boolean, message: String) {
        synchronized(gate) {
            val record = records[id]
            if (
                record == null ||
                    record.status == Status.COMPLETE ||
                    record.status == Status.CANCELLED
            )
                return
            record.status = if (cancelled) Status.CANCELLED else Status.PAUSED
            record.message = message
            record.speed = 0
            if (id == activeId) activeControl?.cancel()
            saveQuietly(record)
            emit()
            if (cancelled) releaseWhenUnreferenced(record.artifact)
        }
    }

    fun pauseAll(reason: String?) {
        synchronized(gate) {
            for (record in records.values) {
                if (!running(record.status)) continue
                record.status = Status.PAUSED
                record.speed = 0
                record.message = reason
                saveQuietly(record)
            }
            activeControl?.cancel()
            emit()
        }
    }

    /** 不删除已发布目标，包括分享 URI。 */
    @Throws(Exception::class)
    fun remove(id: String?) {
        synchronized(gate) {
            val record = require(id)
            if (id == activeId || running(record.status)) throw IOException("FILE_TASK_BUSY")
            // 删除唯一携带退休意图的任务日志前，先持久保存该意图。
            if (!record.local) {
                pendingReleases.add(record.artifact.id)
                saveReleasesRequired()
                flushReleases()
            }
            cleanupPending(record)
            val partial = partial(record)
            if (TransferFiles.FS.stat(partial).type != "MISSING") TransferFiles.FS.delete(partial)
            val staged = File(taskDir(id), "share-pending")
            if (TransferFiles.FS.stat(staged).type != "MISSING") TransferFiles.FS.delete(staged)
            for (suffix in listOf("", ".previous")) {
                val file = File(taskDir(id), "task.json$suffix")
                if (TransferFiles.FS.stat(file).type != "MISSING") TransferFiles.FS.delete(file)
            }
            records.remove(id)
            taskDir(id).delete()
            emit()
        }
    }

    @Throws(Exception::class)
    fun snapshot(taskId: String?): JSONObject =
        synchronized(gate) {
            if (taskId != null && taskId.isNotEmpty()) return json(require(taskId))
            val list = JSONArray()
            for (record in records.values) list.put(json(record))
            JSONObject().put("tasks", list).put("busy", busy())
        }

    @Throws(Exception::class)
    private fun json(record: Record): JSONObject =
        JSONObject()
            .put("id", record.id)
            .put("artifact", record.artifact.toJson())
            .put("destination", record.destination)
            .put("status", record.status.name)
            .put("downloaded", record.downloaded)
            .put("total", record.artifact.bytes)
            .put("speedBytesPerSecond", record.speed)
            .put("message", record.message)
            .put("uri", record.uri)
            .put("displayName", record.displayName)
            .put("location", record.location)
            .put("mime", record.mime)
            .put("createdAt", record.createdAt)

    private fun startService(ticket: Long) {
        flushReleases()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        } catch (denied: RuntimeException) {
            serviceGone(ticket, "下载服务未能启动，请返回文件页继续")
        }
    }

    /** 仅在独立 dataSync Service 已进入前台后调用。 */
    fun runQueued(): Long {
        val ticket: Long
        synchronized(gate) {
            ticket = admissionGeneration
            if (draining) return ticket
            draining = true
        }
        worker.execute(this::drain)
        return ticket
    }

    fun serviceGone(ownedAdmission: Long, reason: String?) {
        synchronized(gate) {
            // 退休 Service 不得暂停仍在等待 onStartCommand 的新用户入队。
            if (ownedAdmission > 0 && ownedAdmission == admissionGeneration) pauseAll(reason)
        }
    }

    private fun drain() {
        while (true) {
            val record: Record
            val control: BackupControl
            synchronized(gate) {
                val next = records.values.firstOrNull { it.status == Status.QUEUED }
                if (next == null) {
                    draining = false
                    activeId = ""
                    activeControl = null
                    emit()
                    return
                }
                record = next
                activeId = record.id
                control = BackupControl(null)
                activeControl = control
                record.status = Status.DOWNLOADING
                record.message = "正在准备下载"
                saveQuietly(record)
                emit()
            }
            try {
                execute(record, control)
            } catch (error: Exception) {
                synchronized(gate) {
                    if (
                        record.status != Status.COMPLETE &&
                            record.status != Status.CANCELLED &&
                            record.status != Status.PAUSED
                    ) {
                        record.status = Status.FAILED
                        record.message = safeError(error)
                    }
                    record.speed = 0
                    try {
                        cleanupPending(record)
                    } catch (ignored: Exception) {}
                    saveQuietly(record)
                }
            } finally {
                synchronized(gate) {
                    activeConnection = null
                    activeId = ""
                    activeControl = null
                    emit()
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun execute(record: Record, control: BackupControl) {
        ensureTaskDir(record.id)
        val partial = partial(record)
        if (recoverPublication(record, control)) return
        cleanupPending(record)
        val offset = TransferFiles.size(partial)
        if (offset > record.artifact.bytes) throw IOException("FILE_PARTIAL_INVALID")
        if (root.usableSpace < TransferProtocol.requiredSpace(record.artifact.bytes, offset, true))
            throw IOException("FILE_SPACE_INSUFFICIENT")
        if (offset < record.artifact.bytes) {
            if (record.local) throw IOException("FILE_LOCAL_COPY_MISSING")
            val source = currentSource(control)
            stage(record, Status.DOWNLOADING, "正在核验源文件，较大文件可能需要数分钟", control)
            val resolved = resolve(source, record.artifact, control)
            record.artifact.requireSame(resolved)
            stage(record, Status.DOWNLOADING, "源文件已核验，正在连接下载", control)
            download(source, record, partial, offset, control)
        }
        if (record.artifact.bytes == 0L && TransferFiles.FS.stat(partial).type == "MISSING") {
            TransferFiles.write(partial, 0).use { empty -> empty.flush() }
        }
        stage(record, Status.VERIFYING, "正在校验完整文件", control)
        TransferFiles.read(partial).use { input ->
            TransferPublisher.require(
                record.artifact,
                FileIntegrity.copy(input, null, record.artifact.bytes, control::check),
            )
        }
        if (record.destination == "saveas" && record.targetUri.isEmpty()) {
            synchronized(gate) {
                control.check()
                record.downloaded = record.artifact.bytes
                record.status = Status.WAITING_DESTINATION
                record.message = "下载已校验，请选择保存位置"
                save(record)
            }
            return
        }
        stage(record, Status.PUBLISHING, "正在写入目标并读回校验", control)
        TransferPublisher.publish(
            context,
            partial,
            record.artifact,
            record.destination,
            record.id,
            if (record.targetUri.isEmpty()) null else Uri.parse(record.targetUri),
            control,
            gate,
            object : TransferPublisher.Journal {
                @Throws(Exception::class)
                override fun pending(uri: Uri, kind: String) {
                    synchronized(gate) {
                        control.check()
                        record.pendingUri = uri.toString()
                        record.pendingKind = kind
                        save(record)
                    }
                }

                @Throws(Exception::class)
                override fun complete(result: TransferPublisher.Result) {
                    finishPublication(record, result)
                }
            },
        )
    }

    @JvmRecord
    @ConsistentCopyVisibility
    private data class RuntimeSource
    private constructor(
        val base: String,
        val cookie: String,
        val identity: PreviewPageSession.Identity,
    ) {
        companion object {
            fun create(
                base: String,
                cookie: String,
                identity: PreviewPageSession.Identity,
            ): RuntimeSource = RuntimeSource(base, cookie, identity)
        }

        override fun toString(): String =
            "RuntimeSource[base=$base, cookie=$cookie, identity=$identity]"
    }

    @Throws(IOException::class)
    private fun currentSource(control: BackupControl): RuntimeSource {
        control.check()
        val harness = HarnessController.get(context)
        val identity = harness.readyWebPageIdentity ?: throw IOException("FILE_RUNTIME_NOT_READY")
        val base =
            WebPreviewPolicy.loopbackBaseUrl(identity.authUrl())
                ?: throw IOException("FILE_RUNTIME_NOT_READY")
        val cookie = harness.exchangeDshAuthCookie()
        if (
            cookie == null ||
                cookie.isEmpty() ||
                cookie.indexOf('\r') >= 0 ||
                cookie.indexOf('\n') >= 0
        )
            throw IOException("FILE_AUTH_REQUIRED")
        val source = RuntimeSource.create(base, cookie, identity)
        checkSource(source, control)
        return source
    }

    @Throws(IOException::class)
    private fun checkSource(source: RuntimeSource, control: BackupControl) {
        control.check()
        if (source.identity != HarnessController.get(context).readyWebPageIdentity)
            throw IOException("FILE_RUNTIME_CHANGED")
    }

    @Throws(Exception::class)
    private fun connect(
        source: RuntimeSource,
        path: String,
        control: BackupControl,
    ): HttpURLConnection {
        checkSource(source, control)
        val connection = URL(source.base + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TransferProtocol.CONNECT_TIMEOUT_MS
        connection.readTimeout = TransferProtocol.CONTENT_READ_TIMEOUT_MS
        connection.setRequestProperty("Cookie", source.cookie)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Origin", source.base.substring(0, source.base.length - 1))
        activeConnection = connection
        return connection
    }

    @Throws(Exception::class)
    private fun resolve(
        source: RuntimeSource,
        artifact: TransferArtifact,
        control: BackupControl,
    ): TransferArtifact {
        val connection = connect(source, "api/dsha/files/resolve", control)
        // resolve 回应前会重算源 SHA；5 GB 文件可能超出内容读取的空闲超时。
        connection.readTimeout = TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS
        try {
            control
                .onCancel { disconnectAsync(connection) }
                .use {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    val data =
                        JSONObject()
                            .put("id", artifact.id)
                            .put("sha256", artifact.sha256)
                            .put("bytes", artifact.bytes)
                            .toString()
                            .toByteArray(StandardCharsets.UTF_8)
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(data.size)
                    connection.outputStream.use { output -> output.write(data) }
                    if (connection.responseCode != 200)
                        throw IOException("FILE_HTTP_${connection.responseCode}")
                    val response = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            checkSource(source, control)
                            if (response.size() + n > 16_384)
                                throw IOException("FILE_MANIFEST_TOO_LARGE")
                            response.write(buffer, 0, n)
                        }
                    }
                    checkSource(source, control)
                    val value = JSONObject(response.toString(StandardCharsets.UTF_8.name()))
                    // 不使用响应给出的相对 contentUrl 选择主机。
                    value.remove("contentUrl")
                    return TransferArtifact.fromJson(value)
                }
        } finally {
            connection.disconnect()
            activeConnection = null
        }
    }

    @Throws(Exception::class)
    private fun download(
        source: RuntimeSource,
        record: Record,
        partial: File,
        requested: Long,
        control: BackupControl,
    ) {
        val connection =
            connect(source, "api/dsha/files/content?id=" + Uri.encode(record.artifact.id), control)
        connection.setRequestProperty("Range", "bytes=$requested-")
        connection.setRequestProperty("If-Range", "\"${record.artifact.sha256}\"")
        try {
            control
                .onCancel { disconnectAsync(connection) }
                .use {
                    var offset =
                        TransferProtocol.responseOffset(
                            connection.responseCode,
                            requested,
                            record.artifact.bytes,
                            connection.getHeaderField("Content-Range"),
                            connection.getHeaderField("Content-Length"),
                            connection.getHeaderField("ETag"),
                            record.artifact.sha256,
                        )
                    var last = SystemClock.elapsedRealtime()
                    var lastBytes = offset
                    connection.inputStream.use { input ->
                        TransferFiles.write(partial, offset).use { output ->
                            val buffer = ByteArray(65_536)
                            try {
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    checkSource(source, control)
                                    if (n > record.artifact.bytes - offset)
                                        throw IOException("FILE_LENGTH_INVALID")
                                    output.write(buffer, 0, n)
                                    offset += n
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - last >= 500 || offset == record.artifact.bytes) {
                                        synchronized(gate) {
                                            control.check()
                                            record.downloaded = offset
                                            record.speed =
                                                (offset - lastBytes) * 1000 / maxOf(1, now - last)
                                            record.message = "正在下载"
                                            save(record)
                                            emit()
                                        }
                                        last = now
                                        lastBytes = offset
                                    }
                                }
                            } finally {
                                output.flush()
                            }
                        }
                    }
                    if (offset != record.artifact.bytes)
                        throw IOException("FILE_TRANSFER_INTERRUPTED")
                    checkSource(source, control)
                }
        } finally {
            connection.disconnect()
            activeConnection = null
        }
    }

    @Throws(Exception::class)
    private fun stage(record: Record, status: Status, message: String, control: BackupControl) {
        synchronized(gate) {
            control.check()
            record.status = status
            record.message = message
            record.speed = 0
            save(record)
            emit()
        }
    }

    private fun busy(): Boolean = activeId.isNotEmpty() || records.values.any { running(it.status) }

    private fun emit() {
        synchronized(gate) {
            val tasks = ArrayList<DownloadTask>()
            for (record in records.values) tasks.add(record.snapshot())
            Collections.reverse(tasks)
            state.postValue(State(tasks, busy(), activeId))
        }
    }

    @Throws(IOException::class)
    private fun require(id: String?): Record =
        records[id] ?: throw IOException("FILE_TASK_NOT_FOUND")

    @Throws(IOException::class)
    private fun taskDir(id: String?): File {
        if (id == null || !id.matches(Regex("[a-f0-9-]{36}"))) throw IOException("FILE_TASK_ID")
        return File(root, id)
    }

    @Throws(IOException::class)
    private fun ensureTaskDir(id: String): File {
        taskDir(id)
        TransferFiles.directory(privateFiles, "transfers")
        return TransferFiles.directory(root, id)
    }

    @Throws(IOException::class)
    private fun partial(record: Record): File = File(taskDir(record.id), "partial")

    @Throws(IOException::class)
    private fun journal(id: String): File = File(taskDir(id), "task.json")

    @Throws(Exception::class)
    private fun save(record: Record) {
        val dir = ensureTaskDir(record.id)
        val value =
            json(record)
                .put("local", record.local)
                .put("pendingUri", record.pendingUri)
                .put("pendingKind", record.pendingKind)
                .put("targetUri", record.targetUri)
        TransferFiles.FS.atomic(
            dir,
            "task.json",
            value.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun saveQuietly(record: Record) {
        try {
            save(record)
        } catch (failure: Exception) {
            record.message = "记录写入失败，进度文件保留"
        }
    }

    private fun restore() {
        val dirs = root.listFiles() ?: return
        dirs.sortWith(compareBy { it.name })
        for (dir in dirs) {
            if (records.size >= 200 || !dir.name.matches(Regex("[a-f0-9-]{36}"))) continue
            try {
                if (TransferFiles.FS.stat(dir).type != "DIRECTORY") continue
                var journal = File(dir, "task.json")
                if (TransferFiles.FS.stat(journal).type == "MISSING")
                    journal = File(dir, "task.json.previous")
                val data = TransferFiles.FS.small(journal, 32_768)
                val value = JSONObject(String(data, StandardCharsets.UTF_8))
                val record =
                    create(
                        TransferArtifact.fromJson(value.getJSONObject("artifact")),
                        value.getString("destination"),
                        value.optBoolean("local"),
                    )
                record.id = dir.name
                if (record.id != value.getString("id")) continue
                record.status = Status.valueOf(value.getString("status"))
                record.uri = value.optString("uri")
                record.location = value.optString("location")
                record.displayName = value.optString("displayName", record.artifact.name)
                record.mime = value.optString("mime", record.artifact.mime)
                record.createdAt = value.getLong("createdAt")
                record.pendingUri = value.optString("pendingUri")
                record.pendingKind = value.optString("pendingKind")
                record.targetUri = value.optString("targetUri")
                record.message = value.optString("message")
                record.downloaded = TransferFiles.size(partial(record))
                if (record.status == Status.COMPLETE) record.downloaded = record.artifact.bytes
                if (running(record.status)) {
                    record.status = Status.PAUSED
                    record.message = "应用或手机已重启，进度保留，请手动继续"
                }
                records[record.id] = record
            } catch (ignored: Exception) {
                // 保留损坏/未知日志和私有字节用于诊断，绝不批量删除。
            }
        }
    }

    @Throws(Exception::class)
    private fun cleanupPending(record: Record) {
        if (record.pendingUri.isEmpty()) return
        val uri = Uri.parse(record.pendingUri)
        if (record.pendingKind == "media") {
            // IS_PENDING=0 与保存 COMPLETE 之间崩溃，不能删除已公开文件。
            context.contentResolver
                .query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.IS_PENDING),
                    null,
                    null,
                    null,
                )
                .use { cursor ->
                    if (cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1)
                        context.contentResolver.delete(uri, null, null)
                }
        }
        // SAF 无 pending 位；保留结果未知的文档，不能删除用户可见字节。
        record.pendingUri = ""
        record.pendingKind = ""
        if (record.destination == "saveas") record.targetUri = ""
    }

    @Throws(Exception::class)
    private fun recoverPublication(record: Record, control: BackupControl): Boolean {
        if (record.pendingKind != "media" || record.pendingUri.isEmpty()) return false
        val uri = Uri.parse(record.pendingUri)
        val name: String?
        context.contentResolver
            .query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            .use { cursor ->
                if (cursor == null || !cursor.moveToFirst() || cursor.getInt(0) != 0) return false
                name = cursor.getString(1)
            }
        DocumentStreams.input(context.contentResolver, uri, control).use { input ->
            TransferPublisher.require(
                record.artifact,
                FileIntegrity.copy(input, null, record.artifact.bytes, control::check),
            )
        }
        synchronized(gate) {
            control.check()
            finishPublication(
                record,
                TransferPublisher.Result(
                    uri,
                    name,
                    record.artifact.mime,
                    if (record.destination == "gallery") "Pictures/DSHA" else "Download/DSHA",
                ),
            )
        }
        return true
    }

    @Throws(Exception::class)
    private fun finishPublication(record: Record, result: TransferPublisher.Result) {
        record.uri = result.uri!!.toString()
        record.displayName = result.name
        record.mime = result.mime
        record.location = result.location
        record.pendingUri = ""
        record.pendingKind = ""
        record.targetUri = ""
        record.downloaded = record.artifact.bytes
        record.speed = 0
        record.status = Status.COMPLETE
        record.message = "文件已保存并校验"
        save(record)
        try {
            val partial = partial(record)
            if (TransferFiles.FS.stat(partial).type == "FILE") TransferFiles.FS.delete(partial)
        } catch (ignored: IOException) {
            // 已持久发布的文件仍是成功结果。
        }
        releaseWhenUnreferenced(record.artifact)
    }

    /** 释放代理清单只是留存记账，不能决定发布是否成功。 */
    private fun releaseWhenUnreferenced(artifact: TransferArtifact) {
        if (artifact.id.startsWith("local-")) return
        synchronized(gate) {
            pendingReleases.add(artifact.id)
            saveReleases()
        }
        flushReleases()
    }

    private fun flushReleases() {
        val attempted: Set<String>
        synchronized(gate) {
            if (releasing || pendingReleases.isEmpty()) return
            releasing = true
            attempted = HashSet(pendingReleases)
        }
        releaseWorker.execute {
            try {
                releasePending()
            } finally {
                synchronized(gate) {
                    releasing = false
                    if (pendingReleases.any { !attempted.contains(it) }) flushReleases()
                }
            }
        }
    }

    private fun releasePending() {
        val ids: List<String>
        synchronized(gate) { ids = ArrayList(pendingReleases) }
        for (id in ids) {
            val referenced =
                synchronized(gate) {
                    records.values.any {
                        it.artifact.id == id &&
                            it.status != Status.COMPLETE &&
                            it.status != Status.CANCELLED
                    }
                }
            if (referenced) continue
            var connection: HttpURLConnection? = null
            try {
                val control = BackupControl(null)
                val source = currentSource(control)
                // 尽力释放请求不替换活动传输连接。
                checkSource(source, control)
                connection =
                    URL(source.base + "api/dsha/files/release").openConnection()
                        as HttpURLConnection
                connection.connectTimeout = TransferProtocol.RELEASE_TIMEOUT_MS
                connection.readTimeout = TransferProtocol.RELEASE_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.setRequestProperty("Cookie", source.cookie)
                connection.setRequestProperty(
                    "Origin",
                    source.base.substring(0, source.base.length - 1),
                )
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val body = JSONObject().put("id", id).toString().toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output -> output.write(body) }
                val status = connection.responseCode
                if (status == 200 || status == 404 || status == 410) {
                    synchronized(gate) {
                        pendingReleases.remove(id)
                        saveReleases()
                    }
                } else return
            } catch (ignored: Exception) {
                return // 保留意图，等待当前运行时/鉴权恢复。
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun saveReleases() {
        try {
            saveReleasesRequired()
        } catch (ignored: Exception) {
            // 任务日志也保留 COMPLETE/CANCELLED 的释放意图。
        }
    }

    @Throws(Exception::class)
    private fun saveReleasesRequired() {
        TransferFiles.FS.atomic(
            root,
            "releases.json",
            JSONArray(pendingReleases).toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun restoreReleases() {
        try {
            var file = File(root, "releases.json")
            if (TransferFiles.FS.stat(file).type == "MISSING")
                file = File(root, "releases.json.previous")
            val values =
                JSONArray(String(TransferFiles.FS.small(file, 1024 * 1024), StandardCharsets.UTF_8))
            for (index in 0 until values.length()) {
                val id = values.getString(index)
                if (id.matches(Regex("[a-zA-Z0-9_-]{1,256}"))) pendingReleases.add(id)
            }
        } catch (ignored: Exception) {}
        for (record in records.values) {
            if (
                !record.local &&
                    (record.status == Status.COMPLETE || record.status == Status.CANCELLED)
            )
                pendingReleases.add(record.artifact.id)
        }
    }

    companion object {
        private var instance: DownloadEngine? = null

        @JvmStatic
        fun get(context: Context?): DownloadEngine =
            synchronized(DownloadEngine::class.java) {
                if (instance == null) instance = DownloadEngine(context!!.applicationContext)
                instance!!
            }

        @JvmStatic
        private fun running(status: Status): Boolean =
            status == Status.QUEUED ||
                status == Status.DOWNLOADING ||
                status == Status.VERIFYING ||
                status == Status.PUBLISHING

        @JvmStatic
        private fun safeError(error: Exception): String {
            var cause: Throwable? = error
            while (cause != null) {
                if (
                    cause is android.system.ErrnoException &&
                        (cause.errno == android.system.OsConstants.ENOSPC ||
                            cause.errno == android.system.OsConstants.EDQUOT)
                )
                    return "FILE_SPACE_INSUFFICIENT"
                val text = cause.message
                if (
                    text != null &&
                        (text == "NO_SPACE" ||
                            text.contains("ENOSPC") ||
                            text.contains("No space left on device"))
                )
                    return "FILE_SPACE_INSUFFICIENT"
                cause = cause.cause
            }
            val code = error.message
            if (code != null && code.matches(Regex("FILE_[A-Z0-9_]{1,80}"))) return code
            if (error is SecurityException) return "FILE_PERMISSION_DENIED"
            return "FILE_TRANSFER_FAILED"
        }

        @JvmStatic
        private fun disconnectAsync(connection: HttpURLConnection) {
            val close = Thread(connection::disconnect, "file-download-cancel")
            close.isDaemon = true
            close.start()
        }
    }
}
