package com.deepseekharness.app.core

import com.deepseekharness.app.PtySession
import com.deepseekharness.app.runtime.ProotBootstrap
import com.deepseekharness.app.util.SensitiveData
import com.deepseekharness.app.util.TerminalReady
import com.deepseekharness.app.util.TerminalSession
import com.deepseekharness.app.util.TerminalTabs
import com.deepseekharness.app.util.UiText
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

/** 进程级终端标签所有者；仅在核验退出后移除会话并释放保护。 */
class TerminalSessionOwner
internal constructor(private val protection: TerminalRuntimeProtection?) {
    private val ownerEpoch = UUID.randomUUID().toString()
    private val ptyProtection: MutableMap<Long, TerminalRuntimeProtection.Ticket> =
        ConcurrentHashMap()
    private val simpleProtection: MutableMap<Long, TerminalRuntimeProtection.Ticket> =
        ConcurrentHashMap()
    private val closeWorker: ExecutorService = Executors.newSingleThreadExecutor { action ->
        Thread(action, "dsha-terminal-close").apply { isDaemon = true }
    }
    private val pty = TerminalTabs<PtySession>()
    private val simple = TerminalTabs<SimpleTab>()
    private val closing = Any()
    private val simpleCreationKey = Any()
    private val simpleStateObservers = CopyOnWriteArrayList<Runnable>()
    @Volatile private var ptyObserver: PtyObserver? = null
    @Volatile private var simpleObserver: SimpleObserver? = null
    private var ptyInitialClaimed = false
    private var simpleInitialClaimed = false

    internal constructor() : this(null)

    init {
        if (protection != null) {
            protection.addObserver {
                notifyPty()
                notifySimpleTabs()
            }
        }
    }

    fun protection(): TerminalRuntimeProtection? = protection

    fun ownerEpoch(): String = ownerEpoch

    fun epoch(): String = ownerEpoch

    fun protectionSnapshot(): TerminalRuntimeProtection.Snapshot =
        if (protection == null) {
            TerminalRuntimeProtection.Snapshot(
                TerminalRuntimeProtection.State.RELEASED,
                0,
                0,
                0,
                "",
            )
        } else {
            protection.snapshot()
        }

    /** 默认首建意图只认领一次；取消/失败后须显式新建，不在旋转或回页时自动重放。 */
    @Synchronized
    fun claimPtyInitialCreation(): Boolean {
        if (ptyInitialClaimed || pty.wasInitialized()) return false
        ptyInitialClaimed = true
        return true
    }

    @Synchronized
    fun claimSimpleInitialCreation(): Boolean {
        if (simpleInitialClaimed || simple.wasInitialized()) return false
        simpleInitialClaimed = true
        return true
    }

    fun interface PtyObserver {
        fun onTabsChanged()
    }

    interface SimpleObserver {
        fun onOutput(tab: SimpleTab?)

        fun onState(tab: SimpleTab?)

        fun onTabsChanged()
    }

    fun attachPty(observer: PtyObserver?) {
        ptyObserver = observer
    }

    fun detachPty(observer: PtyObserver?) {
        if (ptyObserver === observer) ptyObserver = null
    }

    fun attachSimple(observer: SimpleObserver?) {
        simpleObserver = observer
    }

    fun detachSimple(observer: SimpleObserver?) {
        if (simpleObserver === observer) simpleObserver = null
    }

    /** 简易面板未附着时，宿主会话列表仍需接收状态更新。 */
    fun addSimpleStateObserver(observer: Runnable?) {
        if (observer != null) simpleStateObservers.addIfAbsent(observer)
    }

    fun removeSimpleStateObserver(observer: Runnable?) {
        simpleStateObservers.remove(observer)
    }

    fun ptyTabs(): TerminalTabs.ReadOnly<PtySession> = pty.readOnly()

    fun simpleTabs(): TerminalTabs.ReadOnly<SimpleTab> = simple.readOnly()

    fun currentPty(): TerminalTabs.Tab<PtySession>? = pty.current()

    fun currentSimple(): TerminalTabs.Tab<SimpleTab>? = simple.current()

    /** 仅保留给同包纯策略测试；生产 shared owner 必须经精确创建票。 */
    @JvmName("addPty")
    internal fun addPty(session: PtySession?): TerminalTabs.Tab<PtySession> {
        if (protection != null) throw IllegalStateException("TERMINAL_CREATE_TICKET_REQUIRED")
        val tab = pty.add(session)
        notifyPty()
        return tab
    }

    @JvmName("addSimple")
    internal fun addSimple(backend: TerminalSession.Backend?): TerminalTabs.Tab<SimpleTab> {
        if (protection != null) throw IllegalStateException("TERMINAL_CREATE_TICKET_REQUIRED")
        val value = SimpleTab(backend, simpleCreationKey)
        val tab = simple.add(value)
        notifySimpleTabs()
        return tab
    }

    /** 在工作线程调用；prepare 覆盖 fork 到发布之间，失败 pending 由 PtySession 继续持有。 */
    @Throws(IOException::class)
    fun createPty(
        ticket: TerminalRuntimeProtection.Ticket?,
        proot: ProotBootstrap?,
        cols: Int,
        rows: Int,
        listener: PtySession.Listener?,
    ): TerminalTabs.Tab<PtySession> = createPty(ticket, proot, cols, rows, listener, null)

    /** 固定 guest command 只影响 PTY argv；创建票、发布与失败保留时序完全复用默认路径。 */
    @Throws(IOException::class)
    fun createPty(
        ticket: TerminalRuntimeProtection.Ticket?,
        proot: ProotBootstrap?,
        cols: Int,
        rows: Int,
        listener: PtySession.Listener?,
        guestCommand: Array<String?>?,
    ): TerminalTabs.Tab<PtySession> =
        createPty(
            ticket,
            guestCommand,
            PtyStart { verified, changed, beforeFork, allocated, command ->
                PtySession.start(
                    proot,
                    cols,
                    rows,
                    listener,
                    verified,
                    changed,
                    beforeFork,
                    allocated,
                    command,
                )
            },
        )

    internal fun interface PtyStart {
        fun start(
            verified: Runnable?,
            changed: Runnable?,
            beforeFork: Runnable?,
            allocated: Consumer<PtySession?>?,
            guestCommand: Array<String?>?,
        ): PtySession?
    }

    @JvmName("createPty")
    @Throws(IOException::class)
    internal fun createPty(
        ticket: TerminalRuntimeProtection.Ticket?,
        start: PtyStart?,
    ): TerminalTabs.Tab<PtySession> = createPty(ticket, null, start)

    @JvmName("createPty")
    @Throws(IOException::class)
    internal fun createPty(
        ticket: TerminalRuntimeProtection.Ticket?,
        guestCommand: Array<String?>?,
        start: PtyStart?,
    ): TerminalTabs.Tab<PtySession> {
        requireProtection()
        val protection = protection!!
        protection.beginCreate(ticket, ownerEpoch, TerminalRuntimeProtection.Kind.PTY)
        val allocated = arrayOfNulls<PtySession>(1)
        var reservation: TerminalTabs.Reservation? = null

        fun failed(failure: Throwable) {
            // 已 fork 的失败对象保持原出生身份，发布“待核验”，不留下无法显式关闭的隐藏租约。
            if (allocated[0] != null && allocated[0]!!.isCleanupPending()) {
                if (reservation != null) {
                    val tab = pty.publish(reservation, allocated[0])
                    reservation = null
                    ptyProtection[tab.id] = ticket!!
                    pty.closeFailed(tab.id, safeError(failure))
                    notifyPty()
                }
            } else {
                protection.releaseVerified(ticket)
            }
        }

        try {
            // 耗尽必须在任何 fork 前拒绝；失败 pending 仍使用同一预留发布。
            reservation = pty.reserveIdentity()
            protection.retain(ticket)
            val session =
                start!!.start(
                    Runnable { protection.releaseVerified(ticket) },
                    Runnable { notifyPty() },
                    Runnable {
                        try {
                            protection.requireHeld(ticket)
                        } catch (lost: IOException) {
                            throw IllegalStateException(lost.message, lost)
                        }
                    },
                    Consumer { value -> allocated[0] = value },
                    guestCommand,
                )
            val tab = pty.publish(reservation, session)
            reservation = null
            ptyProtection[tab.id] = ticket!!
            notifyPty()
            return tab
        } catch (failure: RuntimeException) {
            failed(failure)
            throw failure
        } catch (failure: Error) {
            failed(failure)
            throw failure
        } finally {
            pty.cancelIdentity(reservation)
            protection.finishCreate(ticket)
        }
    }

    /** 创建后端对象并开启其原有异步启动；票在首次 fork 前即转为逻辑 hold。 */
    @Throws(IOException::class)
    fun createSimple(
        ticket: TerminalRuntimeProtection.Ticket?,
        backend: TerminalSession.Backend?,
    ): TerminalTabs.Tab<SimpleTab> {
        requireProtection()
        val protection = protection!!
        protection.beginCreate(ticket, ownerEpoch, TerminalRuntimeProtection.Kind.SIMPLE)
        var published = false
        var reservation: TerminalTabs.Reservation? = null
        try {
            if (backend == null) throw IllegalArgumentException("TERMINAL_BACKEND_REQUIRED")
            reservation = simple.reserveIdentity()
            val value = SimpleTab(protectedBackend(ticket, backend), simpleCreationKey)
            protection.retain(ticket)
            val tab = simple.publish(reservation, value)
            reservation = null
            simpleProtection[tab.id] = ticket!!
            published = true
            value.session.ensureStarted()
            notifySimpleTabs()
            return tab
        } finally {
            simple.cancelIdentity(reservation)
            if (!published) protection.releaseVerified(ticket)
            protection.finishCreate(ticket)
        }
    }

    @Throws(IOException::class)
    private fun requireProtection() {
        if (protection == null) throw IOException("TERMINAL_PROTECTION_REQUIRED")
    }

    private fun protectedBackend(
        ticket: TerminalRuntimeProtection.Ticket?,
        delegate: TerminalSession.Backend,
    ): TerminalSession.Backend =
        object : TerminalSession.Backend {
            @Throws(Exception::class)
            override fun beginLifetime(): AutoCloseable? {
                protection!!.requireHeld(ticket)
                return delegate.beginLifetime()
            }

            @Throws(Exception::class)
            override fun open(): Process? {
                protection!!.requireHeld(ticket)
                return delegate.open()
            }

            @Throws(Exception::class)
            override fun onReady(process: Process?, identity: TerminalReady?) {
                delegate.onReady(process, identity)
            }

            @Throws(Exception::class)
            override fun terminate(process: Process?, processGroup: Long) {
                delegate.terminate(process, processGroup)
            }

            @Throws(IOException::class)
            override fun write(process: Process?, text: String?) {
                // 已接受输入仍沿原算法执行一次；LOST 在 owner 禁止新输入，不能借写入异常暗停进程。
                delegate.write(process, text)
            }
        }

    fun renamePty(id: Long, title: String?): Boolean {
        val changed = pty.rename(id, title)
        if (changed) notifyPty()
        return changed
    }

    fun renameSimple(id: Long, title: String?): Boolean {
        val changed = simple.rename(id, title)
        if (changed) notifySimpleTabs()
        return changed
    }

    fun isPtyInputAllowed(id: Long): Boolean {
        val tab = pty.find(id)
        return tab != null &&
            !tab.isClosing() &&
            !tab.isUnconfirmed() &&
            tab.value.cleanupError().isEmpty() &&
            tab.value.isRunning() &&
            (protection == null || protection.isProtected(ptyProtection[id]))
    }

    fun isSimpleInputAllowed(id: Long): Boolean {
        val tab = simple.find(id)
        return tab != null &&
            !tab.isClosing() &&
            !tab.isUnconfirmed() &&
            (protection == null || protection.isProtected(simpleProtection[id]))
    }

    fun isPtyProtectionLost(id: Long): Boolean = protectionLost(ptyProtection[id])

    fun isSimpleProtectionLost(id: Long): Boolean = protectionLost(simpleProtection[id])

    private fun protectionLost(ticket: TerminalRuntimeProtection.Ticket?): Boolean {
        if (protection == null || ticket == null) return false
        synchronized(protection) {
            val state = ticket.state()
            return state == TerminalRuntimeProtection.State.LOST ||
                (state == TerminalRuntimeProtection.State.ACTIVE && !protection.isProtected(ticket))
        }
    }

    fun selectPty(id: Long): Boolean {
        val selected = pty.select(id)
        if (selected) notifyPty()
        return selected
    }

    fun selectSimple(id: Long): Boolean {
        val selected = simple.select(id)
        if (selected) notifySimpleTabs()
        return selected
    }

    fun beginClosePty(id: Long): Boolean {
        val accepted = pty.beginClose(id)
        if (accepted) notifyPty()
        return accepted
    }

    fun beginCloseSimple(id: Long): Boolean {
        val accepted = simple.beginClose(id)
        if (accepted) notifySimpleTabs()
        return accepted
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closePty(id: Long, timeoutMs: Long) {
        synchronized(closing) {
            val tab = pty.find(id) ?: return
            if (!tab.isClosing()) pty.beginClose(id)
            try {
                tab.value.finishAndWait(timeoutMs)
                pty.remove(id)
                val ticket = ptyProtection.remove(id)
                if (protection != null) protection.releaseVerified(ticket)
            } catch (error: IOException) {
                pty.closeFailed(id, safeError(error))
                throw error
            } catch (error: InterruptedException) {
                pty.closeFailed(id, safeError(error))
                throw error
            } catch (error: RuntimeException) {
                pty.closeFailed(id, safeError(error))
                throw error
            } finally {
                notifyPty()
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closeSimple(id: Long, timeoutMs: Long) {
        closeSimple(id, timeoutMs, SimpleStop { tab, wait -> tab!!.session.disposeAndWait(wait) })
    }

    internal fun interface SimpleStop {
        @Throws(InterruptedException::class) fun stop(tab: SimpleTab?, timeoutMs: Long): Boolean
    }

    @JvmName("closeSimple")
    @Throws(IOException::class, InterruptedException::class)
    internal fun closeSimple(id: Long, timeoutMs: Long, stop: SimpleStop?) {
        synchronized(closing) {
            val tab = simple.find(id) ?: return
            if (!tab.isClosing()) simple.beginClose(id)
            try {
                if (!stop!!.stop(tab.value, timeoutMs)) throw IOException(UiText.text("尚未确认终端退出"))
                simple.remove(id)
                val ticket = simpleProtection.remove(id)
                if (protection != null) protection.releaseVerified(ticket)
            } catch (error: IOException) {
                simple.closeFailed(id, safeError(error))
                throw error
            } catch (error: InterruptedException) {
                simple.closeFailed(id, safeError(error))
                throw error
            } catch (error: RuntimeException) {
                simple.closeFailed(id, safeError(error))
                throw error
            } finally {
                notifySimpleTabs()
            }
        }
    }

    fun interface CloseCallback {
        fun onClosed(
            ownerEpoch: String?,
            kind: TerminalRuntimeProtection.Kind?,
            id: Long,
            error: String?,
        )
    }

    fun closePtyAsync(id: Long, timeoutMs: Long, callback: CloseCallback?): Boolean {
        if (!beginClosePty(id)) return false
        return dispatchClose(TerminalRuntimeProtection.Kind.PTY, id, timeoutMs, callback)
    }

    fun closeSimpleAsync(id: Long, timeoutMs: Long, callback: CloseCallback?): Boolean {
        if (!beginCloseSimple(id)) return false
        return dispatchClose(TerminalRuntimeProtection.Kind.SIMPLE, id, timeoutMs, callback)
    }

    private fun dispatchClose(
        kind: TerminalRuntimeProtection.Kind,
        id: Long,
        timeoutMs: Long,
        callback: CloseCallback?,
    ): Boolean {
        try {
            closeWorker.execute {
                var error = ""
                try {
                    if (kind == TerminalRuntimeProtection.Kind.PTY) closePty(id, timeoutMs)
                    else closeSimple(id, timeoutMs)
                } catch (failure: IOException) {
                    error = safeError(failure)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error = safeError(failure)
                } catch (failure: RuntimeException) {
                    error = safeError(failure)
                }
                if (callback != null) {
                    try {
                        callback.onClosed(ownerEpoch, kind, id, error)
                    } catch (ignored: RuntimeException) {}
                }
            }
            return true
        } catch (unavailable: RuntimeException) {
            if (kind == TerminalRuntimeProtection.Kind.PTY) {
                pty.closeFailed(id, safeError(unavailable))
                notifyPty()
            } else {
                simple.closeFailed(id, safeError(unavailable))
                notifySimpleTabs()
            }
            return false
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closeAllAndConfirm(timeoutMs: Long) {
        synchronized(closing) {
            closeAllPtyAndConfirm(timeoutMs)
            closeAllSimpleAndConfirm(timeoutMs)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closeAllPtyAndConfirm(timeoutMs: Long) {
        synchronized(closing) {
            // 底层集合同时覆盖已经 fork、尚未发布标签的 PTY。
            PtySession.closeAllForMaintenance(timeoutMs)
            for (tab in pty.snapshot()) closePty(tab.id, timeoutMs)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun closeAllSimpleAndConfirm(timeoutMs: Long) {
        synchronized(closing) {
            for (tab in simple.snapshot()) closeSimple(tab.id, timeoutMs)
        }
    }

    fun requestPtyShutdown() {
        for (tab in pty.snapshot()) {
            try {
                tab.value.finish()
            } catch (error: Throwable) {
                android.util.Log.w(
                    "DSHA",
                    UiText.format(
                        "终端尚未停止，保留会话与环境保护：%s",
                        SensitiveData.redact(java.lang.String.valueOf(error)),
                    ),
                )
            }
        }
    }

    fun requestSimpleShutdown() {
        for (tab in simple.snapshot()) tab.value.session.shutdown()
    }

    private fun checkedSimpleBackend(
        backend: TerminalSession.Backend?,
        key: Any?,
    ): TerminalSession.Backend? {
        if (key !== simpleCreationKey)
            throw IllegalArgumentException("TERMINAL_SIMPLE_TAB_CREATION_MISMATCH")
        return backend
    }

    inner class SimpleTab private constructor(backend: TerminalSession.Backend?) {
        // inner 类不能声明伴生工厂；桥仅接受外层持有的精确私有创建凭据。
        internal constructor(
            backend: TerminalSession.Backend?,
            key: Any?,
        ) : this(checkedSimpleBackend(backend, key))

        @JvmField val session: TerminalSession
        @JvmField val buffer = StringBuilder()
        @JvmField var draft: String? = ""
        @JvmField var cursorStart = 0
        @JvmField var cursorEnd = 0

        init {
            session =
                TerminalSession(
                    backend,
                    Consumer { text -> appendSimple(this, text) },
                    Consumer { _ -> notifySimpleState(this) },
                )
        }

        @Synchronized
        fun clearOutput() {
            buffer.setLength(0)
        }

        @Synchronized fun output(): String = buffer.toString()
    }

    fun appendSimple(tab: SimpleTab?, text: String?) {
        if (tab == null || text == null || text.isEmpty()) return
        val safe = SensitiveData.redact(stripAnsi(text))
        synchronized(tab) {
            tab.buffer.append(safe)
            if (tab.buffer.length > 300000) tab.buffer.delete(0, tab.buffer.length - 100000)
        }
        val observer = simpleObserver
        if (observer != null) {
            try {
                observer.onOutput(tab)
            } catch (ignored: RuntimeException) {}
        }
    }

    private fun notifySimpleState(tab: SimpleTab) {
        val observer = simpleObserver
        if (observer != null) {
            try {
                observer.onState(tab)
            } catch (ignored: RuntimeException) {}
        }
        for (stateObserver in simpleStateObservers) {
            try {
                stateObserver.run()
            } catch (ignored: RuntimeException) {}
        }
    }

    private fun notifyPty() {
        for (tab in pty.snapshot()) {
            val error = tab.value.cleanupError()
            if (error.isNotEmpty() && !tab.isClosing()) pty.closeFailed(tab.id, error)
        }
        val observer = ptyObserver
        if (observer != null) {
            try {
                observer.onTabsChanged()
            } catch (ignored: RuntimeException) {}
        }
    }

    private fun notifySimpleTabs() {
        val observer = simpleObserver
        if (observer != null) {
            try {
                observer.onTabsChanged()
            } catch (ignored: RuntimeException) {}
        }
    }

    companion object {
        private val SHARED = TerminalSessionOwner(TerminalRuntimeProtection.shared())

        @JvmStatic fun shared(): TerminalSessionOwner = SHARED

        private fun safeError(error: Throwable?): String {
            val message = error?.message ?: ""
            val value =
                SensitiveData.redact(
                        if (message.trim { it <= ' ' }.isEmpty()) {
                            UiText.choose("尚未确认终端退出", "Terminal exit is not yet verified")
                        } else {
                            message
                        }
                    )
                    .replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")
                    .trim { it <= ' ' }
            return if (value.length <= 240) value else value.substring(0, 240)
        }

        private fun stripAnsi(text: String): String =
            text
                .replace(Regex("\\x1B\\[[0-9;?]*[a-zA-Z]"), "")
                .replace(Regex("\\x1B\\][^\\x07]*\\x07"), "")
                .replace(Regex("\\x1B[()][0-9A-B]"), "")
    }
}
