package com.deepseekharness.app.runtime

import android.content.Context
import android.system.Os
import com.deepseekharness.app.backup.ColdInstallTransaction
import com.deepseekharness.app.backup.HostMaintenancePending
import com.deepseekharness.app.backup.RuntimeDescriptor
import com.deepseekharness.app.util.BoundedProcessRunner
import com.deepseekharness.app.util.BundledRootfsAsset
import com.deepseekharness.app.util.ColdInstallPlan
import com.deepseekharness.app.util.DshVersionPolicy
import com.deepseekharness.app.util.MaintenanceGate
import com.deepseekharness.app.util.PluginDownloadSource
import com.deepseekharness.app.util.RuntimeWorkPort
import com.deepseekharness.app.util.UiText
import java.io.File
import java.io.IOException
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 兼容门面：实际安装、执行、插件、维护和终端契约由职责协作者管理。 */
open class ProotBootstrap
@JvmOverloads
constructor(
    c: Context,
    @JvmField val forceProot: Boolean = false,
    @JvmField internal val coldCandidate: ColdInstallTransaction.Candidate? = null,
) {
    @JvmField val ctx: Context
    @JvmField val rootfsDir: File
    @JvmField internal var preparedColdRuntime: ColdRuntimeSelection? = null

    private val baseDir: File
    private val libDir: File
    private val tmpDir: File
    private val installation: RuntimeInstallationState
    private val transfers: RuntimeDataTransfers
    private val launcher: RuntimeLauncher
    private val pluginScripts: GuestPluginScripts
    private val hostPorts: RuntimeHostPorts
    private val nativeFiles: RuntimeNativeFiles
    private val execution: RuntimeExecution
    private val terminal: TerminalRuntime
    private val recovery: RecoveryToolchain

    init {
        ctx = c.applicationContext
        hostPorts = RuntimeHostPorts.fromOwner(ctx)
        baseDir = if (coldCandidate == null) File(ctx.filesDir, "linux") else coldCandidate.linux()
        rootfsDir = File(baseDir, "ubuntu")
        libDir = File(baseDir, "lib")
        tmpDir = File(baseDir, "tmp")
        installation = RuntimeInstallationState(ctx, baseDir, rootfsDir)
        transfers = RuntimeDataTransfers(ctx, rootfsDir)
        launcher = RuntimeLauncher(ctx, rootfsDir, baseDir, libDir, tmpDir, hostPorts)
        nativeFiles = RuntimeNativeFiles(ctx, baseDir, libDir, tmpDir)
        execution =
            RuntimeExecution(
                ctx,
                rootfsDir,
                baseDir,
                tmpDir,
                forceProot,
                coldCandidate == null,
                hostPorts,
                RuntimeExecution.Readiness { requireUserRuntime() },
                nativeFiles,
                launcher,
            )
        terminal = TerminalRuntime(ctx, rootfsDir, execution, hostPorts)
        recovery =
            RecoveryToolchain(
                ctx,
                rootfsDir,
                baseDir,
                libDir,
                tmpDir,
                nativeFiles,
                launcher,
                hostPorts,
            )
        pluginScripts =
            GuestPluginScripts(
                ctx,
                rootfsDir,
                BooleanSupplier { isEnvironmentReady() },
                object : GuestPluginScripts.Runner {
                    @Throws(IOException::class, InterruptedException::class)
                    override fun run(
                        command: String,
                        timeoutMs: Long,
                    ): BoundedProcessRunner.Result = execution.collect(command, timeoutMs, true)

                    @Throws(IOException::class, InterruptedException::class)
                    override fun run(
                        command: String,
                        timeoutMs: Long,
                        onLine: Consumer<String>?,
                    ): BoundedProcessRunner.Result =
                        execution.collect(command, timeoutMs, true, onLine)
                },
            )
    }

    fun hostPorts(): RuntimeHostPorts = hostPorts

    /** 排队后的 worker 创建同次只读准备范围；不改变原 invocation、维护或执行门。 */
    fun openStartupPreparation(
        generation: Long,
        current: BooleanSupplier,
        output: Consumer<String>?,
    ): RuntimeHostPorts.Scope = installation.openPreparation(generation, current, output)

    open fun getRootfsDir(): File = rootfsDir

    @Throws(IOException::class)
    fun installBundledDeviceScripts() {
        RuntimeTools.installDeviceScripts(ctx, rootfsDir)
    }

    fun bundledDeviceScriptsMatch(): Boolean = RuntimeTools.deviceScriptsMatch(ctx, rootfsDir)

    fun isOfflineExtracted(): Boolean = installation.isOfflineExtracted()

    fun hasBash(): Boolean = installation.hasBash()

    fun environmentIdentity(): String = installation.environmentIdentity()

    fun expandedEnvironmentBytes(): Long = installation.expandedEnvironmentBytes()

    fun rootfsVersionMatches(): Boolean = installation.rootfsVersionMatches()

    open fun isEnvironmentReady(): Boolean = installation.isEnvironmentReady()

    fun isEnvironmentInstalled(): Boolean = installation.isEnvironmentInstalled()

    @Throws(IOException::class)
    open fun expectedRuntimeDescriptor(): RuntimeDescriptor =
        installation.expectedRuntimeDescriptor()

    @Throws(IOException::class)
    open fun installedRuntimeDescriptor(): RuntimeDescriptor =
        installation.installedRuntimeDescriptor()

    @Throws(IOException::class) fun runtimeHealth(): Map<String, Any> = installation.runtimeHealth()

    fun needsRuntimeUpdate(): Boolean = installation.needsRuntimeUpdate()

    fun installedVersionStatus(): DshVersionPolicy.Status = installation.installedVersionStatus()

    @Throws(IOException::class)
    fun confirmRuntimeHealth(proof: Map<String, Any>) {
        installation.confirmRuntimeHealth(proof)
    }

    fun canUpdateManagedRuntime(): Boolean = installation.canUpdateManagedRuntime()

    @Throws(IOException::class)
    fun stageManagedRuntime(stage: File, progress: Consumer<String>?): List<String> =
        installation.stageManagedRuntime(stage, progress)

    @Throws(IOException::class)
    fun markOfflineExtracted() {
        installation.markOfflineExtracted()
    }

    fun markNotExtracted() {
        installation.markNotExtracted()
    }

    // ================= 运行时文件 =================

    fun findNativeLib(name: String): File = nativeFiles.find(name)

    open fun ensureRuntimeFiles() {
        ensureRuntimeFiles(null)
    }

    /** 启动观测版：固定工具准备结果只作诊断，失败语义与原入口一致。 */
    fun ensureRuntimeFiles(output: Consumer<String>?) {
        try {
            nativeFiles.prepare()
        } catch (error: IOException) {
            throw IllegalStateException("NATIVE_LIBRARY_PREPARATION_FAILED", error)
        }
        ensureDshRuntimePatches(output)
    }

    /** Prepares the shared current recipe chain for cold, managed and installed roots. */
    fun ensureDshRuntimePatches() {
        ensureDshRuntimePatches(null)
    }

    /** 启动观测版：运行工具准备的命中/失效随同一次准备进入启动诊断。 */
    fun ensureDshRuntimePatches(output: Consumer<String>?) {
        execution.ensurePatches(output)
    }

    open fun registerBuiltinPlugins(): String = pluginScripts.register()

    @Throws(IOException::class, InterruptedException::class)
    fun registerBuiltinPluginsResult(): BoundedProcessRunner.Result = pluginScripts.builtin("")

    /** Synchronous startup scope; every helper still has its own bounded guest and exit proof. */
    @Throws(IOException::class, InterruptedException::class)
    fun prepareStartupPlugins(): StartupPlugins = prepareStartupPlugins(null)

    /** 启动观测版：共享 helper 准备与后续 guest 调用仍走同一条链，仅透传有界诊断。 */
    @Throws(IOException::class, InterruptedException::class)
    fun prepareStartupPlugins(output: Consumer<String>?): StartupPlugins =
        StartupPlugins(pluginScripts.startup(output))

    class StartupPlugins internal constructor(private val scripts: GuestPluginScripts.Startup) :
        AutoCloseable {
        @Throws(IOException::class, InterruptedException::class)
        fun register(): BoundedProcessRunner.Result = scripts.register()

        @Throws(IOException::class, InterruptedException::class)
        fun legacy(): BoundedProcessRunner.Result = scripts.legacy()

        override fun close() {
            scripts.close()
        }
    }

    fun prepareRc1Migration(startupId: String): String =
        pluginScripts.migrationText("prepare", startupId)

    @Throws(IOException::class, InterruptedException::class)
    fun prepareRc1MigrationResult(startupId: String): BoundedProcessRunner.Result =
        pluginScripts.migration("prepare", startupId)

    fun finalizeRc1Migration(startupId: String): String =
        pluginScripts.migrationText("finalize", startupId)

    @Throws(IOException::class, InterruptedException::class)
    fun finalizeRc1MigrationResult(startupId: String): BoundedProcessRunner.Result =
        pluginScripts.migration("finalize", startupId)

    /** P1b 最小只读核对入口：同代读回既有副本与账本，不授予恢复/删除。 */
    fun verifyExistingRc1Migration(startupId: String): String =
        pluginScripts.migrationText("verify-existing", startupId)

    @Throws(IOException::class, InterruptedException::class)
    fun verifyExistingRc1MigrationResult(startupId: String): BoundedProcessRunner.Result =
        pluginScripts.migration("verify-existing", startupId)

    open fun setPluginEnabled(name: String, enable: Boolean): String =
        pluginScripts.enable(name, enable)

    fun runPluginManager(args: String): String = runPluginManager(args, "")

    fun runPluginManager(args: String, taskId: String): String =
        pluginScripts.managerText(args, taskId)

    @Throws(IOException::class, InterruptedException::class)
    fun runPluginManagerResult(args: String, taskId: String): BoundedProcessRunner.Result =
        pluginScripts.manager(args, taskId, PluginDownloadSource.AUTO)

    @Throws(IOException::class, InterruptedException::class)
    fun runPluginManagerResult(
        args: String,
        taskId: String,
        source: PluginDownloadSource,
    ): BoundedProcessRunner.Result = pluginScripts.manager(args, taskId, source)

    fun pushFileIntoContainer(source: File, target: String): Boolean =
        transfers.pushFileIntoContainer(source, target)

    fun pullFileFromContainer(source: String, target: File): Boolean =
        transfers.pullFileFromContainer(source, target)

    fun readAssetString(name: String): String = RuntimeAssetText.read(ctx, name)

    fun ensureAndroidGroups() {
        execution.prepareGroups()
    }

    fun runtime(): ContainerRuntime = execution.runtime()

    @Throws(IOException::class)
    fun prepareRuntimeTools() {
        execution.prepareTools()
    }

    fun launch(runtime: ContainerRuntime): LaunchSpec.Builder = execution.launch(runtime)

    @Throws(IOException::class)
    open fun execRootfs(command: String): Process = execRootfs(command, emptyMap())

    @Throws(IOException::class)
    fun execRootfs(command: String, environment: Map<String, String>): Process =
        execution.exec(command, environment)

    /** 仅在全部启动准备完成后调用；调用者仍拥有本次操作与取消状态。 */
    fun interface LaunchCheck {
        @Throws(IOException::class) fun verify()
    }

    @Throws(IOException::class)
    fun execRootfs(
        command: String,
        environment: Map<String, String>,
        beforeStart: LaunchCheck,
    ): Process = execution.exec(command, environment, beforeStart)

    @Throws(IOException::class)
    open fun execRootfsForInstall(command: String): Process = execution.install(command, false)

    @Throws(IOException::class)
    fun execRootfsForInstallWithPipedInput(command: String): Process =
        execution.install(command, true)

    @Throws(IOException::class)
    fun execRootfsForTrial(command: String, data: File, guest: String, mode: String): Process =
        execution.trial(command, data, guest, mode)

    @Throws(IOException::class)
    fun startRootfs(command: String, spec: LaunchSpec): Process = execution.start(command, spec)

    @Throws(IOException::class)
    open fun execRootfsForColdInstall(command: String): Process = execution.cold(command)

    fun execAndRead(command: String): String = execAndRead(command, 60_000)

    fun execAndRead(command: String, timeoutMs: Long): String =
        execution.read(command, timeoutMs, false)

    open fun execAndReadWithProot(command: String, timeoutMs: Long): String =
        execution.read(command, timeoutMs, true)

    @Throws(IOException::class, InterruptedException::class)
    fun execAndReadWithProotResult(command: String, timeoutMs: Long): BoundedProcessRunner.Result =
        execution.collect(command, timeoutMs, true)

    /** 带完整行回调的 proot 命令；用于把固定 guest 阶段行带进宿主诊断。 */
    @Throws(IOException::class, InterruptedException::class)
    fun execAndReadWithProotResult(
        command: String,
        timeoutMs: Long,
        onLine: Consumer<String>?,
    ): BoundedProcessRunner.Result = execution.collect(command, timeoutMs, true, onLine)

    /** 旧 CAS 迁移仍使用固定 proot，诊断只记录本次宿主阶段。 */
    @Throws(IOException::class, InterruptedException::class)
    fun materializeCasLinksResult(timing: Consumer<String>?): BoundedProcessRunner.Result =
        execution.collectCasLinks(timing)

    @Throws(IOException::class)
    fun prepareDataMaintenance() {
        recovery.prepareData()
    }

    fun releaseRecoveryTools() {
        recovery.release()
    }

    @Throws(IOException::class, InterruptedException::class)
    fun runRecoveryMaintenance(
        command: String,
        onLine: Consumer<String>?,
        timeoutMs: Long,
    ): BoundedProcessRunner.Result = recovery.run(command, onLine, timeoutMs)

    @Throws(IOException::class, InterruptedException::class)
    fun runPersonalMaintenance(
        command: String,
        onLine: Consumer<String>?,
        fast: Boolean,
        timeoutMs: Long,
    ): BoundedProcessRunner.Result = recovery.run(command, onLine, timeoutMs)

    @Throws(IOException::class)
    fun execChecked(command: String): String = execution.checked(command)

    class PtyLaunch
    internal constructor(
        @JvmField val argv: Array<String>,
        @JvmField val environment: Array<String>,
    )

    fun ptyLaunch(): PtyLaunch = ptyLaunch(*emptyArray<String>())

    /** 固定 guest command 只替换命令尾部，argv/env 仍由 [TerminalRuntime] 唯一构造。 */
    fun ptyLaunch(vararg guestCommand: String): PtyLaunch {
        val value = terminal.launch(*guestCommand)
        return PtyLaunch(value.argv(), value.environment())
    }

    @Throws(IOException::class) fun execRootfsInteractive(): Process = terminal.interactive()

    open fun ptyArgv(vararg guestCommand: String): Array<String> =
        terminal.launch(*guestCommand).argv()

    fun ptyEnv(): Array<String> = terminal.launch().environment()

    @Throws(IOException::class)
    fun requireUserRuntime() {
        if (MaintenanceGate.shared().isOwner) return
        if (!isEnvironmentReady() || HostMaintenancePending.blocked(baseDir.parentFile))
            throw IOException(UiText.text("运行环境尚未恢复，可先在主界面查看日志与配置，再进入安装与修复页处理"))
    }

    /**
     * proroot / proot-seccomp / proot-no-seccomp，见 [com.deepseekharness.app.util.RuntimeModeLabel]。
     */
    class SmokeResult
    internal constructor(
        @JvmField val runtimeMode: String,
        @JvmField val runtimeDetail: String,
        @JvmField val command: BoundedProcessRunner.Result,
    )

    /** Read-only selected-runtime probe; mode and command use the same config image. */
    @Throws(IOException::class, InterruptedException::class)
    fun smokeTestResult(): SmokeResult {
        val result = execution.probe()
        return SmokeResult(result.runtimeMode, result.runtimeDetail, result.command)
    }

    /** 已选运行时的启动日志标签；proot 写明是否带 seccomp 过滤。调用方须在本次启动的配置 scope 内。 */
    fun smokeTest(): String = execution.smokeText()

    // ================= 离线 rootfs 解压 =================

    fun hasOfflineBundle(): Boolean {
        try {
            ZipFile(ctx.packageCodePath).use { apk ->
                if (findBundleEntry(apk) != null) return true
            }
        } catch (_: Exception) {
            // 非 APK 或不可读时继续尝试资产路径。
        }
        try {
            ctx.assets.open(BundledRootfsAsset.NAME).close()
            return true
        } catch (_: IOException) {
            // 两类来源都没有才是真的没有。
        }
        return false
    }

    fun findBundleEntry(z: ZipFile): ZipEntry? = BundledRootfsAsset.find(z)

    /** 解压字节与后续安装阶段分开报告，避免安装工具时界面仍显示“正在解压”。 */
    interface ExtractionProgress : BiConsumer<Long, Long> {
        fun onStage(stage: String)
    }

    fun extractionStage(progress: BiConsumer<Long, Long>, stage: String) {
        hostPorts.stage(stage)
        if (progress is ExtractionProgress) progress.onStage(stage)
    }

    /**
     * Stream signed APK bundle entries into a staged installation with separate extraction
     * progress.
     */
    @Throws(IOException::class)
    fun extractOfflineBundle(onProgress: BiConsumer<Long, Long>) {
        ColdBundleTransaction.run(this, onProgress)
    }

    @Throws(IOException::class)
    fun requireColdClosed() {
        if (
            RuntimeWorkPort.hasOtherTasks() ||
                BoundedGuestSessions.firstPending(ctx.filesDir) != null
        )
            throw IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED")
    }

    @Throws(IOException::class)
    fun requireColdCandidate() {
        val candidate = coldCandidate ?: return
        try {
            if (
                Os.lstat(candidate.linux().path).st_uid != android.os.Process.myUid() ||
                    Os.lstat(candidate.root().path).st_uid != android.os.Process.myUid()
            )
                throw IOException("COLD_CANDIDATE_OWNERSHIP")
        } catch (error: android.system.ErrnoException) {
            throw IOException("COLD_CANDIDATE_UNREADABLE", error)
        }
    }

    @Throws(IOException::class)
    fun extractOfflineBundleInternal(onProgress: BiConsumer<Long, Long>) {
        ColdBundleExtractor.run(this, onProgress)
    }

    /** 在新解压环境中通过 dpkg 离线安装，保留正常包数据库与维护脚本。 */
    class ColdRuntimeSelection(
        @JvmField val mode: ColdInstallPlan.Mode,
        @JvmField val packageSlotSha: String,
    )

    @Throws(IOException::class)
    fun installBundledUbuntuTools(onProgress: BiConsumer<Long, Long>): ColdRuntimeSelection =
        ColdToolsInstaller.run(this, onProgress)

    @Throws(IOException::class)
    fun installBundledPython(stage: File) {
        RootfsInstaller.python(ctx, stage)
    }

    /** 老用户覆盖安装时按需补齐 Python，不重解压或删除其 rootfs。 */
    open fun ensureBundledPython(): Boolean = ensureGlibcPython()

    fun ensureGlibcPython(): Boolean = RootfsInstaller.ensurePython(ctx, rootfsDir)

    @Throws(IOException::class)
    fun installBundledPnpm(stage: File) {
        RootfsInstaller.pnpm(ctx, stage)
    }

    fun ensureBundledPnpm(): Boolean = RootfsInstaller.ensurePnpm(ctx, rootfsDir)

    companion object {
        @JvmField val OFFLINE_VERSION_ASSET: String = RuntimeInstallationState.OFFLINE_VERSION_ASSET

        @JvmField val BUILTIN_REGISTER_SCRIPT: String = GuestPluginScripts.BUILTIN_REGISTER_SCRIPT
        @JvmField val RC1_MIGRATION_SCRIPT: String = GuestPluginScripts.RC1_MIGRATION_SCRIPT
        @JvmField val PLUGIN_MANAGER_SCRIPT: String = GuestPluginScripts.PLUGIN_MANAGER_SCRIPT

        /** 已选运行时的启动日志标签；proot 写明是否带 seccomp 过滤。 */
        @JvmStatic
        fun runtimeLabel(runtime: ContainerRuntime): String =
            com.deepseekharness.app.util.RuntimeModeLabel.display(
                runtime.id(),
                RuntimeExecution.seccompOff(runtime),
            )

        @JvmStatic
        @Throws(IOException::class)
        fun requireOwnedColdFiles(files: File) {
            try {
                val uid = android.os.Process.myUid()
                if (
                    Os.lstat(files.path).st_uid != uid ||
                        Os.lstat(files.parentFile.path).st_uid != uid
                )
                    throw IOException("COLD_PACKAGE_HOST_ROOT_OWNERSHIP")
            } catch (error: android.system.ErrnoException) {
                throw IOException("COLD_PACKAGE_HOST_ROOT_UNREADABLE", error)
            }
        }
    }
}
