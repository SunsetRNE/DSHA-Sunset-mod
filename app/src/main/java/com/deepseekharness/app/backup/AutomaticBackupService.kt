package com.deepseekharness.app.backup

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import com.deepseekharness.app.util.ScheduledBackupOwner
import java.util.Arrays

/** JobScheduler 持有任务寿命，不从后台强行启动前台服务或停止用户会话。 */
class AutomaticBackupService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private val owner = ScheduledBackupOwner<JobParameters>()
    private var poll: Runnable? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ticket = owner.begin(params) ?: return false
        val producer = AutomaticBackups.beginProducer()
        if (producer == null) {
            owner.finish(ticket)
            return false
        }
        try {
            val manual = params.extras.getBoolean("manual")
            val worker = Thread({ prepare(ticket, manual, producer) }, "automatic-backup-schedule")
            worker.start()
            return true
        } catch (unavailable: RuntimeException) {
            producer.close()
            owner.finish(ticket)
            AutomaticBackups.recordError(this, "WORKER_UNAVAILABLE")
            return false
        } catch (unavailable: Error) {
            producer.close()
            owner.finish(ticket)
            AutomaticBackups.recordError(this, "WORKER_UNAVAILABLE")
            return false
        }
    }

    /** 工作线程仅准备；原生作业的提交与所有权登记仍在主事件线程。 */
    private fun prepare(
        ticket: ScheduledBackupOwner.Ticket<JobParameters>,
        manual: Boolean,
        producer: WorkerQuiescence.Lease,
    ) {
        var secret: CharArray? = null
        var retry = false
        var error = ""
        var handedOff = false
        try {
            if (AutomaticBackups.enabled(this) && (manual || AutomaticBackups.due(this))) {
                if (!AutomaticBackups.idle(this)) retry = manual
                else secret = AutomaticBackups.password(this)
            }
        } catch (failure: Exception) {
            error = failureCode(failure)
        } catch (failure: LinkageError) {
            error = failureCode(failure)
        } finally {
            val prepared = secret
            val reschedule = retry
            val failure = error
            try {
                handedOff = main.post {
                    completePreparation(ticket, prepared, reschedule, failure, producer)
                }
            } finally {
                if (!handedOff) {
                    erase(prepared)
                    producer.close()
                }
            }
        }
    }

    /** 与 onStopJob 在同一主事件线程中提交并登记精确原生作业 ID。 */
    private fun completePreparation(
        ticket: ScheduledBackupOwner.Ticket<JobParameters>,
        secret: CharArray?,
        retry: Boolean,
        error: String,
        producer: WorkerQuiescence.Lease,
    ) {
        try {
            owner.accept(ticket) submission@{
                if (error.isNotEmpty()) {
                    AutomaticBackups.recordError(this, error)
                    finish(ticket, false)
                    return@submission
                }
                if (secret == null || !AutomaticBackups.enabled(this)) {
                    finish(ticket, retry)
                    return@submission
                }
                val jobs = NativeBackupJobs.get(this)
                synchronized(jobs) {
                    if (!jobs.exportAutomatic(secret)) {
                        finish(ticket, false)
                        return@submission
                    }
                    owner.submitted(ticket, jobs.state().id)
                }
                val nextPoll = Runnable { poll(ticket) }
                poll = nextPoll
                main.post(nextPoll)
            }
        } catch (failure: Exception) {
            cancelOwned(ticket)
            AutomaticBackups.recordError(this, failureCode(failure))
            finish(ticket, false)
        } catch (failure: LinkageError) {
            cancelOwned(ticket)
            AutomaticBackups.recordError(this, failureCode(failure))
            finish(ticket, false)
        } finally {
            erase(secret)
            producer.close()
        }
    }

    private fun poll(ticket: ScheduledBackupOwner.Ticket<JobParameters>) {
        if (!owner.current(ticket)) return
        val jobs = NativeBackupJobs.get(this)
        val state: NativeBackupJobs.State
        synchronized(jobs) {
            state = jobs.state()
            if (!AutomaticBackups.enabled(this) && state.busy && owner.owns(ticket, state.id))
                jobs.cancel()
        }
        if (!owner.owns(ticket, state.id) || !state.busy) {
            if (owner.owns(ticket, state.id) && state.error.isNotEmpty())
                AutomaticBackups.recordError(this, state.error)
            finish(ticket, false)
            return
        }
        main.postDelayed(poll!!, 300)
    }

    private fun finish(ticket: ScheduledBackupOwner.Ticket<JobParameters>, retry: Boolean) {
        if (owner.finish(ticket)) {
            poll?.let { main.removeCallbacks(it) }
            poll = null
            jobFinished(ticket.parameters, retry)
        }
    }

    private fun cancelOwned(ticket: ScheduledBackupOwner.Ticket<JobParameters>) {
        val id = owner.jobId(ticket)
        if (id.isEmpty()) return
        val jobs = NativeBackupJobs.get(this)
        // 与 state()/cancel() 共用 monitor，不让无关用户导出插入身份检查与取消之间。
        synchronized(jobs) {
            val state = jobs.state()
            if (state.busy && owner.owns(ticket, state.id)) jobs.cancel()
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val stopped = owner.stop(params) ?: return false
        poll?.let { main.removeCallbacks(it) }
        poll = null
        cancelOwned(stopped)
        return false
    }

    override fun onDestroy() {
        val stopped = owner.stopCurrent()
        poll?.let { main.removeCallbacks(it) }
        poll = null
        if (stopped != null) cancelOwned(stopped)
        // 保留已排队准备回调，让它擦除密钥并释放生产者租约。
        super.onDestroy()
    }

    companion object {
        @JvmStatic
        private fun erase(secret: CharArray?) {
            if (secret != null) Arrays.fill(secret, '\u0000')
        }

        @JvmStatic
        private fun failureCode(failure: Throwable): String =
            if (failure is Exception) NativeBackupJobs.code(failure)
            else "RUNTIME_LINKAGE_UNAVAILABLE"
    }
}
