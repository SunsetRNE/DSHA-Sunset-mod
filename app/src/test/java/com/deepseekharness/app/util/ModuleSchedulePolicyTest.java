package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import org.junit.Test;

/** 定时任务契约的硬边界：kind 白名单、时间合法、下次触发落在明天且整分。 */
public final class ModuleSchedulePolicyTest {

  // ------------------------------------------------------------ validate

  @Test
  public void acceptsExecTask() {
    assertNull(ModuleSchedulePolicy.validate(7, 30, "exec", "uname -m"));
    assertNull(ModuleSchedulePolicy.validate(0, 0, "exec", "x"));
    assertNull(ModuleSchedulePolicy.validate(23, 59, "exec", "  tail -1 /var/log  "));
  }

  @Test
  public void acceptsContainerLifecycleKindsWithoutValue() {
    assertNull(ModuleSchedulePolicy.validate(8, 0, "dsh.start", ""));
    assertNull(ModuleSchedulePolicy.validate(22, 0, "dsh.stop", ""));
    assertNull(ModuleSchedulePolicy.validate(8, 0, "dsh.start", null));
  }

  @Test
  public void rejectsOutOfRangeTime() {
    assertEquals("hour 要在 0-23 之间", ModuleSchedulePolicy.validate(-1, 0, "exec", "x"));
    assertEquals("hour 要在 0-23 之间", ModuleSchedulePolicy.validate(24, 0, "exec", "x"));
    assertEquals("minute 要在 0-59 之间", ModuleSchedulePolicy.validate(7, 60, "exec", "x"));
    assertEquals("minute 要在 0-59 之间", ModuleSchedulePolicy.validate(7, -1, "exec", "x"));
  }

  @Test
  public void rejectsUnknownKindAndMisplacedValue() {
    assertTrue(ModuleSchedulePolicy.validate(7, 0, "dsh.restart", "").startsWith("kind 只能是"));
    assertTrue(ModuleSchedulePolicy.validate(7, 0, null, "").startsWith("kind 只能是"));
    assertEquals("dsh.start 不需要 value", ModuleSchedulePolicy.validate(7, 0, "dsh.start", "x"));
    assertEquals("exec 需要 value（要跑的命令）", ModuleSchedulePolicy.validate(7, 0, "exec", "  "));
    assertTrue(ModuleSchedulePolicy.validate(7, 0, "exec", "x".repeat(501)).startsWith("命令太长"));
    assertNull(ModuleSchedulePolicy.validate(7, 0, "exec", "x".repeat(500)));
  }

  // ------------------------------------------------------------ nextTriggerMillis

  @Test
  public void nextTriggerIsTodayWhenStillAhead() {
    long now = millis(2026, Calendar.NOVEMBER, 1, 6, 0, 1);
    long next = ModuleSchedulePolicy.nextTriggerMillis(now, 7, 30);
    assertEquals(millis(2026, Calendar.NOVEMBER, 1, 7, 30, 0), next);
  }

  @Test
  public void nextTriggerIsTomorrowWhenTimePassed() {
    long now = millis(2026, Calendar.NOVEMBER, 1, 8, 0, 0);
    long next = ModuleSchedulePolicy.nextTriggerMillis(now, 7, 30);
    assertEquals(millis(2026, Calendar.NOVEMBER, 2, 7, 30, 0), next);
  }

  @Test
  public void exactNowFiresTomorrowSoItCanNeverFireTwiceInARow() {
    long now = millis(2026, Calendar.NOVEMBER, 1, 7, 30, 0);
    long next = ModuleSchedulePolicy.nextTriggerMillis(now, 7, 30);
    assertEquals(millis(2026, Calendar.NOVEMBER, 2, 7, 30, 0), next);
  }

  @Test
  public void secondsAndMillisAreZeroed() {
    long now = millis(2026, Calendar.NOVEMBER, 1, 6, 0, 999);
    long next = ModuleSchedulePolicy.nextTriggerMillis(now, 23, 59);
    assertEquals(0, next % 60000);
  }

  private static long millis(int year, int month, int day, int hour, int minute, int second) {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(year, month, day, hour, minute, second);
    return calendar.getTimeInMillis();
  }

  // ------------------------------------------------------------ scheduleId

  @Test
  public void scheduleIdIsDeterministicAndScoped() {
    String first = ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo hi", 7, 30);
    String again = ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo hi", 7, 30);
    assertEquals(first, again);
    assertTrue(first.startsWith("pet.a#"));
    assertEquals("pet.a#".length() + 8, first.length());
    // 任何输入维度变化都要换 id：不同任务互相覆盖就是丢任务。
    assertNotEquals(first, ModuleSchedulePolicy.scheduleId("pet.b", "exec", "echo hi", 7, 30));
    assertNotEquals(first, ModuleSchedulePolicy.scheduleId("pet.a", "dsh.start", "", 7, 30));
    assertNotEquals(first, ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo bye", 7, 30));
    assertNotEquals(first, ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo hi", 7, 31));
    assertNotEquals(first, ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo hi", 8, 30));
  }

  @Test
  public void distinctTasksDoNotShareIds() {
    // 每张卡每天最多 8 条，但取一整分钟的槽位批量验证两两不同，防哈希截取撞车。
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int minute = 0; minute < 60; minute++) {
      String id = ModuleSchedulePolicy.scheduleId("pet.a", "exec", "echo " + minute, 7, minute);
      assertTrue(seen.add(id));
    }
  }
}
