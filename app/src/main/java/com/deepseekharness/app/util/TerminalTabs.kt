package com.deepseekharness.app.util

import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

/** 会话身份永久递增；显示编号复用空缺，关闭失败或关闭中仍占用编号。 */
class TerminalTabs<T> {
    enum class CleanupState {
        NOT_REQUESTED,
        CLOSING,
        VERIFIED,
        UNCONFIRMED,
    }

    /** 关闭投影用单一不可变值发布。 */
    class CloseSnapshot
    private constructor(
        @JvmField val state: CleanupState,
        @JvmField val generation: Long,
        @JvmField val error: String,
    ) {
        companion object {
            @JvmSynthetic
            internal fun create(
                state: CleanupState,
                generation: Long,
                error: String,
            ): CloseSnapshot = CloseSnapshot(state, generation, error)
        }
    }

    /** 仅供展示的实时视图，对象本身不是可变表。 */
    interface ReadOnly<T> {
        fun snapshot(): List<Tab<T>>

        fun current(): Tab<T>?

        fun find(id: Long): Tab<T>?

        fun wasInitialized(): Boolean
    }

    private class View<T>(private val owner: TerminalTabs<T>) : ReadOnly<T> {
        override fun snapshot(): List<Tab<T>> = owner.snapshot()

        override fun current(): Tab<T>? = owner.current()

        override fun find(id: Long): Tab<T>? = owner.find(id)

        override fun wasInitialized(): Boolean = owner.wasInitialized()
    }

    class Tab<T>
    private constructor(
        @JvmField val id: Long,
        @JvmField val number: Int,
        @JvmField val value: T,
    ) {
        @Volatile
        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var close = CloseSnapshot.create(CleanupState.NOT_REQUESTED, 0, "")
        @Volatile @get:JvmSynthetic @set:JvmSynthetic internal var title = ""

        fun isClosing(): Boolean = close.state == CleanupState.CLOSING

        fun isUnconfirmed(): Boolean = close.state == CleanupState.UNCONFIRMED

        fun isCloseUnconfirmed(): Boolean = isUnconfirmed()

        fun cleanupState(): CleanupState = close.state

        fun closeSnapshot(): CloseSnapshot = close

        fun error(): String = close.error

        fun closeError(): String = error()

        fun customTitle(): String = title

        companion object {
            @JvmSynthetic
            internal fun <T> create(id: Long, number: Int, value: T): Tab<T> =
                Tab(id, number, value)
        }
    }

    /** 取消预留也不回退永久身份计数。 */
    class Reservation private constructor(@get:JvmSynthetic internal val id: Long) {
        companion object {
            @JvmSynthetic internal fun create(id: Long): Reservation = Reservation(id)
        }
    }

    private val tabs = ArrayList<Tab<T>>()
    private val readOnly: ReadOnly<T> = View(this)
    private val reservations = HashSet<Reservation>()
    private var next = 1L
    private var selected = 0L
    private var initialized = false
    private var identityExhausted = false

    fun readOnly(): ReadOnly<T> = readOnly

    @Synchronized
    fun reserveIdentity(): Reservation {
        if (identityExhausted || next <= 0) {
            throw IllegalStateException("TERMINAL_SESSION_ID_EXHAUSTED")
        }
        val reservation = Reservation.create(next)
        if (next == Long.MAX_VALUE) identityExhausted = true else next++
        reservations.add(reservation)
        return reservation
    }

    @Synchronized
    fun cancelIdentity(reservation: Reservation?) {
        reservations.remove(reservation)
    }

    @Synchronized
    fun add(value: T?): Tab<T> {
        if (value == null) throw IllegalArgumentException(UiText.text("终端会话不能为空"))
        return publish(reserveIdentity(), value)
    }

    /** 只能发布本表尚未消费的精确预留；发布时独立选择最小显示空缺。 */
    @Synchronized
    fun publish(reservation: Reservation?, value: T?): Tab<T> {
        if (value == null) throw IllegalArgumentException(UiText.text("终端会话不能为空"))
        if (reservation == null || !reservations.remove(reservation)) {
            throw IllegalArgumentException("TERMINAL_IDENTITY_RESERVATION_MISMATCH")
        }
        var number = 1
        val occupied = HashSet<Int>()
        for (item in tabs) occupied.add(item.number)
        while (occupied.contains(number)) number++
        val tab = Tab.create<T>(reservation.id, number, value)
        tabs.add(tab)
        selected = tab.id
        initialized = true
        return tab
    }

    @Synchronized fun snapshot(): List<Tab<T>> = Collections.unmodifiableList(ArrayList(tabs))

    @Synchronized fun current(): Tab<T>? = find(selected)

    @Synchronized
    fun find(id: Long): Tab<T>? {
        for (tab in tabs) if (tab.id == id) return tab
        return null
    }

    @Synchronized
    fun select(id: Long): Boolean {
        if (find(id) == null) return false
        selected = id
        return true
    }

    @Synchronized
    fun beginClose(id: Long): Boolean {
        val tab = find(id)
        if (tab == null || tab.isClosing()) return false
        tab.close = CloseSnapshot.create(CleanupState.CLOSING, tab.close.generation + 1, "")
        return true
    }

    @Synchronized
    fun closeFailed(id: Long) {
        closeFailed(id, "")
    }

    @Synchronized
    fun closeFailed(id: Long, error: String?) {
        val tab = find(id)
        if (tab != null) {
            tab.close =
                CloseSnapshot.create(
                    CleanupState.UNCONFIRMED,
                    tab.close.generation,
                    safeLabel(error, 240),
                )
        }
    }

    @Synchronized
    fun closeFailed(id: Long, generation: Long, error: String?): Boolean {
        val tab = find(id)
        if (tab == null || tab.close.generation != generation || !tab.isClosing()) return false
        closeFailed(id, error)
        return true
    }

    @Synchronized
    fun rename(id: Long, title: String?): Boolean {
        val tab = find(id) ?: return false
        tab.title = safeLabel(title, 80)
        return true
    }

    @Synchronized
    fun remove(id: Long) {
        val tab = find(id) ?: return
        val index = tabs.indexOf(tab)
        tab.close = CloseSnapshot.create(CleanupState.VERIFIED, tab.close.generation, "")
        tabs.removeAt(index)
        if (selected == id)
            selected = if (tabs.isEmpty()) 0 else tabs[Math.min(index, tabs.size - 1)].id
    }

    @Synchronized fun wasInitialized(): Boolean = initialized

    companion object {
        private fun safeLabel(value: String?, maxCodePoints: Int): String {
            val safe =
                SensitiveData.redact(value ?: "").replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").trim {
                    it <= ' '
                }
            val count = safe.codePointCount(0, safe.length)
            return if (count <= maxCodePoints) safe
            else safe.substring(0, safe.offsetByCodePoints(0, maxCodePoints))
        }
    }
}
