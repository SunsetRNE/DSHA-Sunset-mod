package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 能力总线的授权门：每个写/执行能力都按卡片单独授权（ModuleExec.isAllowed），
 * 未授权必须在进处理器之前被拒绝——不执行命令、不登记任务、不碰容器生命周期。
 * 只读能力（schedule.list 等）不设门。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleServicesGateTest {

  private Context context;
  private ModuleCard card;

  private org.robolectric.shadows.ShadowAlarmManager alarms() {
    return (org.robolectric.shadows.ShadowAlarmManager)
        org.robolectric.shadow.api.Shadow.extract(context.getSystemService(AlarmManager.class));
  }

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"pet.gate\",\"name\":\"门\",\"actions\":[]}");
    card = ModuleCardStore.loadById(context).get("user:pet.gate");
    assertNotNull(card);
  }

  @Test
  public void unauthorizedExecRunIsRejectedBeforeAnyExecution() {
    assertFalse(ModuleExec.isAllowed(context, "pet.gate"));
    String result = ModuleServices.call(context, card, "exec.run", "{\"command\":\"uname -m\"}");
    assertTrue(result, result.startsWith("[未授权]"));
  }

  @Test
  public void unauthorizedScheduleSetIsRejectedWithoutRegistering() {
    String result =
        ModuleServices.call(
            context,
            card,
            "schedule.set",
            "{\"hour\":7,\"minute\":30,\"kind\":\"exec\",\"value\":\"echo hi\"}");
    assertTrue(result, result.startsWith("[未授权]"));
    assertTrue(ModuleScheduler.forCard(context, "pet.gate").isEmpty());
  }

  @Test
  public void unauthorizedDshStopIsRejected() {
    String result = ModuleServices.call(context, card, "dsh.stop", "{}");
    assertTrue(result, result.startsWith("[未授权]"));
  }

  @Test
  public void unknownServiceIsNamedNotSilent() {
    String result = ModuleServices.call(context, card, "dsh.restart", "{}");
    assertTrue(result, result.startsWith("[没有这个能力]"));
  }

  @Test
  public void authorizedScheduleSetArmsDailyTaskAndListClearWork() {
    ModuleExec.setAllowed(context, "pet.gate", true);
    String set =
        ModuleServices.call(
            context,
            card,
            "schedule.set",
            "{\"hour\":7,\"minute\":30,\"kind\":\"exec\",\"value\":\"echo hi\",\"label\":\"晨检\"}");
    assertTrue(set, set.startsWith("ok："));
    // 登记成功时必须向用户说明 Doze 可能推迟触发。
    assertTrue(set, set.contains("推迟"));
    assertEquals(1, ModuleScheduler.forCard(context, "pet.gate").size());
    assertEquals(1, alarms().getScheduledAlarms().size());

    String list = ModuleServices.call(context, card, "schedule.list", "");
    assertTrue(list, list.contains("\"kind\":\"exec\""));

    String cleared = ModuleServices.call(context, card, "schedule.clear", "{}");
    assertEquals("已清除 1 条定时任务", cleared);
    assertTrue(ModuleScheduler.forCard(context, "pet.gate").isEmpty());
    assertEquals(0, alarms().getScheduledAlarms().size());
  }

  @Test
  public void cardlessCallsStayGatedWhileReadOnlyListStaysOpen() {
    // 只读能力没有卡片身份也开放。
    assertEquals("[]", ModuleServices.call(context, null, "schedule.list", ""));
    // 写能力没有卡片身份无法绑定授权，必须拒绝。
    String set =
        ModuleServices.call(
            context,
            null,
            "schedule.set",
            "{\"hour\":7,\"minute\":30,\"kind\":\"exec\",\"value\":\"x\"}");
    assertTrue(set, set.startsWith("[未授权]"));
  }
}
