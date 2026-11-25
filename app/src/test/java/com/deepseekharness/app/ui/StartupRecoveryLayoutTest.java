package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.StartupRecoveryModel;
import com.deepseekharness.app.util.StartupRecoveryState;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

/**
 * 启动恢复页的操作行只放动作本身（UI 基准 ①）：效果说明收进行尾 ⓘ，行内只留「不可用：原因」
 * 这类状态；分组说明也不再默认铺在页面上（审计 B-05/B-06）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = StartupRecoveryLayoutTest.App.class)
public final class StartupRecoveryLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private String language;

  @Before
  public void pinChinese() {
    // UiText 语言是进程级静态状态，其它 UI 测试可能改成英文；这里固定在中文再比对文案。
    language = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(language);
  }

  private static StartupRecoveryLayout layout() {
    return new StartupRecoveryLayout(RuntimeEnvironment.getApplication(), () -> {}, () -> {});
  }

  @Test
  public void actionRowShowsOnlyTheActionAndKeepsEffectBehindInfo() {
    StartupRecoveryLayout page = layout();
    AtomicBoolean clicked = new AtomicBoolean();
    View row =
        page.action(
            page.recovery,
            "安全启动",
            "只加载官方基础界面；插件开关与配置原样保留。",
            "",
            false,
            R.drawable.ic_recovery_shield,
            () -> clicked.set(true),
            true);
    assertEquals("行描述只放动作标题", "安全启动", row.getContentDescription().toString());
    assertFalse("效果说明不再默认铺在页面上", pageTexts(page).contains("只加载官方基础界面；插件开关与配置原样保留。"));
    ImageButton info = findInfo(row);
    assertNotNull("功能说明保留 ⓘ 入口", info);
    assertTrue(info.getContentDescription().toString().startsWith("查看说明"));
    assertTrue(info.isEnabled());
    row.performClick();
    assertTrue("整行仍是动作入口", clicked.get());
  }

  @Test
  public void blockedRowKeepsReasonVisibleAndInfoReadable() {
    StartupRecoveryLayout page = layout();
    View row =
        page.action(
            page.recovery,
            "恢复最近健康快照",
            "只覆盖配置文件；会话、工作区和插件文件不动。",
            "没有可用的健康快照",
            true,
            R.drawable.ic_recovery_clock,
            () -> {},
            true);
    assertTrue(row.getContentDescription().toString().contains("不可用：没有可用的健康快照"));
    assertNotNull("不可用原因仍要可读", findText(row, "不可用：没有可用的健康快照"));
    assertFalse("禁用行不能触发动作", row.isEnabled());
    ImageButton info = findInfo(row);
    assertNotNull(info);
    assertTrue("禁用行仍可点开效果说明", info.isEnabled());
  }

  @Test
  public void statusOnlyRowHasNoInfoIcon() {
    StartupRecoveryLayout page = layout();
    View row =
        page.action(page.plugins, "example-plugin · 卸载", "", "本次启动报错", false, 0, () -> {}, true);
    assertNotNull(findText(row, "本次启动报错"));
    assertNull("没有效果说明就不要多余入口", findInfo(row));
  }

  @Test
  public void snapshotGroupHintMovesBehindInfoInsteadOfDefaultText() {
    StartupRecoveryLayout page = layout();
    String hint = "保留三次健康启动与三次修复前快照；仅保存配置，不复制会话、工作区或依赖目录。";
    assertFalse("分组说明默认不显示", pageTexts(page).contains(hint));
    assertNotNull("分组标题旁仍能打开说明", findInfo((View) page.snapshots.getParent()));
  }

  @Test
  public void bannerShowsResultAndHidesWhenCleared() {
    StartupRecoveryLayout page = layout();
    page.banner(StartupRecoveryState.Tone.BAD, "配置修复未完成");
    assertEquals("配置修复未完成", page.progress.getText().toString());
    assertEquals(View.VISIBLE, page.progress.getVisibility());
    page.banner(StartupRecoveryState.Tone.IDLE, "");
    assertEquals(View.GONE, page.progress.getVisibility());
    assertEquals(1f, page.progress.getAlpha(), 0.001f);
  }

  @Test
  public void migrationReviewOpensRetainedCopiesAndKeepsHostBackupReachable() throws Exception {
    var activity = Robolectric.buildActivity(StartupRecoveryActivity.class).get();
    var app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    setField(activity, "controller", new HarnessController(app));
    setField(activity, "model", new StartupRecoveryModel(app));
    var renderStatus =
        StartupRecoveryActivity.class.getDeclaredMethod(
            "renderStatus",
            StartupRecoveryState.Kind.class,
            StartupRecoveryState.Failure.class,
            boolean.class,
            org.json.JSONObject.class,
            String.class);
    var renderActions =
        StartupRecoveryActivity.class.getDeclaredMethod(
            "renderActions",
            StartupRecoveryState.Kind.class,
            StartupRecoveryState.Failure.class,
            boolean.class,
            org.json.JSONObject.class);
    renderStatus.setAccessible(true);
    renderActions.setAccessible(true);
    String code = "RC5_RECHECK_SNAPSHOT_UNVERIFIED";
    var failure = StartupRecoveryState.classify("migration review", "", false, code);
    for (String locale : List.of("zh", "en", "ar")) {
      UiText.setLanguage(locale);
      StartupRecoveryLayout page = layout();
      setField(activity, "layout", page);
      renderStatus.invoke(activity, failure.kind, failure, false, null, "");
      renderActions.invoke(activity, failure.kind, failure, false, null);
      assertNotNull(findText(page.status, StartupRecoveryState.migrationReviewReason()));
      assertNotNull(findText(page.status, StartupRecoveryState.migrationReviewDetail(code)));
      TextView retained = findText(page.status, UiText.text("保留副本与旧环境"));
      assertTrue(retained instanceof Button && retained.isEnabled() && retained.performClick());
      ShadowActivity shadow = Shadow.extract(activity);
      assertEquals(
          RetainedDataActivity.class.getName(),
          shadow.getNextStartedActivity().getComponent().getClassName());
      View backup = findDescription(page.tools, UiText.text("宿主备份与只读救援"));
      assertTrue(backup != null && backup.isEnabled() && backup.performClick());
      assertEquals(
          NativeDataActivity.class.getName(),
          shadow.getNextStartedActivity().getComponent().getClassName());
      assertNull(findText(page.root, UiText.text("安全启动")));
      assertNull(findText(page.root, UiText.text("重试启动")));
      System.out.printf("migration navigation locale=%s retained=1 nativeBackup=1%n", locale);
    }

    UiText.setLanguage("zh");
    StartupRecoveryLayout other = layout();
    setField(activity, "layout", other);
    var failed = StartupRecoveryState.classify("regular failure", "", false);
    renderStatus.invoke(activity, failed.kind, failed, false, null, "");
    renderActions.invoke(activity, failed.kind, failed, false, null);
    assertNotNull(findText(other.root, "安全启动"));
    assertNotNull(findText(other.root, "重试启动"));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static View findDescription(View root, String label) {
    if (root.getContentDescription() != null && label.contentEquals(root.getContentDescription()))
      return root;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private static ImageButton findInfo(View root) {
    if (root instanceof ImageButton button) {
      CharSequence description = button.getContentDescription();
      if (description != null && description.toString().startsWith("查看说明")) return button;
    }
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        ImageButton found = findInfo(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  private static TextView findText(View root, String text) {
    if (root instanceof TextView view && text.contentEquals(view.getText())) return view;
    if (root instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static List<String> pageTexts(StartupRecoveryLayout page) {
    List<String> values = new ArrayList<>();
    collect((ViewGroup) page.root, values);
    return values;
  }

  private static void collect(ViewGroup root, List<String> values) {
    for (int i = 0; i < root.getChildCount(); i++) {
      View child = root.getChildAt(i);
      if (child instanceof TextView view) values.add(view.getText().toString());
      if (child instanceof ViewGroup group) collect(group, values);
    }
  }
}
