package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.GrantSummary;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * 设备能力授权页压缩版：手机单栏不横向溢出、概览头部固定在滚动区之外、档位与确认方式为三段选择；
 * 内容宽 ≥640dp 时屏幕操作与敏感能力移到右栏。Root 未启用时点放宽档不会保存。
 *
 * <p>mdpi 下 1dp = 1px。只验证布局与本地状态，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DeviceGrantsLayoutTest.App.class,
    shadows = DeviceGrantsLayoutTest.NoStorageManager.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeviceGrantsLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** Robolectric 没有存储卷，原生 isExternalStorageManager 会越界；这里按「未授予」处理。 */
  @Implements(android.os.Environment.class)
  public static final class NoStorageManager {
    @Implementation
    protected static boolean isExternalStorageManager() {
      return false;
    }
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;

  @Before
  public void language() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  private View start(int width) {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new DeviceGrantsFragment())
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
    View page =
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).getView();
    layout(page, width);
    return page;
  }

  private static void layout(View root, int width) {
    for (int pass = 0; pass < 4; pass++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, width, root.getMeasuredHeight());
      ShadowLooper.shadowMainLooper().idle();
    }
  }

  private static void collectOverflow(View view, int left, int width, List<String> out) {
    if (view.getVisibility() != View.VISIBLE) return;
    int start = left + view.getLeft();
    int end = start + view.getWidth();
    if (start < -1 || end > width + 1) {
      String label =
          view instanceof TextView ? String.valueOf(((TextView) view).getText()) : view.toString();
      out.add(label + " [" + start + "," + end + "]");
      return;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        collectOverflow(group.getChildAt(i), start - view.getScrollX(), width, out);
  }

  private static List<Integer> sectionOrder(ViewGroup column) {
    List<Integer> ids = new ArrayList<>();
    for (int i = 0; i < column.getChildCount(); i++) ids.add(column.getChildAt(i).getId());
    return ids;
  }

  @Test
  public void phoneIsOneColumnWithPinnedSummaryAndSegments() {
    View page = start(360);
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 360, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());

    // 概览头部在滚动区之外，滚动内容时保持可见；胶囊放进 PluginFlowRow 按内容自适应宽度。
    View header = page.findViewById(R.id.grants_header);
    View scroll = page.findViewById(R.id.grants_scroll);
    assertSame(page, header.getParent());
    assertSame(page, scroll.getParent());
    assertTrue(header.getBottom() <= scroll.getTop());
    // 头部 = 胶囊流式区 + 固定在行尾的页面说明 ⓘ。
    assertEquals(2, ((ViewGroup) header).getChildCount());
    ViewGroup pills = page.findViewById(R.id.grants_pills);
    assertTrue(pills instanceof PluginFlowRow);
    assertEquals(4, pills.getChildCount());
    assertTrue(((ViewGroup) header).getChildAt(1) instanceof ImageButton);
    assertSummaryPillsFit(pills, "zh");

    assertEquals(
        List.of(
            R.id.grants_section_global,
            R.id.grants_section_channels,
            R.id.grants_section_screen,
            R.id.grants_section_system,
            R.id.grants_section_sensitive),
        sectionOrder(page.findViewById(R.id.grants_col_start)));
    assertEquals(View.GONE, page.findViewById(R.id.grants_col_end).getVisibility());

    RadioGroup root = page.findViewById(R.id.device_root_modes);
    assertEquals(3, root.getChildCount());
    assertTrue(((RadioButton) root.getChildAt(0)).isChecked());
    // 全局权限卡追加到槽位里，不再按页面下标插入。
    assertEquals(1, ((ViewGroup) page.findViewById(R.id.grants_global_slot)).getChildCount());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h1200dp-mdpi")
  public void wideContentMovesScreenAndSensitiveToSecondColumn() {
    View page = start(900);
    ViewGroup end = page.findViewById(R.id.grants_col_end);
    assertEquals(View.VISIBLE, end.getVisibility());
    assertEquals(
        List.of(R.id.grants_section_screen, R.id.grants_section_sensitive), sectionOrder(end));
    assertEquals(
        List.of(
            R.id.grants_section_global, R.id.grants_section_channels, R.id.grants_section_system),
        sectionOrder(page.findViewById(R.id.grants_col_start)));
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 900, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  /**
   * 紧凑行式：Root / ADB 的开关与标题同一行；屏幕设置入口是状态行行尾的短按钮，虚拟识屏并入屏幕卡。
   * 高度预算按 mdpi、360dp 宽、全部未开启的默认状态；Robolectric 字体行高偏大，预算按其实测值加余量。
   */
  @Test
  public void channelAndScreenSectionsUseCompactRows() {
    View page = start(360);
    // 标题与状态叠成左侧两行，开关在同一行右侧。
    View rootSwitch = page.findViewById(R.id.config_root_shell);
    assertSame(
        page.findViewById(R.id.device_root_title).getParent().getParent(), rootSwitch.getParent());
    assertSame(
        page.findViewById(R.id.device_root_title).getParent(),
        page.findViewById(R.id.device_root_status).getParent());
    assertSame(
        page.findViewById(R.id.config_adb_title).getParent().getParent(),
        page.findViewById(R.id.config_adb_enable).getParent());
    assertSame(
        page.findViewById(R.id.config_a11y_status).getParent(),
        page.findViewById(R.id.computer_use_accessibility).getParent());
    assertSame(
        page.findViewById(R.id.screen_card),
        page.findViewById(R.id.screen_virtual_group).getParent());
    int channels = page.findViewById(R.id.grants_section_channels).getHeight();
    int screen = page.findViewById(R.id.grants_section_screen).getHeight();
    assertTrue("channels too tall: " + channels, channels <= 460);
    assertTrue("screen too tall: " + screen, screen <= 400);
  }

  @Test
  public void relaxedSegmentDoesNotSaveWhileRootIsDisabled() {
    Context context = org.robolectric.RuntimeEnvironment.getApplication();
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false)
        .commit();
    new ConfigStore(context).setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    View page = start(360);
    RadioGroup root = page.findViewById(R.id.device_root_modes);
    root.getChildAt(1).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(DeviceShellPolicy.Mode.STRICT, new ConfigStore(context).rootPolicyMode());
    assertTrue(((RadioButton) root.getChildAt(0)).isChecked());
    assertFalse(((RadioButton) root.getChildAt(1)).isChecked());
  }

  /**
   * 十语种状态词：中文保留「关闭 / 關閉」，其余语种用「已关闭」译条（x1970619），
   * 不能复用「关闭按钮」词条，否则会得到 Close / Fermer / Закрыть 这类动词译法。
   */
  @Test
  public void offStateWordUsesOffTranslationsInEveryLanguage() {
    java.util.Map<String, String> expected =
        java.util.Map.of(
            "zh", "关闭",
            "zh-Hant", "關閉",
            "en", "Off",
            "ru", "Выключено",
            "ja", "無効",
            "ko", "꺼짐",
            "ar", "معطّل",
            "pt", "Desativado",
            "la", "Inactivum",
            "fr", "Désactivé");
    for (var entry : expected.entrySet()) {
      UiText.setLanguage(entry.getKey());
      assertEquals(
          entry.getKey(),
          entry.getValue(),
          DeviceGrantsFragment.summaryText(GrantSummary.Label.GLOBAL_OFF));
    }
  }

  /** 长译文语种（fr / ru / pt）在 320dp 窄屏下胶囊自适应宽度，状态词不出现省略号。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void summaryPillsFitLongTranslationsAtNarrowWidth() {
    for (String language : new String[] {"fr", "ru", "pt"}) {
      UiText.setLanguage(language);
      View page = start(320);
      List<String> overflow = new ArrayList<>();
      collectOverflow(page, 0, 320, overflow);
      assertTrue(language + " horizontal overflow: " + overflow, overflow.isEmpty());
      assertSummaryPillsFit((ViewGroup) page.findViewById(R.id.grants_pills), language);
      host.pause().stop().destroy();
      host = null;
    }
  }

  /** 1.3 倍字体 + 320dp + 最长译文（ru）也不截断。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void summaryPillsFitAtLargeFontScale() {
    org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
    try {
      UiText.setLanguage("ru");
      View page = start(320);
      assertSummaryPillsFit((ViewGroup) page.findViewById(R.id.grants_pills), "ru@1.3");
    } finally {
      org.robolectric.RuntimeEnvironment.setFontScale(1f);
    }
  }

  /** 短屏（≤480dp）概览条并入滚动区：不再吸顶，避免固定头部挤占正文。 */
  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h400dp-mdpi")
  public void shortScreenPutsSummaryHeaderIntoScroll() {
    View page = start(360);
    View header = page.findViewById(R.id.grants_header);
    ViewGroup content = page.findViewById(R.id.grants_content);
    ViewGroup scroll = page.findViewById(R.id.grants_scroll);
    assertNotSame(page, header.getParent());
    assertSame(content, header.getParent());
    assertEquals(0, content.indexOfChild(header));
    assertSame(scroll, content.getParent());
    assertEquals(2, ((ViewGroup) header).getChildCount());
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 360, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
    assertSummaryPillsFit((ViewGroup) page.findViewById(R.id.grants_pills), "short");
  }

  /**
   * 每个胶囊按内容自适应宽度（不再是等分权重），状态允许换行且与标题左对齐，文本无省略号。
   *
   * <p>Robolectric 默认（LEGACY）字体宽度是近似值，这里主要锁定结构契约；像素级十语种是否截断仍需
   * 真机 LayoutAuditInstrumentation 或截图核对（本次未跑真机）。
   */
  private static void assertSummaryPillsFit(ViewGroup header, String language) {
    for (int i = 0; i < 4; i++) {
      ViewGroup pill = (ViewGroup) header.getChildAt(i);
      TextView title = (TextView) ((ViewGroup) pill.getChildAt(0)).getChildAt(1);
      TextView value = (TextView) pill.getChildAt(1);
      assertEquals(
          language + " pill " + i + " must wrap content",
          ViewGroup.LayoutParams.WRAP_CONTENT,
          pill.getLayoutParams().width);
      assertTrue(language + " value " + i + " must wrap lines", value.getMaxLines() >= 2);
      assertEquals(
          language + " value " + i + " must not indent",
          0,
          ((ViewGroup.MarginLayoutParams) value.getLayoutParams()).getMarginStart());
      assertEquals(language + " title " + i, 0, ellipsisCount(title));
      assertEquals(language + " value " + i + ": " + value.getText(), 0, ellipsisCount(value));
      assertTrue(
          language + " pill " + i + " clipped",
          pill.getLeft() >= 0 && pill.getRight() <= header.getWidth());
    }
  }

  private static int ellipsisCount(TextView view) {
    android.text.Layout layout = view.getLayout();
    assertNotNull(String.valueOf(view.getText()), layout);
    int total = 0;
    for (int line = 0; line < layout.getLineCount(); line++) total += layout.getEllipsisCount(line);
    return total;
  }
}
