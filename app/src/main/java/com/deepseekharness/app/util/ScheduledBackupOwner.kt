package com.deepseekharness.app.util

/** 一个事件线程拥有调度参数，以及每个请求产生的精确原生作业。 */
class ScheduledBackupOwner<P> {
    class Ticket<P> private constructor(@JvmField val parameters: P) {
        @get:JvmSynthetic @set:JvmSynthetic internal var jobId: String = ""

        companion object {
            @JvmSynthetic internal fun <P> create(parameters: P): Ticket<P> = Ticket(parameters)
        }
    }

    private val eventThread: Thread = Thread.currentThread()
    private var active: Ticket<P>? = null

    private fun checkThread() {
        if (Thread.currentThread() !== eventThread)
            throw IllegalStateException("SCHEDULED_BACKUP_OWNER_THREAD")
    }

    fun begin(parameters: P): Ticket<P>? {
        checkThread()
        if (active != null) return null
        active = Ticket.create(parameters)
        return active
    }

    fun current(ticket: Ticket<P>?): Boolean {
        checkThread()
        return ticket != null && active === ticket
    }

    fun accept(ticket: Ticket<P>?, submit: Runnable?): Boolean {
        checkThread()
        if (!current(ticket)) return false
        submit!!.run()
        return true
    }

    fun submitted(ticket: Ticket<P>?, jobId: String?): Boolean {
        checkThread()
        if (!current(ticket)) return false
        if (jobId == null || jobId.isEmpty() || ticket!!.jobId.isNotEmpty())
            throw IllegalStateException("SCHEDULED_BACKUP_JOB_ID")
        ticket.jobId = jobId
        return true
    }

    fun jobId(ticket: Ticket<P>?): String {
        checkThread()
        return ticket?.jobId ?: ""
    }

    fun owns(ticket: Ticket<P>?, currentJobId: String?): Boolean {
        checkThread()
        val id = jobId(ticket)
        return id.isNotEmpty() && id == currentJobId
    }

    fun finish(ticket: Ticket<P>?): Boolean {
        checkThread()
        if (!current(ticket)) return false
        active = null
        return true
    }

    fun stop(parameters: P): Ticket<P>? {
        checkThread()
        val current = active
        if (current == null || current.parameters !== parameters) return null
        val stopped = active
        active = null
        return stopped
    }

    fun stopCurrent(): Ticket<P>? {
        checkThread()
        val stopped = active
        active = null
        return stopped
    }
}
