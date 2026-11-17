package com.deepseekharness.app.util

import java.io.IOException

/** 本次独立终端会话的退出判据；不据裸 PID 或一次快照宣布退出。 */
class TerminalExitPolicy private constructor() {
    enum class Signal {
        STOP,
        CONTINUE,
        KILL,
        QUIT,
    }

    enum class Code {
        INVALID_IDENTITY,
        IDENTITY_CHANGED,
        FOREIGN_UID,
        SCAN_UNAVAILABLE,
        SESSION_NOT_EMPTY,
        EXIT_TIMEOUT,
    }

    class Failure private constructor(private val code: Code) : IOException("TERMINAL_" + code) {
        fun code(): Code = code

        companion object {
            @JvmSynthetic internal fun create(code: Code): Failure = Failure(code)
        }
    }

    interface Ports {
        /** null 只能表示已消失或已离开指定会话，其余未知必须抛出。 */
        @Throws(IOException::class) fun read(pid: Int, session: Int): ProcessIdentity?

        @Throws(IOException::class) fun belongsToSession(pid: Int, session: Int): Boolean

        @Throws(IOException::class) fun uid(pid: Int): Int

        fun ownUid(): Int

        @Throws(IOException::class) fun pids(): IntArray?

        @Throws(IOException::class) fun signal(pid: Int, signal: Signal?)

        fun now(): Long

        @Throws(InterruptedException::class) fun pause(millis: Long)
    }

    companion object {
        private const val POLL_MILLIS = 20L

        /** QUIT 只是一条请求，调用方仍须等待 launcher 并确认会话为空。 */
        @JvmStatic
        @Throws(IOException::class)
        fun requestProotExit(expected: ProcessIdentity?, ports: Ports?) {
            requireIdentity(expected)
            val identity = expected!!
            val leader = ports!!.read(identity.pid, identity.pid)
            if (leader == null || leader.exited()) return
            verifyLeader(identity, leader, ports)
            send(leader, Signal.QUIT, ports)
        }

        /** 保留组长直到两次独立扫描未见 guest，再结束组长并确认会话为空。 */
        @JvmStatic
        @Throws(IOException::class, InterruptedException::class)
        fun close(expected: ProcessIdentity?, timeoutMs: Long, ports: Ports?) {
            requireIdentity(expected)
            val identity = expected!!
            val started = ports!!.now()
            if (timeoutMs <= 0) throw Failure.create(Code.EXIT_TIMEOUT)
            val leader = ports.read(identity.pid, identity.pid)
            if (leader == null || leader.exited()) {
                requireSessionEmpty(identity.pid, ports)
                return
            }
            verifyLeader(identity, leader, ports)
            var paused = false
            var launcherSignalled = false
            try {
                checkBudget(started, timeoutMs, ports)
                paused = send(leader, Signal.STOP, ports)
                if (!paused) {
                    requireSessionEmpty(identity.pid, ports)
                    return
                }
                var emptyScans = 0
                while (emptyScans < 2) {
                    checkInterrupted()
                    checkBudget(started, timeoutMs, ports)
                    verifyLeader(identity, ports.read(identity.pid, identity.pid), ports)
                    var active = false
                    for (pid in snapshot(ports)) {
                        if (pid == identity.pid || !ports.belongsToSession(pid, identity.pid))
                            continue
                        val member = ports.read(pid, identity.pid)
                        if (member == null || member.exited()) continue
                        if (!requireOwnUid(member.pid, ports)) continue
                        active = true
                        checkInterrupted()
                        checkBudget(started, timeoutMs, ports)
                        verifyLeader(identity, ports.read(identity.pid, identity.pid), ports)
                        send(member, Signal.KILL, ports)
                    }
                    emptyScans = if (active) 0 else emptyScans + 1
                    if (emptyScans < 2) pause(started, timeoutMs, ports)
                }
                checkBudget(started, timeoutMs, ports)
                verifyLeader(identity, ports.read(identity.pid, identity.pid), ports)
                send(leader, Signal.KILL, ports)
                launcherSignalled = true
                // KILL 已发送仍不是退出证明。
                var settledScans = 0
                while (settledScans < 2) {
                    checkInterrupted()
                    settledScans = if (hasMembers(identity.pid, ports)) 0 else settledScans + 1
                    if (settledScans < 2) pause(started, timeoutMs, ports)
                }
            } catch (failure: Exception) {
                if (
                    failure !is IOException &&
                        failure !is InterruptedException &&
                        failure !is RuntimeException
                )
                    throw failure
                if (paused && !launcherSignalled) {
                    try {
                        send(leader, Signal.CONTINUE, ports)
                    } catch (resume: Exception) {
                        if (resume !is IOException && resume !is RuntimeException) throw resume
                        failure.addSuppressed(resume)
                    }
                }
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                throw failure
            }
        }

        /** 两次扫描之间保留原观察间隔。 */
        @JvmStatic
        @Throws(IOException::class)
        fun requireSessionEmpty(session: Int, ports: Ports?) {
            if (session <= 1) throw Failure.create(Code.INVALID_IDENTITY)
            if (hasMembers(session, ports!!)) throw Failure.create(Code.SESSION_NOT_EMPTY)
            try {
                checkInterrupted()
                ports.pause(POLL_MILLIS)
                checkInterrupted()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("TERMINAL_EXIT_INTERRUPTED", interrupted)
            }
            if (hasMembers(session, ports)) throw Failure.create(Code.SESSION_NOT_EMPTY)
        }

        private fun hasMembers(session: Int, ports: Ports): Boolean {
            for (pid in snapshot(ports)) {
                if (!ports.belongsToSession(pid, session)) continue
                val member = ports.read(pid, session)
                if (member != null && !member.exited()) return true
            }
            return false
        }

        private fun snapshot(ports: Ports): IntArray {
            val values = ports.pids() ?: throw Failure.create(Code.SCAN_UNAVAILABLE)
            for (pid in values) if (pid <= 1) throw Failure.create(Code.SCAN_UNAVAILABLE)
            return values
        }

        private fun requireIdentity(expected: ProcessIdentity?) {
            if (
                expected == null ||
                    expected.pid <= 1 ||
                    !expected.ownsSession() ||
                    expected.exited()
            ) {
                throw Failure.create(Code.INVALID_IDENTITY)
            }
        }

        private fun verifyLeader(
            expected: ProcessIdentity,
            current: ProcessIdentity?,
            ports: Ports,
        ) {
            if (
                current == null ||
                    current.exited() ||
                    !expected.sameProcess(current) ||
                    !current.ownsSession()
            ) {
                throw Failure.create(Code.IDENTITY_CHANGED)
            }
            if (!requireOwnUid(current.pid, ports)) throw Failure.create(Code.IDENTITY_CHANGED)
        }

        /** 捕获后的第二次身份与 UID 核验；失败不进入信号口。 */
        private fun send(expected: ProcessIdentity, signal: Signal, ports: Ports): Boolean {
            val current = ports.read(expected.pid, expected.session)
            if (current == null || current.exited()) return false
            if (!expected.sameProcess(current) || current.session != expected.session)
                throw Failure.create(Code.IDENTITY_CHANGED)
            if (!requireOwnUid(current.pid, ports)) return false
            ports.signal(expected.pid, signal)
            return true
        }

        private fun requireOwnUid(pid: Int, ports: Ports): Boolean {
            val uid = ports.uid(pid)
            if (uid == -1) return false
            if (ports.ownUid() < 0 || uid != ports.ownUid()) throw Failure.create(Code.FOREIGN_UID)
            return true
        }

        private fun remaining(started: Long, budget: Long, ports: Ports): Long {
            val elapsed = ports.now() - started
            if (elapsed < 0 || elapsed >= budget) throw Failure.create(Code.EXIT_TIMEOUT)
            return budget - elapsed
        }

        private fun checkBudget(started: Long, budget: Long, ports: Ports) {
            remaining(started, budget, ports)
        }

        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted)
                throw InterruptedException("TERMINAL_EXIT_INTERRUPTED")
        }

        private fun pause(started: Long, budget: Long, ports: Ports) {
            checkInterrupted()
            ports.pause(Math.min(POLL_MILLIS, remaining(started, budget, ports)))
        }
    }
}
