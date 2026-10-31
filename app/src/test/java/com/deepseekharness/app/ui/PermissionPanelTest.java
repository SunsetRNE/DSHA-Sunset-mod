package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSettings;

/**
 * 真实 AppCompatActivity 宿主上的授权卡片组：构造、复查与推荐标记。只读系统状态，不点击任何外跳，
 * 不初始化 HarnessController（Root 开关回调才会用到，测试不触发）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PermissionPanelTest {
  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;
  private Context app;

  @Before
  public void prepare() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    app = RuntimeEnvironment.getApplication();
    // 首次安装：七类授权全部未开启（Robolectric 默认未授予“所有文件访问”）。
    ShadowSettings.setCanDrawOverlays(false);
    battery(false);
    org.robolectric.shadows.ShadowApplication application =
        org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication());
    application.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    Settings.Secure.putString(
        app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
    app.getSharedPreferences(PermissionPanel.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    host = Robolectric.buildActivity(Host.class).setup();
  }

  private void battery(boolean unrestricted) {
    org.robolectric.shadows.ShadowPowerManager power =
        org.robolectric.shadow.api.Shadow.extract(app.getSystemService(PowerManager.class));
    power.setIgnoringBatteryOptimizations(app.getPackageName(), unrestricted);
  }

  private static Intent startedActivity(AppCompatActivity activity) {
    org.robolectric.shadows.ShadowActivity shadow =
        org.robolectric.shadow.api.Shadow.extract(activity);
    return shadow.getNextStartedActivity();
  }

  @After
  public void close() {
    host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  private PermissionPanel panel(Set<String> goals) {
    PermissionPanel panel = new PermissionPanel(host.get(), goals, false);
    host.get().setContentView(panel.view());
    ShadowLooper.idleMainLooper();
    return panel;
  }

  private static List<String> texts(View view) {
    List<String> out = new ArrayList<>();
    collect(view, out);
    return out;
  }

  private static void collect(View view, List<String> out) {
    if (view instanceof TextView) out.add(String.valueOf(((TextView) view).getText()));
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        collect(((ViewGroup) view).getChildAt(i), out);
  }

  private static int count(List<String> texts, String value) {
    int n = 0;
    for (String text : texts) if (text.equals(value)) n++;
    return n;
  }

  @Test
  public void freshInstallBuildsAndRefreshesWithNothingGranted() {
    PermissionPanel panel = panel(new LinkedHashSet<>(Set.of(PermissionGoals.BASE)));
    assertEquals(0, panel.grantedCount());
    panel.refresh();
    panel.setEnvironmentReady(true);
    panel.setEnvironmentReady(false);
    ShadowLooper.idleMainLooper();
    assertEquals(0, panel.grantedCount());
    List<String> texts = texts(panel.view());
    assertTrue(texts.contains("已开启 0 / 7"));
    // 没有选择用途时不出现任何推荐标记。
    assertEquals(0, count(texts, "按你的用途推荐"));
    assertEquals(0, count(texts, "推荐"));
    // 构造与复查不得外跳到任何系统页面。
    assertNull(startedActivity(host.get()));
  }

  @Test
  public void summaryKeepsTheContractOrderAndFlags() {
    List<String[]> summary = panel(new LinkedHashSet<>()).summary();
    assertEquals(7, summary.size());
    String[] names = {"存储读写", "Shizuku / Stellar", "Root", "无障碍", "通知", "悬浮窗", "电池优化"};
    for (int i = 0; i < names.length; i++) {
      assertEquals(names[i], summary.get(i)[0]);
      assertEquals("0", summary.get(i)[1]);
    }
  }

  @Test
  public void returningFromSettingsRecountsSystemState() {
    PermissionPanel panel = panel(new LinkedHashSet<>());
    ShadowSettings.setCanDrawOverlays(true);
    battery(true);
    panel.refresh();
    assertEquals(2, panel.grantedCount());
    List<String[]> summary = panel.summary();
    assertEquals("1", summary.get(5)[1]);
    assertEquals("1", summary.get(6)[1]);
    assertTrue(texts(panel.view()).contains("已开启 2 / 7"));
  }

  @Test
  public void selectedGoalsMarkRecommendedCardsAndGroups() {
    Set<String> goals = new LinkedHashSet<>(List.of(PermissionGoals.BASE, PermissionGoals.SCREEN));
    PermissionPanel panel = panel(goals);
    List<String> texts = texts(panel.view());
    // 屏幕操作推荐无障碍、悬浮窗、通知三张卡片；分组推荐出现在基础与屏幕操作两组。
    assertEquals(3, count(texts, "按你的用途推荐"));
    assertEquals(2, count(texts, "推荐"));
    assertTrue(texts.indexOf("屏幕操作（手机任务）") < texts.indexOf("文件"));
    // 面板持有宿主的同一个 Set：就地增删后重绘即按新用途排序。
    goals.add(PermissionGoals.SHELL);
    panel.refresh();
    texts = texts(panel.view());
    assertEquals(4, count(texts, "按你的用途推荐"));
    assertTrue(texts.indexOf("设备命令通道") < texts.indexOf("文件"));
  }

  @Test
  public void unknownAccessibilityStateIsNotReportedAsOff() {
    Settings.Secure.putString(
        app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null);
    PermissionPanel panel = panel(new LinkedHashSet<>());
    List<String> texts = texts(panel.view());
    assertFalse(texts.contains("未开启 · 开启后可在对话中让助手操作屏幕"));
    assertEquals(0, panel.grantedCount());
  }

  @Test
  public void accessibilityStepsUseTheSystemServiceLabel() {
    String label = app.getString(R.string.accessibility_service_label);
    String step =
        AccessibilitySetupActivity.withServiceName(
            app, UiText.choose("请打开系统设置 → 无障碍 →「%s」。", "Open Settings → Accessibility → “%s”."));
    assertTrue(step.contains(label));
    assertFalse(step.contains("配对助手"));
  }

  private static List<String> registeredResultKeys(AppCompatActivity activity) {
    Bundle saved = new Bundle();
    activity.getActivityResultRegistry().onSaveInstanceState(saved);
    List<String> keys = saved.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS");
    List<String> ours = new ArrayList<>();
    if (keys != null)
      for (String key : keys) if (key.startsWith("dsha-onboarding-notifications-")) ours.add(key);
    return ours;
  }

  @Test
  public void notificationRequestIsRegisteredAfterStartAndReleasedOnDestroy() {
    // 宿主已经 RESUMED 后才构造：注册不依赖 STARTED 之前，也不会因此抛异常。
    panel(new LinkedHashSet<>());
    assertEquals(1, registeredResultKeys(host.get()).size());
    Host destroyed = host.get();
    host.pause().stop().destroy();
    assertTrue(registeredResultKeys(destroyed).isEmpty());
    host = Robolectric.buildActivity(Host.class).setup();
  }

  @Test
  public void onlyAClickLeavesDsha() {
    PermissionPanel panel = panel(new LinkedHashSet<>(Set.of(PermissionGoals.FILES)));
    host.pause();
    host.resume();
    ShadowLooper.idleMainLooper();
    assertNull(startedActivity(host.get()));
    View button = find(panel.view(), "去开启所有文件访问");
    assertNotNull(button);
    button.performClick();
    Intent started = startedActivity(host.get());
    assertNotNull(started);
    assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, started.getAction());
    assertEquals("package:" + app.getPackageName(), started.getDataString());
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View hit = find(((ViewGroup) view).getChildAt(i), text);
        if (hit != null) return hit;
      }
    return null;
  }
}
