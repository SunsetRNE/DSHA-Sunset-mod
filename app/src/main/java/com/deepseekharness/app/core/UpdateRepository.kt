package com.deepseekharness.app.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.deepseekharness.app.util.SensitiveData
import com.deepseekharness.app.util.UiText
import com.deepseekharness.app.util.UpdatePolicy
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/** 页面只订阅应用范围的下载状态；关闭页面不会取消前台下载。 */
class UpdateRepository : AndroidViewModel {
    private val engine: UpdateEngine
    private val verifyExecutor: Executor
    private val verifyApk: Callable<File>
    private val main = Handler(Looper.getMainLooper())
    private val installation = MutableLiveData(InstallState(false, null, null))
    private var installRequest = 0L
    private var cleared = false
    private var installRelease: UpdatePolicy.Release? = null
    private var installChannel: String? = null

    private class PendingPermission(
        val code: Int,
        val release: UpdatePolicy.Release,
        val channel: String,
        val apk: File?,
        val revision: Long,
    )

    private var downloadPermission: PendingPermission? = null
    private var installPermission: PendingPermission? = null

    private fun clearPermissions() {
        downloadPermission = null
        installPermission = null
    }

    private fun permissionMatches(request: PendingPermission): Boolean =
        !cleared &&
            !installationPending() &&
            request.revision == engine.permissionRevision() &&
            if (request.apk == null) engine.isCurrentCandidate(request.release, request.channel)
            else engine.isCurrentInstall(request.apk, request.release, request.channel)

    private fun preparePermission(install: Boolean): Int {
        if (cleared || installationPending()) return -1
        clearPermissions()
        val current = state().value ?: return -1
        val release = current.release ?: return -1
        val channel = engine.channel()
        if (!engine.isCurrentCandidate(release, channel) || (install && current.apk == null))
            return -1
        return try {
            val request =
                PendingPermission(
                    engine.nextPermissionRequestCode(),
                    release,
                    channel,
                    if (install) current.apk else null,
                    engine.permissionRevision(),
                )
            if (install) installPermission = request else downloadPermission = request
            request.code
        } catch (failure: Exception) {
            installFailed(
                UiText.choose(
                    "无法登记权限请求，请稍后重试",
                    "Unable to register the permission request. Try again later.",
                )
            )
            -1
        }
    }

    fun prepareDownloadPermission(): Int = preparePermission(false)

    fun prepareInstallPermission(): Int = preparePermission(true)

    fun hasInstallPermissionRequest(): Boolean = installPermission != null

    fun hasDownloadPermissionRequest(): Boolean = downloadPermission != null

    fun restoreInterruptedDownloadPermission(wasPending: Boolean) {
        if (
            wasPending &&
                downloadPermission == null &&
                installPermission == null &&
                !installationPending() &&
                installation.value!!.error == null
        )
            installFailed(
                UiText.choose(
                    "下载请求已因进程重建中断，请重新点击下载",
                    "The download request was interrupted. Tap Download again.",
                )
            )
    }

    fun discardInstallPermission(code: Int): Boolean {
        if (installPermission?.code != code) return false
        installPermission = null
        return true
    }

    /** 先消费精确请求号，再续接原下载；重复/迟到回调不能领取同候选的新请求。 */
    fun downloadAfterPermission(code: Int): Boolean {
        val request = downloadPermission ?: return false
        if (request.code != code) return false
        downloadPermission = null
        if (!permissionMatches(request)) return false
        download()
        return true
    }

    fun discardDownloadPermission(code: Int): Boolean {
        if (downloadPermission?.code != code) return false
        downloadPermission = null
        return true
    }

    /** 系统设置结果不作为安装成功；只续接原意图，仍走安装前完整校验与单次领取。 */
    fun finishInstallPermission(code: Int, granted: Boolean): Boolean {
        val request = installPermission ?: return false
        if (request.code != code) return false
        installPermission = null
        if (!permissionMatches(request)) return true
        if (granted) requestInstall() else installFailed(UiText.text("未允许安装更新，可稍后重试"))
        return true
    }

    enum class Stage {
        IDLE,
        CHECKING,
        DOWNLOADING,
        VERIFYING,
    }

    class State(
        @JvmField val message: String?,
        @JvmField val stage: Stage?,
        @JvmField val downloaded: Long,
        @JvmField val total: Long,
        @JvmField val release: UpdatePolicy.Release?,
        @JvmField val apk: File?,
        @JvmField val channel: String?,
        @JvmField val sourceChannel: String?,
    ) {
        @JvmField val busy = stage != Stage.IDLE

        constructor(
            text: String?,
            busy: Boolean,
            n: Long,
            total: Long,
            release: UpdatePolicy.Release?,
            apk: File?,
        ) : this(text, busy, n, total, release, apk, null, null)

        constructor(
            text: String?,
            busy: Boolean,
            n: Long,
            total: Long,
            release: UpdatePolicy.Release?,
            apk: File?,
            channel: String?,
            sourceChannel: String?,
        ) : this(
            text,
            if (busy) Stage.DOWNLOADING else Stage.IDLE,
            n,
            total,
            release,
            apk,
            channel,
            sourceChannel,
        )
    }

    class InstallState(
        @JvmField val verifying: Boolean,
        @JvmField val apk: File?,
        @JvmField val error: String?,
    ) {
        fun pending(): Boolean = verifying || apk != null
    }

    constructor(app: Application) : this(app, UpdateEngine.get(app))

    private constructor(
        app: Application,
        engine: UpdateEngine,
    ) : this(app, engine, engine.verificationExecutor(), null)

    // 可控执行器用于校验中重建、离开页面和重复领取的确定性自测。
    constructor(
        app: Application,
        engine: UpdateEngine,
        executor: Executor,
        verifier: Callable<File>?,
    ) : super(app) {
        this.engine = engine
        verifyExecutor = executor
        verifyApk = verifier ?: Callable { engine.installableApk() }
    }

    fun state(): LiveData<State> = engine.state()

    fun channel(): String = engine.channel()

    fun setChannel(channel: String?) {
        if (!installationPending()) {
            clearPermissions()
            engine.setChannel(channel)
        }
    }

    fun hasTask(): Boolean = engine.hasTask()

    fun check() {
        if (!installationPending()) {
            clearPermissions()
            engine.check()
        }
    }

    fun download() {
        if (!installationPending()) {
            clearPermissions()
            engine.download()
        }
    }

    fun cancel() {
        if (!installationPending()) {
            clearPermissions()
            engine.cancel()
        }
    }

    @Throws(Exception::class) fun installableApk(): File = engine.installableApk()

    fun installation(): LiveData<InstallState> = installation

    fun installationPending(): Boolean = installation.value!!.pending()

    fun requestInstall() {
        val current = state().value
        if (cleared || installationPending() || current == null || current.busy) return
        if (current.apk == null || current.release == null) {
            installFailed(UiText.text("请先在当前通道下载并校验安装包"))
            return
        }
        clearPermissions()
        engine.invalidatePermissionRequests()
        installRelease = current.release
        installChannel = engine.channel()
        val request = ++installRequest
        installation.value = InstallState(true, null, null)
        verifyExecutor.execute {
            var apk: File? = null
            var error: String? = null
            try {
                apk = verifyApk.call()
            } catch (failure: Exception) {
                error = SensitiveData.redact(failure.message)
            }
            val result = apk
            val message = error
            main.post {
                if (cleared || request != installRequest) return@post
                installation.value =
                    InstallState(
                        false,
                        result,
                        if (result == null)
                            UiText.format("安装前校验失败：%s；请重试", message ?: UiText.text("无法读取安装包"))
                        else null,
                    )
            }
        }
    }

    /** 新 Activity 接手同一请求，且结果只领取一次；只有前台页面负责打开系统安装器。 */
    fun takeInstallReady(): File? {
        val current = installation.value!!
        if (cleared || current.verifying || current.apk == null) return null
        if (!engine.isCurrentInstall(current.apk, installRelease, installChannel)) {
            installFailed(UiText.text("通道或候选已改变，请重新检查后重试安装"))
            return null
        }
        installation.value = InstallState(false, null, null)
        return current.apk
    }

    fun installFailed(reason: String?) {
        installRequest++
        clearPermissions()
        installRelease = null
        installChannel = null
        installation.value = InstallState(false, null, SensitiveData.redact(reason))
    }

    fun restoreInterruptedInstall(wasPending: Boolean) {
        val current = installation.value!!
        if (
            wasPending &&
                installPermission == null &&
                downloadPermission == null &&
                !current.pending() &&
                current.error == null
        )
            installFailed(UiText.text("安装前校验已因进程重建中断，请点击「安装更新」重试"))
    }

    override fun onCleared() {
        cleared = true
        clearPermissions()
        installRequest++
        super.onCleared()
    }

    companion object {
        const val FEED = UpdateEngine.FEED
    }
}
