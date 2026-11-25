package com.deepseekharness.app.plugins

import android.annotation.SuppressLint
import android.os.FileObserver
import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.util.PluginJournalNames
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Objects
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * 只报告失效，不读取插件内容，不推导安装、激活或加载成功。
 *
 * <p>所有目录枚举及 FileObserver 构建/释放均在单独后台队列。前台期间按事件修复观察树， 后台释放观察器；无定时全盘扫描、常驻服务或唤醒锁。回调须由 Engine 转交其调度队列。
 */
class PluginChangeDetector
internal constructor(
    private val onDirty: Runnable,
    private val onRuntimeDirty: Runnable,
    private val onReconcile: Runnable,
    private val onCoverageChanged: Runnable,
    private val worker: Scheduler,
    private val clock: Clock,
    private val factory: ObserverFactory,
    private val limits: Limits,
) : AutoCloseable {
    enum class Kind {
        CONTENT,
        RUNTIME_PROJECTION,
        SOURCE_IDENTITY,
    }

    enum class Scope {
        EXACT,
        TREE,
        PACKAGES,
        HOME_PACKAGES,
        PROFILES,
        PREVIEWS,
        HISTORY,
        JOURNALS,
        SIGNED_BUILTIN,
    }

    /** Only these two private host roots are allowed targets of guest package aliases. */
    class GuestLayout
    internal constructor(
        root: File,
        data: File,
        internal val fs: BackupFileSystem,
    ) {
        internal val root: Path = root.absoluteFile.toPath().normalize()
        internal val data: Path = data.absoluteFile.toPath().normalize()

        internal fun guest(path: Path): Path? =
            when {
                path.startsWith(data) -> GUEST_DATA.resolve(data.relativize(path))
                path.startsWith(root) -> GUEST_ROOT.resolve(root.relativize(path))
                else -> null
            }

        internal fun host(path: Path): Pair<Path, Path> =
            if (path.startsWith(GUEST_DATA)) {
                data to data.resolve(GUEST_DATA.relativize(path))
            } else {
                root to root.resolve(GUEST_ROOT.relativize(path))
            }

        override fun equals(other: Any?): Boolean =
            other is GuestLayout && root == other.root && data == other.data

        override fun hashCode(): Int = Objects.hash(root, data)
    }

    /** Adapter 提供实际宿主路径；trustedRoot 必须是已经核验的宿主边界，不能传 guest 别名。 */
    class WatchPath(
        trustedRoot: File,
        path: File,
        @JvmField val kind: Kind,
        @JvmField val scope: Scope,
        internal val layout: GuestLayout?,
    ) {
        @JvmField val trustedRoot: File = trustedRoot.absoluteFile.toPath().normalize().toFile()

        @JvmField val path: File = path.absoluteFile.toPath().normalize().toFile()

        @JvmField val recursive: Boolean = scope != Scope.EXACT

        constructor(
            trustedRoot: File,
            path: File,
            kind: Kind,
            recursive: Boolean,
        ) : this(trustedRoot, path, kind, if (recursive) Scope.TREE else Scope.EXACT, null)

        init {
            if (!this.path.toPath().startsWith(this.trustedRoot.toPath())) {
                throw IllegalArgumentException("观察路径越过已核宿主边界")
            }
            require(layout != null || scope == Scope.TREE || scope == Scope.EXACT)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is WatchPath) return false
            return trustedRoot == other.trustedRoot &&
                path == other.path &&
                kind == other.kind &&
                scope == other.scope &&
                layout == other.layout
        }

        override fun hashCode(): Int = Objects.hash(trustedRoot, path, kind, scope, layout)
    }

    internal interface Scheduler {
        fun execute(work: Runnable)

        fun schedule(work: Runnable, delayMs: Long): Cancellation

        fun close()
    }

    internal fun interface Clock {
        fun now(): Long
    }

    internal fun interface Cancellation {
        fun cancel()
    }

    internal fun interface EventSink {
        fun event(event: Int, child: String?)
    }

    internal fun interface Watch {
        fun stop()
    }

    internal fun interface ObserverFactory {
        @Throws(IOException::class) fun start(directory: File, events: EventSink): Watch
    }

    internal class Limits
    internal constructor(
        @JvmField val directories: Int,
        @JvmField val entries: Int,
        @JvmField val depth: Int,
        @JvmField val buildMillis: Long,
    ) {
        init {
            if (directories < 1 || entries < 1 || depth < 1 || buildMillis < 1) {
                throw IllegalArgumentException("观察预算必须为正数")
            }
        }
    }

    private val lock = Any()

    private val repairs = PluginChangeCoalescer(100, 1000, 750)

    /** 仅后台队列访问。 */
    private val registrations: MutableMap<Path, Registration> = HashMap()

    private var desired: List<WatchPath> = Collections.emptyList()

    private var identity: Any? = null

    private var generation: Long = 0
    private var repairVersion: Long = 0

    private var foreground: Boolean = false

    private var closed: Boolean = false

    private var drainQueued: Boolean = false

    private var pendingContent: Boolean = false

    private var pendingRuntime: Boolean = false

    private var pendingRepair: Boolean = false

    private var pendingForce: Boolean = false
    private var reconcileAfterRepair: Boolean = false

    private var deferredContent: Boolean = false

    private var repairTask: Cancellation? = null

    /** 有界观察覆盖结果；后台、未绑定和修复中均不能宣称完整。 */
    class Coverage internal constructor(@JvmField val limited: Boolean, reasons: List<String>) {
        @JvmField val reasons: List<String> = java.util.List.copyOf(reasons)
    }

    @Volatile
    private var coverage: Coverage = Coverage(true, java.util.List.of("WATCH_NOT_OBSERVING"))

    internal data class PlanningStats(
        @JvmField val entries: Int,
        @JvmField val directories: Int,
        @JvmField val manifests: Int,
    )

    @Volatile private var planningStats = PlanningStats(0, 0, 0)

    @JvmName("planningStats") internal fun planningStats(): PlanningStats = planningStats

    /** 单回调版本不会把动态 heartbeat 升级为完整扫描。 */
    constructor(onDirty: Runnable) : this(onDirty, Runnable {})

    constructor(
        onDirty: Runnable,
        onRuntimeDirty: Runnable,
    ) : this(onDirty, onRuntimeDirty, onDirty)

    /** onReconcile 只请求核对；Engine 根据来源/缓存年龄决定是否扫描，不能把无事件视为内容证明。 */
    constructor(
        onDirty: Runnable,
        onRuntimeDirty: Runnable,
        onReconcile: Runnable,
    ) : this(onDirty, onRuntimeDirty, onReconcile, Runnable {})

    /** 覆盖变化是独立内存投影，不能回流到目录失效或调度核对。 */
    constructor(
        onDirty: Runnable,
        onRuntimeDirty: Runnable,
        onReconcile: Runnable,
        onCoverageChanged: Runnable,
    ) : this(
        onDirty,
        onRuntimeDirty,
        onReconcile,
        onCoverageChanged,
        Background(),
        Clock {
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() and Long.MAX_VALUE)
        },
        ObserverFactory { directory, events -> nativeWatch(directory, events) },
        Limits(1024, 32768, 48, 1000),
    )

    internal constructor(
        onDirty: Runnable,
        onRuntimeDirty: Runnable,
        worker: Scheduler,
        clock: Clock,
        factory: ObserverFactory,
        limits: Limits,
    ) : this(onDirty, onRuntimeDirty, onDirty, Runnable {}, worker, clock, factory, limits)

    internal constructor(
        onDirty: Runnable,
        onRuntimeDirty: Runnable,
        onReconcile: Runnable,
        worker: Scheduler,
        clock: Clock,
        factory: ObserverFactory,
        limits: Limits,
    ) : this(
        onDirty,
        onRuntimeDirty,
        onReconcile,
        Runnable {},
        worker,
        clock,
        factory,
        limits,
    )

    /** 仅复制描述符和比较来源身份；主线程不会探测磁盘。初始为非前台。 */
    fun bind(paths: List<WatchPath>, sourceIdentity: Any?) {
        if (paths.size > MAX_PATHS) {
            synchronized(lock) {
                if (!closed) updateCoverageLocked(true, java.util.List.of("WATCH_PATH_LIMIT"))
            }
            throw IllegalArgumentException("观察入口超过预算")
        }
        val copy = ArrayList(paths)
        for (path in copy) Objects.requireNonNull(path)
        synchronized(lock) {
            if (closed || (Objects.equals(identity, sourceIdentity) && desired == copy)) return
            identity = sourceIdentity
            desired = Collections.unmodifiableList(copy)
            generation++
            updateCoverageLocked(
                true,
                java.util.List.of(if (foreground) "WATCH_PENDING" else "WATCH_NOT_OBSERVING"),
            )
            pendingForce = true
            pendingRepair = true
            reconcileAfterRepair = foreground
            enqueueDrainLocked()
        }
    }

    /** 恢复前台必补偿请求；离开前台使旧回调立刻失效，后台队列负责关闭本机观察。 */
    fun foreground(value: Boolean) {
        synchronized(lock) {
            if (closed || foreground == value) return
            foreground = value
            generation++
            updateCoverageLocked(
                true,
                java.util.List.of(if (value) "WATCH_PENDING" else "WATCH_NOT_OBSERVING"),
            )
            deferredContent = deferredContent or pendingContent
            pendingContent = false
            pendingForce = value
            pendingRepair = value
            reconcileAfterRepair = value
            pendingRuntime = false
            enqueueDrainLocked()
        }
    }

    /** A successful expiry or explicit inventory scan can close unreported FileObserver gaps. */
    fun reconcileObservation() {
        synchronized(lock) {
            if (closed || !foreground) return
            pendingRepair = true
            enqueueDrainLocked()
        }
    }

    /** 超出枚举/目录/深度预算或路径暂不可读时为 true；不能把有限观察覆盖当作完整证明。 */
    fun coverageLimited(): Boolean = coverage.limited

    fun coverageReasons(): List<String> = coverage.reasons

    /** 一次取得不可变快照；getter 不读盘、不排队、不续期。 */
    fun coverage(): Coverage = coverage

    private fun updateCoverageLocked(limited: Boolean, reasons: List<String>) {
        val before = coverage
        if (before.limited == limited && before.reasons == reasons) return
        coverage = Coverage(limited, reasons)
        try {
            onCoverageChanged.run()
        } catch (ignored: RuntimeException) {
            // UI 投影失败不能变成目录失效或重新扫描。
        }
    }

    private fun publishCoverage(plan: Plan): Boolean {
        synchronized(lock) {
            if (closed || !foreground || plan.epoch != generation) return false
            planningStats = plan.stats()
            if (plan.repairVersion != repairVersion && !plan.changed) return false
            updateCoverageLocked(plan.limited, ArrayList(plan.reasons))
            if (plan.repairVersion != repairVersion) return false
            val reconcile = reconcileAfterRepair
            reconcileAfterRepair = false
            return reconcile
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            foreground = false
            generation++
            updateCoverageLocked(true, java.util.List.of("WATCH_NOT_OBSERVING"))
            worker.execute(
                Runnable {
                    cancelRepair()
                    stopAll()
                    worker.close()
                }
            )
        }
    }

    private fun enqueueDrainLocked() {
        if (!drainQueued) {
            drainQueued = true
            worker.execute(Runnable { drain() })
        }
    }

    private fun drain() {
        var content = false
        var runtime = false
        var repair = false
        var force = false
        var active = false
        var epoch = 0L
        synchronized(lock) {
            drainQueued = false
            active = !closed && foreground
            epoch = generation
            content = pendingContent || deferredContent
            runtime = pendingRuntime
            repair = pendingRepair
            force = pendingForce
            pendingContent = false
            pendingRuntime = false
            pendingRepair = false
            pendingForce = false
            if (active) deferredContent = false
            else if (!closed) deferredContent = deferredContent or content
        }
        if (!active) {
            cancelRepair()
            stopAll()
            return
        }
        if (content) dirtyCallback(epoch)
        if (runtime) callback(onRuntimeDirty, epoch)
        if (repair) {
            if (force) {
                // 来源换代及本机失效不能复用旧观察器，即使路径与 inode 恰好未变。
                stopAll()
                repairs.requestNow(clock.now())
            } else repairs.markDirty(clock.now())
            scheduleRepair()
        }
    }

    private fun scheduleRepair() {
        cancelRepair()
        val delay = repairs.nextDelayMillis(clock.now())
        if (delay >= 0) repairTask = worker.schedule(Runnable { rebuild() }, delay)
    }

    private fun cancelRepair() {
        if (repairTask != null) {
            repairTask!!.cancel()
            repairTask = null
        }
    }

    private fun rebuild() {
        repairTask = null
        var paths: List<WatchPath> = Collections.emptyList()
        var epoch = 0L
        var observedVersion = 0L
        synchronized(lock) {
            if (closed || !foreground) return
            paths = desired
            epoch = generation
            observedVersion = repairVersion
        }
        if (!repairs.beginScan(clock.now())) {
            scheduleRepair()
            return
        }
        val plan = Plan(epoch, observedVersion)
        if (paths.isEmpty()) plan.limit("WATCH_NO_PATHS")
        try {
            // 先绑定每个入口的父链/顶层，防止一个大插件吃掉 profile/journal 的观察配额。
            for (path in paths) plan.seed(path)
            plan.descend()
            if (!active(epoch)) return
            val next = HashMap<Path, Registration>()
            for (spec in plan.specs.values) {
                if (!active(epoch)) break
                val existing = registrations[spec.path]
                val created = Registration(spec, epoch)
                var observerFailed = false
                try {
                    if (safeDirectory(spec.root, spec.path, spec.device) != spec.stamp) {
                        plan.limit("WATCH_PATH_CHANGED")
                        plan.changed = true
                        continue
                    }
                    if (
                        existing != null && existing.epoch == epoch && existing.stamp == spec.stamp
                    ) {
                        existing.rules = java.util.List.copyOf(spec.rules)
                        next[spec.path] = existing
                        continue
                    }
                    try {
                        created.watch =
                            factory.start(
                                spec.path.toFile(),
                                EventSink { mask, child -> event(created, mask, child) },
                            )
                        if (created.watch == null) {
                            throw IOException("PLUGIN_WATCH_START_UNAVAILABLE")
                        }
                    } catch (unavailable: Exception) {
                        plan.limit("WATCH_OBSERVER_FAILED")
                        observerFailed = true
                        throw unavailable
                    }
                    if (safeDirectory(spec.root, spec.path, spec.device) != spec.stamp) {
                        created.stop()
                        plan.limit("WATCH_PATH_CHANGED")
                        plan.changed = true
                        continue
                    }
                    next[spec.path] = created
                } catch (unavailable: Exception) {
                    created.stop()
                    if (!observerFailed) {
                        plan.limit(
                            if (pathChanged(unavailable)) "WATCH_PATH_CHANGED"
                            else "WATCH_PATH_UNREADABLE"
                        )
                        plan.changed = plan.changed or pathChanged(unavailable)
                    }
                }
            }
            for (old in registrations.values) {
                if (next[old.path] !== old) old.stop()
            }
            registrations.clear()
            if (active(epoch)) registrations.putAll(next)
            else for (stale in next.values) stale.stop()
            plan.checkRegistrationWindow()
            val reconcile = publishCoverage(plan)
            // 新观察器建立后请求核对，实测到身份变化则必须保留真实 dirty。
            if (plan.changed) dirtyCallback(epoch) else if (reconcile) callback(onReconcile, epoch)
        } finally {
            if (plan.changed && active(epoch)) repairs.markDirty(clock.now())
            repairs.scanFinished(clock.now())
            if (repairs.hasPending()) scheduleRepair()
        }
    }

    private fun pathChanged(error: Exception): Boolean =
        error is java.nio.file.NoSuchFileException ||
            "PLUGIN_WATCH_ROOT_CHANGED" == error.message ||
            "PLUGIN_WATCH_PATH_CHANGED" == error.message

    private fun event(registration: Registration, event: Int, child: String?) {
        if (registration.retired || (event and (CHANGE_MASK or INVALID_MASK)) == 0) return
        val invalid = (event and SELF_MASK) != 0
        var content = false
        var runtime = false
        var repair = invalid
        for (rule in registration.rules) {
            if (!invalid && !rule.matches(child)) continue
            if (rule.kind == Kind.RUNTIME_PROJECTION) runtime = true else content = true
            if (
                rule.replan ||
                    (event and STRUCTURAL_MASK) != 0 &&
                        (rule.ancestor || (event and IS_DIRECTORY) != 0)
            )
                repair = true
        }
        if (!content && !runtime && !repair) return
        synchronized(lock) {
            if (closed || !foreground || registration.retired || registration.epoch != generation)
                return
            pendingContent = pendingContent or content
            pendingRuntime = pendingRuntime or runtime
            pendingRepair = pendingRepair or repair
            pendingForce = pendingForce or invalid
            if (repair) {
                repairVersion++
                reconcileAfterRepair = true
                updateCoverageLocked(
                    true,
                    java.util.List.of(
                        when {
                            (event and OVERFLOW) != 0 -> "WATCH_EVENT_OVERFLOW"
                            invalid -> "WATCH_INVALIDATED"
                            else -> "WATCH_PENDING"
                        }
                    ),
                )
            }
            enqueueDrainLocked()
        }
    }

    private fun active(epoch: Long): Boolean =
        synchronized(lock) {
            !closed && foreground && generation == epoch
        }

    private fun dirtyCallback(epoch: Long) {
        if (callback(onDirty, epoch)) return
        synchronized(lock) {
            if (!closed) deferredContent = true
        }
    }

    private fun callback(callback: Runnable, epoch: Long): Boolean {
        if (!active(epoch)) return false
        // Engine 回调只投递失效消息；一处异常不应永久终止本机观察器线程。
        return try {
            callback.run()
            true
        } catch (ignored: RuntimeException) {
            false
        }
    }

    private fun stopAll() {
        for (registration in registrations.values) registration.stop()
        registrations.clear()
    }

    private inner class Plan(val epoch: Long, val repairVersion: Long) {
        private val startedAt: Long = clock.now()

        val specs: MutableMap<Path, Spec> = LinkedHashMap()

        private val branches: ArrayDeque<Branch> = ArrayDeque()

        private val visited = HashSet<BranchKey>()

        private var inspected: Int = 0
        private var manifestReads: Int = 0
        private var manifestBytes: Long = 0
        private val manifestVersions = HashMap<Path, Version?>()
        private val directoryVersions = HashMap<Path, Version?>()
        private val payloads = ArrayDeque<Branch>()
        private val signedRoots = HashSet<Path>()

        var limited: Boolean = false

        var changed: Boolean = false

        val reasons: MutableSet<String> = LinkedHashSet()

        fun stats() = PlanningStats(inspected, specs.size, manifestReads)

        fun limit(reason: String) {
            limited = true
            if (reasons.size < 8) reasons.add(reason)
        }

        private fun budget(): Boolean {
            if (!active(epoch)) return false
            if (clock.now() - startedAt >= limits.buildMillis) {
                limit("WATCH_TIME_LIMIT")
                return false
            }
            return true
        }

        fun seed(input: WatchPath) {
            if (!budget()) return
            val root = input.trustedRoot.toPath()
            val target = input.path.toPath()
            try {
                val base = stamp(root)
                if (!base.directory || base.symlink) {
                    limit("WATCH_UNSAFE_PATH")
                    return
                }
                var parent = root
                var ancestors = 0
                for (component in root.relativize(target)) {
                    if (component.toString().isEmpty()) continue
                    if (!budget()) return
                    ancestors++
                    if (ancestors > limits.depth) {
                        limit("WATCH_DEPTH_LIMIT")
                        return
                    }
                    val child = parent.resolve(component)
                    val ancestor = child != target || input.scope != Scope.EXACT
                    add(root, parent, base.device, Rule(input.kind, component.toString(), ancestor))
                    val state =
                        try {
                            stamp(child)
                        } catch (missing: java.nio.file.NoSuchFileException) {
                            directoryVersions[child] = null
                            return // 父级观察器会报告将来的创建；不可读不在此分支伪装缺席。
                        }
                    if (state.symlink || state.device != base.device) {
                        limit("WATCH_UNSAFE_PATH")
                        return
                    }
                    if (!state.directory) {
                        if (ancestor) limit("WATCH_UNSAFE_PATH")
                        return
                    }
                    parent = child
                }
                val walk =
                    when (input.scope) {
                        Scope.EXACT -> null
                        Scope.TREE -> Walk.TREE
                        Scope.PACKAGES -> Walk.PACKAGES
                        Scope.HOME_PACKAGES -> Walk.HOME_PACKAGES
                        Scope.PROFILES -> Walk.PROFILES
                        Scope.PREVIEWS -> Walk.PREVIEWS
                        Scope.HISTORY -> Walk.HISTORY
                        Scope.JOURNALS -> Walk.JOURNALS
                        Scope.SIGNED_BUILTIN ->
                            Walk.PACKAGE_PAYLOAD.also { signedRoots.add(target) }
                    }
                if (walk == null) {
                    // 身份根仅观察自身，不能让日志或 heartbeat 触发完整扫描。
                    add(
                        root,
                        target,
                        base.device,
                        Rule(
                            input.kind,
                            if (input.kind == Kind.SOURCE_IDENTITY) "" else null,
                            false,
                        ),
                    )
                } else branch(root, target, base.device, input.kind, walk, 0, input.layout)
            } catch (unavailable: Exception) {
                limit("WATCH_PATH_UNREADABLE")
            }
        }

        private fun add(root: Path, directory: Path, device: Long, rule: Rule): Boolean {
            var spec = specs[directory]
            if (spec == null) {
                if (specs.size >= limits.directories) {
                    limit("WATCH_DIRECTORY_LIMIT")
                    return false
                }
                spec = Spec(root, directory, device, safeDirectory(root, directory, device))
                specs[directory] = spec
            }
            if (!spec.rules.contains(rule)) spec.rules.add(rule)
            return true
        }

        private fun branch(
            root: Path,
            path: Path,
            device: Long,
            kind: Kind,
            walk: Walk,
            depth: Int,
            layout: GuestLayout?,
        ) {
            val node = Branch(root, path, device, kind, walk, depth, layout)
            val rule = Rule(kind, null, walk.collection, filter = walk)
            if (add(root, path, device, rule) && visited.add(BranchKey(path, kind, walk))) {
                if (walk == Walk.TREE || walk == Walk.PACKAGE_PAYLOAD) payloads.addLast(node)
                else branches.addLast(node)
            }
        }

        fun descend() {
            while ((branches.isNotEmpty() || payloads.isNotEmpty()) && budget()) {
                val branch =
                    if (branches.isNotEmpty()) branches.removeFirst() else payloads.removeFirst()
                if (branch.depth >= limits.depth) {
                    limit("WATCH_DEPTH_LIMIT")
                    continue
                }
                try {
                    val before = safeDirectory(branch.root, branch.path, branch.device)
                    directoryVersions.putIfAbsent(branch.path, versionOrMissing(branch.path))
                    Files.newDirectoryStream(branch.path).use { children ->
                        for (child in children) {
                            if (inspected >= limits.entries) {
                                limit("WATCH_ENTRY_LIMIT")
                                return
                            }
                            inspected++
                            if (!budget()) return
                            val name = child.fileName.toString()
                            if (branch.walk.allows(name)) inspect(branch, child, name)
                            if (reasons.contains("WATCH_DIRECTORY_LIMIT")) return
                        }
                    }
                    if (safeDirectory(branch.root, branch.path, branch.device) != before) {
                        limit("WATCH_PATH_CHANGED")
                        changed = true
                    }
                } catch (unavailable: Exception) {
                    limit(
                        if (pathChanged(unavailable)) "WATCH_PATH_CHANGED"
                        else "WATCH_PATH_UNREADABLE"
                    )
                    changed = changed or pathChanged(unavailable)
                }
            }
        }

        private fun inspect(branch: Branch, child: Path, name: String) {
            val state = stamp(child)
            val walk = branch.walk
            if (state.symlink) {
                if (
                    walk == Walk.PACKAGES ||
                        walk == Walk.PACKAGE_SCOPE ||
                        walk == Walk.HOME_PACKAGES
                ) {
                    val target = resolveAlias(branch, child) ?: return
                    if (walk == Walk.PACKAGES && name.startsWith("@")) {
                        branch(
                            target.root,
                            target.path,
                            target.device,
                            branch.kind,
                            Walk.PACKAGE_SCOPE,
                            branch.depth + 1,
                            branch.layout,
                        )
                    } else observePackage(target, branch)
                } else if (walk.collection && !name.endsWith(".disabled")) {
                    limit("WATCH_UNSAFE_PATH")
                }
                return
            }
            if (state.device != branch.device) {
                limit("WATCH_UNSAFE_PATH")
                return
            }
            if (!state.directory) return
            when (walk) {
                Walk.PACKAGES,
                Walk.PACKAGE_SCOPE,
                Walk.HOME_PACKAGES -> {
                    if (walk == Walk.PACKAGES && name.startsWith("@")) {
                        branch(
                            branch.root,
                            child,
                            branch.device,
                            branch.kind,
                            Walk.PACKAGE_SCOPE,
                            branch.depth + 1,
                            branch.layout,
                        )
                    } else observePackage(Target(branch.root, child, branch.device), branch)
                }
                Walk.PROFILES -> {
                    val modules =
                        if (name == "node_modules") child else child.resolve("node_modules")
                    if (modules != child)
                        add(
                            branch.root,
                            child,
                            branch.device,
                            Rule(branch.kind, "node_modules", true),
                        )
                    val selected = stampOrMissing(modules)
                    if (selected == null) {
                        directoryVersions[modules] = null
                        return
                    }
                    if (
                        selected.directory && !selected.symlink && selected.device == branch.device
                    ) {
                        branch(
                            branch.root,
                            modules,
                            branch.device,
                            branch.kind,
                            Walk.PACKAGES,
                            branch.depth + 1,
                            branch.layout,
                        )
                    } else limit("WATCH_UNSAFE_PATH")
                }
                Walk.PREVIEWS ->
                    add(
                        branch.root,
                        child,
                        branch.device,
                        Rule(branch.kind, "preview.json", false),
                    )
                Walk.HISTORY -> {
                    add(branch.root, child, branch.device, Rule(branch.kind, "state.json", false))
                    add(branch.root, child, branch.device, Rule(branch.kind, "package", true))
                    val saved = child.resolve("package")
                    val selected = stampOrMissing(saved)
                    if (selected == null) {
                        directoryVersions[saved] = null
                        return
                    }
                    if (
                        selected.directory && !selected.symlink && selected.device == branch.device
                    ) {
                        add(
                            branch.root,
                            saved,
                            branch.device,
                            Rule(branch.kind, "package.json", false),
                        )
                    } else limit("WATCH_UNSAFE_PATH")
                }
                Walk.JOURNALS -> {
                    if (name == PluginJournalNames.HISTORY) {
                        add(branch.root, child, branch.device, Rule(branch.kind, null, false))
                    } else {
                        for (file in JOURNAL_FILES) {
                            add(branch.root, child, branch.device, Rule(branch.kind, file, false))
                        }
                    }
                }
                Walk.TREE,
                Walk.PACKAGE_PAYLOAD ->
                    branch(
                        branch.root,
                        child,
                        branch.device,
                        branch.kind,
                        Walk.TREE,
                        branch.depth + 1,
                        branch.layout,
                    )
            }
        }

        /**
         * Guest aliases may cross the /root/.dsh bind. Rewalk each target from one verified host
         * root, watching link components and missing targets via their parents.
         */
        private fun resolveAlias(branch: Branch, alias: Path): Target? {
            val layout = branch.layout ?: return unsafeTarget()
            var at = layout.guest(alias) ?: return unsafeTarget()
            val seen = HashSet<Path>()
            repeat(MAX_ALIAS_HOPS) {
                if (!budget()) return null
                if (!seen.add(at)) return unsafeTarget()
                val (root, target) = layout.host(at)
                val base = stamp(root)
                if (!base.directory || base.symlink) return unsafeTarget()
                var parent = root
                val suffix = root.relativize(target)
                if (suffix.toString().isEmpty() || suffix.nameCount > limits.depth)
                    return unsafeTarget()
                for (index in 0 until suffix.nameCount) {
                    val name = suffix.getName(index)
                    val child = parent.resolve(name)
                    val state = stampOrMissing(child)
                    if (state == null || state.symlink) {
                        add(root, parent, base.device, Rule(branch.kind, name.toString(), true))
                        if (state == null) {
                            directoryVersions[child] = null
                            return null
                        }
                        val raw = Paths.get(layout.fs.readLink(child.toFile()))
                        // Supported relative package links have leading ../ only. Resolving
                        // a/../b lexically could conceal an intervening alias.
                        var normalComponent = false
                        for (part in raw) {
                            if (part.toString() == ".." && (raw.isAbsolute || normalComponent))
                                return unsafeTarget()
                            if (part.toString() != ".." && part.toString() != ".")
                                normalComponent = true
                        }
                        val guestParent = layout.guest(parent) ?: return unsafeTarget()
                        at = guestParent.resolve(raw).normalize()
                        if (index + 1 < suffix.nameCount)
                            at = at.resolve(suffix.subpath(index + 1, suffix.nameCount))
                        if (GUEST_EXTERNAL.any { at.startsWith(it) }) return unsafeTarget()
                        break
                    }
                    if (!state.directory || state.device != base.device) return unsafeTarget()
                    // Replacing an ancestor of the resolved physical target changes the alias.
                    add(root, parent, base.device, Rule(branch.kind, name.toString(), true))
                    parent = child
                    if (child == target) return Target(root, target, base.device)
                }
            }
            return unsafeTarget()
        }

        private fun unsafeTarget(): Target? {
            limit("WATCH_UNSAFE_PATH")
            return null
        }

        private fun observePackage(target: Target, branch: Branch) {
            if (signedRoots.contains(target.path)) return
            val manifest = target.path.resolve("package.json")
            if (
                !add(
                    target.root,
                    target.path,
                    target.device,
                    Rule(branch.kind, "package.json", true, replan = true),
                )
            )
                return
            val layout = branch.layout ?: return
            if (manifestVersions.containsKey(manifest)) return
            val before = versionOrMissing(manifest)
            manifestVersions[manifest] = before
            if (before == null) return
            if (!before.regular) {
                limit("WATCH_UNSAFE_PATH")
                return
            }
            // The authoritative discovery reader never opens a larger package.json.
            if (before.size > MANIFEST_LIMIT) return
            if (manifestBytes + before.size > MANIFEST_BYTES_BUDGET) {
                limit("WATCH_ENTRY_LIMIT")
                return
            }
            manifestBytes += before.size
            manifestReads++
            val value =
                try {
                    val bytes = layout.fs.small(manifest.toFile(), MANIFEST_LIMIT)
                    JsonParser.parseString(String(bytes, Charsets.UTF_8))
                } catch (error: Exception) {
                    limit("WATCH_PATH_UNREADABLE")
                    return
                }
            val bundle = value.objectMember("dsh")?.objectMember("bundle") ?: return
            if (!bundle.isJsonObject) return
            // Runtime provenance reads all bytes of actual dsh bundles, including their
            // dependencies.
            // Outside that anchor, native inventory reads declared patches but not the dependency
            // tree.
            val payload =
                if (target.path.startsWith(layout.root.resolve(RUNTIME_ANCHOR))) {
                    Walk.TREE
                } else Walk.PACKAGE_PAYLOAD
            branch(
                target.root,
                target.path,
                target.device,
                branch.kind,
                payload,
                branch.depth + 1,
                layout,
            )
            if (payload == Walk.PACKAGE_PAYLOAD) {
                val declaration = bundle.asJsonObject.get("patch") ?: return
                val patches =
                    if (declaration.isJsonArray) declaration.asJsonArray.asList()
                    else listOf(declaration)
                if (patches.size > MAX_DECLARED_PATCHES) {
                    limit("WATCH_ENTRY_LIMIT")
                    return
                }
                for (patch in patches) watchDeclaredPatch(target, branch.kind, patch)
            }
        }

        private fun watchDeclaredPatch(target: Target, kind: Kind, entry: JsonElement) {
            if (!budget() || !entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) return
            val raw = entry.asString
            if (raw.isBlank() || raw.length > 4096 || '\u0000' in raw || '\\' in raw) return
            val parsed = Paths.get(raw)
            if (parsed.root != null) return
            val file = target.path.resolve(parsed).normalize()
            if (file == target.path || !file.startsWith(target.path)) return
            // Also checks for a declared symlink. An in-place edit through a target that cannot be
            // observed safely is covered by catalog reconciliation, so do not claim full coverage.
            seed(WatchPath(target.root.toFile(), file.toFile(), kind, false))
        }

        fun checkRegistrationWindow() {
            checkVersions(manifestVersions)
            checkVersions(directoryVersions)
        }

        private fun checkVersions(versions: Map<Path, Version?>) {
            for ((path, before) in versions) {
                if (!active(epoch)) return
                try {
                    if (versionOrMissing(path) != before) {
                        limit("WATCH_PATH_CHANGED")
                        changed = true
                        return
                    }
                } catch (error: IOException) {
                    limit("WATCH_PATH_UNREADABLE")
                }
            }
        }
    }

    private fun safeDirectory(root: Path, path: Path, device: Long): Stamp {
        if (!path.startsWith(root)) throw IOException("PLUGIN_WATCH_OUTSIDE_ROOT")
        var current = root
        var state = stamp(current)
        if (!state.directory || state.symlink || state.device != device) {
            throw IOException("PLUGIN_WATCH_ROOT_CHANGED")
        }
        for (component in root.relativize(path)) {
            if (component.toString().isEmpty()) continue
            current = current.resolve(component)
            state = stamp(current)
            if (!state.directory || state.symlink || state.device != device) {
                throw IOException("PLUGIN_WATCH_PATH_CHANGED")
            }
        }
        return state
    }

    private fun stamp(path: Path): Stamp {
        val values =
            Files.readAttributes(
                path,
                "unix:dev,fileKey,isDirectory,isSymbolicLink",
                LinkOption.NOFOLLOW_LINKS,
            )
        val device = values["dev"]
        val key = values["fileKey"]
        if (device !is Number || key == null) {
            throw IOException("PLUGIN_WATCH_IDENTITY_UNAVAILABLE")
        }
        return Stamp(
            values["isDirectory"] == true,
            values["isSymbolicLink"] == true,
            device.toLong(),
            key,
        )
    }

    private fun stampOrMissing(path: Path): Stamp? =
        try {
            stamp(path)
        } catch (missing: java.nio.file.NoSuchFileException) {
            null
        }

    private fun versionOrMissing(path: Path): Version? =
        try {
            val values =
                Files.readAttributes(
                    path,
                    "unix:fileKey,isRegularFile,size,lastModifiedTime,ctime",
                    LinkOption.NOFOLLOW_LINKS,
                )
            Version(
                values["fileKey"] ?: throw IOException("PLUGIN_WATCH_IDENTITY_UNAVAILABLE"),
                values["isRegularFile"] == true,
                (values["size"] as Number).toLong(),
                values["lastModifiedTime"] as FileTime,
                values["ctime"] as FileTime,
            )
        } catch (missing: java.nio.file.NoSuchFileException) {
            null
        }

    private data class Version(
        val key: Any,
        val regular: Boolean,
        val size: Long,
        val modified: FileTime,
        val changed: FileTime,
    )

    private fun JsonElement.objectMember(name: String): JsonElement? =
        if (isJsonObject) asJsonObject.get(name) else null

    private class Stamp(
        val directory: Boolean,
        val symlink: Boolean,
        val device: Long,
        val key: Any,
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is Stamp) return false
            return directory == other.directory &&
                symlink == other.symlink &&
                device == other.device &&
                key == other.key
        }

        override fun hashCode(): Int = Objects.hash(directory, symlink, device, key)
    }

    private data class Rule(
        val kind: Kind,
        val child: String?,
        val ancestor: Boolean,
        val replan: Boolean = false,
        val filter: Walk = Walk.TREE,
    ) {
        fun matches(value: String?): Boolean =
            if (child != null) child == value else value == null || filter.allows(value)
    }

    private class Spec(val root: Path, val path: Path, val device: Long, val stamp: Stamp) {
        val rules: MutableList<Rule> = ArrayList()
    }

    private enum class Walk(val collection: Boolean) {
        TREE(false),
        PACKAGE_PAYLOAD(false),
        PACKAGES(true),
        PACKAGE_SCOPE(true),
        HOME_PACKAGES(true),
        PROFILES(true),
        PREVIEWS(true),
        HISTORY(true),
        JOURNALS(true);

        fun allows(name: String): Boolean =
            when (this) {
                TREE -> true
                PACKAGE_PAYLOAD -> name != "node_modules"
                PACKAGES -> PACKAGE_COMPONENT.matches(name) || PACKAGE_SCOPE_NAME.matches(name)
                PACKAGE_SCOPE -> PACKAGE_COMPONENT.matches(name)
                HOME_PACKAGES -> name != "node_modules" && PACKAGE_COMPONENT.matches(name)
                PROFILES -> PROFILE_NAME.matches(name)
                PREVIEWS,
                HISTORY -> RECORD_NAME.matches(name)
                JOURNALS ->
                    when (PluginJournalNames.kind(name)) {
                        PluginJournalNames.Kind.HISTORY,
                        PluginJournalNames.Kind.TRANSACTION -> true
                        else -> false
                    }
            }
    }

    private data class BranchKey(val path: Path, val kind: Kind, val walk: Walk)

    private class Branch(
        val root: Path,
        val path: Path,
        val device: Long,
        val kind: Kind,
        val walk: Walk,
        val depth: Int,
        val layout: GuestLayout?,
    )

    private data class Target(val root: Path, val path: Path, val device: Long)

    private class Registration(spec: Spec, val epoch: Long) {
        val path: Path = spec.path

        val stamp: Stamp = spec.stamp

        @Volatile var rules: List<Rule> = java.util.List.copyOf(spec.rules)

        @Volatile var retired: Boolean = false

        var watch: Watch? = null

        fun stop() {
            retired = true
            watch?.stop()
        }
    }

    private class Background : Scheduler {
        private val executor: ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { work ->
                    Thread(work, "plugin-file-observers").apply { isDaemon = true }
                },
            )

        init {
            executor.removeOnCancelPolicy = true
            executor.setKeepAliveTime(30, TimeUnit.SECONDS)
            executor.allowCoreThreadTimeOut(true)
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }

        override fun execute(work: Runnable) {
            executor.execute(work)
        }

        override fun schedule(work: Runnable, delayMs: Long): Cancellation {
            val future = executor.schedule(work, delayMs, TimeUnit.MILLISECONDS)
            return Cancellation { future.cancel(false) }
        }

        override fun close() {
            executor.shutdown()
        }
    }

    companion object {
        // Linux 的失效/目录标记没有全部暴露为 FileObserver 常量。
        internal const val UNMOUNT = 0x2000

        internal const val OVERFLOW = 0x4000

        internal const val IGNORED = 0x8000

        internal const val IS_DIRECTORY = 0x40000000

        private const val IN_ONLYDIR = 0x01000000

        private const val IN_DONT_FOLLOW = 0x02000000

        internal val CHANGE_MASK: Int =
            FileObserver.MODIFY or
                FileObserver.ATTRIB or
                FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF

        private val INVALID_MASK: Int = UNMOUNT or OVERFLOW or IGNORED

        private val SELF_MASK: Int =
            FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or INVALID_MASK

        private val STRUCTURAL_MASK: Int =
            FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO

        private const val MAX_PATHS = 128
        private const val MAX_ALIAS_HOPS = 16
        private const val MAX_DECLARED_PATCHES = 32
        private const val MANIFEST_LIMIT = 1024 * 1024
        private const val MANIFEST_BYTES_BUDGET = 8L * 1024 * 1024
        private val PACKAGE_COMPONENT = Regex("[a-z0-9][a-z0-9._-]{0,213}")
        private val PACKAGE_SCOPE_NAME = Regex("@[a-z0-9][a-z0-9._-]{0,211}")
        private val PROFILE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")
        private val RECORD_NAME = Regex("[a-f0-9]{32}")
        private val JOURNAL_FILES = arrayOf("plan.json", "committed", "rolled-back")
        private val GUEST_ROOT = Paths.get("/")
        private val GUEST_DATA = Paths.get("/root/.dsh")
        private const val RUNTIME_ANCHOR = "usr/local/lib/node_modules/@deepseek-ai/dsh"
        // These guest paths can be bind-mounted from outside either plugin source root.
        private val GUEST_EXTERNAL =
            listOf(
                    "/apex",
                    "/data",
                    "/dev",
                    "/mnt",
                    "/proc",
                    "/run/dsha-rc1-state",
                    "/sdcard",
                    "/storage",
                    "/sys",
                    "/system",
                )
                .map { Paths.get(it) }

        @Suppress("DEPRECATION")
        @SuppressLint("WrongConstant")
        private fun nativeWatch(directory: File, events: EventSink): Watch {
            // AOSP FileObserver 的 Java/JNI 原样转交 mask 给 inotify_add_watch；公开 IntDef 只列事件位。
            // IN_ONLYDIR | IN_DONT_FOLLOW 是注册保护位：路径在注册瞬间被替换为 symlink 时也不追随目标。
            // 此处局部声明该差异，不能删掉保护位或放宽全工程 Lint。
            val observer: FileObserver =
                object :
                    FileObserver(
                        directory.absolutePath,
                        CHANGE_MASK or IN_ONLYDIR or IN_DONT_FOLLOW,
                    ) {
                    override fun onEvent(event: Int, path: String?) {
                        events.event(event, path)
                    }
                }
            observer.startWatching()
            return Watch { observer.stopWatching() }
        }
    }
}
