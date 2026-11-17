package com.deepseekharness.app.util

import java.time.Instant
import java.time.ZoneId

/** 日历计划按本地时区计算；到期时繁忙则延后，不中断用户工作。 */
class AutomaticBackupPolicy private constructor() {
    companion object {
        @JvmStatic
        fun due(
            mode: String?,
            intervalHours: Int,
            minuteOfDay: Int,
            now: Long,
            anchor: Long,
            last: Long,
            stopped: Long,
            zone: ZoneId?,
        ): Boolean {
            val since = maxOf(anchor, last)
            if (now < since) return false
            if (mode == "stop") return stopped > since && now >= stopped
            if (mode == "interval") return now - since >= intervalHours.coerceIn(1, 168) * 3_600_000L
            val start = Instant.ofEpochMilli(since).atZone(zone)
            val minute = minuteOfDay.coerceIn(0, 1439)
            var next = start.toLocalDate().atTime(minute / 60, minute % 60).atZone(zone)
            if (!next.isAfter(start)) {
                next = next.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(zone)
            }
            return now >= next.toInstant().toEpochMilli()
        }
    }
}
