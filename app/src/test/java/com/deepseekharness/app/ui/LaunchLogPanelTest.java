package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.StartupLogDigest;
import com.deepseekharness.app.util.StartupTrace;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 启动日志区（docs/design/launch-log-20261101）：概览分阶段、原始筛选、复制原文不变。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = LaunchLogPanelTest.App.class)
public final class LaunchLogPanelTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private View root;
  private LaunchLogPanel panel;

  @Before
  public void setUp() {
    UiText.setLanguage(UiLanguagePreference.ZH);
    var context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    root = LayoutInflater.from(context).inflate(R.layout.fragment_launch, null, false);
    panel = new LaunchLogPanel(root);
  }

  private static StartupTrace.Snapshot sample(boolean issue) {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.stage(1, 100, "检查旧插件文件链接");
    trace.add(1, 600, "CAS_TIMING: {\"phase\":\"scan\",\"elapsedMs\":122.07,\"entries\":16}");
    trace.stage(1, 700, "注册插件");
    trace.add(
        1,
        900,
        "STARTUP_PREP_TIMING: phase=register-builtins durationMs=683 elapsedMs=1521 outcome=returned");
    if (issue) trace.issue(1, 1200, "dsh-example-tools", "入口文件不存在");
    trace.stage(1, 1700, "启动 Web 服务");
    return trace.snapshot(2000, "zh");
  }

  private static int count(View view, Class<?> type) {
    if (!(view instanceof ViewGroup)) return type.isInstance(view) ? 1 : 0;
    int n = type.isInstance(view) ? 1 : 0;
    ViewGroup group = (ViewGroup) view;
    for (int i = 0; i < group.getChildCount(); i++) n += count(group.getChildAt(i), type);
    return n;
  }

  @Test
  public void overviewIsDefaultAndShowsOneRowPerStage() {
    StartupTrace.Snapshot s = sample(false);
    panel.render(s, StartupLogDigest.Outcome.RUNNING, s.log, "");
    LinearLayout overview = root.findViewById(R.id.launch_log_overview);
    assertEquals(View.VISIBLE, overview.getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.launch_log_raw).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.launch_log_rawbar).getVisibility());
    assertEquals(4, overview.getChildCount());
    String meta = ((TextView) root.findViewById(R.id.launch_log_meta)).getText().toString();
    assertTrue(meta, meta.startsWith("启动 Web 服务 · 2.0s"));
    String tabs = ((TextView) root.findViewById(R.id.launch_log_tab_overview)).getText().toString();
    assertTrue(tabs, tabs.contains("4"));
    String digest = ((TextView) root.findViewById(R.id.launch_log_digest)).getText().toString();
    assertTrue(digest, digest.contains("4 个阶段") && digest.contains("计时 2 条"));
  }

  @Test
  public void rawTabFiltersDisplayButCopyKeepsFullOriginal() {
    StartupTrace.Snapshot s = sample(true);
    String plain = s.log + "\n\n本机 Web 地址：http://127.0.0.1:1/";
    panel.render(s, StartupLogDigest.Outcome.RUNNING, plain, "本机 Web 地址：http://127.0.0.1:1/");
    TextView rawTab = root.findViewById(R.id.launch_log_tab_raw);
    assertTrue(rawTab.getText().toString(), rawTab.getText().toString().contains("2 问题"));
    rawTab.performClick();
    assertTrue(panel.isRawMode());
    assertEquals(View.VISIBLE, root.findViewById(R.id.launch_log_rawbar).getVisibility());
    TextView raw = root.findViewById(R.id.launch_log);
    String all = raw.getText().toString();
    assertTrue(all.contains("CAS_TIMING"));
    assertTrue(all.contains("http://127.0.0.1:1/"));
    // 时间槽替代 [0.6s] 前缀。
    assertFalse(all.contains("[0.6s]"));
    root.findViewById(R.id.launch_log_filter_issues).performClick();
    String issues = raw.getText().toString();
    assertFalse(issues.contains("CAS_TIMING"));
    assertTrue(issues.contains("入口文件不存在"));
    root.findViewById(R.id.launch_log_filter_notiming).performClick();
    String noTiming = raw.getText().toString();
    assertFalse(noTiming.contains("STARTUP_PREP_TIMING"));
    assertTrue(noTiming.contains("注册插件"));
    assertEquals(plain, panel.plainText());
  }

  @Test
  public void issueStageIsExpandedAutomatically() {
    StartupTrace.Snapshot s = sample(true);
    panel.render(s, StartupLogDigest.Outcome.RUNNING, s.log, "");
    LinearLayout overview = root.findViewById(R.id.launch_log_overview);
    ViewGroup plugins = (ViewGroup) overview.getChildAt(2);
    assertEquals(View.VISIBLE, plugins.getChildAt(1).getVisibility());
    ViewGroup links = (ViewGroup) overview.getChildAt(1);
    assertEquals(View.GONE, links.getChildAt(1).getVisibility());
    links.getChildAt(0).performClick();
    assertEquals(View.VISIBLE, links.getChildAt(1).getVisibility());
    // 同一 revision 的刷新不重建视图，用户展开状态保持。
    panel.render(s, StartupLogDigest.Outcome.RUNNING, s.log, "");
    assertSame(links, overview.getChildAt(1));
  }

  @Test
  public void readyMetaDurationIsFixedAtBackendReady() {
    // T22：后端就绪、尚未进入网页且未 completed() 时，面板每秒刷新不能让「已就绪 · 用时」增长。
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.stage(1, 100, "启动 Web 服务");
    trace.backendReady(1, 3400);
    TextView meta = root.findViewById(R.id.launch_log_meta);
    for (long now : new long[] {3400, 4400, 179_400}) {
      StartupTrace.Snapshot s = trace.snapshot(now, "zh");
      assertEquals(now, s.elapsedMs);
      panel.render(s, StartupLogDigest.Outcome.READY, s.log, "");
      assertEquals("已就绪 · 用时 3.4s", meta.getText().toString());
    }
  }

  @Test
  public void rawRuntimeLinesKeepTheirRuntimeLabelAndOriginalTimestamp() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    for (int i = 1; i <= 119; i++) trace.add(1, i, "startup sample " + i);
    trace.backendReady(1, 3000);
    trace.browserReady(1, 9400);
    trace.add(1, 930_300, "model output");
    trace.browserReady(1, 930_400);
    StartupTrace.Snapshot s = trace.snapshot(930_500, "zh");
    assertEquals(125, s.entries.size());
    panel.render(s, StartupLogDigest.Outcome.READY, s.log, "");
    root.findViewById(R.id.launch_log_tab_raw).performClick();
    String raw = logText(R.id.launch_log);
    assertTrue(
        raw.contains("9.4 BROWSER_READY: 9.4s\n启动计时截止 · 9.4s\n930.3 运行期 +920.9s · model output"));
    assertEquals("已就绪 · 用时 9.4s", logText(R.id.launch_log_meta));
    assertEquals("125 行原文", logText(R.id.launch_log_digest));
    assertEquals(s.log, panel.plainText());
    assertTrue(panel.plainText().contains("[运行 +920.9s / 自启动记录 930.3s] model output"));
    assertFalse(panel.plainText().contains("启动计时截止"));
    assertFalse(panel.plainText().contains("运行期 +"));
    trace.completed(1, 1_000_000);
    StartupTrace.Snapshot stopped = trace.snapshot(1_001_000, "zh");
    panel.render(stopped, StartupLogDigest.Outcome.STOPPED, stopped.log, "");
    assertEquals(raw, logText(R.id.launch_log));
  }

  @Test
  public void failedOrStoppedBeforeBrowserReadyUsesActualEndOfAttempt() {
    StartupTrace trace = new StartupTrace();
    int generation = 0;
    for (StartupLogDigest.Outcome outcome :
        new StartupLogDigest.Outcome[] {
          StartupLogDigest.Outcome.FAILED,
          StartupLogDigest.Outcome.STOPPED,
          // 页面状态可能在刚刚结束的快照之前读取到。
          StartupLogDigest.Outcome.READY
        }) {
      generation++;
      trace.begin(generation, 1000, false);
      trace.backendReady(generation, 2000);
      trace.completed(generation, 4200);
      trace.add(generation, 6500, "cleanup output");
      StartupTrace.Snapshot s = trace.snapshot(20_000, "zh");
      panel.render(s, outcome, s.log, "");
      root.findViewById(R.id.launch_log_tab_raw).performClick();
      assertTrue(logText(R.id.launch_log).contains("启动计时截止 · 3.2s\n 5.5 cleanup output"));
      assertFalse(logText(R.id.launch_log).contains("运行期 +"));
    }
  }

  @Test
  public void cutoffSurvivesOriginalLineRotationAndLanguageChange() {
    StartupTrace trace = new StartupTrace();
    trace.begin(1, 0, false);
    trace.browserReady(1, 9400);
    for (int i = 0; i <= 300; i++) trace.add(1, 9400 + i * 100L, "runtime sample " + i);
    UiText.setLanguage(UiLanguagePreference.EN);
    StartupTrace.Snapshot s = trace.snapshot(40_000, "en");
    assertFalse(s.log.contains("BROWSER_READY"));
    panel.render(s, StartupLogDigest.Outcome.READY, s.log, "");
    root.findViewById(R.id.launch_log_tab_raw).performClick();
    assertTrue(logText(R.id.launch_log).startsWith("Startup timing ended · 9.4s\n"));
    assertTrue(logText(R.id.launch_log).contains("39.4 Runtime +30.0s · runtime sample 300"));
    assertEquals("Ready · 9.4s", logText(R.id.launch_log_meta));
    assertEquals("300 raw lines", logText(R.id.launch_log_digest));
    UiText.setLanguage(UiLanguagePreference.AR);
    StartupTrace.Snapshot arabic = trace.snapshot(41_000, "ar");
    panel.render(arabic, StartupLogDigest.Outcome.READY, arabic.log, "");
    assertTrue(logText(R.id.launch_log).startsWith("انتهى قياس زمن بدء التشغيل · 9.4s\n"));
    assertTrue(logText(R.id.launch_log).contains("30.0s · runtime sample 300"));
    assertEquals(300, arabic.entries.size());
  }

  private String logText(int id) {
    return ((TextView) root.findViewById(id)).getText().toString();
  }

  @Test
  public void plainTextPathStillDrivesRawView() {
    panel.renderPlain("验收日志 0\n验收日志 1");
    assertTrue(panel.isRawMode());
    assertEquals(
        "验收日志 0\n验收日志 1", ((TextView) root.findViewById(R.id.launch_log)).getText().toString());
    assertEquals("验收日志 0\n验收日志 1", panel.plainText());
  }

  @Test
  public void emptyTraceShowsNeutralState() {
    StartupTrace trace = new StartupTrace();
    StartupTrace.Snapshot s = trace.snapshot(0, "zh");
    panel.render(s, StartupLogDigest.Outcome.IDLE, "还没有日志。", "");
    assertEquals("尚未启动", ((TextView) root.findViewById(R.id.launch_log_meta)).getText().toString());
    assertEquals(1, ((LinearLayout) root.findViewById(R.id.launch_log_overview)).getChildCount());
    assertTrue(count(root.findViewById(R.id.launch_log_overview), TextView.class) >= 1);
  }
}
