package com.deepseekharness.app.plugins

import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * S3a 锁探针：由短命 Python 使用现有 flock/PID 锁，Kotlin 本身不取得文件锁。
 *
 * 只接受探针执行端，不提供 profile 写回调。生产 writer 仍由既有 guest 事务持有。
 */
class PluginGuestLockAdapter {
    class Probe
    internal constructor(
        private val child: Process,
        private val reader: java.io.BufferedReader,
        private val reads: java.util.concurrent.ExecutorService,
        val receipt: String,
    ) : Closeable {
        var exitConfirmed: Boolean = false
            private set

        private var closeAttempted = false

        override fun close() {
            if (closeAttempted) return
            closeAttempted = true
            try {
                child.outputStream.write("RELEASE\n".toByteArray(StandardCharsets.US_ASCII))
                child.outputStream.flush()
                child.outputStream.close()
                if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) {
                    throw IOException("PLUGIN_LOCK_RELEASE_UNKNOWN")
                }
                exitConfirmed = true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("PLUGIN_LOCK_RELEASE_UNKNOWN", interrupted)
            } finally {
                reader.close()
                reads.shutdownNow()
            }
        }
    }

    /** command 为独立 argv；冻结的值经私有 stdin 送入，不能靠修改同一 Python 模块的环境复用。 */
    @Throws(IOException::class, InterruptedException::class)
    fun acquireProbe(
        context: PluginInvocationContext,
        command: List<String>,
        waitMs: Long,
    ): Probe {
        require(command.isNotEmpty() && waitMs in 1..120_000) { "PLUGIN_LOCK_PROBE_ARGUMENT" }
        val child =
            ProcessBuilder(ArrayList(command))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        val reads = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "plugin-lock-probe").apply { isDaemon = true }
        }
        val reader = child.inputStream.bufferedReader(StandardCharsets.UTF_8)
        try {
            val encoder = Base64.getEncoder()
            val payload = buildString {
                context.fields().forEach { (key, value) ->
                    append(key)
                        .append('=')
                        .append(encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8)))
                        .append('\n')
                }
                append("fingerprint=")
                    .append(
                        encoder.encodeToString(
                            context.fingerprint().toByteArray(StandardCharsets.US_ASCII)
                        )
                    )
                    .append("\nEND\n")
            }
            child.outputStream.write(payload.toByteArray(StandardCharsets.UTF_8))
            child.outputStream.flush()
            val line =
                reads.submit<String?> { reader.readLine() }.get(waitMs, TimeUnit.MILLISECONDS)
            val expected = Regex("HELD\\t${context.fingerprint()}\\t[0-9]+:[0-9]+\\t[0-9]+:[0-9]+")
            if (line == null || !expected.matches(line)) {
                throw IOException("PLUGIN_LOCK_PROBE_NOT_ACQUIRED")
            }
            return Probe(child, reader, reads, line)
        } catch (failure: Exception) {
            // 只结束本适配器创建的无写入探针；未知结果不允许重放或取得 writer 权限。
            var interrupted = failure is InterruptedException || Thread.interrupted()
            var exited = false
            try {
                runCatching { child.outputStream.close() }
                child.destroy()
                try {
                    exited = child.waitFor(5, TimeUnit.SECONDS)
                } catch (cancelled: InterruptedException) {
                    interrupted = true
                }
            } finally {
                if (exited) runCatching { reader.close() }
                reads.shutdownNow()
                if (interrupted) Thread.currentThread().interrupt()
            }
            if (!exited || failure is TimeoutException)
                throw IOException("PLUGIN_LOCK_PROBE_UNKNOWN", failure)
            throw failure
        }
    }
}
