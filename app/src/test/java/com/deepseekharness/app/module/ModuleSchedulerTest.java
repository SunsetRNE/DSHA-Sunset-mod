package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.content.Context;
import com.deepseekharness.app.util.ModuleSchedulePolicy;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 定时任务存储与闹钟登记：登记/更新/上限/删除、重启重排、孤儿撤销。
 *
 * <p>AlarmManager 用 Robolectric 影子：只断言「有没有登记、类型与幂等」，不断言绝对触发时刻。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleSchedulerTest {

  private Context context;

  private org.robolectric.shadows.ShadowAlarmManager alarms() {
    return (org.robolectric.shadows.ShadowAlarmManager)
        org.robolectric.shadow.api.Shadow.extract(context.getSystemService(AlarmManager.class));
  }

  @Before
  public void setUp() {
    // RuntimeEnvironment 的 Application 才带 base context；new Application() 没挂 base，
    // getFilesDir() 会直接炸。
    context = RuntimeEnvironment.getApplication();
    // 卡片要在真实目录里：孤儿判定靠 ModuleCardStore.loadAll 读这个目录。
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"pet.a\",\"name\":\"甲\",\"actions\":[]}");
  }

  /** 移除卡片后，它名下的定时任务在下一次清理时被撤掉，不残留孤儿。 */
  @Test
  public void removingTheCardDropsItsTasksOnNextSweep() {
    ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    assertEquals(1, ModuleScheduler.forCard(context, "pet.a").size());
    ModuleCardStore.remove(context, "pet.a");
    assertEquals(1, ModuleScheduler.dropOrphans(context));
    assertTrue(ModuleScheduler.forCard(context, "pet.a").isEmpty());
    assertEquals(0, ModuleScheduler.dropOrphans(context));
  }

  private JSONObject args(int hour, int minute, String kind, String value, String label) {
    JSONObject json = new JSONObject();
    try {
      json.put("hour", hour);
      json.put("minute", minute);
      json.put("kind", kind);
      json.put("value", value);
      json.put("label", label);
    } catch (org.json.JSONException impossible) {
      throw new AssertionError(impossible);
    }
    return json;
  }

  @Test
  public void setListsAndClearsPerCard() {
    ModuleScheduler.Entry first =
        ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    assertEquals("pet.a", first.cardId);
    assertTrue(first.id.startsWith("pet.a#"));
    assertEquals("晨检", first.label);
    ModuleScheduler.set(context, "pet.a", args(22, 0, "dsh.stop", "", "睡觉"));
    Map<String, ModuleScheduler.Entry> entries = ModuleScheduler.forCard(context, "pet.a");
    assertEquals(2, entries.size());
    assertEquals(
        "dsh.stop",
        entries.get(ModuleSchedulePolicy.scheduleId("pet.a", "dsh.stop", "", 22, 0)).kind);

    assertTrue(ModuleScheduler.clear(context, "pet.a", first.id));
    assertFalse(ModuleScheduler.clear(context, "pet.a", first.id));
    assertEquals(1, ModuleScheduler.forCard(context, "pet.a").size());
    ModuleScheduler.clearForCard(context, "pet.a");
    assertTrue(ModuleScheduler.forCard(context, "pet.a").isEmpty());
    assertNull(ModuleScheduler.byId(context, first.id));
  }

  @Test
  public void sameSlotSetAgainIsAnUpdateNotADuplicate() {
    ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "改名"));
    Map<String, ModuleScheduler.Entry> entries = ModuleScheduler.forCard(context, "pet.a");
    assertEquals(1, entries.size());
    assertEquals("改名", entries.values().iterator().next().label);
  }

  @Test
  public void perCardLimitCountsOnlyNewEntries() {
    for (int minute = 0; minute < ModuleSchedulePolicy.MAX_PER_CARD; minute++) {
      ModuleScheduler.set(
          context, "pet.a", args(7, minute, "exec", "echo " + minute, "t" + minute));
    }
    // 真正的新任务被上限挡住。
    try {
      ModuleScheduler.set(context, "pet.a", args(9, 0, "exec", "echo more", "第9条"));
      fail("超过每卡上限必须拒绝");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("最多"));
    }
    // 已有任务的更新不受上限影响（改 label、同槽位）。
    ModuleScheduler.set(context, "pet.a", args(7, 3, "exec", "echo 3", "改名"));
    assertEquals(
        ModuleSchedulePolicy.MAX_PER_CARD, ModuleScheduler.forCard(context, "pet.a").size());
  }

  @Test
  public void rejectsInvalidInputAndMissingCardIdentity() {
    try {
      ModuleScheduler.set(context, "pet.a", args(24, 0, "exec", "x", "t"));
      fail("hour 越界必须拒绝");
    } catch (IllegalArgumentException expected) {
      // 人话错误
    }
    try {
      ModuleScheduler.set(context, "pet.a", args(7, 0, "dsh.restart", "", "t"));
      fail("未知 kind 必须拒绝");
    } catch (IllegalArgumentException expected) {
      // 人话错误
    }
    try {
      ModuleScheduler.set(context, "", args(7, 0, "exec", "x", "t"));
      fail("没有卡片身份不能登记");
    } catch (IllegalArgumentException expected) {
      // 人话错误
    }
    assertTrue(ModuleScheduler.forCard(context, "pet.a").isEmpty());
  }

  @Test
  public void setArmsOneIdleAlarmAndClearCancelsIt() {
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    ModuleScheduler.Entry entry =
        ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    var scheduled = alarms().getScheduledAlarms();
    assertEquals(1, scheduled.size());
    assertEquals(AlarmManager.RTC_WAKEUP, scheduled.get(0).type);
    assertTrue(scheduled.get(0).allowWhileIdle);
    // 不申请精确闹钟：触发时刻必须在未来（跨天语义由 ModuleSchedulePolicyTest 覆盖）。
    assertTrue(scheduled.get(0).triggerAtTime > System.currentTimeMillis());
    // 同一任务重复登记是更新：还是一条闹钟。
    ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    assertEquals(1, alarms().getScheduledAlarms().size());
    // 删除前按 id 能取回任务，删除后取不到，闹钟一并撤销。
    assertNotNull(ModuleScheduler.byId(context, entry.id));
    ModuleScheduler.clear(context, "pet.a", entry.id);
    assertNull(ModuleScheduler.byId(context, entry.id));
    assertEquals(0, alarms().getScheduledAlarms().size());
  }

  @Test
  public void dropIfCardMissingOnlyForGhosts() {
    ModuleScheduler.Entry alive =
        ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    ModuleScheduler.Entry ghost =
        ModuleScheduler.set(context, "pet.ghost", args(8, 0, "dsh.start", "", "开机"));
    assertFalse(ModuleScheduler.dropIfCardMissing(context, alive));
    assertTrue(ModuleScheduler.dropIfCardMissing(context, ghost));
    assertTrue(ModuleScheduler.forCard(context, "pet.ghost").isEmpty());
  }

  @Test
  public void rearmAllDropsOrphansAndRearmsTheRest() {
    ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    ModuleScheduler.set(context, "pet.ghost", args(8, 0, "dsh.start", "", "开机"));
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    assertEquals(2, alarms().getScheduledAlarms().size());
    assertEquals(1, ModuleScheduler.dropOrphans(context));
    assertEquals(1, ModuleScheduler.forCard(context, "pet.a").size());
    assertTrue(ModuleScheduler.forCard(context, "pet.ghost").isEmpty());
    // 再排一次孤儿也不会重复删；重排后闹钟只剩存活任务的一条。
    assertEquals(0, ModuleScheduler.dropOrphans(context));
    ModuleScheduler.rearmAll(context);
    assertEquals(1, alarms().getScheduledAlarms().size());
  }

  @Test
  public void lastResultRoundTripsAndClearsWithTask() {
    ModuleScheduler.Entry entry =
        ModuleScheduler.set(context, "pet.a", args(7, 30, "exec", "uname -m", "晨检"));
    assertNull(ModuleScheduler.lastResult(context, entry.id));
    ModuleScheduler.recordResult(context, entry, true, "  完成：ok  ");
    ModuleScheduler.Result result = ModuleScheduler.lastResult(context, entry.id);
    assertNotNull(result);
    assertTrue(result.ok);
    assertEquals("完成：ok", result.text);
    assertTrue(result.at > 0);
    ModuleScheduler.clear(context, "pet.a", entry.id);
    assertNull(ModuleScheduler.lastResult(context, entry.id));
  }
}
