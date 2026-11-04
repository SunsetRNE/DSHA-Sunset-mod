package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleExec;
import com.deepseekharness.app.module.ModuleScheduler;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 定时任务触发的两条硬规则：未授权任务只发「跳过」通知、绝不静默执行；
 * 卡片已被移除的任务连记录一起撤销，不留指向不存在模块的通知。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleScheduleReceiverTest {

  private Context context;

  @Before
  public void setUp() {
    // 通知文案跟随应用语言；单测不依赖运行器默认系统语言。
    com.deepseekharness.app.util.UiText.setLanguage("zh");
    context = RuntimeEnvironment.getApplication();
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"pet.task\",\"name\":\"任务\",\"actions\":[]}");
  }

  private ModuleScheduler.Entry entry(int hour, int minute, String kind, String value) {
    JSONObject args = new JSONObject();
    try {
      args.put("hour", hour);
      args.put("minute", minute);
      args.put("kind", kind);
      args.put("value", value);
    } catch (org.json.JSONException impossible) {
      throw new AssertionError(impossible);
    }
    return ModuleScheduler.set(context, "pet.task", args);
  }

  private Notification solePosted() {
    StatusBarNotification[] active =
        context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
    assertEquals(1, active.length);
    return active[0].getNotification();
  }

  @Test
  public void unauthorizedTaskIsSkippedWithVisibleReason() {
    ModuleScheduler.Entry task = entry(7, 30, "exec", "echo hi");
    assertFalse(ModuleExec.isAllowed(context, "pet.task"));
    ModuleScheduleReceiver.run(context, task);
    String text = String.valueOf(solePosted().extras.getCharSequence(Notification.EXTRA_TEXT));
    assertTrue(text, text.contains("跳过"));
  }

  @Test
  public void removedCardDropsTaskSilentlyWithoutNotification() {
    ModuleScheduler.Entry task = entry(8, 0, "dsh.start", "");
    ModuleCardStore.remove(context, "pet.task");
    ModuleScheduleReceiver.run(context, task);
    // 记录被撤掉（含触发前排上的明天），也不留通知。
    assertNull(ModuleScheduler.byId(context, task.id));
    assertEquals(
        0,
        context
            .getSystemService(android.app.NotificationManager.class)
            .getActiveNotifications()
            .length);
  }

  @Test
  public void disabledCardIsSkippedAndResultIsPersisted() {
    ModuleScheduler.Entry task = entry(9, 0, "exec", "echo hi");
    ModuleExec.setAllowed(context, "pet.task", true);
    com.deepseekharness.app.module.ModuleState.setEnabled(context, "pet.task", false);
    ModuleScheduleReceiver.run(context, task);
    String text = String.valueOf(solePosted().extras.getCharSequence(Notification.EXTRA_TEXT));
    assertTrue(text, text.contains("关闭") || text.contains("disabled"));
    // 即使通知权限被拒绝，模块中心也要能从落盘结果看到这次跳过。
    ModuleScheduler.Result last = ModuleScheduler.lastResult(context, task.id);
    assertTrue(last != null && !last.ok);
    assertTrue(last.text.contains("关闭") || last.text.contains("disabled"));
  }
}
