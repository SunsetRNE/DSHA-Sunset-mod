package com.deepseekharness.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** 在 Java 实现上冻结的边界表；迁移后沿用相同输入和期望，不重生成 oracle。 */
class AutomaticBackupPolicyGoldenTest {
    private fun at(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun due(
        mode: String?,
        now: Long,
        anchor: Long,
        last: Long = 0,
        stopped: Long = 0,
        hours: Int = 24,
        minute: Int = 180,
        zone: ZoneId? = ZoneOffset.UTC,
    ): Boolean = AutomaticBackupPolicy.due(mode, hours, minute, now, anchor, last, stopped, zone)

    @Test
    fun intervalClampsAndUsesTheLaterAnchorOrCompletion() {
        val hour = 3_600_000L
        for (hours in listOf(Int.MIN_VALUE, -1, 0, 1, 24, 168, 169, Int.MAX_VALUE)) {
            val interval = hours.coerceIn(1, 168) * hour
            assertEquals("before interval $hours", false, due("interval", 100 + interval - 1, 100, hours = hours))
            assertEquals("at interval $hours", true, due("interval", 100 + interval, 100, hours = hours))
        }
        assertEquals(false, due("interval", 200 + hour - 1, 100, last = 200, hours = 1))
        assertEquals(true, due("interval", 200 + hour, 100, last = 200, hours = 1))
        assertEquals(false, due("interval", 99, 100, hours = 1))
        assertEquals(true, due("interval", 100 + hour, 100, hours = 1, zone = null))
    }

    @Test
    fun stopRequiresANewerStopAndDoesNotConsumeAFutureStop() {
        data class Case(val now: Long, val anchor: Long, val last: Long, val stop: Long, val expected: Boolean)
        val cases = listOf(
            Case(300, 100, 0, 200, true),
            Case(200, 100, 0, 200, true),
            Case(199, 100, 0, 200, false),
            Case(300, 100, 200, 200, false),
            Case(400, 100, 300, 200, false),
            Case(99, 100, 0, 200, false),
            Case(100, 100, 0, 100, false),
        )
        for (case in cases) assertEquals(case.toString(), case.expected, due("stop", case.now, case.anchor, case.last, case.stop, zone = null))
    }

    @Test
    fun dailyBoundaryClampsMinutesAndUnknownModesRemainDaily() {
        val start = at("2026-09-17T00:00:00Z")
        for (mode in listOf("daily", "unknown", "", "STOP", null)) {
            assertEquals(false, due(mode, start + 3 * 3_600_000 - 1, start))
            assertEquals(true, due(mode, start + 3 * 3_600_000, start))
        }
        assertEquals(false, due("daily", start + 86_400_000 - 1, start, minute = -1))
        assertEquals(true, due("daily", start + 86_400_000, start, minute = Int.MIN_VALUE))
        assertEquals(false, due("daily", start + 86_340_000 - 1, start, minute = Int.MAX_VALUE))
        assertEquals(true, due("daily", start + 86_340_000, start, minute = 1440))
        assertEquals(false, due("daily", start + 3 * 3_600_000, start + 3 * 3_600_000))
    }

    @Test
    fun springGapShiftsByTheGapAndUsesCalendarDays() {
        val ny = ZoneId.of("America/New_York")
        val start = at("2026-03-08T06:00:00Z")
        assertEquals(false, due("daily", at("2026-03-08T07:29:59.999Z"), start, minute = 150, zone = ny))
        assertEquals(true, due("daily", at("2026-03-08T07:30:00Z"), start, minute = 150, zone = ny))
        val last = at("2026-03-08T07:30:00Z")
        assertEquals(false, due("daily", at("2026-03-09T06:29:59.999Z"), start, last, minute = 150, zone = ny))
        assertEquals(true, due("daily", at("2026-03-09T06:30:00Z"), start, last, minute = 150, zone = ny))
    }

    @Test
    fun fallOverlapUsesTheEarlierOffsetAndDoesNotRunTwice() {
        val ny = ZoneId.of("America/New_York")
        val start = at("2026-11-01T04:00:00Z")
        assertEquals(false, due("daily", at("2026-11-01T05:29:59.999Z"), start, minute = 90, zone = ny))
        assertEquals(true, due("daily", at("2026-11-01T05:30:00Z"), start, minute = 90, zone = ny))
        assertEquals(false, due("daily", at("2026-11-01T06:30:00Z"), start, at("2026-11-01T05:30:00Z"), minute = 90, zone = ny))
        assertEquals(true, due("daily", at("2026-11-02T06:30:00Z"), start, at("2026-11-01T05:30:00Z"), minute = 90, zone = ny))
    }

    @Test
    fun halfHourGapAndTimezoneChangesUseTheSuppliedZone() {
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        val start = at("2026-10-03T14:00:00Z")
        assertEquals(false, due("daily", at("2026-10-03T15:44:59.999Z"), start, minute = 135, zone = lordHowe))
        assertEquals(true, due("daily", at("2026-10-03T15:45:00Z"), start, minute = 135, zone = lordHowe))
        val anchor = at("2026-09-17T00:00:00Z")
        val now = at("2026-09-17T03:00:00Z")
        assertEquals(true, due("daily", now, anchor, zone = ZoneOffset.UTC))
        assertEquals(false, due("daily", now, anchor, zone = ZoneId.of("Asia/Shanghai")))
    }
}
