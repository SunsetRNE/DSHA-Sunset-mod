package com.deepseekharness.app.core

import java.io.IOException
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/** 终端专属前台准入；逻辑租约不是进程退出证明，也不承担 RuntimeTasks 的维护屏障。 */
class TerminalRuntimeProtection(private val clock: LongSupplier?) {
    enum class Kind {
        PTY,
        SIMPLE,
    }

    enum class State {
        PREPARING,
        ACTIVE,
        LOST,
        RELEASED,
    }

    interface Admission {
        fun onReady(ticket: Ticket?)

        fun onRejected(ticket: Ticket?, reason: String?)
    }

    class Snapshot
    internal constructor(
        @JvmField val state: State?,
        @JvmField val preparing: Int,
        @JvmField val creating: Int,
        @JvmField val held: Int,
        @JvmField val error: String?,
    )

    private val tickets: MutableMap<String?, Ticket> = LinkedHashMap()
    private val observers = CopyOnWriteArrayList<Runnable?>()
    private var generation = 0L
    private val ticketCreationKey = Any()

    private fun checkedTicketEpoch(ownerEpoch: String?, key: Any?): String? {
        if (key !== ticketCreationKey)
            throw IllegalArgumentException("TERMINAL_TICKET_CREATION_MISMATCH")
        return ownerEpoch
    }

    // Kotlin 外部类不能访问 inner 的 private 成员；新增访问器仅供实现使用，并隐藏于 Java 源码。
    inner class Ticket
    private constructor(
        ownerEpoch: String?,
        kind: Kind?,
        admission: Admission?,
    ) : AutoCloseable {
        // 校验发生在原私有构造器的 UUID、代次与时钟初始化之前。
        internal constructor(
            ownerEpoch: String?,
            kind: Kind?,
            admission: Admission?,
            key: Any?,
        ) : this(checkedTicketEpoch(ownerEpoch, key), kind, admission)

        @get:JvmSynthetic internal val owner = this@TerminalRuntimeProtection
        @get:JvmSynthetic internal val id = UUID.randomUUID().toString()
        @get:JvmSynthetic internal val ownerEpoch: String? = ownerEpoch
        @get:JvmSynthetic internal val kind: Kind? = kind
        @get:JvmSynthetic @set:JvmSynthetic internal var admission: Admission? = admission
        @get:JvmSynthetic internal val generation = ++this@TerminalRuntimeProtection.generation
        @get:JvmSynthetic internal val created = clock!!.asLong
        @get:JvmSynthetic @set:JvmSynthetic internal var service: Any? = null
        @get:JvmSynthetic @set:JvmSynthetic internal var current: BooleanSupplier? = null
        @get:JvmSynthetic @set:JvmSynthetic internal var promoted = false
        @get:JvmSynthetic @set:JvmSynthetic internal var started = false
        @get:JvmSynthetic @set:JvmSynthetic internal var finished = false
        @get:JvmSynthetic @set:JvmSynthetic internal var hold = false
        @get:JvmSynthetic @set:JvmSynthetic internal var released = false
        @get:JvmSynthetic @set:JvmSynthetic internal var lost = false
        @get:JvmSynthetic @set:JvmSynthetic internal var failure = ""

        fun id(): String = id

        fun ownerEpoch(): String? = ownerEpoch

        fun kind(): Kind? = kind

        fun generation(): Long = generation

        fun hasStarted(): Boolean = synchronized(owner) { started }

        fun state(): State {
            synchronized(owner) {
                if (released) return State.RELEASED
                if (lost) return State.LOST
                return if (promoted) State.ACTIVE else State.PREPARING
            }
        }

        fun error(): String = synchronized(owner) { failure }

        /** 页面离开只可撤销尚未被工作线程接管的票；绝不取消已 fork 的寿命。 */
        fun cancel(): Boolean = reject(this, "TERMINAL_CREATION_CANCELLED")

        override fun close() {
            cancel()
        }
    }

    /** 仅由已经核对可见宿主的 Android 适配层调用；本类不持有 Activity。 */
    fun prepare(ownerEpoch: String?, kind: Kind?, admission: Admission?): Ticket {
        if (ownerEpoch == null || ownerEpoch.isEmpty() || kind == null || admission == null)
            throw IllegalArgumentException("TERMINAL_INVALID_PREPARATION")
        val ticket: Ticket
        synchronized(this) {
            ticket = Ticket(ownerEpoch, kind, admission, ticketCreationKey)
            tickets[ticket.id] = ticket
        }
        changed()
        return ticket
    }

    private fun exact(ticket: Ticket?): Boolean =
        ticket != null && ticket.owner === this && tickets[ticket.id] === ticket

    @Synchronized
    fun matches(id: String?): Boolean {
        val ticket = tickets[id]
        return ticket != null && !ticket.released && !ticket.started && !expired(ticket)
    }

    private fun expired(ticket: Ticket): Boolean {
        val now = clock!!.asLong
        val limit = if (ticket.promoted) PREPARATION_TIMEOUT_MS else PROMOTION_TIMEOUT_MS
        return now < ticket.created || now - ticket.created >= limit
    }

    /** 必须在真实 startForeground 成功后，以同一服务实例回调。 */
    fun promoted(id: String?, service: Any?, serviceCurrent: BooleanSupplier?): Boolean {
        val ticket: Ticket
        val callback: Admission?
        synchronized(this) {
            val found = tickets[id]
            if (
                found == null ||
                    found.released ||
                    found.started ||
                    found.promoted ||
                    expired(found) ||
                    service == null ||
                    serviceCurrent == null
            )
                return false
            ticket = found
            ticket.service = service
            ticket.current = serviceCurrent
            if (!current(ticket)) return false
            ticket.promoted = true
            callback = ticket.admission
            ticket.admission = null
        }
        changed()
        if (callback != null) {
            try {
                callback.onReady(ticket)
            } catch (failure: RuntimeException) {
                ticket.cancel()
            }
        }
        return true
    }

    /** 精确对象、ownerEpoch、类型及服务身份同锁消费一次；不是“存在任意票”守卫。 */
    @JvmName("beginCreate")
    @Throws(IOException::class)
    fun beginCreate(ticket: Ticket?, ownerEpoch: String?, kind: Kind?) {
        var failure = ""
        synchronized(this) {
            if (
                !exact(ticket) ||
                    ticket == null ||
                    ticket.released ||
                    ticket.started ||
                    ticket.kind !== kind ||
                    !ticket.ownerEpoch!!.equals(ownerEpoch)
            )
                throw IOException("TERMINAL_CREATE_TICKET_MISMATCH")
            if (!ticket.promoted || expired(ticket)) failure = "TERMINAL_CREATE_TICKET_EXPIRED"
            else if (ticket.lost || !current(ticket)) failure = "TERMINAL_FOREGROUND_SERVICE_LOST"
            if (failure.isEmpty()) ticket.started = true
        }
        if (failure.isNotEmpty()) {
            reject(ticket, failure)
            throw IOException(failure)
        }
        changed()
    }

    /** 在任何可能 fork 的工作之前登记；创建 finally 结束前即使释放也仍有准备保护。 */
    @JvmName("retain")
    internal fun retain(ticket: Ticket?) {
        synchronized(this) {
            if (
                !exact(ticket) ||
                    ticket == null ||
                    !ticket.started ||
                    ticket.finished ||
                    ticket.released
            )
                throw IllegalStateException("TERMINAL_HOLD_TICKET_MISMATCH")
            ticket.hold = true
        }
        changed()
    }

    /** 底层成功核验退出，或明确未创建；不接受 isRunning/FAILED 代替这条证据。 */
    fun releaseVerified(ticket: Ticket?) {
        synchronized(this) {
            if (!exact(ticket) || ticket == null || ticket.released) return
            ticket.hold = false
            if (ticket.finished) remove(ticket)
        }
        changed()
    }

    /** 创建调用必须 finally 收束；已登记但未核验的 hold 继续留存，包括未发布 PTY。 */
    @JvmName("finishCreate")
    internal fun finishCreate(ticket: Ticket?) {
        synchronized(this) {
            if (!exact(ticket) || ticket == null || !ticket.started || ticket.finished) return
            ticket.finished = true
            if (!ticket.hold) remove(ticket)
        }
        changed()
    }

    /** 简易后端重启仍需原服务存活；失去保护不暗中重新提升/重放 shell。 */
    @Synchronized
    @Throws(IOException::class)
    fun requireHeld(ticket: Ticket?) {
        if (
            !exact(ticket) ||
                ticket == null ||
                ticket.released ||
                !ticket.started ||
                !ticket.hold ||
                ticket.lost ||
                !current(ticket)
        )
            throw IOException("TERMINAL_FOREGROUND_SERVICE_LOST")
    }

    @Synchronized
    fun isProtected(ticket: Ticket?): Boolean =
        exact(ticket) &&
            ticket != null &&
            !ticket.released &&
            !ticket.lost &&
            ticket.promoted &&
            current(ticket)

    fun expire(ticket: Ticket?) {
        synchronized(this) {
            if (!exact(ticket) || ticket == null || ticket.started || !expired(ticket)) return
        }
        reject(ticket, "TERMINAL_FOREGROUND_PROMOTION_TIMEOUT")
    }

    fun reject(id: String?, reason: String?) {
        val ticket: Ticket?
        synchronized(this) { ticket = tickets[id] }
        reject(ticket, reason)
    }

    private fun reject(ticket: Ticket?, reason: String?): Boolean {
        val callback: Admission?
        synchronized(this) {
            if (!exact(ticket) || ticket == null || ticket.released || ticket.started) return false
            ticket.failure = reason ?: "TERMINAL_FOREGROUND_UNAVAILABLE"
            callback = ticket.admission
            ticket.admission = null
            remove(ticket)
        }
        changed()
        if (callback != null) {
            try {
                callback.onRejected(ticket, ticket!!.failure)
            } catch (ignored: RuntimeException) {}
        }
        return true
    }

    private fun remove(ticket: Ticket) {
        ticket.released = true
        ticket.admission = null
        ticket.current = null
        ticket.service = null
        tickets.remove(ticket.id)
    }

    /** 服务丢失不代表任何进程退出；运行中与创建中的逻辑票保留为 LOST。 */
    fun serviceDestroyed(service: Any?) {
        val rejected = ArrayList<Ticket>()
        synchronized(this) {
            for (ticket in tickets.values) {
                if (ticket.service !== service) continue
                ticket.lost = true
                ticket.failure = "TERMINAL_FOREGROUND_SERVICE_LOST"
                ticket.current = null
                ticket.service = null
                if (!ticket.started) rejected.add(ticket)
            }
        }
        for (ticket in rejected) reject(ticket, "TERMINAL_FOREGROUND_SERVICE_LOST")
        changed()
    }

    @Synchronized fun hasDemand(): Boolean = tickets.isNotEmpty()

    /** 服务只能为属于本实例的票保活；已 LOST 的旧票不能借新会话变成 ACTIVE。 */
    @Synchronized
    fun needsService(service: Any?): Boolean {
        for (ticket in tickets.values) if (
            !ticket.released && !ticket.lost && (ticket.service === service || !ticket.promoted)
        )
            return true
        return false
    }

    @Synchronized
    fun snapshot(): Snapshot {
        var preparing = 0
        var creating = 0
        var held = 0
        var lost = false
        var active = false
        for (ticket in tickets.values) {
            if (!ticket.started) preparing++ else if (!ticket.finished) creating++
            if (ticket.hold) held++
            lost = lost or (ticket.lost || (ticket.promoted && !current(ticket)))
            active = active or ticket.promoted
        }
        val state =
            when {
                tickets.isEmpty() -> State.RELEASED
                lost -> State.LOST
                active -> State.ACTIVE
                else -> State.PREPARING
            }
        return Snapshot(
            state,
            preparing,
            creating,
            held,
            if (lost) "TERMINAL_FOREGROUND_SERVICE_LOST" else "",
        )
    }

    fun addObserver(observer: Runnable?) {
        observers.addIfAbsent(observer)
    }

    fun removeObserver(observer: Runnable?) {
        observers.remove(observer)
    }

    private fun changed() {
        for (observer in observers) {
            try {
                observer!!.run()
            } catch (ignored: RuntimeException) {}
        }
    }

    companion object {
        const val PROMOTION_TIMEOUT_MS: Long = 8_000L
        const val PREPARATION_TIMEOUT_MS: Long = 120_000L

        private val SHARED =
            TerminalRuntimeProtection(LongSupplier { System.nanoTime() / 1_000_000L })

        @JvmStatic fun shared(): TerminalRuntimeProtection = SHARED

        private fun current(ticket: Ticket): Boolean {
            try {
                return ticket.current != null && ticket.current!!.asBoolean
            } catch (unavailable: RuntimeException) {
                return false
            }
        }
    }
}
