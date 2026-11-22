package com.deepseekharness.app.runtime

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.deepseekharness.app.util.Compat
import com.deepseekharness.app.util.ProcessIdentity
import com.deepseekharness.app.util.TerminalExitPolicy
import com.deepseekharness.app.util.UiText
import java.io.File
import java.io.IOException
import java.util.ArrayList

/** 终端退出的 Android 观察与信号适配；纯判据不把 launcher 退出或一次快照当作会话退出。 */
class TerminalProcessCloser private constructor() {
    private class AndroidPorts : TerminalExitPolicy.Ports {
        @Throws(IOException::class)
        override fun read(pid: Int, session: Int): ProcessIdentity? =
            TerminalProcessCloser.read(pid, session)

        @Throws(IOException::class)
        override fun belongsToSession(pid: Int, session: Int): Boolean =
            TerminalProcessCloser.belongsToSession(pid, session)

        @Throws(IOException::class)
        override fun uid(pid: Int): Int {
            try {
                return Os.stat("/proc/" + pid).st_uid
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) return -1
                throw IOException(UiText.text("无法核验终端进程 UID"), error)
            }
        }

        override fun ownUid(): Int = android.os.Process.myUid()

        @Throws(IOException::class)
        override fun pids(): IntArray {
            val files = File("/proc").listFiles()
            if (files == null) throw IOException(UiText.text("无法核验终端后台进程"))
            val pids = ArrayList<Int>()
            for (file in files) {
                if (!file.name.matches(Regex("[1-9][0-9]*"))) continue
                try {
                    val pid = Integer.parseInt(file.name)
                    if (pid > 1) pids.add(pid)
                } catch (ignored: NumberFormatException) {}
            }
            return pids.stream().mapToInt { it.toInt() }.toArray()
        }

        @Throws(IOException::class)
        override fun signal(pid: Int, signal: TerminalExitPolicy.Signal?) {
            if (pid <= 1) throw IOException(UiText.text("终端进程身份无效"))
            val number =
                when (signal!!) {
                    TerminalExitPolicy.Signal.STOP -> OsConstants.SIGSTOP
                    TerminalExitPolicy.Signal.CONTINUE -> OsConstants.SIGCONT
                    TerminalExitPolicy.Signal.KILL -> OsConstants.SIGKILL
                    TerminalExitPolicy.Signal.QUIT -> OsConstants.SIGQUIT
                }
            try {
                Os.kill(pid, number)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ESRCH)
                    throw IOException(UiText.text("终端进程回收失败"), error)
            }
        }

        override fun now(): Long = SystemClock.elapsedRealtime()

        @Throws(InterruptedException::class)
        override fun pause(millis: Long) {
            Thread.sleep(millis)
        }
    }

    companion object {
        /** 简易终端固定 READY 只有声明，宿主核验本次出生身份后才能接受命令。 */
        @JvmStatic
        @Throws(IOException::class)
        fun captureSimpleLeader(session: Int, started: Long): ProcessIdentity {
            if (session <= 1 || started <= 0) throw IOException(UiText.text("终端进程身份无效"))
            val leader = read(session, session)
            if (
                leader == null ||
                    leader.exited() ||
                    leader.started != started ||
                    !leader.ownsSession()
            )
                throw IOException(UiText.text("终端本次进程身份不匹配，原环境保持保护"))
            val ports = AndroidPorts()
            val uid = ports.uid(session)
            if (uid == -1) throw IOException(UiText.text("终端进程身份暂不可读，保留环境保护"))
            if (uid != ports.ownUid()) throw IOException(UiText.text("终端进程 UID 不属于本应用"))
            if (!belongsToSession(session, session))
                throw IOException(UiText.text("终端会话号已变化，原环境保持保护"))
            return leader
        }

        @JvmStatic
        @Throws(IOException::class, InterruptedException::class)
        fun closeSimple(expected: ProcessIdentity?, timeoutMs: Long) {
            close(expected, timeoutMs)
        }

        /** proot 自己回收 tracee；调用者仍须确认 launcher 退出与整个会话为空。 */
        @JvmStatic
        @Throws(IOException::class)
        fun closeProot(expected: ProcessIdentity?) {
            try {
                TerminalExitPolicy.requestProotExit(expected, AndroidPorts())
            } catch (failure: TerminalExitPolicy.Failure) {
                throw present(failure)
            }
        }

        @JvmStatic
        @Throws(IOException::class, InterruptedException::class)
        fun close(expected: ProcessIdentity?, timeoutMs: Long) {
            try {
                TerminalExitPolicy.close(expected, timeoutMs, AndroidPorts())
            } catch (failure: TerminalExitPolicy.Failure) {
                throw present(failure)
            }
        }

        /** 已自然退出的组长不授权按旧 SID 发信号；剩余成员或观察未知继续保留环境保护。 */
        @JvmStatic
        @Throws(IOException::class)
        fun requireSessionEmpty(session: Int) {
            try {
                TerminalExitPolicy.requireSessionEmpty(session, AndroidPorts())
            } catch (failure: TerminalExitPolicy.Failure) {
                throw present(failure)
            }
        }

        private fun present(failure: TerminalExitPolicy.Failure): IOException {
            val message =
                when (failure.code()) {
                    TerminalExitPolicy.Code.INVALID_IDENTITY -> "终端本次进程身份缺失"
                    TerminalExitPolicy.Code.IDENTITY_CHANGED -> "终端进程身份已变化，未发送信号"
                    TerminalExitPolicy.Code.FOREIGN_UID -> "终端进程 UID 不属于本应用"
                    TerminalExitPolicy.Code.SCAN_UNAVAILABLE -> "无法核验终端后台进程"
                    TerminalExitPolicy.Code.SESSION_NOT_EMPTY -> "终端后台命令仍在退出，原环境保持保护；稍后可直接重试"
                    TerminalExitPolicy.Code.EXIT_TIMEOUT -> "终端后台命令尚未退出，原环境保持保护"
                }
            return IOException(UiText.text(message), failure)
        }

        @Throws(IOException::class)
        private fun belongsToSession(pid: Int, session: Int): Boolean {
            val actual = NativeProcess.sessionId(pid)
            if (actual == -OsConstants.ESRCH) return false
            if (actual < 0) throw IOException(UiText.format("无法查询终端会话（errno=%s），请稍后重试", -actual))
            return actual == session
        }

        @Throws(IOException::class)
        private fun read(pid: Int, session: Int): ProcessIdentity? {
            val deadline = SystemClock.elapsedRealtime() + 500L
            var failure: IOException? = null
            do {
                try {
                    val current =
                        ProcessIdentity.inSession(
                            Compat.readAll(File("/proc/" + pid + "/stat")),
                            pid,
                            session,
                        )
                    if (current != null) return current
                    failure = IOException(UiText.text("终端进程身份暂不可读，保留环境保护"))
                } catch (error: IOException) {
                    failure = error
                }
                // exec/退出窗口内短读仍是未知；由独立内核会话查询区分 ESRCH 与权限/读取错误。
                val actual = NativeProcess.sessionId(pid)
                if (actual == -OsConstants.ESRCH || actual > 0 && actual != session) return null
                if (actual < 0)
                    throw IOException(UiText.format("无法查询终端会话（errno=%s），请稍后重试", -actual), failure)
                if (SystemClock.elapsedRealtime() >= deadline) break
                try {
                    Thread.sleep(20L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("TERMINAL_IDENTITY_INTERRUPTED", interrupted)
                }
            } while (true)
            throw IOException(UiText.text("无法核验终端独立会话，原环境保持保护"), failure)
        }
    }
}
