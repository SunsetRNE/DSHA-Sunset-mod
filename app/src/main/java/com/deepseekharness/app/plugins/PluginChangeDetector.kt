package com.deepseekharness.app.plugins

import android.annotation.SuppressLint
import android.os.FileObserver
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

    /** Adapter 提供实际宿主路径；trustedRoot 必须是已经核验的宿主边界，不能传 guest 别名。 */
    class WatchPath(trustedRoot: File, path: File, kind: Kind, recursive: Boolean) {
        @JvmField val trustedRoot: File = trustedRoot.absoluteFile.toPath().normalize().toFile()

        @JvmField val path: File = path.absoluteFile.toPath().normalize().toFile()

        @JvmField val kind: Kind = kind

        @JvmField val recursive: Boolean = recursive

        init {
            if (!this.path.toPath().startsWith(this.trustedRoot.toPath())) {
                throw IllegalArgumentException("观察路径越过已核宿主边界")
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is WatchPath) return false
            return trustedRoot == other.trustedRoot &&
                path == other.path &&
                kind == other.kind &&
                recursive == other.recursive
        }

        override fun hashCode(): Int = Objects.hash(trustedRoot, path, kind, recursive)
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

    private var foreground: Boolean = false

    private var closed: Boolean = false

    private var drainQueued: Boolean = false

    private var pendingContent: Boolean = false

    private var pendingRuntime: Boolean = false

    private var pendingRepair: Boolean = false

    private var pendingForce: Boolean = false

    private var pendingReconcile: Boolean = false

    private var deferredContent: Boolean = false

    private var repairTask: Cancellation? = null

    /** 有界观察覆盖结果；后台、未绑定和修复中均不能宣称完整。 */
    class Coverage internal constructor(@JvmField val limited: Boolean, reasons: List<String>) {
        @JvmField val reasons: List<String> = java.util.List.copyOf(reasons)
    }

    @Volatile
    private var coverage: Coverage = Coverage(true, java.util.List.of("WATCH_NOT_OBSERVING"))

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
            pendingReconcile = true
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
            pendingReconcile = value
            pendingRuntime = false
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

    private fun publishCoverage(plan: Plan) {
        synchronized(lock) {
            if (closed || !foreground || plan.epoch != generation) return
            updateCoverageLocked(plan.limited, ArrayList(plan.reasons))
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
        var reconcile = false
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
            reconcile = pendingReconcile
            pendingContent = false
            pendingRuntime = false
            pendingRepair = false
            pendingForce = false
            pendingReconcile = false
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
        if (reconcile) callback(onReconcile, epoch)
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
        synchronized(lock) {
            if (closed || !foreground) return
            paths = desired
            epoch = generation
        }
        if (!repairs.beginScan(clock.now())) {
            scheduleRepair()
            return
        }
        val plan = Plan(epoch)
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
            publishCoverage(plan)
            // 新观察器建立后请求核对，实测到身份变化则必须保留真实 dirty。
            if (plan.changed) dirtyCallback(epoch) else callback(onReconcile, epoch)
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
                (event and STRUCTURAL_MASK) != 0 && (rule.ancestor || (event and IS_DIRECTORY) != 0)
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

    private inner class Plan(val epoch: Long) {
        private val startedAt: Long = clock.now()

        val specs: MutableMap<Path, Spec> = LinkedHashMap()

        private val branches: ArrayDeque<Branch> = ArrayDeque()

        private val visited: MutableSet<String> = HashSet()

        private var inspected: Int = 0

        var limited: Boolean = false

        var changed: Boolean = false

        val reasons: MutableSet<String> = LinkedHashSet()

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
                    val ancestor = child != target
                    add(root, parent, base.device, Rule(input.kind, component.toString(), ancestor))
                    val state =
                        try {
                            stamp(child)
                        } catch (missing: java.nio.file.NoSuchFileException) {
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
                // 身份根只观察自身/父项生命周期，不能让根目录里的每个 heartbeat 触发 full scan。
                val rule =
                    Rule(input.kind, if (input.kind == Kind.SOURCE_IDENTITY) "" else null, false)
                add(root, target, base.device, rule)
                if (input.recursive) {
                    branches.add(Branch(root, target, base.device, input.kind, 0))
                }
            } catch (unavailable: Exception) {
                limit("WATCH_PATH_UNREADABLE")
            }
        }

        private fun add(root: Path, directory: Path, device: Long, rule: Rule) {
            var spec = specs[directory]
            if (spec == null) {
                if (specs.size >= limits.directories) {
                    limit("WATCH_DIRECTORY_LIMIT")
                    return
                }
                spec = Spec(root, directory, device, safeDirectory(root, directory, device))
                specs[directory] = spec
            }
            if (!spec.rules.contains(rule)) spec.rules.add(rule)
        }

        fun descend() {
            while (branches.isNotEmpty() && budget()) {
                val branch = branches.removeFirst()
                if (!visited.add(branch.path.toString() + "\n" + branch.kind)) continue
                if (branch.depth >= limits.depth) {
                    limit("WATCH_DEPTH_LIMIT")
                    continue
                }
                if (!specs.containsKey(branch.path)) {
                    limit("WATCH_DIRECTORY_LIMIT")
                    continue
                }
                try {
                    val before = safeDirectory(branch.root, branch.path, branch.device)
                    Files.newDirectoryStream(branch.path).use { children ->
                        for (child in children) {
                            inspected++
                            if (inspected > limits.entries) {
                                limit("WATCH_ENTRY_LIMIT")
                                return
                            }
                            if (!budget()) return
                            val state = stamp(child)
                            if (state.symlink || state.device != branch.device) continue
                            if (!state.directory) continue
                            add(branch.root, child, branch.device, Rule(branch.kind, null, false))
                            if (specs.containsKey(child)) {
                                branches.addLast(
                                    Branch(
                                        branch.root,
                                        child,
                                        branch.device,
                                        branch.kind,
                                        branch.depth + 1,
                                    )
                                )
                            }
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
        val value =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val device = Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS)
        val key = value.fileKey()
        if (device !is Number || key == null) {
            throw IOException("PLUGIN_WATCH_IDENTITY_UNAVAILABLE")
        }
        return Stamp(value.isDirectory, value.isSymbolicLink, device.toLong(), key)
    }

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

    private class Rule(val kind: Kind, val child: String?, val ancestor: Boolean) {
        fun matches(value: String?): Boolean = child == null || child == value

        override fun equals(other: Any?): Boolean {
            if (other !is Rule) return false
            return kind == other.kind && child == other.child && ancestor == other.ancestor
        }

        override fun hashCode(): Int = Objects.hash(kind, child, ancestor)
    }

    private class Spec(val root: Path, val path: Path, val device: Long, val stamp: Stamp) {
        val rules: MutableList<Rule> = ArrayList()
    }

    private class Branch(
        val root: Path,
        val path: Path,
        val device: Long,
        val kind: Kind,
        val depth: Int,
    )

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
