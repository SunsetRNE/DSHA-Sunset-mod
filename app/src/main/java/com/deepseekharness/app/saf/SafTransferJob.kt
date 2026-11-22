package com.deepseekharness.app.saf

/** 一次传输的顺序与收尾：先确认目标和记录再写入，失败不自动重放。 */
class SafTransferJob private constructor() {
    interface Ports<T> {
        fun enabled(): Boolean

        @Throws(Exception::class) fun openHistory()

        @Throws(Exception::class) fun retarget(selection: T, path: String?): T

        fun path(ticket: T): String?

        @Throws(Exception::class)
        fun beginRecord(name: String?, importing: Boolean, time: Long): String?

        @Throws(Exception::class)
        fun transfer(ticket: T, guestPath: String?, importing: Boolean): Long

        @Throws(Exception::class)
        fun finishRecord(record: String?, bytes: Long, code: String?): Boolean

        fun cancel(ticket: T)

        fun failureCode(error: Exception): String? = SafTransferJob.failureCode(error)
    }

    class Result private constructor(@JvmField val bytes: Long, @JvmField val code: String?) {
        companion object {
            @JvmSynthetic
            internal fun create(bytes: Long, code: String?): Result = Result(bytes, code)
        }
    }

    companion object {
        @JvmStatic
        fun <T> run(
            ports: Ports<T>?,
            selection: T,
            remotePath: String?,
            guestPath: String?,
            importing: Boolean,
            time: Long,
        ): Result {
            var ticket = selection
            var record: String? = null
            var bytes = 0L
            var code: String?
            try {
                if (!ports!!.enabled()) throw IllegalStateException("WORKBENCH_DISABLED")
                ports.openHistory()
                if (!importing) ticket = ports.retarget(selection, remotePath)
                val path = ports.path(ticket)!!
                record =
                    ports.beginRecord(path.substring(path.lastIndexOf('/') + 1), importing, time)
                bytes = ports.transfer(ticket, guestPath, importing)
                code = "OK"
            } catch (failure: Exception) {
                code = ports!!.failureCode(failure)
            } finally {
                ports!!.cancel(selection)
                if (ticket !== selection) ports.cancel(ticket)
            }
            if (record != null) {
                val saved =
                    try {
                        ports.finishRecord(record, bytes, code)
                    } catch (unavailable: Exception) {
                        false
                    }
                if (!saved && code == "OK") code = "OK_HISTORY_UNAVAILABLE"
            }
            return Result.create(bytes, code)
        }

        /** 只接受完整短码，不持久化可能含路径或凭据的异常正文。 */
        @JvmStatic
        fun failureCode(error: Exception?): String {
            val code = error!!.message
            return if (code != null && code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) code
            else "IO_ERROR"
        }
    }
}
