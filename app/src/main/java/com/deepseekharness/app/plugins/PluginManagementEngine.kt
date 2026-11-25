package com.deepseekharness.app.plugins

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.deepseekharness.app.DshaApp
import com.deepseekharness.app.backup.NativeBackupJobs
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.core.PluginRepository
import com.deepseekharness.app.util.PluginSource
import com.deepseekharness.app.util.SensitiveData
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * 应用级管理入口；页面只提交意图，原有仓储继续独占 guest、事务和页面状态。
 *
 * <p>构造只建立内存协作者，不探测环境、不读目录、不启动 Web。
 */
class PluginManagementEngine(application: Application, repository: PluginRepository) :
    AutoCloseable {
    private val application: Application = application

    private val repository: PluginRepository = repository

    private val main: Handler = Handler(Looper.getMainLooper())

    private val worker: Background = Background(main)

    private val detector: PluginChangeDetector =
        PluginChangeDetector(
            Runnable { dispatch(Runnable { repository.invalidateInstalledState() }) },
            Runnable { runtimeChanged() },
            Runnable { readyChanged(projectRuntime = false, lifecycleEvent = false) },
            Runnable { watchCoverageChanged() },
        )

    private val coordinator: Coordinator<PluginBackendAdapter.ResolvedSource> =
        Coordinator(
            object : Backend<PluginBackendAdapter.ResolvedSource> {
                override fun cache(): Cache {
                    val value = repository.registry().snapshot()
                    return Cache(
                        value.version,
                        value.source,
                        value.known,
                        value.stale,
                        value.verifiedAtMillis,
                        value.error,
                    )
                }

                @Throws(Exception::class)
                override fun probe(): PluginBackendAdapter.ResolvedSource? =
                    repository.probeSource()

                override fun source(value: PluginBackendAdapter.ResolvedSource): Any? =
                    value.identity

                override fun bind(value: PluginBackendAdapter.ResolvedSource) {
                    if (repository.registry().updateSource(value.identity)) publishSourceChange()
                    detector.bind(value.observedPaths, value.identity)
                    watchCoverageChanged()
                }

                override fun query(automatic: Boolean) {
                    if (automatic) repository.refreshAutomatic() else repository.refresh()
                }

                override fun automaticRetryDelayMillis(): Long =
                    (autoQuerySuspendedUntil.get() - android.os.SystemClock.elapsedRealtime())
                        .coerceAtLeast(0)

                override fun queryOutstanding(): Boolean = repository.hasOutstandingQuery()

                override fun cancelQuery() {
                    repository.cancelAutomaticQueryForUserAction()
                }

                override fun projection() {
                    repository.refreshRuntimeProjection()
                }

                override fun watchCoverage() {
                    val current = detector.coverage()
                    repository.updateWatchCoverage(current.limited, current.reasons)
                }

                override fun reconcileObservation() = detector.reconcileObservation()

                override fun observing(value: Boolean) {
                    detector.foreground(value)
                    watchCoverageChanged()
                }

                override fun unavailable(error: Exception?) {
                    // 队首正在工作时，探测未准入不代表目录失败，也不能采纳其临时 items。
                    if (repository.isBusy()) return
                    val blocked = repository.environmentBlockMessage()
                    val message =
                        if (blocked == null || blocked.isEmpty()) {
                            if (error == null) {
                                "环境尚未就绪，目录读取将在可用后进行"
                            } else {
                                "插件来源暂不可读：" + SensitiveData.redact(error.message ?: "null")
                            }
                        } else {
                            blocked
                        }
                    repository.registry().failSource(message)
                    repository.publishCatalog(message)
                }
            },
            object : Clock {
                override fun now(): Long = android.os.SystemClock.elapsedRealtime()

                override fun wallTime(): Long = System.currentTimeMillis()
            },
            worker,
            Executor { action -> dispatch(action) },
        )

    private val backupBoundary: BackupBoundary = BackupBoundary()

    private val readyChangedListener: Runnable = Runnable { readyChanged() }

    private val backupChangedListener: Observer<NativeBackupJobs.State> = Observer { value ->
        backupChanged(value)
    }

    // 生命周期状态只由主线程修改；后台携带不可变来源，完成后投递回主线程。
    private var foreground: Boolean = false

    private var closed: Boolean = false

    private var signalsLoading: Boolean = false

    private var signalsAttached: Boolean = false

    private var watchers: Int = 0

    private var signalController: HarnessController? = null

    private var backupChanges: LiveData<NativeBackupJobs.State>? = null

    private val autoQuerySuspendedUntil = AtomicLong()

    init {
        repository.setListener(
            object : PluginRepository.Listener {
                override fun invalidated() {
                    dispatch(Runnable { coordinator.invalidated() })
                }

                override fun sourceResolved(source: PluginBackendAdapter.ResolvedSource) {
                    dispatch(Runnable { coordinator.sourceResolved(source) })
                }

                override fun queryFinished() {
                    dispatch(Runnable { coordinator.queryFinished() })
                }
            }
        )
    }

    private fun dispatch(action: Runnable) {
        val guarded = Runnable { if (!closed) action.run() }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else main.post(guarded)
    }

    private fun readyChanged(
        reconcile: Boolean = true,
        projectRuntime: Boolean = true,
        lifecycleEvent: Boolean = true,
    ) {
        dispatch(
            Runnable {
                val controller = signalController
                var generationChanged = false
                var startupReady = false
                if (controller != null) {
                    if (lifecycleEvent) repository.observeMutationIdentity(controller)
                    else repository.reconcileMutationIdentity(controller)
                    generationChanged =
                        repository.registry().updateWebGeneration(controller.webGeneration)
                    if (generationChanged) publishSourceChange()
                    val suspendedUntil = autoQuerySuspendedUntil.get()
                    if (suspendedUntil > 0 && projectRuntime) {
                        val ready = controller.readyWebPageIdentity
                        startupReady =
                            ready != null &&
                                ready.generation() == controller.webGeneration &&
                                autoQuerySuspendedUntil.compareAndSet(suspendedUntil, 0)
                    }
                }
                if (generationChanged || startupReady || (reconcile && projectRuntime))
                    coordinator.reconcile()
                else if (reconcile) coordinator.observationReconciled()
            }
        )
    }

    private fun publishSourceChange() {
        // 在途任务可能正在组装回执；此时只发布 Registry，不采纳或覆盖它的临时 items。
        if (repository.isBusy()) repository.publishCatalogState()
        else {
            val shown = repository.state().value
            repository.publishCatalog(if (shown == null) "" else shown.message)
        }
    }

    private fun watchCoverageChanged() {
        dispatch(Runnable { coordinator.watchCoverageChanged() })
    }

    private fun runtimeChanged() {
        dispatch(Runnable { coordinator.runtimeDirty() })
    }

    private fun backupChanged(value: NativeBackupJobs.State?) {
        if (!closed && foreground && value != null && backupBoundary.accept(value.id, value.busy)) {
            repository.invalidateInstalledState()
        }
    }

    fun ensureLoaded() {
        dispatch(Runnable { coordinator.ensureLoaded() })
    }

    /** 用户启动/重启等优先动作调用；取消自动查询并给启动留出有界收尾窗口。 必须在后台线程调用，不能阻塞主线程。 */
    fun yieldToUserAction() {
        autoQuerySuspendedUntil.set(android.os.SystemClock.elapsedRealtime() + 120_000)
        repository.cancelAutomaticQueryForUserAction()
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        try {
            while (
                android.os.SystemClock.elapsedRealtime() < deadline &&
                    (repository.isQueryRunning() || repository.hasPendingAutomaticQuery())
            ) {
                Thread.sleep(20)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun refresh() {
        dispatch(Runnable { coordinator.refresh() })
    }

    fun onForeground() {
        dispatch(
            Runnable {
                if (!foreground) {
                    foreground = true
                    coordinator.foreground(true)
                    attachSignals()
                }
            }
        )
    }

    /**
     * 插件目录页可见期间持有；只有持有期间才做 TTL 周期核对与自动目录查询。
     *
     * @return 幂等释放；必须在页面 onPause 调用
     */
    fun watch(): Runnable {
        dispatch(
            Runnable {
                watchers++
                if (watchers == 1) coordinator.watched(true)
            }
        )
        val held = AtomicBoolean(true)
        return Runnable {
            if (held.compareAndSet(true, false)) {
                dispatch(
                    Runnable {
                        if (watchers > 0) {
                            watchers--
                            if (watchers == 0) coordinator.watched(false)
                        }
                    }
                )
            }
        }
    }

    fun onBackground() {
        dispatch(
            Runnable {
                if (foreground) {
                    foreground = false
                    coordinator.foreground(false)
                    detachSignals()
                }
            }
        )
    }

    private fun attachSignals() {
        if (signalsAttached || signalsLoading || !foreground) return
        val controller = signalController
        val changes = backupChanges
        if (controller != null && changes != null) {
            signalsAttached = true
            controller.addReadyWebPageListener(readyChangedListener)
            changes.observeForever(backupChangedListener)
            // 实际源头记录了订阅前事件，初始只采样当前身份和事件代次。
            readyChanged(reconcile = false, lifecycleEvent = false)
            return
        }
        signalsLoading = true
        // 两个服务首次创建可能读盘；即使 idle 回调也不能在主线程创建它们。
        worker.execute(
            Runnable {
                var controller: HarnessController? = null
                var changes: LiveData<NativeBackupJobs.State>? = null
                try {
                    controller = HarnessController.get(application)
                    changes = NativeBackupJobs.get(application).changes()
                } catch (unavailable: RuntimeException) {
                    dispatch(Runnable { signalsLoading = false })
                }
                val loadedController = controller
                val loadedChanges = changes
                if (loadedController != null && loadedChanges != null) {
                    dispatch(
                        Runnable {
                            signalsLoading = false
                            signalController = loadedController
                            backupChanges = loadedChanges
                            attachSignals()
                        }
                    )
                }
            }
        )
    }

    private fun detachSignals() {
        if (!signalsAttached) return
        signalsAttached = false
        signalController!!.removeReadyWebPageListener(readyChangedListener)
        backupChanges!!.removeObserver(backupChangedListener)
    }

    override fun close() {
        dispatch(
            Runnable {
                foreground = false
                detachSignals()
                repository.setListener(null)
                detector.foreground(false)
                watchCoverageChanged()
                coordinator.close()
                detector.close()
                closed = true
            }
        )
    }

    // 保留仓储原有页面 API 和返回类型，安装、取消与导出均由原执行器处理。
    fun repository(): PluginRepository = repository

    fun registry(): PluginRegistry = repository.registry()

    fun state(): LiveData<PluginRepository.State> = repository.state()

    fun preview(): LiveData<PluginRepository.Preview> = repository.preview()

    fun operation(id: String?): PluginOperationQueue.Operation? =
        if (id.isNullOrBlank()) null else repository.operation(id)

    fun operationPreview(id: String?): PluginRepository.Preview? =
        if (id.isNullOrBlank()) null else repository.operationPreview(id)

    fun isBusy(): Boolean = repository.isBusy()

    fun hasKnownItems(): Boolean = repository.hasKnownItems()

    fun installedRevision(): Long = repository.installedRevision()

    fun isSafeMode(): Boolean = repository.isSafeMode()

    fun installationSucceeded(): Boolean = repository.installationSucceeded()

    fun installedDescription(): String = repository.installedDescription()

    fun environmentBlockMessage(): String? = repository.environmentBlockMessage()

    fun pendingReviews(): List<PluginRepository.PendingReview> = repository.pendingReviews()

    fun invalidateInstalledState() {
        repository.invalidateInstalledState()
    }

    fun selectionMessage(message: String) {
        repository.selectionMessage(message)
    }

    fun inspectCapabilities(item: PluginRepository.Item, shown: Consumer<String>): String =
        repository.inspectCapabilities(item, shown)

    fun openPendingReview(id: String?): String = repository.openPendingReview(id)

    fun reviewRestored(operation: String, node: String): String =
        repository.reviewRestored(operation, node)

    fun reviewLegacyPreset(key: String): String = repository.reviewLegacyPreset(key)

    fun install(source: PluginSource): String = repository.install(source)

    fun inspect(source: PluginSource, sha256: String, name: String, version: String): String =
        repository.inspect(source, sha256, name, version)

    fun confirmPreview(): String = repository.confirmPreview()

    fun discardPreview(): String = repository.discardPreview()

    fun checkAllUpdates(): String = repository.checkAllUpdates()

    fun checkUpdates(item: PluginRepository.Item?): String = repository.checkUpdates(item)

    fun prepareUpdate(item: PluginRepository.Item): String = repository.prepareUpdate(item)

    fun rollback(item: PluginRepository.Item): String = repository.rollback(item)

    fun safeMode(enabled: Boolean, afterSuccess: Runnable?): String =
        repository.safeMode(enabled, afterSuccess)

    fun importArchive(uri: Uri): String = repository.importArchive(uri)

    fun exportArchives(names: List<String>, uri: Uri): String =
        repository.exportArchives(names, uri)

    fun setEnabled(item: PluginRepository.Item, enabled: Boolean): String =
        repository.setEnabled(item, enabled)

    fun delete(item: PluginRepository.Item): String = repository.delete(item)

    fun cancelTask() {
        repository.cancelTask()
    }

    fun cancelOperation(id: String): Boolean = repository.cancelOperation(id)

    internal interface Clock {
        fun now(): Long

        fun wallTime(): Long
    }

    internal fun interface Cancellation {
        fun cancel()
    }

    internal interface Scheduler {
        fun execute(action: Runnable)

        fun schedule(action: Runnable, delayMillis: Long): Cancellation

        fun close()
    }

    /** 调度只需要快照元数据；不复制目录状态，也不根据通知推导操作成功。 */
    internal class Cache
    internal constructor(
        @JvmField val version: Long,
        @JvmField val source: Any?,
        @JvmField val known: Boolean,
        @JvmField val stale: Boolean,
        @JvmField val verifiedAtMillis: Long,
        error: String?,
    ) {
        @JvmField val error: String = error ?: ""
    }

    internal interface Backend<S : Any> {
        fun cache(): Cache

        @Throws(Exception::class) fun probe(): S?

        fun source(source: S): Any?

        fun bind(source: S)

        fun query(automatic: Boolean)

        fun automaticRetryDelayMillis(): Long = 0

        fun queryOutstanding(): Boolean = false

        fun cancelQuery() {}

        fun projection()

        fun observing(foreground: Boolean)

        fun watchCoverage() {}

        fun reconcileObservation() {}

        fun unavailable(error: Exception?)
    }

    /** 纯调度协议：入口和完成通知在主线程，probe/投影在独立后台单调度器。 */
    internal class Coordinator<S : Any>(
        private val backend: Backend<S>,
        private val clock: Clock,
        private val worker: Scheduler,
        private val main: Executor,
    ) {
        private val changes = PluginChangeCoalescer()
        private var scanTask: Cancellation? = null
        private var scanDue: Long = -1
        private var scanRevision: Long = 0
        private var periodicTask: Cancellation? = null
        private var periodicRevision: Long = 0
        private var runtimeTask: Cancellation? = null
        private var runtimePending: Boolean = false
        @Volatile private var runtimeRevision: Long = 0
        private var foreground: Boolean = false
        private var watched: Boolean = false
        private var lifecycle: Long = 0
        private var closed: Boolean = false
        private var pageDemand: Boolean = false
        private var manualPending: Boolean = false
        private var manualQuery: Boolean = false
        private var observationPending: Boolean = false
        private var cancellingAutomatic: Boolean = false
        private var queryRevision: Long = 0
        private var probing: Boolean = false
        private var reconcilePending: Boolean = false
        private var reconcileRuntime: Boolean = false
        private var sourceUnavailable: Boolean = false
        private var failedAt: Long = -1
        private var failedSource: Any? = null
        private var freshStamp: Long = Long.MIN_VALUE
        private var freshObservedAt: Long = 0
        private var freshFor: Long = 0
        private var freshSource: Any? = null

        /** 观察器仅发布覆盖状态，目录新鲜度由实际查询决定。 */
        @JvmName("watchCoverageChanged")
        internal fun watchCoverageChanged() {
            if (!closed) backend.watchCoverage()
        }

        /** 独立安装入口的显式读取需求，允许在 Activity 的首个空闲回调之前入队。 */
        @JvmName("ensureLoaded")
        internal fun ensureLoaded() {
            if (closed) return
            val cache = backend.cache()
            if (fresh(cache) || !mayRetry(cache)) return
            if (changes.isScanning() && !cancellingAutomatic) return
            pageDemand = true
            requestLoad()
        }

        @JvmName("refresh")
        internal fun refresh() {
            if (closed) return
            observationPending = true
            if (changes.isScanning()) {
                // 仓储原子接管同一查询，或在取消/换代后接续一个显式请求。
                manualQuery = true
                issueQuery(automatic = false)
            } else {
                manualPending = true
                changes.requestNow(clock.now())
                scheduleScan()
            }
        }

        @JvmName("foreground")
        internal fun foreground(value: Boolean) {
            if (closed || foreground == value) return
            foreground = value
            lifecycle++
            backend.observing(value)
            if (value) {
                if (active()) requestLoad()
                if (!changes.hasPending() && backend.cache().source != null) reconcile()
                schedulePeriodic()
            } else {
                reconcilePending = false
                reconcileRuntime = false
                cancelPeriodic()
                cancelRuntime()
                cancelAutomatic()
                scheduleScan()
            }
        }

        /** 切换应用内页面时保留在途查询和已绑定的来源，只有可见期间才启动自动列表读取。 */
        @JvmName("watched")
        internal fun watched(value: Boolean) {
            if (closed || watched == value) return
            watched = value
            if (active()) {
                requestLoad()
                if (runtimePending) runtimeDirty()
                schedulePeriodic()
            } else {
                cancelPeriodic()
                cancelRuntime()
                scheduleScan()
            }
        }

        private fun active(): Boolean = foreground && watched

        private fun requestLoad() {
            if (!changes.isScanning() && !changes.hasPending()) {
                val cache = backend.cache()
                if (!fresh(cache) && mayRetry(cache)) {
                    // 低频核对同时修复未能传递到 Android FileObserver 的丢失观察事件。
                    if (cache.known && !cache.stale) observationPending = true
                    changes.requestNow(clock.now())
                }
            }
            scheduleScan()
        }

        @JvmName("invalidated")
        internal fun invalidated() {
            if (closed) return
            changes.markDirty(clock.now())
            scheduleScan()
        }

        /** READY、观察根变化或实际前后台切换时核对身份；目录查询自身会解析来源。 */
        @JvmName("reconcile")
        internal fun reconcile() {
            requestReconcile(projectRuntime = true)
        }

        @JvmName("observationReconciled")
        internal fun observationReconciled() {
            // Foreground can publish an empty initial plan before the first source is resolved.
            if (backend.cache().source != null) requestReconcile(projectRuntime = false)
        }

        private fun requestReconcile(projectRuntime: Boolean) {
            if (closed || !foreground) return
            reconcilePending = true
            reconcileRuntime = reconcileRuntime || projectRuntime
            reconcileIfIdle()
        }

        private fun reconcileIfIdle() {
            if (!reconcilePending || closed || !foreground || probing || changes.isScanning())
                return
            reconcilePending = false
            val projectRuntime = reconcileRuntime
            reconcileRuntime = false
            val before = backend.cache()
            if (active() && changes.hasPending()) {
                scheduleScan()
                return
            }
            if (before.source == null) {
                if (active()) {
                    changes.requestNow(clock.now())
                    scheduleScan()
                }
                return
            }
            probing = true
            val epoch = lifecycle
            val revision = queryRevision
            worker.execute(
                Runnable {
                    var source: S? = null
                    var error: Exception? = null
                    try {
                        source = backend.probe()
                    } catch (unavailable: Exception) {
                        error = unavailable
                    }
                    val resolved = source
                    val failure = error
                    main.execute(
                        Runnable {
                            probed(epoch, revision, before, resolved, failure, projectRuntime)
                        }
                    )
                }
            )
        }

        private fun probed(
            epoch: Long,
            revision: Long,
            before: Cache,
            source: S?,
            error: Exception?,
            projectRuntime: Boolean,
        ) {
            probing = false
            if (closed) return
            try {
                if (!foreground || lifecycle != epoch || queryRevision != revision) return
                val current = backend.cache()
                if (
                    !Objects.equals(current.source, before.source) &&
                        (source == null || !Objects.equals(current.source, backend.source(source)))
                ) {
                    reconcilePending = true
                    reconcileRuntime = reconcileRuntime || projectRuntime
                    return
                }
                if (source == null) {
                    backend.unavailable(error)
                    sourceUnavailable = true
                    return
                }
                val recovered = sourceUnavailable
                sourceUnavailable = false
                backend.bind(source)
                if (recovered || !Objects.equals(current.source, backend.cache().source))
                    failedAt = -1
                if (active()) requestLoad()
                if (projectRuntime && fresh(backend.cache())) runtimeDirty()
            } finally {
                reconcileIfIdle()
            }
        }

        @JvmName("sourceResolved")
        internal fun sourceResolved(source: S?) {
            if (closed || source == null) return
            if (Objects.equals(backend.cache().source, backend.source(source))) {
                backend.bind(source)
            } else {
                reconcile()
            }
        }

        @JvmName("queryFinished")
        internal fun queryFinished() {
            if (closed || !changes.isScanning() || backend.queryOutstanding()) return
            val cache = backend.cache()
            val cancelled = cancellingAutomatic
            cancellingAutomatic = false
            manualQuery = false
            changes.scanFinished(clock.now())
            if (cache.error.isNotEmpty() && !cancelled) {
                failedAt = clock.now()
                failedSource = cache.source
            } else {
                failedAt = -1
                sourceUnavailable = false
            }
            if (cancelled && !fresh(cache) && (active() || pageDemand))
                changes.requestNow(clock.now())
            if (observationPending && fresh(cache)) {
                observationPending = false
                if (foreground) backend.reconcileObservation()
            }
            // 新到的 READY 允许一次恢复，普通失败只在实际变化或到期后重试。
            if (reconcilePending && cache.error.isNotEmpty()) failedAt = -1
            reconcileIfIdle()
            scheduleScan()
            cancelPeriodic()
            schedulePeriodic()
            if (runtimePending) runtimeDirty()
        }

        private fun cancelAutomatic() {
            if (changes.isScanning() && !manualQuery && !cancellingAutomatic) {
                cancellingAutomatic = true
                backend.cancelQuery()
            }
        }

        @JvmName("runtimeDirty")
        internal fun runtimeDirty() {
            if (closed) return
            runtimePending = true
            if (!active() || runtimeTask != null || !backend.cache().known) return
            val revision = ++runtimeRevision
            runtimeTask =
                worker.schedule(
                    Runnable {
                        main.execute(
                            Runnable {
                                if (!closed && active() && runtimeRevision == revision) {
                                    runtimeTask = null
                                    runtimePending = false
                                    worker.execute(
                                        Runnable {
                                            if (runtimeRevision == revision) backend.projection()
                                        }
                                    )
                                }
                            }
                        )
                    },
                    RUNTIME_COALESCE_MS,
                )
        }

        private fun cancelRuntime() {
            runtimeRevision++
            runtimeTask?.cancel()
            runtimeTask = null
        }

        private fun scheduleScan() {
            val now = clock.now()
            val explicit = manualPending || pageDemand
            val delay = changes.nextDelayMillis(now)
            if (closed || (!active() && !explicit) || delay < 0) {
                cancelScan()
                return
            }
            val scheduledDelay =
                if (explicit) delay else maxOf(delay, backend.automaticRetryDelayMillis())
            val due = now + scheduledDelay
            if (scanTask != null && scanDue == due) return
            cancelScan()
            scanDue = due
            val revision = scanRevision
            scanTask =
                worker.schedule(
                    Runnable { main.execute(Runnable { beginScan(revision) }) },
                    scheduledDelay,
                )
        }

        private fun beginScan(revision: Long) {
            if (closed || revision != scanRevision) return
            scanTask = null
            val explicit = manualPending || pageDemand
            if (!active() && !explicit) return
            if (!explicit && backend.automaticRetryDelayMillis() > 0) {
                scheduleScan()
                return
            }
            if (!changes.beginScan(clock.now())) {
                scheduleScan()
                return
            }
            pageDemand = false
            val forced = manualPending
            manualPending = false
            if (!forced && fresh(backend.cache())) {
                changes.scanFinished(clock.now())
                return
            }
            manualQuery = explicit
            queryRevision++
            issueQuery(automatic = !explicit)
        }

        private fun issueQuery(automatic: Boolean) {
            try {
                backend.query(automatic)
            } catch (rejected: RuntimeException) {
                backend.unavailable(rejected)
                queryFinished()
            }
        }

        private fun mayRetry(cache: Cache): Boolean =
            failedAt < 0 ||
                !Objects.equals(failedSource, cache.source) ||
                clock.now() - failedAt >= SNAPSHOT_TTL_MS

        private fun fresh(cache: Cache): Boolean {
            if (!cache.known || cache.stale) return false
            if (
                freshStamp != cache.verifiedAtMillis || !Objects.equals(freshSource, cache.source)
            ) {
                freshStamp = cache.verifiedAtMillis
                freshSource = cache.source
                freshObservedAt = clock.now()
                val age = clock.wallTime() - cache.verifiedAtMillis
                freshFor =
                    if (cache.verifiedAtMillis > 0 && age >= 0 && age < SNAPSHOT_TTL_MS) {
                        SNAPSHOT_TTL_MS - age
                    } else {
                        0
                    }
            }
            return clock.now() - freshObservedAt < freshFor
        }

        private fun schedulePeriodic() {
            if (closed || !active() || periodicTask != null) return
            val revision = periodicRevision
            val delay =
                if (fresh(backend.cache())) freshFor - (clock.now() - freshObservedAt)
                else SNAPSHOT_TTL_MS
            periodicTask =
                worker.schedule(
                    Runnable {
                        main.execute(
                            Runnable {
                                if (!closed && active() && periodicRevision == revision) {
                                    periodicTask = null
                                    requestLoad()
                                    schedulePeriodic()
                                }
                            }
                        )
                    },
                    delay.coerceAtLeast(1),
                )
        }

        private fun cancelPeriodic() {
            periodicRevision++
            periodicTask?.cancel()
            periodicTask = null
        }

        private fun cancelScan() {
            scanRevision++
            scanTask?.cancel()
            scanTask = null
        }

        @JvmName("close")
        internal fun close() {
            if (closed) return
            closed = true
            backend.observing(false)
            cancelScan()
            cancelPeriodic()
            cancelRuntime()
            cancelAutomatic()
            worker.close()
        }

        private companion object {
            private const val RUNTIME_COALESCE_MS = 200L
        }
    }

    /** 备份进度与首次 LiveData 重放不是恢复完成；同一终态也只补偿一次。 */
    internal class BackupBoundary {
        private var seen: Boolean = false

        private var busy: Boolean = false

        private var id: String = ""

        @JvmName("accept")
        internal fun accept(nextId: String?, nextBusy: Boolean): Boolean {
            val normalized = nextId ?: ""
            val changed = seen && !nextBusy && (busy || id != normalized)
            seen = true
            busy = nextBusy
            id = normalized
            return changed
        }
    }

    private class Background(private val main: Handler) : Scheduler {
        private val executor: ThreadPoolExecutor =
            ThreadPoolExecutor(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                ThreadFactory { action ->
                    Thread(action, "plugin-management").apply { isDaemon = true }
                },
            )

        init {
            executor.allowCoreThreadTimeOut(true)
        }

        override fun execute(action: Runnable) {
            executor.execute(action)
        }

        override fun schedule(action: Runnable, delayMillis: Long): Cancellation {
            // 调度定时器独立于来源 I/O，手动读取可以直接交给仓储。
            main.postDelayed(action, delayMillis)
            return Cancellation { main.removeCallbacks(action) }
        }

        override fun close() {
            executor.shutdown()
        }
    }

    companion object {
        const val SNAPSHOT_TTL_MS: Long = 60_000L

        @JvmStatic
        fun get(context: Context): PluginManagementEngine =
            DshaApp.from(context).pluginManagementEngine()
    }
}
