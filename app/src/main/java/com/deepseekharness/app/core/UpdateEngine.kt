package com.deepseekharness.app.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.deepseekharness.app.BuildConfig
import com.deepseekharness.app.DshaApp
import com.deepseekharness.app.UpdateDownloadService
import com.deepseekharness.app.core.UpdateRepository.Stage
import com.deepseekharness.app.core.UpdateRepository.State
import com.deepseekharness.app.util.ResumableDownload
import com.deepseekharness.app.util.SensitiveData
import com.deepseekharness.app.util.UiText
import com.deepseekharness.app.util.UpdateFailures
import com.deepseekharness.app.util.UpdateLocations
import com.deepseekharness.app.util.UpdateManifest
import com.deepseekharness.app.util.UpdatePolicy
import com.deepseekharness.app.util.UpdateRedirects
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/** 版本清单、可取消下载及 APK 核验；不持有 Activity，旋转不会重新下载。 */
class UpdateEngine
internal constructor(
    private val context: Context,
    source: Transport?,
    private val io: Executor,
) {
    fun interface Transport {
        @Throws(Exception::class) fun open(url: String, offset: Long): HttpURLConnection
    }

    private val transport = source ?: Transport { url, offset -> connect(url, offset) }
    private val busy = AtomicBoolean()
    @Volatile private var actionRevision = 0L
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var channel: String
    // 预览通道也可选中稳定版，来源必须记录所选通道，不能从 release.channel 推断。
    @Volatile private var candidateChannel: String? = null
    @Volatile private var candidate: UpdatePolicy.Release? = null
    @Volatile private var verifiedApk: File? = null
    private val state = MutableLiveData(State(UiText.text("尚未检查更新"), false, 0, 0, null, null))
    @Volatile private var phase = ""
    @Volatile private var stopReason = ""
    @Volatile private var runningDownload = false
    @Volatile private var checking = false
    // 检查中切换通道：当前检查作废，收尾后按新通道重查。与 cancelled 一起在 commitLock 下读写。
    @Volatile private var recheckPending = false
    private val commitLock = Any()
    private val main = Handler(Looper.getMainLooper())

    // 生产实例及串行队列由 Application 通过三参构造装配，保留旧二参 null 调用无歧义。

    // 保留同包 Java 夹具的构造形态；生产入口必须经 get/Application。
    internal constructor(app: Context) : this(app, null, Executors.newSingleThreadExecutor())

    internal constructor(
        app: Context,
        source: Transport?,
    ) : this(app, source, Executors.newSingleThreadExecutor())

    init {
        val saved =
            prefs().getString("channel", UpdatePolicy.defaultChannel(BuildConfig.VERSION_NAME))
        channel = if (UpdatePolicy.PREVIEW == saved) UpdatePolicy.PREVIEW else UpdatePolicy.STABLE
        restore()
    }

    private fun prefs(): SharedPreferences = context.getSharedPreferences("dsha-updates", 0)

    fun hasTask(): Boolean = candidate != null || busy.get()

    fun shouldResume(): Boolean = currentCandidate() && phase == "downloading"

    fun state(): LiveData<State> = state

    fun channel(): String = channel

    /** 页面请求校验借用同一应用队列，不再创建页面级或静态执行器。 */
    fun verificationExecutor(): Executor = io

    fun permissionRevision(): Long = actionRevision

    /** 主动检查、下载、取消和安装请求都退休先前权限票，不借候选相同恢复旧意图。 */
    fun invalidatePermissionRequests() {
        actionRevision++
    }

    /** 系统回调只有 16 位请求号；本机持久单调分配，拒绝耗尽或损坏的计数而不复用旧号。 */
    @Synchronized
    @Throws(IOException::class)
    fun nextPermissionRequestCode(): Int {
        val last =
            prefs().getString("permission_request_code", "16383")?.toIntOrNull()
                ?: throw IOException("UPDATE_PERMISSION_REQUEST_INVALID")
        if (last < 0x3fff || last >= 0xfffe)
            throw IOException("UPDATE_PERMISSION_REQUEST_EXHAUSTED")
        val next = last + 1
        if (!prefs().edit().putString("permission_request_code", next.toString()).commit())
            throw IOException("UPDATE_PERMISSION_REQUEST_NOT_SAVED")
        return next
    }

    /** 权限回调只可续接最初观察的候选，不能把旧授权转交给后来检查出的包。 */
    fun isCurrentCandidate(release: UpdatePolicy.Release?, sourceChannel: String?): Boolean =
        !busy.get() &&
            currentCandidate() &&
            release != null &&
            candidate === release &&
            channel == sourceChannel

    /** 切换通道并重新检查；下载 / 校验进行中仍锁定，避免换掉正在传输的目标包。 */
    fun setChannel(value: String?) {
        if (channel == value) return
        if (value == null || (value != UpdatePolicy.STABLE && value != UpdatePolicy.PREVIEW)) return
        val supersede = busy.get()
        if (supersede && (runningDownload || !checking)) return
        invalidatePermissionRequests()
        channel = value
        prefs().edit().putString("channel", value).apply()
        if (!supersede) {
            check()
            return
        }
        synchronized(commitLock) {
            recheckPending = true
            cancelled = true
        }
        connection?.disconnect()
        state.value = snapshot(checkingMessage(), true)
    }

    private fun checkingMessage(): String =
        UiText.format(
            "正在检查%s更新…",
            if (channel == UpdatePolicy.PREVIEW) UiText.text("预览版") else UiText.text("稳定版"),
        )

    fun cancel() {
        stop(UiText.text("已取消下载，进度已保留"), "cancelled")
    }

    /** 格式化前终止并收敛更新线程，避免它在偏好和私有目录清空后又写回旧候选。 */
    @Throws(IOException::class)
    fun stopForFactoryReset() {
        val reason = UiText.choose("格式化已停止更新任务", "Formatting stopped the update task")
        if (Looper.myLooper() == Looper.getMainLooper()) stop(reason, "cancelled")
        else {
            val requested = CountDownLatch(1)
            main.post {
                try {
                    stop(reason, "cancelled")
                } finally {
                    requested.countDown()
                }
            }
            try {
                if (!requested.await(5, TimeUnit.SECONDS))
                    throw IOException("FORMAT_UPDATE_STOP_TIMEOUT")
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("FORMAT_UPDATE_INTERRUPTED")
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (
            (busy.get() || checking || runningDownload) && SystemClock.elapsedRealtime() < deadline
        ) {
            try {
                Thread.sleep(25)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("FORMAT_UPDATE_INTERRUPTED")
            }
        }
        if (busy.get() || checking || runningDownload)
            throw IOException("FORMAT_UPDATE_STILL_RUNNING")
        candidate = null
        candidateChannel = null
        verifiedApk = null
        phase = ""
        stopReason = ""
        state.postValue(
            State(
                UiText.choose("等待重新检查更新", "Waiting for a fresh update check"),
                false,
                0,
                0,
                null,
                null,
            )
        )
    }

    private fun submit(message: String, task: () -> String) {
        if (!busy.compareAndSet(false, true)) return
        invalidatePermissionRequests()
        checking = true
        cancelled = false
        state.value = snapshot(message, true)
        io.execute {
            val result =
                try {
                    task()
                } catch (error: Exception) {
                    if (cancelled) UiText.text("已取消，可重新检查或下载")
                    else
                        String.format(
                            Locale.ROOT,
                            UiText.choose("暂时无法确认更新：%s", "Unable to confirm updates: %s"),
                            UpdateFailures.message(error),
                        ) +
                            if (currentCandidate())
                                UiText.choose(
                                    "\n此通道上次结果已保留，可重试检查或使用已下载包",
                                    "\nThe previous result for this channel is kept. Retry the check or use the downloaded package.",
                                )
                            else ""
                }
            connection = null
            DiagnosticLog.record(context, "APP_UPDATE", result)
            main.post {
                busy.set(false)
                checking = false
                if (recheckPending) {
                    recheckPending = false
                    check()
                    return@post
                }
                finishState(if (verifiedApk != null) "ready" else "available", result)
            }
        }
    }

    /** 兼容旧 Java 调用；APK 无人触发的检查入口已退休，历史偏好不再授予检查意图。 */
    @Suppress("UNUSED_PARAMETER") fun checkOnStartup(enabled: Boolean) {}

    /** 兼容旧 Java 提示领取入口；普通启动不会生成更新提示。 */
    fun startupNotice(): UpdatePolicy.Release? = null

    @Suppress("UNUSED_PARAMETER") fun markStartupNoticeShown(shown: UpdatePolicy.Release?) {}

    /** 仅由用户主动检查或切换通道的请求调用。 */
    fun check() {
        val requestedChannel = channel
        submit(checkingMessage()) {
            val conn = transport.open(FEED, 0)
            connection = conn
            val raw: ByteArray?
            try {
                val status = conn.responseCode
                raw =
                    if (status == 404 || status == 410) null
                    else {
                        if (status != 200) throw UpdateFailures.HttpFailure(status)
                        conn.inputStream.use { input ->
                            ByteArrayOutputStream().use { bytes ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    ensureActive()
                                    if (bytes.size() + n > 1024 * 1024)
                                        throw UpdateFailures.InvalidManifest()
                                    bytes.write(buffer, 0, n)
                                }
                                bytes.toByteArray()
                            }
                        }
                    }
            } finally {
                conn.disconnect()
            }
            val options =
                if (raw == null) emptyList()
                else UpdateManifest.parse(raw, context.packageName, installedSignerHashes())
            ensureActive()
            val selected =
                UpdatePolicy.select(
                    options,
                    BuildConfig.VERSION_CODE,
                    "standard",
                    Build.VERSION.SDK_INT,
                    requestedChannel,
                )
            synchronized(commitLock) {
                ensureActive()
                val previous = candidate
                if (
                    requestedChannel != candidateChannel ||
                        previous == null ||
                        selected == null ||
                        previous.versionCode != selected.versionCode ||
                        previous.version != selected.version ||
                        previous.bytes != selected.bytes ||
                        !previous.sha256.equals(selected.sha256, ignoreCase = true)
                )
                    verifiedApk = null
                candidate = selected
                candidateChannel = if (selected == null) null else requestedChannel
            }
            val current = candidate
            if (current != null) UiText.format("发现新版本 %s", current.version)
            else UiText.choose("暂无可用更新", "No updates available")
        }
    }

    fun download() {
        if (!currentCandidate() || !candidate!!.valid() || !busy.compareAndSet(false, true)) return
        invalidatePermissionRequests()
        cancelled = false
        stopReason = ""
        verifiedApk = null
        try {
            save("downloading", UiText.text("正在准备下载…"))
            val release = candidate!!
            state.value =
                State(
                    UiText.text("正在准备下载，可离开此页面"),
                    Stage.DOWNLOADING,
                    partial().length(),
                    release.bytes,
                    release,
                    null,
                    channel,
                    candidateChannel,
                )
            ContextCompat.startForegroundService(
                context,
                Intent(context, UpdateDownloadService::class.java),
            )
        } catch (error: Exception) {
            startFailed(error)
        }
    }

    fun startFailed(error: Exception) {
        runningDownload = false
        busy.set(false)
        finishState(
            "paused",
            UiText.format("下载尚未开始：%s；请在更新页重试", SensitiveData.redact(error.message)),
        )
    }

    /** 由前台服务调用；进程重建后从持久清单和实际文件长度恢复。 */
    @Synchronized
    fun runDownload() {
        if (runningDownload || !shouldResume()) return
        runningDownload = true
        busy.set(true)
        cancelled = false
        stopReason = ""
        val release = candidate!!
        state.value =
            State(
                UiText.text("正在恢复下载…"),
                Stage.DOWNLOADING,
                partial().length(),
                release.bytes,
                release,
                null,
                channel,
                candidateChannel,
            )
        io.execute {
            var message: String
            var completedPhase: String
            try {
                val partial = partial()
                val apk = apk()
                val directory = partial.parentFile!!
                if (!directory.isDirectory && !directory.mkdirs())
                    throw IOException(UiText.text("无法创建更新目录"))
                if (
                    directory.usableSpace <
                        maxOf(0, release.bytes - partial.length()) + 32L * 1024 * 1024
                )
                    throw IOException(UiText.text("存储不足，请留出剩余下载大小加 32 MiB 空间"))
                ResumableDownload.transfer(
                    partial,
                    release.bytes,
                    release.sha256,
                    { offset -> transport.open(release.url, offset).also { connection = it } },
                    object : ResumableDownload.Progress {
                        private var last = 0L

                        override fun check() {
                            ensureActive()
                        }

                        override fun changed(n: Long, total: Long) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - last >= 500 || n == total) {
                                state.postValue(
                                    State(
                                        UiText.text("正在下载，离开页面后继续"),
                                        Stage.DOWNLOADING,
                                        n,
                                        total,
                                        release,
                                        null,
                                        channel,
                                        candidateChannel,
                                    )
                                )
                                last = now
                            }
                        }
                    },
                )
                ensureActive()
                state.postValue(
                    State(
                        UiText.text("正在核验包名、版本与签名…"),
                        Stage.VERIFYING,
                        release.bytes,
                        release.bytes,
                        release,
                        null,
                        channel,
                        candidateChannel,
                    )
                )
                validatePackage(partial, release)
                ensureActive()
                android.system.Os.rename(partial.absolutePath, apk.absolutePath)
                verifiedApk = apk
                message = UiText.text("下载及校验完成，点击「安装更新」继续")
                completedPhase = "ready"
            } catch (error: Exception) {
                verifiedApk = null
                message =
                    if (cancelled) stopReason
                    else UiText.format("下载暂停：%s；可继续下载", UpdateFailures.message(error))
                completedPhase =
                    if (cancelled) {
                        if (UiText.text("已取消下载，进度已保留") == stopReason) "cancelled" else "paused"
                    } else "paused"
            }
            connection = null
            main.post {
                runningDownload = false
                busy.set(false)
                finishState(completedPhase, message)
            }
        }
    }

    fun pause(reason: String) {
        stop(reason, "paused")
    }

    private fun stop(reason: String, nextPhase: String) {
        invalidatePermissionRequests()
        recheckPending = false
        cancelled = true
        stopReason = reason
        try {
            save(nextPhase, reason)
        } catch (ignored: Exception) {}
        connection?.disconnect()
        if (!runningDownload && !checking) {
            busy.set(false)
            finishState(nextPhase, reason)
        }
    }

    private fun partial(): File =
        File(context.filesDir, "updates/" + candidate!!.sha256.lowercase(Locale.ROOT) + ".part")

    private fun apk(): File =
        File(context.filesDir, "updates/" + candidate!!.sha256.lowercase(Locale.ROOT) + ".apk")

    private fun currentCandidate(): Boolean = candidate != null && channel == candidateChannel

    private fun snapshot(text: String, working: Boolean): State {
        val matches = currentCandidate()
        val current = candidate
        var message = text
        if (current != null) {
            message =
                when {
                    candidateChannel == null ->
                        UiText.format(
                            "%s\n已保留 %s 及已有下载文件，但无法确认查询来源；请联网重新检查，确认当前通道后才能下载/安装",
                            message,
                            current.version,
                        )
                    matches -> UiText.format("%s\n候选来源：%s", message, channelName(candidateChannel))
                    else ->
                        UiText.format(
                            "%s\n已保留 %s（来源：%s），与当前%s不匹配；切回来源通道或重新检查后才能下载/安装",
                            message,
                            current.version,
                            channelName(candidateChannel),
                            channelName(channel),
                        )
                }
        }
        return State(
            message,
            if (working) Stage.CHECKING else Stage.IDLE,
            if (!matches) 0 else if (verifiedApk != null) current!!.bytes else partial().length(),
            if (matches) current!!.bytes else 0,
            if (matches) current else null,
            if (matches) verifiedApk else null,
            channel,
            candidateChannel,
        )
    }

    private fun finishState(nextPhase: String, text: String) {
        var message = text
        try {
            save(nextPhase, message)
        } catch (error: Exception) {
            message = UiText.format("%s；保存任务状态失败，下次需要重新检查", message)
        }
        DiagnosticLog.record(context, "APP_UPDATE", message)
        state.value = snapshot(message, false)
    }

    private fun save(nextPhase: String, message: String) {
        val doc = JSONObject()
        val r = candidate
        if (r != null) {
            doc.put("code", r.versionCode)
                .put("version", r.version)
                .put("channel", r.channel)
                .put("flavor", r.flavor)
                .put("minSdk", r.minSdk)
                .put("abi", r.abi)
                .put("url", r.url)
                .put("sha256", r.sha256)
                .put("bytes", r.bytes)
                .put("notes", r.notes)
                .put("pageUrl", r.pageUrl)
                .put("checkedChannel", candidateChannel ?: "")
        }
        if (
            !prefs()
                .edit()
                .putString("task", doc.toString())
                .putString("phase", nextPhase)
                .putString("message", message)
                .commit()
        )
            throw IOException(UiText.text("任务状态无法写入存储"))
        phase = nextPhase
    }

    private fun restore() {
        try {
            val doc = JSONObject(prefs().getString("task", "{}"))
            if (!doc.has("code")) return
            val r =
                UpdatePolicy.Release(
                    doc.getInt("code"),
                    doc.getString("version"),
                    doc.getString("channel"),
                    doc.getString("flavor"),
                    doc.getInt("minSdk"),
                    doc.getString("abi"),
                    doc.getString("url"),
                    doc.getString("sha256"),
                    doc.getLong("bytes"),
                    doc.optString("notes"),
                    doc.getString("pageUrl"),
                )
            candidateChannel =
                UpdatePolicy.restoreCheckedChannel(
                    doc.has("checkedChannel"),
                    doc.optString("checkedChannel", ""),
                    r.channel,
                )
            candidate =
                UpdatePolicy.select(
                    listOf(r),
                    BuildConfig.VERSION_CODE,
                    "standard",
                    Build.VERSION.SDK_INT,
                    candidateChannel ?: UpdatePolicy.PREVIEW,
                )
            if (candidate == null) return
            phase = prefs().getString("phase", "paused") ?: "paused"
            val retained = apk()
            // rename 后、ready 回执落盘前可能被终止；先观察已命名的包，不重下已完成的字节。
            // 文件存在只提供安装前复核入口，installableApk 仍完整检查摘要、包名、版本与签名。
            val completedWithoutReceipt =
                phase == "downloading" && retained.isFile && !partial().exists()
            verifiedApk =
                if ((phase == "ready" || completedWithoutReceipt) && retained.isFile) retained
                else null
            if (completedWithoutReceipt) phase = "ready"
            val restored =
                snapshot(
                    if (verifiedApk != null) UiText.text("安装包已保留，安装前将重新校验")
                    else UiText.text("更新候选已恢复，可重新检查或继续下载"),
                    false,
                )
            if (Looper.myLooper() == Looper.getMainLooper()) state.value = restored
            else state.postValue(restored)
        } catch (ignored: Exception) {
            candidate = null
            candidateChannel = null
            verifiedApk = null
        }
    }

    @Throws(Exception::class)
    fun installableApk(): File {
        val release = candidate
        val file = verifiedApk
        val sourceChannel = candidateChannel
        if (!isCurrentInstall(file, release, sourceChannel))
            throw IOException(UiText.text("候选来源与当前通道不匹配或任务已改变，请重新检查"))
        if (release == null || file == null || !file.isFile)
            throw IOException(UiText.text("请先下载并校验安装包"))
        try {
            ResumableDownload.verify(file, release.bytes, release.sha256)
            validatePackage(file, release)
        } catch (error: Exception) {
            main.post {
                if (verifiedApk === file && candidate === release) {
                    verifiedApk = null
                    finishState(
                        "paused",
                        UiText.format("安装前校验失败：%s", SensitiveData.redact(error.message)),
                    )
                }
            }
            throw error
        }
        if (!isCurrentInstall(file, release, sourceChannel))
            throw IOException(UiText.text("校验期间通道或候选已改变，请重试安装"))
        return file
    }

    /** 领取安装结果时再次核对身份，阻止校验期间发生的检查/切通道竞态。 */
    fun isCurrentInstall(
        file: File?,
        release: UpdatePolicy.Release?,
        sourceChannel: String?,
    ): Boolean =
        !busy.get() &&
            currentCandidate() &&
            release != null &&
            candidate === release &&
            file != null &&
            file == verifiedApk &&
            channel == sourceChannel

    @Throws(Exception::class)
    fun validatePackage(apk: File, release: UpdatePolicy.Release) {
        val pm = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val next = pm.getPackageArchiveInfo(apk.absolutePath, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (next == null || installed.packageName != next.packageName)
            throw IOException(UiText.text("安装包不是 DSHA"))
        val app = next.applicationInfo
        if (app == null || (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            throw IOException(UiText.text("安装包不是正式非调试版本"))
        val code =
            if (Build.VERSION.SDK_INT >= 28) next.longVersionCode else next.versionCode.toLong()
        if (code != release.versionCode.toLong() || code <= BuildConfig.VERSION_CODE)
            throw IOException(UiText.text("安装包版本不匹配或不是更新版本"))
        if (Build.VERSION.SDK_INT >= 24 && app.minSdkVersion > Build.VERSION.SDK_INT)
            throw IOException(UiText.text("安装包不支持当前 Android 版本"))
        if (release.version != next.versionName) throw IOException(UiText.text("安装包不是当前高/低安卓版本"))
        val oldSign = signatures(installed)
        val newSign = signatures(next)
        if (oldSign.isEmpty() || newSign.size != oldSign.size)
            throw IOException(UiText.text("安装包签名不匹配"))
        val oldHashes = oldSign.map {
            hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
        val newHashes = newSign.map {
            hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
        if (!UpdatePolicy.installedSignersMatch(oldHashes, newHashes))
            throw IOException(UiText.text("签名与已安装版本不同，已阻止安装"))
    }

    private fun signatures(info: PackageInfo): Array<Signature> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners ?: emptyArray()
        else info.signatures ?: emptyArray()

    private fun installedSignerHashes(): List<String> {
        val flags =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        return signatures(installed).map {
            hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
    }

    private fun connect(target: String, offset: Long): HttpURLConnection =
        UpdateRedirects.open(
            target,
            offset,
            "DSHA/" + BuildConfig.VERSION_NAME,
            { address ->
                val conn = URL(address).openConnection() as HttpURLConnection
                connection = conn
                (conn as HttpsURLConnection).sslSocketFactory = TrustedNetwork.sockets(context)
                conn
            },
            { ensureActive() },
        )

    private fun ensureActive() {
        if (cancelled) throw IOException(UiText.text("已取消"))
    }

    companion object {
        const val FEED = UpdateLocations.FEED

        @JvmStatic fun get(context: Context): UpdateEngine = DshaApp.from(context).updateEngine()

        @JvmStatic
        fun channelName(value: String?): String =
            if (value == UpdatePolicy.PREVIEW) UiText.text("预览通道") else UiText.text("稳定通道")

        private fun hex(bytes: ByteArray): String = buildString {
            for (b in bytes) append(String.format(Locale.ROOT, "%02x", b.toInt() and 255))
        }
    }
}
