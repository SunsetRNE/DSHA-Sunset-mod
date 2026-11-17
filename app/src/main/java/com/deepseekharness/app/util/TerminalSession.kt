package com.deepseekharness.app.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** 普通管道终端：独立会话取消、串行发送；旧进程事件不能影响新会话。 */
class TerminalSession(
    private val backend: Backend?,
    private val output: Consumer<String>?,
    private val changed: Consumer<State>?,
) {
    enum class State {
        STOPPED,
        STARTING,
        READY,
        BUSY,
        STOPPING,
        FAILED,
    }

    interface Backend {
        /** 任何环境准备和进程创建之前登记，清理成功之前不得释放。 */
        @Throws(Exception::class) fun beginLifetime(): AutoCloseable? = null

        @Throws(Exception::class) fun open(): Process?

        /** 核验固定引导的出生身份，默认实现保持无操作。 */
        @Throws(Exception::class) fun onReady(process: Process?, identity: TerminalReady?) {}

        @Throws(Exception::class) fun terminate(process: Process?, processGroup: Long)

        /** 门禁拒绝时须保证一个字节都未发送。 */
        @Throws(IOException::class)
        fun write(process: Process?, text: String?) {
            process!!.outputStream.write(text!!.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
        }
    }

    class EnvironmentUnavailable(message: String?) : IOException(message)

    class UncertainStart(cause: Throwable?) :
        IOException(
            UiText.text("启动失败且无法确认子进程已回收，保留环境占用"),
            cause,
        )

    private val outputSlots = Semaphore(16)
    private val control = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "terminal-control").apply { isDaemon = true }
    }
    // 以下可变会话状态仅由 control 线程访问。
    private val pending = ArrayDeque<String>()
    private var active: Run? = null
    private var commandId = 0L
    @Volatile private var state = State.STOPPED
    @Volatile private var permanentlyClosing = false
    @Volatile private var disposed = false

    private class Run {
        val prefix = "\u001eDSHA_" + UUID.randomUUID().toString().replace("-", "") + ":"
        val resultVariable = "__dsha_exit_" + prefix.substring(6, prefix.length - 1)
        val incoming = StringBuilder()
        val openedAt = System.nanoTime()
        var process: Process? = null
        var lifetime: AutoCloseable? = null
        var group = 0L
        var inFlight = 0L
        var exitedAt = 0L
        var ready = false
        var stopRequested = false
        var restartAfterStop = false
        var uncertainStart = false
    }

    fun state(): State = state

    private fun submitControl(action: Runnable): Boolean {
        try {
            control.execute(action)
            return true
        } catch (error: RejectedExecutionException) {
            if (disposed || permanentlyClosing) return false
            throw error
        }
    }

    fun ensureStarted() {
        if (!permanentlyClosing) submitControl { if (!permanentlyClosing) start() }
    }

    /** 接受后仅发送一次；未接受时调用方须保留输入框文本。 */
    fun submit(command: String?): Boolean {
        if (
            permanentlyClosing ||
                command == null ||
                command.trim { it <= ' ' }.isEmpty() ||
                command.length > MAX_COMMAND ||
                command.indexOf('\u0000') >= 0
        )
            return false
        return submitControl {
            if (permanentlyClosing) return@submitControl
            pending.addLast(command)
            emit("$ " + command + "\n")
            if (active == null) start()
            pump()
        }
    }

    fun cancelAndRestart() {
        if (permanentlyClosing) return
        submitControl {
            if (permanentlyClosing) return@submitControl
            if (active == null) {
                start()
                return@submitControl
            }
            requestStop(true)
        }
    }

    fun shutdown() {
        if (!disposed) submitControl(this::shutdownNow)
    }

    /** 先确认关闭请求执行过，再等进程回收完成。 */
    @Throws(InterruptedException::class)
    fun shutdownAndWait(timeoutMs: Long): Boolean {
        if (disposed) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs))
        val dispatched = CountDownLatch(1)
        if (
            !submitControl {
                try {
                    shutdownNow()
                } finally {
                    dispatched.countDown()
                }
            }
        )
            return disposed
        if (!dispatched.await(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS)) return false
        while (
            state != State.STOPPED && state != State.FAILED && System.nanoTime() < deadline
        ) Thread.sleep(25)
        return state == State.STOPPED
    }

    /** 仅确认进程退出后才释放控制线程；失败允许重试关闭。 */
    @Synchronized
    @Throws(InterruptedException::class)
    fun disposeAndWait(timeoutMs: Long): Boolean {
        if (disposed) return true
        permanentlyClosing = true
        try {
            if (!shutdownAndWait(timeoutMs)) return false
            disposed = true
            control.shutdown()
            return true
        } finally {
            if (!disposed) permanentlyClosing = false
        }
    }

    private fun deliverExit(run: Run, failure: Exception?) {
        try {
            control.execute { exited(run, failure) }
        } catch (error: RejectedExecutionException) {
            if (!disposed) throw error
        }
    }

    private fun shutdownNow() {
        if (!pending.isEmpty()) emit(UiText.format("[关闭会话：取消 %s 条尚未发送的命令]\n", pending.size))
        pending.clear()
        if (active != null) requestStop(false) else change(State.STOPPED)
    }

    private fun start() {
        if (active != null) return
        val run = Run()
        active = run
        change(State.STARTING)
        emit(UiText.text("[正在启动简易终端；命令按顺序等待发送]\n"))
        try {
            run.lifetime = backend!!.beginLifetime()
            run.process = backend.open()
            // 沿用原交互入口，仅给此 shell 建立独立 session/group。
            val script =
                "IFS= read -r __dsha_stat < /proc/\$\$/stat && " +
                    "__dsha_tail=\${__dsha_stat##*) } && " +
                    "read -r -a __dsha_fields <<< \"\$__dsha_tail\" && " +
                    "[[ \${__dsha_fields[19]} =~ ^[1-9][0-9]*\$ ]] && " +
                    "printf '" +
                    escapedPrefix(run) +
                    "READY:%s:%s\\037' \"\$\$\" \"\${__dsha_fields[19]}\" && " +
                    "exec /bin/bash --noprofile --norc"
            write(
                run,
                "exec /usr/bin/setsid /bin/bash --noprofile --norc -c " +
                    ShellQuote.arg(script) +
                    "\n",
            )
            val reader = Thread({ read(run) }, "terminal-output")
            reader.isDaemon = true
            reader.start()
            poll(run)
        } catch (error: Exception) {
            emit(UiText.format("[终端启动失败：%s；未发送的命令保留，重试后继续]\n", error))
            if (error is UncertainStart) {
                run.uncertainStart = true
                change(State.FAILED)
                emit(UiText.text("[无法取得进程句柄，禁止重启会话或移动环境；请结束应用进程后再恢复]\n"))
            } else if (run.process == null) {
                try {
                    releaseLifetime(run)
                    active = null
                } catch (cleanup: Exception) {
                    emit(UiText.format("[终端启动清理失败，保留环境占用：%s]\n", cleanup))
                }
                change(State.FAILED)
            } else {
                run.stopRequested = true
                run.restartAfterStop = false
                stop(run)
            }
        }
    }

    private fun read(run: Run) {
        // 不在空管道上阻塞读取；跨批保留 UTF-8 不完整字符。
        try {
            run.process!!.inputStream.use { input ->
                val decoder =
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                val bytes = ByteBuffer.allocate(8192)
                val chars = CharBuffer.allocate(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted)
                        throw InterruptedException(UiText.text("终端读取被中断"))
                    val available = input.available()
                    if (available > 0) {
                        val count =
                            input.read(
                                bytes.array(),
                                bytes.position(),
                                Math.min(available, bytes.remaining()),
                            )
                        if (count < 0) break
                        if (count > 0) {
                            bytes.position(bytes.position() + count)
                            decode(run, decoder, bytes, chars, false)
                            continue
                        }
                    }
                    if (!alive(run.process)) {
                        if (input.available() > 0) continue
                        break
                    }
                    Thread.sleep(20)
                }
                decode(run, decoder, bytes, chars, true)
                val flushed = decoder.flush(chars)
                if (flushed.isError) flushed.throwException()
                enqueueOutput(run, chars)
                deliverExit(run, null)
            }
        } catch (error: IOException) {
            deliverExit(run, error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            deliverExit(run, error)
        }
    }

    private fun decode(
        run: Run,
        decoder: CharsetDecoder,
        bytes: ByteBuffer,
        chars: CharBuffer,
        end: Boolean,
    ) {
        bytes.flip()
        val result = decoder.decode(bytes, chars, end)
        if (result.isError) result.throwException()
        if (result.isOverflow) throw IOException(UiText.text("终端解码缓冲区溢出"))
        bytes.compact()
        enqueueOutput(run, chars)
    }

    private fun enqueueOutput(run: Run, chars: CharBuffer) {
        chars.flip()
        val chunk = chars.toString()
        chars.clear()
        if (chunk.isEmpty()) return
        outputSlots.acquire()
        try {
            control.execute {
                try {
                    receive(run, chunk)
                } finally {
                    outputSlots.release()
                }
            }
        } catch (error: RejectedExecutionException) {
            outputSlots.release()
            if (!disposed) throw error
        }
    }

    private fun receive(run: Run, chunk: String) {
        if (active !== run) return
        run.incoming.append(chunk)
        while (active === run) {
            val begin = run.incoming.indexOf(run.prefix)
            if (begin < 0) {
                var keep = Math.min(run.prefix.length - 1, run.incoming.length)
                while (
                    keep > 0 &&
                        !run.prefix.startsWith(run.incoming.substring(run.incoming.length - keep))
                ) keep--
                emit(run.incoming.substring(0, run.incoming.length - keep))
                run.incoming.delete(0, run.incoming.length - keep)
                return
            }
            if (begin > 0) {
                emit(run.incoming.substring(0, begin))
                run.incoming.delete(0, begin)
            }
            val end = run.incoming.indexOf("\u001f", run.prefix.length)
            if (end < 0) {
                if (run.incoming.length > 256) {
                    emit(run.incoming.substring(0, 1))
                    run.incoming.deleteCharAt(0)
                    continue
                }
                return
            }
            val message = run.incoming.substring(run.prefix.length, end)
            run.incoming.delete(0, end + 1)
            if (message.startsWith("READY:") && !run.ready) {
                val identity = TerminalReady.parse(message) ?: continue
                run.group = identity.session.toLong()
                try {
                    backend!!.onReady(run.process, identity)
                } catch (invalid: Exception) {
                    run.stopRequested = true
                    change(State.FAILED)
                    emit(UiText.format("[无法核验终端本次进程身份，保留环境占用：%s]\n", invalid))
                    return
                }
                run.ready = true
                if (run.stopRequested) {
                    stop(run)
                    return
                }
                change(State.READY)
                emit(UiText.text("[会话已就绪；中止会重启 shell，cd/export 状态随重启清除]\n"))
                pump()
            } else if (run.inFlight != 0L && message.startsWith("DONE:" + run.inFlight + ":")) {
                run.inFlight = 0
                if (!run.stopRequested) {
                    change(State.READY)
                    pump()
                }
            }
        }
    }

    private fun pump() {
        val run = active
        if (
            run == null ||
                !run.ready ||
                run.stopRequested ||
                run.inFlight != 0L ||
                pending.isEmpty()
        )
            return
        if (!alive(run.process)) {
            exited(run, null)
            return
        }
        val command = pending.removeFirst()
        val id = ++commandId
        run.inFlight = id
        change(State.BUSY)
        try {
            // 整行先解析再 eval；子 shell 恢复上一命令的退出码。
            write(
                run,
                "eval " +
                    ShellQuote.arg(command) +
                    "; " +
                    run.resultVariable +
                    "=\$?; builtin printf '" +
                    escapedPrefix(run) +
                    "DONE:" +
                    id +
                    ":%s\\037' \"\$" +
                    run.resultVariable +
                    "\"; (exit \"\$" +
                    run.resultVariable +
                    "\")\n",
            )
        } catch (blocked: EnvironmentUnavailable) {
            pending.addFirst(command)
            run.inFlight = 0
            emit(UiText.format("[%s；命令尚未发送，已保留。完成后请重试重启]\n", blocked.message))
            requestStop(false)
        } catch (error: IOException) {
            // 部分写入不能证明命令是否执行，绝不自动重发。
            emit(UiText.format("[发送结果不确定，未自动重发：%s；请检查执行结果。%s]\n", command, error))
            requestStop(true)
        }
    }

    private fun requestStop(restart: Boolean) {
        val run = active ?: return
        val retryFailedStop = state == State.FAILED
        run.stopRequested = true
        run.restartAfterStop = restart
        change(State.STOPPING)
        emit(
            if (restart) UiText.text("[正在中止本会话进程组并重启；尚未发送的命令保留]\n") else UiText.text("[正在关闭简易终端]\n")
        )
        // 等 READY 获得身份，不以容器启动器代替本次会话。
        if (run.ready || retryFailedStop || !alive(run.process)) stop(run)
    }

    private fun stop(run: Run) {
        if (active !== run) return
        if (run.uncertainStart) {
            change(State.FAILED)
            emit(UiText.text("[未确认启动进程已回收，继续保留环境占用，不创建新会话]\n"))
            return
        }
        try {
            if (run.process != null) backend!!.terminate(run.process, run.group)
            releaseLifetime(run)
            active = null
            change(State.STOPPED)
            emit(UiText.text("[旧会话已结束]\n"))
            if (run.restartAfterStop) start()
        } catch (error: Exception) {
            change(State.FAILED)
            emit(UiText.format("[中止失败，未创建新会话：%s；待发命令保留，请再次点中止重试]\n", error))
        }
    }

    private fun exited(run: Run, failure: Exception?) {
        if (active !== run) return
        emit(run.incoming.toString())
        run.incoming.setLength(0)
        if (failure != null) emit(UiText.format("[终端读取失败：%s]\n", failure))
        emit(UiText.text("[会话已退出；已发送的命令不会自动重放]\n"))
        run.stopRequested = true
        run.restartAfterStop = run.ready && (run.restartAfterStop || !pending.isEmpty())
        stop(run)
    }

    private fun poll(run: Run) {
        control.schedule(
            {
                if (active !== run) return@schedule
                val now = System.nanoTime()
                if (!alive(run.process)) {
                    if (run.exitedAt == 0L) run.exitedAt = now
                    if (now - run.exitedAt > TimeUnit.MILLISECONDS.toNanos(500)) {
                        exited(run, null)
                        return@schedule
                    }
                } else if (!run.ready && now - run.openedAt > TimeUnit.SECONDS.toNanos(15)) {
                    emit(UiText.text("[终端初始化超时，未发送任何用户命令]\n"))
                    run.stopRequested = true
                    stop(run)
                    return@schedule
                }
                poll(run)
            },
            100,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun write(run: Run, text: String) {
        backend!!.write(run.process, text)
    }

    private fun change(value: State) {
        state = value
        try {
            changed!!.accept(value)
        } catch (ignored: RuntimeException) {}
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        try {
            output!!.accept(text)
        } catch (ignored: RuntimeException) {}
    }

    companion object {
        private const val MAX_COMMAND = 16 * 1024

        private fun escapedPrefix(run: Run): String = "\\036" + run.prefix.substring(1)

        private fun releaseLifetime(run: Run) {
            if (run.lifetime != null) {
                run.lifetime!!.close()
                run.lifetime = null
            }
        }

        private fun alive(process: Process?): Boolean {
            if (process == null) return false
            try {
                process.exitValue()
                return false
            } catch (running: IllegalThreadStateException) {
                return true
            }
        }
    }
}
