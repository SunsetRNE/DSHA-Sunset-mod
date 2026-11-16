package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginInvocationContext.Stage
import java.io.IOException
import java.util.Collections

/** 单阶段协议适配。执行端仍为原有 guest 事务；调用方在每阶段之间重新提供准入事实。 */
class PluginPreparationProtocol(
    val context: PluginInvocationContext,
    private val origin: Origin,
    private val currentFingerprint: () -> String?,
) {
    enum class Origin {
        HOST_STARTUP,
        HOST_PLUGIN_UI,
        TERMINAL,
        NODE_LOCKED_CALLBACK,
    }

    enum class Status {
        COMPLETED,
        FAILED,
        CANCELLED,
        INVALIDATED,
        PARTIAL,
        UNKNOWN,
    }

    /** 回执已由旧执行端按自身格式校验，不能把正常返回、changed=false 或日志行当完成。 */
    data class Outcome(
        val status: Status,
        val exitConfirmed: Boolean,
        val receiptValidated: Boolean,
    )

    data class Request(
        val context: PluginInvocationContext,
        val stage: Stage,
        val argv: List<String>,
        val environment: Map<String, String>,
        val timeoutMs: Long,
    )

    fun interface Executor {
        @Throws(IOException::class, InterruptedException::class)
        fun execute(request: Request): Outcome
    }

    private val owner = Thread.currentThread()
    private val attempted = mutableSetOf<Stage>()
    private var next = 0
    private var terminal: Outcome? = null

    /** 先记 attempted 再派发。异常、取消、PARTIAL、UNKNOWN 都封闭本次对象，绝不自动重发。 */
    @Throws(IOException::class, InterruptedException::class)
    fun dispatch(stage: Stage, executor: Executor): Outcome {
        check(Thread.currentThread() === owner) { "PLUGIN_INVOCATION_OWNER" }
        check(terminal == null) { "PLUGIN_INVOCATION_CLOSED" }
        check(stage.ordinal == next && stage !in attempted) { "PLUGIN_STAGE_ORDER_OR_REPLAY" }
        if (origin == Origin.NODE_LOCKED_CALLBACK) {
            return stop(Status.INVALIDATED)
        }
        if (currentFingerprint() != context.fingerprint()) return stop(Status.INVALIDATED)
        if (Thread.currentThread().isInterrupted) return stop(Status.CANCELLED)
        attempted.add(stage)
        val outcome =
            try {
                executor.execute(request(stage))
            } catch (interrupted: InterruptedException) {
                terminal = Outcome(Status.UNKNOWN, false, false)
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (failure: Exception) {
                terminal = Outcome(Status.UNKNOWN, false, false)
                throw failure
            }
        // 出口取消不能抹掉写入可能已发生的事实；保留原始 PARTIAL/UNKNOWN 结果。
        if (outcome.status != Status.COMPLETED) {
            terminal = outcome
            return outcome
        }
        if (!outcome.exitConfirmed || !outcome.receiptValidated) return stop(Status.UNKNOWN)
        if (currentFingerprint() != context.fingerprint()) {
            return outcome.copy(status = Status.INVALIDATED).also { terminal = it }
        }
        next++
        if (next == Stage.entries.size) terminal = outcome
        return outcome
    }

    private fun stop(status: Status): Outcome = Outcome(status, false, false).also { terminal = it }

    /** 每次独立 Python 进程，环境在 import 前固定；不在持锁进程里再次 operation_lock。 */
    fun request(stage: Stage): Request {
        val script =
            when (stage) {
                Stage.CAS -> "materialize-cas-links.py"
                Stage.REGISTER -> "register-builtin-plugins.py"
                Stage.LEGACY -> "plugin-manager.py"
            }
        val argv = mutableListOf("python3")
        if (stage != Stage.LEGACY) argv.add("-u")
        argv.add("${context.guestHome}/$script")
        if (stage == Stage.LEGACY) argv.add("migrate-review-markers")
        val env = linkedMapOf("DSH_HOME" to context.guestHome)
        if (context.taskId.isNotEmpty()) env["DSHA_PLUGIN_TASK"] = context.taskId
        if (stage == Stage.LEGACY) env["DSHA_PLUGIN_DOWNLOAD_SOURCE"] = "auto"
        return Request(
            context,
            stage,
            Collections.unmodifiableList(argv),
            Collections.unmodifiableMap(env),
            context.budgets.getValue(stage),
        )
    }

    companion object {
        /** 当前生产预算：CAS 900s；注册普通90s/首启240s；legacy 3000s，不能改成统一总超时。 */
        @JvmStatic
        fun normalWebBudgets(firstRegistration: Boolean): Map<Stage, Long> =
            mapOf(
                Stage.CAS to 900_000L,
                Stage.REGISTER to if (firstRegistration) 240_000L else 90_000L,
                Stage.LEGACY to 3_000_000L,
            )
    }
}
