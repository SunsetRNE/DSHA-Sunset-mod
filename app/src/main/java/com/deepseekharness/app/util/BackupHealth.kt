// 保留原 Java 方法 flags；类本身仍为 final，不开放继承。
@file:Suppress("NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

/**
 * 数据与备份状态只根据已记录事实分级，不推断内容是否完整。
 *
 * 优先级保持：运行中 > 维护未完成 > 上次失败 > 从未备份 > 较久未备份 > 已保护。
 */
class BackupHealth private constructor() {
    enum class Level {
        RUNNING,
        PENDING,
        FAILED,
        NEVER,
        STALE,
        PROTECTED,
    }

    companion object {
        const val STALE_DAYS: Int = 7
        private const val DAY: Long = 24L * 60 * 60 * 1000

        @JvmStatic
        open fun evaluate(
            lastSuccess: Long,
            lastError: String?,
            busy: Boolean,
            pending: Boolean,
            now: Long,
        ): Level {
            if (busy) return Level.RUNNING
            if (pending) return Level.PENDING
            if (lastError != null && lastError.isNotEmpty()) return Level.FAILED
            if (lastSuccess <= 0) return Level.NEVER
            // 保留 Java long 减法及溢出语义；不增加新的时间归一化规则。
            if (now - lastSuccess > STALE_DAYS * DAY) return Level.STALE
            return Level.PROTECTED
        }

        /** 完整天数；未备份或时钟回拨返回零。 */
        @JvmStatic
        open fun ageDays(lastSuccess: Long, now: Long): Long {
            if (lastSuccess <= 0 || now <= lastSuccess) return 0
            return (now - lastSuccess) / DAY
        }

        @JvmStatic
        open fun severity(level: Level?): Int =
            when (level ?: throw NullPointerException()) {
                Level.PROTECTED,
                Level.RUNNING -> 0
                Level.STALE,
                Level.NEVER -> 1
                Level.FAILED,
                Level.PENDING -> 2
            }
    }
}
