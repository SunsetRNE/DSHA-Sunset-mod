package com.deepseekharness.app.core

import com.deepseekharness.app.runtime.ProotBootstrap
import com.deepseekharness.app.runtime.TerminalProcessCloser
import com.deepseekharness.app.util.Compat
import com.deepseekharness.app.util.EnvironmentTaskGate
import com.deepseekharness.app.util.ProcessIdentity
import com.deepseekharness.app.util.ProcessTermination
import com.deepseekharness.app.util.TerminalReady
import com.deepseekharness.app.util.TerminalSession
import com.deepseekharness.app.util.UiStateText
import com.deepseekharness.app.util.UiText
import java.io.IOException

/** 与 View 无关的简易终端后端及环境门禁。 */
class SimpleTerminalBackend private constructor() {
    companion object {
        @JvmStatic
        fun create(proot: ProotBootstrap?): TerminalSession.Backend =
            object : TerminalSession.Backend {
                private var sessionLeader: ProcessIdentity? = null
                private var reported: TerminalReady? = null

                override fun beginLifetime(): AutoCloseable = RuntimeTasks.beginDetached()

                @Throws(Exception::class)
                override fun open(): Process? =
                    acquire(proot, UiText.text("启动简易终端")).use { lease ->
                        lease.run<Process?> {
                            checkPending(proot)
                            if (!proot!!.isEnvironmentReady())
                                throw IOException(UiText.text("环境未就绪，请先到「安装」页完成安装"))
                            try {
                                proot.execRootfsInteractive()
                            } catch (error: RuntimeException) {
                                throw TerminalSession.UncertainStart(error)
                            } catch (error: Error) {
                                throw TerminalSession.UncertainStart(error)
                            }
                        }
                    }

                @Throws(IOException::class)
                override fun write(process: Process?, text: String?) {
                    try {
                        acquire(proot, UiText.text("终端命令发送")).use { lease ->
                            lease.run<Any?> {
                                checkPending(proot)
                                super<TerminalSession.Backend>.write(process, text)
                                null
                            }
                        }
                    } catch (error: IOException) {
                        throw error
                    } catch (error: Exception) {
                        throw IOException(UiText.text("终端命令发送失败"), error)
                    }
                }

                @Throws(IOException::class)
                override fun onReady(process: Process?, identity: TerminalReady?) {
                    if (
                        reported != null &&
                            (reported!!.session != identity!!.session ||
                                reported!!.started != identity.started)
                    )
                        throw IOException(UiText.text("终端会话身份发生变化"))
                    reported = identity
                    sessionLeader =
                        TerminalProcessCloser.captureSimpleLeader(
                            identity!!.session,
                            identity.started,
                        )
                }

                @Throws(Exception::class)
                override fun terminate(process: Process?, group: Long) {
                    if (group > 1) {
                        if (group > Int.MAX_VALUE) throw IOException(UiText.text("终端进程组无效"))
                        if (reported == null || reported!!.session.toLong() != group)
                            throw IOException(UiText.text("终端组号缺少本次出生身份"))
                        if (sessionLeader == null)
                            sessionLeader =
                                TerminalProcessCloser.captureSimpleLeader(
                                    reported!!.session,
                                    reported!!.started,
                                )
                        TerminalProcessCloser.closeSimple(sessionLeader, 3000L)
                    } else {
                        // 固定 READY 握手未完成；即使启动器已退，guest 仍可能存在。
                        throw IOException(UiText.text("未取得终端本次会话身份，保留环境占用"))
                    }
                    // 复用既有的进程身份停止判据，并核验启动器退出。
                    if (!ProcessTermination.awaitExit(process, 1000L)) Compat.destroy(process)
                    if (!ProcessTermination.awaitExit(process, 1000L))
                        throw IOException(UiText.text("旧终端启动器尚未退出"))
                    try {
                        process!!.outputStream.close()
                    } catch (ignored: IOException) {}
                    try {
                        process!!.inputStream.close()
                    } catch (ignored: IOException) {}
                    try {
                        process!!.errorStream.close()
                    } catch (ignored: IOException) {}
                    sessionLeader = null
                    reported = null
                }
            }

        @Throws(IOException::class)
        private fun acquire(proot: ProotBootstrap?, kind: String?): EnvironmentTaskGate.Lease {
            val blocked = blockMessage(proot)
            if (blocked.isNotEmpty()) throw TerminalSession.EnvironmentUnavailable(blocked)
            val lease = EnvironmentTaskGate.tryAcquireShared(kind)
            if (lease == null)
                throw TerminalSession.EnvironmentUnavailable(UiText.text("环境维护或独占任务正在进行，请稍后再打开终端"))
            return lease
        }

        @Throws(IOException::class)
        private fun checkPending(proot: ProotBootstrap?) {
            if (
                MaintenanceCoordinator.pending(proot!!.getRootfsDir().parentFile!!.parentFile) ||
                    MaintenanceCoordinator.isExclusive()
            )
                throw TerminalSession.EnvironmentUnavailable(UiText.text("环境维护尚未完成，请先恢复维护，再打开终端"))
        }

        @JvmStatic
        fun blockMessage(proot: ProotBootstrap?): String {
            if (MaintenanceCoordinator.pending(proot!!.getRootfsDir().parentFile!!.parentFile))
                return UiText.text("上次环境维护未完成，请到安装与修复页恢复维护，再打开终端")
            if (MaintenanceCoordinator.isEnvironmentTaskBusy()) {
                val kind = EnvironmentTaskGate.activeKind()
                return if (kind.isEmpty()) UiText.text("正在执行环境任务，请稍后再打开终端或发送命令")
                else UiText.format("正在%s，请稍后再打开终端或发送命令", UiStateText.render(kind))
            }
            return ""
        }
    }
}
