package com.deepseekharness.app.runtime

import android.content.Context
import android.util.Log
import com.deepseekharness.app.util.BoundedProcessRunner
import com.deepseekharness.app.util.Compat
import com.deepseekharness.app.util.InstallProcess
import com.deepseekharness.app.util.InstallTask
import com.deepseekharness.app.util.MaintenanceGate
import com.deepseekharness.app.util.RuntimeModeLabel
import com.deepseekharness.app.util.RuntimeWorkPort
import com.deepseekharness.app.util.SensitiveData
import com.deepseekharness.app.util.UiText
import java.io.File
import java.io.IOException
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** 选择运行方式，负责非交互命令启动、收集及未确认退出的 guest lease。 */
internal class RuntimeExecution(
    private val context: Context,
    private val rootfs: File,
    private val base: File,
    private val tmp: File,
    private val forceProot: Boolean,
    private val userDomain: Boolean,
    private val ports: RuntimeHostPorts,
    private val readiness: Readiness,
    private val nativeFiles: RuntimeNativeFiles,
    launcherRef: RuntimeLauncher,
) {
    fun interface Readiness {
        @Throws(IOException::class) fun require()
    }

    private val hardlinks: RuntimeHardlinks = RuntimeHardlinks(context, rootfs, ports)
    private val launcher: RuntimeLauncher = launcherRef

    fun runtime(): ContainerRuntime {
        val settings = ports.settings()
        try {
            if (!forceProot && android.os.Build.VERSION.SDK_INT >= 26 && settings.proroot) {
                val selected =
                    ContainerRuntime.Proroot(
                        context,
                        ContainerRuntime.Proroot.defaultDir(context),
                        settings.staticLoader,
                    )
                if (selected.available()) {
                    selected.prepare()
                    return selected
                }
                ports.record(
                    "RUNTIME_SELECTION_FALLBACK",
                    SensitiveData.redact(selected.unavailableReason()),
                )
            }
        } catch (failure: Exception) {
            ports.record("RUNTIME_SELECTION_FALLBACK", SensitiveData.redact(failure.toString()))
        }
        return proot()
    }

    fun proot(): ContainerRuntime = ContainerRuntime.Proot(context, nativeFiles.find("libproot.so"))

    fun launch(runtime: ContainerRuntime): LaunchSpec.Builder =
        LaunchSpec.runtime(runtime).dataDomain(if (userDomain) context.filesDir else null)

    @Throws(IOException::class)
    fun requireReady() {
        readiness.require()
    }

    fun hardlinks(): Boolean = hardlinks.supported()

    @Throws(IOException::class)
    fun nativeFiles() {
        nativeFiles.prepare()
    }

    fun launcher(): RuntimeLauncher = launcher

    @Throws(IOException::class)
    fun prepareTools() {
        prepareTools(null)
    }

    /** 启动观测版：准备命中/失效与固定受管文件数进入调用方诊断，不改变准备本身。 */
    @Throws(IOException::class)
    fun prepareTools(output: Consumer<String>?) {
        RuntimeTools.prepare(context, rootfs, output)
    }

    fun ensurePatches() {
        ensurePatches(null)
    }

    fun ensurePatches(output: Consumer<String>?) {
        if (!RootfsReadiness.ready(rootfs)) return
        try {
            prepareTools(output)
        } catch (error: IOException) {
            val detail = UiText.format("运行工具准备失败：%s", SensitiveData.redact(error.toString()))
            Log.e("DSHA", detail, error)
            throw IllegalStateException(detail, error)
        }
        val home = File(rootfs, "root")
        val link = File(home, "手机存储")
        if (home.isDirectory && !link.exists() && !Compat.isSymbolicLink(link))
            try {
                Compat.symlink("/sdcard", link)
            } catch (error: IOException) {
                ports.record("OPTIONAL_STORAGE_LINK_UNAVAILABLE", error.javaClass.simpleName)
            } catch (error: RuntimeException) {
                ports.record("OPTIONAL_STORAGE_LINK_UNAVAILABLE", error.javaClass.simpleName)
            }
    }

    fun prepareGroups() {
        try {
            AndroidGroups.prepare(context, rootfs)
        } catch (error: IOException) {
            ports.record(
                "ANDROID_GROUPS_UNAVAILABLE",
                error.javaClass.simpleName + ":" + SensitiveData.redact(error.message.toString()),
            )
        } catch (error: RuntimeException) {
            ports.record(
                "ANDROID_GROUPS_UNAVAILABLE",
                error.javaClass.simpleName + ":" + SensitiveData.redact(error.message.toString()),
            )
        }
    }

    @JvmRecord
    data class Probe(
        val runtimeMode: String,
        val runtimeDetail: String,
        val command: BoundedProcessRunner.Result,
    )

    @Throws(IOException::class, InterruptedException::class)
    fun probe(): Probe =
        ports.open().use {
            nativeFiles()
            ensurePatches()
            val selected = runtime()
            val detail = modeKey(selected)
            Probe(selected.id(), detail, collect("/bin/echo SMOKE_OK", 60_000, false, selected))
        }

    fun smokeText(): String =
        try {
            val result = probe()
            val output = GuestCommandOutput.legacy(result.command, 60_000)
            SensitiveData.redact(
                UiText.format(
                    "proot 路径: %s\nnativeLibDir: %s\nruntimeMode: %s\nrootfs exec: %s\n",
                    nativeFiles.find("libproot.so").absolutePath,
                    context.applicationInfo.nativeLibraryDir,
                    result.runtimeMode,
                    output?.trim() ?: "",
                )
            )
        } catch (error: Exception) {
            SensitiveData.redact("PROOT_FAIL: " + error.javaClass.simpleName + ": " + error.message)
        }

    @Throws(IOException::class)
    fun exec(command: String, environment: Map<String, String>): Process =
        exec(command, environment, null)

    @Throws(IOException::class)
    fun exec(
        command: String,
        environment: Map<String, String>,
        beforeStart: ProotBootstrap.LaunchCheck?,
        selected: ContainerRuntime? = null,
    ): Process =
        ports.open().use {
            requireReady()
            val runtime = selected ?: runtime()
            val links = hardlinks()
            prepareTools()
            launcher.start(
                command,
                launch(runtime).hardlinks(links).environment(environment).build(),
                null,
                beforeStart,
            )
        }

    @Throws(IOException::class)
    private fun bounded(command: String, selected: ContainerRuntime?): Process =
        ports.open().use {
            requireReady()
            val runtime = selected ?: runtime()
            val links = hardlinks()
            prepareTools()
            if ("proroot" != runtime.id())
                return@use start(command, launch(runtime).hardlinks(links).build())
            if (IsolatedInstallProcess.bundledLauncher(context) == null)
                throw IOException("BOUNDED_PROROOT_SESSION_UNAVAILABLE")
            nativeFiles()
            val record = BoundedGuestSessions.begin(context.filesDir)
            try {
                start(
                    command,
                    launch(runtime).hardlinks(links).isolated(true).record(record).build(),
                )
            } catch (failure: IOException) {
                record.uncertain()
                throw failure
            } catch (failure: RuntimeException) {
                record.uncertain()
                throw failure
            } catch (failure: Error) {
                record.uncertain()
                throw failure
            }
        }

    @Throws(IOException::class)
    fun install(command: String, pipedInput: Boolean): Process = install(command, pipedInput, null)

    /** A read-only install probe owns an isolated session, including GNU timeout's other groups. */
    @Throws(Exception::class)
    fun checkInstall(
        command: String,
        timeoutMs: Long,
        cancelled: BooleanSupplier,
        output: Consumer<String>,
    ): Int = checkInstall(listOf("/bin/bash", "-c", command), timeoutMs, cancelled, output)

    @Throws(Exception::class)
    fun checkInstall(
        guestArgv: List<String>,
        timeoutMs: Long,
        cancelled: BooleanSupplier,
        output: Consumer<String>,
    ): Int {
        val process =
            ports.open().use {
                if (cancelled.asBoolean) throw InstallTask.Cancelled()
                nativeFiles()
                if (IsolatedInstallProcess.bundledLauncher(context) == null)
                    throw IOException("INSTALL_PROBE_SESSION_UNAVAILABLE")
                val builder = launcher.builder(guestArgv, launch(proot()).isolated(true).build())
                if (cancelled.asBoolean) throw InstallTask.Cancelled()
                val record = BoundedGuestSessions.begin(context.filesDir)
                try {
                    IsolatedInstallProcess.startProbe(builder, tmp, context, record, cancelled)
                } catch (failure: Throwable) {
                    record.uncertain()
                    throw failure
                }
            }
        return InstallProcess.readOwned(
            process,
            timeoutMs,
            cancelled,
            output,
            process::close,
            process::markRecordUncertain,
        )
    }

    @Throws(IOException::class)
    private fun install(
        command: String,
        pipedInput: Boolean,
        timing: RuntimeCommandTiming?,
        beforeStart: ProotBootstrap.LaunchCheck? = null,
    ): Process =
        ports.open().use {
            nativeFiles()
            timing?.mark("native-files")
            launcher.start(
                command,
                launch(proot()).pipedInput(pipedInput).build(),
                timing,
                beforeStart,
            )
        }

    /** List and refresh have already checked their narrow managed inputs. */
    @Throws(IOException::class)
    private fun startPluginQuery(command: String, timing: RuntimeCommandTiming): Process =
        ports.open().use {
            requireReady()
            GuestScriptBudget.checkpoint()
            timing.mark("launch-admission")
            val settings = ports.settings()
            val preferred =
                if (!forceProot && settings.proroot && android.os.Build.VERSION.SDK_INT >= 26)
                    ContainerRuntime.Proroot(
                        context,
                        ContainerRuntime.Proroot.defaultDir(context),
                        settings.staticLoader,
                    )
                else null
            if (
                preferred == null ||
                    !preferred.available() ||
                    IsolatedInstallProcess.bundledLauncher(context) == null
            ) {
                timing.mark("engine-proot")
                return@use install(command, false, timing) { GuestScriptBudget.checkpoint() }
            }
            timing.mark("engine-proroot")
            // Proroot uses the APK native directory and always passes --link2symlink.
            // The proot-only dependency copy and hardlink probe do not affect this launch.
            val spec = launch(preferred).isolated(true).build()
            val builder = launcher.builder(command, spec)
            timing.mark("launcher-prepare")
            GuestScriptBudget.checkpoint()
            val record = BoundedGuestSessions.begin(context.filesDir)
            timing.mark("launch-record")
            try {
                IsolatedInstallProcess.start(builder, tmp, context, record).also {
                    timing.mark("process-create")
                }
            } catch (failure: Throwable) {
                // After begin, preserve the recovery record on an uncertain launch. Do not
                // dispatch the same query through another engine after a launcher failure.
                record.uncertain()
                throw failure
            }
        }

    @Throws(IOException::class)
    fun trial(
        command: String,
        privateData: File,
        guest: String,
        expectedMode: String,
    ): Process =
        ports.open().use {
            if (!MaintenanceGate.shared().isOwner) throw IOException("TRIAL_REQUIRES_MAINTENANCE")
            val parent = File(base.parentFile, "runtime-trials").canonicalFile
            if (
                privateData.canonicalFile != privateData.absoluteFile ||
                    privateData.parentFile.parentFile != parent ||
                    !guest.matches(Regex("/root/\\.dsha-runtime-trial-[a-f0-9]{32}"))
            )
                throw IOException("TRIAL_DIRECTORY")
            nativeFiles()
            val runtime = if ("proot" == expectedMode) proot() else runtime()
            if (runtime.id() != expectedMode) throw IOException("TRIAL_RUNTIME_CHANGED")
            try {
                runtime.prepare()
            } catch (error: Exception) {
                throw IOException("TRIAL_RUNTIME_UNAVAILABLE", error)
            }
            start(command, launch(runtime).bind(privateData.absolutePath, guest).build())
        }

    @Throws(IOException::class)
    fun start(command: String, spec: LaunchSpec): Process =
        ports.open().use { launcher.start(command, spec) }

    @Throws(IOException::class)
    fun cold(command: String): Process =
        ports.open().use {
            val runtime =
                ContainerRuntime.Proroot(context, ContainerRuntime.Proroot.defaultDir(context))
            start(command, launch(runtime).isolated(true).build())
        }

    @Throws(IOException::class, InterruptedException::class)
    fun collect(command: String, timeoutMs: Long, proot: Boolean): BoundedProcessRunner.Result =
        collect(command, timeoutMs, proot, null as ContainerRuntime?)

    @Throws(IOException::class, InterruptedException::class)
    fun collect(
        command: String,
        timeoutMs: Long,
        proot: Boolean,
        selected: ContainerRuntime?,
    ): BoundedProcessRunner.Result = collect(command, timeoutMs, proot, selected, null, null)

    @Throws(IOException::class, InterruptedException::class)
    fun collect(
        command: String,
        timeoutMs: Long,
        proot: Boolean,
        onLine: Consumer<String>?,
    ): BoundedProcessRunner.Result =
        collect(command, timeoutMs, proot, null as ContainerRuntime?, null, onLine)

    @Throws(IOException::class, InterruptedException::class)
    fun collectPluginQuery(
        command: String,
        timeoutMs: Long,
        report: Consumer<String>,
    ): BoundedProcessRunner.Result {
        val timing = RuntimeCommandTiming { line ->
            report.accept("host-" + line.substringAfter(": "))
        }
        var guestRecorded = false
        val guest =
            Consumer<String> { line ->
                if (!guestRecorded && PLUGIN_QUERY_GUEST_TIMING.matches(line)) {
                    guestRecorded = true
                    report.accept(line)
                }
            }
        return collect(command, timeoutMs, true, null, timing, guest, pluginQuery = true)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun collectCasLinks(timing: Consumer<String>?): BoundedProcessRunner.Result {
        // #209：link() 被 ROM 拒绝的设备上，实体化旧 pnpm CAS 链接退化为逐文件全量
        // 复制（实测一例 420MB），固定 120 秒会在复制中段 SIGKILL，把事务留在
        // pending、卡死在下一次启动。脚本本身带进度回调和取消票据，这里的超时
        // 只兜底真正的挂死，不再按快设备的正常耗时定值。
        val phases = timing?.let { RuntimeCommandTiming(it) }
        return collect(900_000, phases, null, false) {
            ports.open().use {
                nativeFiles()
                phases?.mark("native-files")
                // PRoot resolves python3 in the guest PATH. CAS uses only the standard library;
                // skip Python site discovery and avoid writing bytecode during the scan.
                launcher.start(
                    listOf("python3", "-B", "-S", "-u", "/root/.dsh/materialize-cas-links.py"),
                    launch(proot()).build(),
                    phases,
                    null,
                )
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun collect(
        command: String,
        timeoutMs: Long,
        proot: Boolean,
        selected: ContainerRuntime?,
        timing: RuntimeCommandTiming?,
        onLine: Consumer<String>?,
        pluginQuery: Boolean = false,
    ): BoundedProcessRunner.Result =
        collect(timeoutMs, timing, onLine, pluginQuery) {
            if (pluginQuery) startPluginQuery(command, requireNotNull(timing))
            else if (proot) install(command, false, timing) else bounded(command, selected)
        }

    @Throws(IOException::class, InterruptedException::class)
    private fun collect(
        timeoutMs: Long,
        timing: RuntimeCommandTiming?,
        onLine: Consumer<String>?,
        includePreparationInBudget: Boolean,
        dispatch: () -> Process,
    ): BoundedProcessRunner.Result {
        val started = System.nanoTime()
        val work = RuntimeWorkPort.begin("容器命令")
        var process: Process? = null
        var original: Throwable? = null
        try {
            process = dispatch()
            val collectMillis =
                if (includePreparationInBudget) {
                    val remaining =
                        timeoutMs -
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - started
                            )
                    // Even after a slow dispatch, hand the owned process to the collector so expiry
                    // or interruption triggers its normal termination and exit-proof path.
                    maxOf(1L, remaining)
                } else timeoutMs
            val result =
                BoundedProcessRunner.collect(
                    process,
                    collectMillis,
                    256 * 1024,
                    Compat::destroy,
                    onLine,
                )
            timing?.mark("process-collect")
            return result
        } catch (failure: Throwable) {
            original = failure
            timing?.mark("command-failed")
            throw failure
        } finally {
            val ending = process
            val close: Runnable? =
                if (ending is IsolatedInstallProcess) Runnable { ending.close() } else null
            val uncertain: Runnable? =
                if (ending is IsolatedInstallProcess) Runnable { ending.markRecordUncertain() }
                else null
            try {
                BoundedGuestLifetime.finish(ending, close, uncertain, work, original)
            } finally {
                timing?.mark("exit-finalize")
            }
        }
    }

    fun read(command: String, timeoutMs: Long, proot: Boolean): String =
        try {
            GuestCommandOutput.legacy(collect(command, timeoutMs, proot), timeoutMs)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            UiText.text("ERROR: 命令等待被中断")
        } catch (error: IOException) {
            "ERROR: " + SensitiveData.redact(error.toString())
        } catch (error: RuntimeException) {
            "ERROR: " + SensitiveData.redact(error.toString())
        }

    @Throws(IOException::class)
    fun checked(command: String): String =
        try {
            val result = collect(command, 600_000, false)
            if (result.timedOut) throw IOException(UiText.format("命令执行超时（%s 秒），已请求停止本次进程", 600))
            if (result.exitCode != 0) {
                val out = result.output
                throw IOException(
                    UiText.format(
                        "退出码 %s：\n%s",
                        result.exitCode,
                        SensitiveData.redact(
                            if (out.length > 600) out.substring(out.length - 600) else out
                        ),
                    )
                )
            }
            result.output
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(UiText.text("命令被中断"), error)
        }

    companion object {
        private val PLUGIN_QUERY_GUEST_TIMING =
            Regex(
                "PLUGIN_QUERY_GUEST_TIMING: bootMs=[0-9]{1,7} revisionMs=[0-9]{1,7} " +
                    "discoveryMs=[0-9]{1,7} cardsMs=[0-9]{1,7} parseMs=[0-9]{1,7} " +
                    "nodeMs=[0-9]{1,7} recheckMs=[0-9]{1,7}"
            )

        /** 诊断键：proot 带 seccomp 状态；须在与启动参数相同的配置 scope 内读取。 */
        @JvmStatic
        fun modeKey(runtime: ContainerRuntime): String =
            RuntimeModeLabel.key(runtime.id(), seccompOff(runtime))

        @JvmStatic
        fun seccompOff(runtime: ContainerRuntime): Boolean =
            runtime is ContainerRuntime.Proot && runtime.seccompDisabled()
    }
}
