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
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private val worker: Background = Background()

    private val detector: PluginChangeDetector =
        PluginChangeDetector(
            Runnable { dispatch(Runnable { repository.invalidateInstalledState() }) },
            Runnable { runtimeChanged() },
            Runnable { readyChanged() },
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

                override fun autoQueryAllowed(): Boolean = autoQueriesAllowed()

                override fun cancelQuery() {
                    repository.cancelRefresh()
                }

                override fun projection() {
                    watchCoverageChanged()
                    repository.refreshRuntimeProjection()
                }

                override fun watchCoverage() {
                    val current = detector.coverage()
                    repository.updateWatchCoverage(current.limited, current.reasons)
                }

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

    @Volatile private var autoQuerySuspendedUntil: Long = 0

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

    private fun readyChanged() {
        dispatch(
            Runnable {
                val controller = signalController
                if (controller != null) {
                    repository.observeMutationIdentity(controller)
                    if (repository.registry().updateWebGeneration(controller.webGeneration)) {
                        publishSourceChange()
                    }
                }
                coordinator.reconcile()
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
        autoQuerySuspendedUntil = android.os.SystemClock.elapsedRealtime() + 120_000
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

    private fun autoQueriesAllowed(): Boolean =
        android.os.SystemClock.elapsedRealtime() >= autoQuerySuspendedUntil

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
     * 插件页 / 安装页可见期间持有；只有持有期间才做 TTL 周期核对与自动目录查询。
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
            // 订阅之前可能已发生切换；先撤销旧代证据，再只读核对来源。
            readyChanged()
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

    fun operation(id: String): PluginOperationQueue.Operation? = repository.operation(id)

    fun operationPreview(id: String): PluginRepository.Preview? = repository.operationPreview(id)

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

    fun checkUpdates(item: PluginRepository.Item): String = repository.checkUpdates(item)

    fun prepareUpdate(item: PluginRepository.Item): String = repository.prepareUpdate(item)

    fun rollback(item: PluginRepository.Item): String = repository.rollback(item)

    fun safeMode(enabled: Boolean, afterSuccess: Runnable): String =
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

        fun autoQueryAllowed(): Boolean = true

        fun cancelQuery() {}

        fun projection()

        fun observing(foreground: Boolean)

        fun watchCoverage() {}

        fun unavailable(error: Exception?)
    }

    /** 纯调度协议：入口和完成通知在主线程，probe/投影在独立后台单调度器。 */
    internal class Coordinator<S : Any>(
        private val backend: Backend<S>,
        private val clock: Clock,
        private val worker: Scheduler,
        private val main: Executor,
    ) {
        private val changes: PluginChangeCoalescer = PluginChangeCoalescer()

        private var scanTask: Cancellation? = null

        private var periodicTask: Cancellation? = null

        private var runtimeTask: Cancellation? = null

        private var runtimeExpiryTask: Cancellation? = null

        private var foreground: Boolean = false

        private var demand: Boolean = false

        private var closed: Boolean = false

        private var probing: Boolean = false

        private var awaitingProbe: Boolean = false

        private var queryIssued: Boolean = false

        // 有插件页可见时才做 TTL 周期核对与自动目录查询；App 前台本身只维持廉价的来源核对、变更标记和运行投影。
        private var watched: Boolean = false

        private var cancellingPrefetch: Boolean = false

        private var reconcilePending: Boolean = false

        private var sourceUnavailable: Boolean = false

        private var lifecycle: Long = 0

        private var runtimeRevision: Long = 0

        private var failedAt: Long = -1

        private var failedSource: Any? = null

        private var freshStamp: Long = Long.MIN_VALUE

        private var freshObservedAt: Long = 0

        private var freshFor: Long = 0

        private var freshSource: Any? = null

        /** 覆盖提示不修改缓存新鲜度，也不成为查询、probe 或运行投影的调度事件。 */
        @JvmName("watchCoverageChanged")
        internal fun watchCoverageChanged() {
            if (!closed) backend.watchCoverage()
        }

        @JvmName("ensureLoaded")
        internal fun ensureLoaded() {
            if (closed || fresh(backend.cache())) return
            if (changes.isScanning()) {
                demand = true
                return
            }
            if (!mayRetry(backend.cache())) return
            demand = true
            changes.requestNow(clock.now())
            scheduleScan()
        }

        @JvmName("refresh")
        internal fun refresh() {
            if (closed) return
            demand = true
            changes.requestNow(clock.now())
            scheduleScan()
        }

        @JvmName("foreground")
        internal fun foreground(value: Boolean) {
            if (closed || foreground == value) return
            foreground = value
            lifecycle++
            backend.observing(value)
            if (value) {
                reconcile()
                scheduleScan()
                schedulePeriodic()
            } else {
                reconcilePending = false
                cancelScan()
                periodicTask?.cancel()
                runtimeTask?.cancel()
                runtimeExpiryTask?.cancel()
                periodicTask = null
                runtimeTask = null
                runtimeExpiryTask = null
                if (queryIssued && !demand && !cancellingPrefetch) {
                    cancellingPrefetch = true
                    backend.cancelQuery()
                }
                // 自动预取的 probe 可退休；用户已请求的查询可继续，但不保留后台周期工作。
                if (awaitingProbe && !queryIssued && !demand) {
                    awaitingProbe = false
                    changes.scanFinished(clock.now())
                }
                if (demand) scheduleScan()
            }
        }

        /** 插件页可见性。对话页等其它界面在前台时不周期跑 guest 目录查询；变更信号保留到下次可见再核对。 */
        @JvmName("watched")
        internal fun watched(value: Boolean) {
            if (closed || watched == value) return
            watched = value
            if (value) {
                if (!foreground) return
                reconcile()
                scheduleScan()
                schedulePeriodic()
            } else {
                periodicTask?.cancel()
                periodicTask = null
                if (!demand) cancelScan()
            }
        }

        private fun active(): Boolean = foreground && watched

        @JvmName("invalidated")
        internal fun invalidated() {
            if (closed) return
            changes.markDirty(clock.now())
            scheduleScan()
        }

        @JvmName("reconcile")
        internal fun reconcile() {
            if (closed || !foreground) return
            reconcilePending = true
            reconcileIfIdle()
        }

        private fun reconcileIfIdle() {
            if (!reconcilePending || closed || !foreground || probing || changes.isScanning())
                return
            reconcilePending = false
            probe()
        }

        @JvmName("sourceResolved")
        internal fun sourceResolved(source: S?) {
            if (closed || source == null) return
            if (!Objects.equals(backend.cache().source, backend.source(source))) {
                // Repo 可能已发布较新来源；迟到回调不能反向覆盖。
                reconcile()
                return
            }
            backend.bind(source)
        }

        @JvmName("queryFinished")
        internal fun queryFinished() {
            if (closed) return
            awaitingProbe = false
            queryIssued = false
            val cache = backend.cache()
            val cancelledPrefetch = cancellingPrefetch
            val resumeCancelled = cancelledPrefetch && (active() || demand) && !fresh(cache)
            cancellingPrefetch = false
            sourceUnavailable = false
            demand = demand && (changes.hasPending() || resumeCancelled)
            // 在途期间新到的 READY 是一次独立核对机会；普通失败不会自行再查。
            if (!cache.error.isEmpty() && !cancelledPrefetch && !reconcilePending) {
                failedAt = clock.now()
                failedSource = cache.source
            } else {
                failedAt = -1
                freshStamp = Long.MIN_VALUE
            }
            changes.scanFinished(clock.now())
            // 取消期间发生的前台/页面需求允许一次新读取；普通成功或失败仍不会自行失效。
            if (resumeCancelled) changes.requestNow(clock.now())
            scheduleScan()
            if (!cancelledPrefetch && cache.known && cache.error.isEmpty()) runtimeDirty()
            reconcileIfIdle()
        }

        @JvmName("runtimeDirty")
        internal fun runtimeDirty() {
            if (closed || !foreground) return
            val epoch = lifecycle
            val revision = ++runtimeRevision
            runtimeExpiryTask?.cancel()
            runtimeExpiryTask =
                worker.schedule(
                    Runnable {
                        main.execute(
                            Runnable {
                                if (
                                    !closed &&
                                        foreground &&
                                        lifecycle == epoch &&
                                        runtimeRevision == revision
                                ) {
                                    runtimeExpiryTask = null
                                    worker.execute(Runnable { backend.projection() })
                                }
                            }
                        )
                    },
                    RUNTIME_EXPIRY_MS,
                )
            if (runtimeTask != null) return
            runtimeTask =
                worker.schedule(
                    Runnable {
                        main.execute(
                            Runnable {
                                if (!closed && foreground && lifecycle == epoch) {
                                    runtimeTask = null
                                    worker.execute(Runnable { backend.projection() })
                                }
                            }
                        )
                    },
                    RUNTIME_COALESCE_MS,
                )
        }

        private fun scheduleScan() {
            cancelScan()
            if (closed || (!active() && !demand)) return
            val delay = changes.nextDelayMillis(clock.now())
            if (delay < 0) return
            scanTask = worker.schedule(Runnable { main.execute(Runnable { beginScan() }) }, delay)
        }

        private fun beginScan() {
            scanTask = null
            if (closed || (!active() && !demand) || !changes.beginScan(clock.now())) return
            awaitingProbe = true
            probe()
        }

        private fun probe() {
            if (closed || probing) return
            probing = true
            val epoch = lifecycle
            val before = backend.cache()
            worker.execute(
                Runnable {
                    var source: S? = null
                    var failure: Exception? = null
                    try {
                        source = backend.probe()
                    } catch (unavailable: Exception) {
                        failure = unavailable
                    }
                    val resolved = source
                    val error = failure
                    main.execute(Runnable { probed(epoch, before, resolved, error) })
                }
            )
        }

        private fun probed(epoch: Long, before: Cache, source: S?, error: Exception?) {
            probing = false
            if (closed) return
            try {
                acceptProbe(epoch, before, source, error)
            } finally {
                reconcileIfIdle()
            }
        }

        private fun acceptProbe(epoch: Long, before: Cache, source: S?, error: Exception?) {
            if (epoch != lifecycle) {
                if (awaitingProbe && !queryIssued) {
                    awaitingProbe = false
                    changes.scanFinished(clock.now())
                    changes.requestNow(clock.now())
                    scheduleScan()
                }
                if (foreground) reconcile()
                return
            }
            val current = backend.cache()
            if (
                current.version != before.version &&
                    !Objects.equals(current.source, before.source) &&
                    (source == null || !Objects.equals(current.source, backend.source(source)))
            ) {
                // 后台读取期间已有来源换代：不绑定旧结果，保留一轮新的有界补偿。
                awaitingProbe = false
                if (!queryIssued) changes.scanFinished(clock.now())
                changes.requestNow(clock.now())
                scheduleScan()
                return
            }
            if (source == null) {
                backend.unavailable(error)
                sourceUnavailable = true
                failedAt = clock.now()
                failedSource = backend.cache().source
                if (awaitingProbe) {
                    awaitingProbe = false
                    demand = demand && changes.hasPending()
                    changes.scanFinished(clock.now())
                    scheduleScan()
                }
                return
            }
            if (sourceUnavailable) {
                // 相同来源恢复可读也构成恢复边界；列表自身失败仍沿原冷却处理。
                sourceUnavailable = false
                failedAt = -1
            }
            backend.bind(source)
            if (awaitingProbe) {
                awaitingProbe = false
                issueQuery(!demand)
            } else if (foreground) {
                val cache = backend.cache()
                if (watched && !fresh(cache) && mayRetry(cache)) {
                    changes.requestNow(clock.now())
                    cancelScan()
                    if (changes.beginScan(clock.now())) issueQuery(!demand) else scheduleScan()
                } else runtimeDirty()
            }
        }

        private fun issueQuery(automatic: Boolean) {
            if (automatic && !backend.autoQueryAllowed()) {
                // 用户动作期间只保留已有快照；下次页面需求或补偿窗口再核对。
                awaitingProbe = false
                changes.scanFinished(clock.now())
                return
            }
            if (automatic && !active()) {
                // probe 在途时插件页已离开：本轮不查，保留一次待办到下次可见。
                awaitingProbe = false
                changes.scanFinished(clock.now())
                changes.requestNow(clock.now())
                return
            }
            queryIssued = true
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
            // 心跳投影改变版本号但不能续租；时钟回拨也不能无限延长缓存。
            return clock.now() - freshObservedAt < freshFor
        }

        private fun schedulePeriodic() {
            if (closed || !active() || periodicTask != null) return
            val epoch = lifecycle
            periodicTask =
                worker.schedule(
                    Runnable {
                        main.execute(
                            Runnable {
                                if (!closed && active() && lifecycle == epoch) {
                                    periodicTask = null
                                    reconcile()
                                    schedulePeriodic()
                                }
                            }
                        )
                    },
                    SNAPSHOT_TTL_MS,
                )
        }

        private fun cancelScan() {
            scanTask?.cancel()
            scanTask = null
        }

        @JvmName("close")
        internal fun close() {
            if (closed) return
            closed = true
            lifecycle++
            backend.observing(false)
            cancelScan()
            periodicTask?.cancel()
            runtimeTask?.cancel()
            runtimeExpiryTask?.cancel()
            worker.close()
        }

        private companion object {
            // 活动摘要最多有效 10 秒；到期只重读投影，不自行重排或查询目录。
            private const val RUNTIME_EXPIRY_MS = 10_001L

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

    private class Background : Scheduler {
        private val executor: ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { action ->
                    Thread(action, "plugin-management").apply { isDaemon = true }
                },
            )

        init {
            executor.removeOnCancelPolicy = true
            executor.setKeepAliveTime(30, TimeUnit.SECONDS)
            executor.allowCoreThreadTimeOut(true)
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

        override fun execute(action: Runnable) {
            executor.execute(action)
        }

        override fun schedule(action: Runnable, delayMillis: Long): Cancellation {
            val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
            return Cancellation { future.cancel(false) }
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
