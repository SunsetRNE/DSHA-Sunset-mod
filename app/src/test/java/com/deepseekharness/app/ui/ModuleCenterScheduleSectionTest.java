package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleExec;
import com.deepseekharness.app.module.ModuleScheduler;
import com.deepseekharness.app.module.ModuleServices;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * 模块中心定时任务区的渲染与绑定：直接用应用上下文构造分区（不启动整个 Activity），
 * 验证任务行内容（标签 / 每日时间 / 动作 / exec 命令摘要）、删除单条、清空此模块、
 * 空列表状态与授权状态显示。操作走 ModuleScheduler 既有 API，闹钟同步撤销。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCenterScheduleSectionTest {

  private android.content.Context context;
  private ModuleCard card;
  private LinearLayout container;
  private AtomicInteger refreshed;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    UiText.setLanguage("zh");
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"pet.schedule\",\"name\":\"闹钟\",\"actions\":[]}");
    card = ModuleCardStore.loadAll(context).get(0);
    container = new LinearLayout(context);
    refreshed = new AtomicInteger();
  }

  private String schedule(String json) {
    return ModuleServices.call(context, card, "schedule.set", json);
  }

  private void render() {
    container.removeAllViews();
    ModuleCenterActivity.addScheduleSection(context, container, card, refreshed::incrementAndGet);
  }

  private LinearLayout section() {
    assertEquals("分区应整体加入父容器", 1, container.getChildCount());
    return (LinearLayout) container.getChildAt(0);
  }

  private ShadowAlarmManager alarms() {
    return (ShadowAlarmManager)
        org.robolectric.shadow.api.Shadow.extract(context.getSystemService(AlarmManager.class));
  }

  private List<TextView> textViews(View view) {
    List<TextView> found = new ArrayList<>();
    collect(view, found);
    return found;
  }

  private static void collect(View view, List<TextView> into) {
    if (view instanceof TextView) into.add((TextView) view);
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
    }
  }

  private TextView byDescription(String prefix) {
    for (TextView view : textViews(section())) {
      Object description = view.getContentDescription();
      if (description != null && description.toString().startsWith(prefix)) return view;
    }
    return null;
  }

  @Test
  public void rendersTaskLineWithWorkingDeleteBinding() {
    ModuleExec.setAllowed(context, card.id, true);
    assertTrue(
        schedule(
                "{\"hour\":7,\"minute\":30,\"kind\":\"exec\","
                    + "\"value\":\"uname -m\\n\",\"label\":\"晨检\"}")
            .startsWith("ok："));
    assertEquals(1, alarms().getScheduledAlarms().size());

    render();

    List<TextView> views = textViews(section());
    assertTrue(
        "任务行应含标签 / 每日时间 / 动作 / 命令摘要",
        views.stream()
            .anyMatch(
                v ->
                    v.getText().toString().contains("晨检")
                        && v.getText().toString().contains("每天 07:30")
                        && v.getText().toString().contains("执行命令")
                        && v.getText().toString().contains("uname -m")));
    assertTrue(
        "授权状态下应显示已授权", views.stream().anyMatch(v -> v.getText().toString().contains("已授权运行")));
    assertTrue(
        "Doze 推迟说明必须出现", views.stream().anyMatch(v -> v.getText().toString().contains("推迟")));

    TextView delete = byDescription("删除定时任务：晨检");
    assertNotNull("应有删除单条任务的按钮", delete);
    delete.performClick();
    assertEquals("删除后任务应清空", 0, ModuleScheduler.forCard(context, card.id).size());
    assertEquals("对应闹钟应同步撤销", 0, alarms().getScheduledAlarms().size());
    assertEquals("删除后应回调刷新", 1, refreshed.get());
  }

  @Test
  public void clearAllChipEmptiesEveryTaskOfThisCard() {
    ModuleExec.setAllowed(context, card.id, true);
    schedule("{\"hour\":7,\"minute\":30,\"kind\":\"exec\",\"value\":\"echo hi\"}");
    schedule("{\"hour\":8,\"minute\":0,\"kind\":\"dsh.start\"}");
    assertEquals(2, ModuleScheduler.forCard(context, card.id).size());
    assertEquals(2, alarms().getScheduledAlarms().size());

    render();

    TextView clearAll = byDescription("清空此模块的全部定时任务");
    assertNotNull("有任务时应有清空入口", clearAll);
    assertEquals("清空此模块任务", clearAll.getText().toString());
    clearAll.performClick();
    assertTrue(ModuleScheduler.forCard(context, card.id).isEmpty());
    assertEquals(0, alarms().getScheduledAlarms().size());
    assertEquals(1, refreshed.get());

    // 清空后再渲染：只留一行「无定时任务」，不再出现删除 / 清空入口。
    render();
    assertTrue(
        textViews(section()).stream().anyMatch(v -> v.getText().toString().contains("无定时任务")));
    assertNull(byDescription("清空此模块的全部定时任务"));
    assertNull(byDescription("删除定时任务："));
  }

  @Test
  public void emptyCardShowsStatusTextWithoutActionChips() {
    render();

    assertTrue(
        textViews(section()).stream().anyMatch(v -> v.getText().toString().contains("无定时任务")));
    assertNull(byDescription("清空此模块的全部定时任务"));
    assertNull(byDescription("删除定时任务："));
    // 标题写明 0 条
    assertTrue(
        textViews(section()).stream().anyMatch(v -> v.getText().toString().contains("定时任务 · 0 条")));
    // 没有任务时不再铺 Doze 说明：信息只保留一行，避免模块中心一屏全是字。
    assertFalse(textViews(section()).stream().anyMatch(v -> v.getText().toString().contains("推迟")));
    assertEquals("只渲染未触发任何操作", 0, refreshed.get());
  }

  @Test
  public void revokedAuthorizationShowsSkipStateAndNeverGrants() {
    ModuleExec.setAllowed(context, card.id, true);
    assertTrue(
        schedule("{\"hour\":9,\"minute\":15,\"kind\":\"exec\",\"value\":\"echo hi\"}")
            .startsWith("ok："));
    // 用户随后在别处撤销授权：界面必须如实显示「未授权 / 跳过」，且绝不借此授权。
    ModuleExec.setAllowed(context, card.id, false);

    render();

    List<TextView> views = textViews(section());
    assertTrue(views.stream().anyMatch(v -> v.getText().toString().contains("未授权运行")));
    assertTrue(views.stream().anyMatch(v -> v.getText().toString().contains("跳过")));
    assertTrue(
        "已登记任务仍要列出（到点会被跳过而不是静默消失）",
        views.stream().anyMatch(v -> v.getText().toString().contains("每天 09:15")));
    assertFalse("展示定时任务不得暗中授予执行权限", ModuleExec.isAllowed(context, card.id));
    assertEquals(1, ModuleScheduler.forCard(context, card.id).size());
  }
}
