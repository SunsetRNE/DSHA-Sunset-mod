package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * F4 设置页布局接入：真实 fragment_settings 里三组都有稳定 id，标题可折叠、分组正文随之收起；
 * 短屏 + 1.3 倍字体下标题与页签不横向溢出。只验证布局与本地状态，不启动 Harness。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = SettingsGroupsLayoutTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SettingsGroupsLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class ObservedSettings extends SettingsFragment {
    private int languageWrites;
    private int themeWrites;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
      View root = super.onCreateView(inflater, container, state);
      observeWrites(root.findViewById(R.id.settings_language), () -> languageWrites++);
      observeWrites(root.findViewById(R.id.settings_theme_sub), () -> themeWrites++);
      return root;
    }
  }

  private ContextThemeWrapper context;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  @After
  public void tearDown() {
    RuntimeEnvironment.setFontScale(1f);
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void threeGroupsCollapseIndependently() {
    ScrollView scroll = inflateSettings();
    GroupedList list = install(scroll, new GroupedList.Memory());
    assertNotNull(list);
    assertEquals(List.of("environment", "experience", "support"), list.keys());

    TextView supportHeader = scroll.findViewById(R.id.settings_section_support);
    View supportGroup = scroll.findViewById(R.id.settings_group_support);
    supportHeader.performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(View.GONE, supportGroup.getVisibility());
    assertEquals("已收起", supportHeader.getStateDescription().toString());
    assertEquals(View.VISIBLE, scroll.findViewById(R.id.settings_group_experience).getVisibility());

    supportHeader.performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(View.VISIBLE, supportGroup.getVisibility());
  }

  @Test
  public void collapsedStateIsRestoredFromMemory() {
    ScrollView scroll = inflateSettings();
    GroupedList.Memory memory = new GroupedList.Memory();
    install(scroll, memory).toggle("experience");
    android.os.Bundle saved = new android.os.Bundle();
    memory.save(saved, "groups");
    GroupedList.Memory restoredMemory = GroupedList.Memory.restore(saved, "groups");
    ScrollView second = inflateSettings();
    GroupedList restored = install(second, restoredMemory);
    assertTrue(restored.collapsed("experience"));
    assertEquals(View.GONE, second.findViewById(R.id.settings_group_experience).getVisibility());
  }

  @Test
  public void stickyHeaderDrawsAndReceivesTouchesAboveFollowingBody() {
    ScrollView scroll = inflateSettings();
    GroupedList list = install(scroll, new GroupedList.Memory());
    TextView header = scroll.findViewById(R.id.settings_section_experience);
    View body = scroll.findViewById(R.id.settings_group_experience);
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
    scroll.layout(0, 0, 320, 240);
    float restingZ = header.getZ();

    scroll.scrollTo(0, body.getTop() + 20);
    list.refresh();
    assertTrue("header overlaps its body while pinned", header.getY() >= body.getTop());
    assertTrue("pinned header must be above its following body", header.getZ() > body.getZ());

    list.toggle("experience");
    assertEquals(restingZ, header.getZ(), 0f);
  }

  @Test
  public void powerRowTitlesToggleTheSameMutuallyExclusiveControls() {
    DshaApp app = (DshaApp) RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    try (var controller = Robolectric.buildActivity(FragmentActivity.class)) {
      FragmentActivity host = controller.get();
      host.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      ConfigStore config = new ConfigStore(host);
      config.setEcoMode(false);
      config.setKeepScreenOn(false);
      SettingsFragment settings = new SettingsFragment();
      host.getSupportFragmentManager()
          .beginTransaction()
          .add(android.R.id.content, settings)
          .commitNow();
      View root = settings.requireView();

      root.findViewById(R.id.settings_keep_screen_on).performClick();
      assertTrue(config.isKeepScreenOn());
      assertFalse(config.isEcoMode());

      root.findViewById(R.id.settings_power).performClick();
      assertFalse(config.isKeepScreenOn());
      assertTrue(config.isEcoMode());

      root.findViewById(R.id.settings_power).performClick();
      assertFalse(config.isKeepScreenOn());
      assertFalse(config.isEcoMode());
    }
  }

  @Test
  @Config(qualifiers = "w1360dp-h900dp-land")
  public void resumeOnlyWritesAppearanceSummariesWhenValuesChange() {
    RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir =
        RuntimeEnvironment.getApplication().getCacheDir().getAbsolutePath();
    try (var controller = Robolectric.buildActivity(FragmentActivity.class)) {
      FragmentActivity host = controller.get();
      host.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      ConfigStore config = new ConfigStore(host);
      config.setUiLanguage("zh");
      config.setUiPalette("standard");
      ObservedSettings settings = new ObservedSettings();
      host.getSupportFragmentManager()
          .beginTransaction()
          .add(android.R.id.content, settings)
          .commitNow();
      assertTrue(settings.isTwoPane());
      assertEquals("首次恢复保留已显示的语言摘要", 0, settings.languageWrites);
      assertEquals("首次恢复保留已显示的主题摘要", 0, settings.themeWrites);

      controller.pause();
      config.setUiLanguage("en");
      config.setUiPalette("sakura");
      controller.resume();
      TextView language = settings.requireView().findViewById(R.id.settings_language);
      TextView theme = settings.requireView().findViewById(R.id.settings_theme_sub);
      assertEquals(UiLanguagePreference.nativeName("en"), language.getText().toString());
      assertTrue(theme.getText().toString().endsWith(ThemeActivity.paletteName("sakura")));
      assertEquals(1, settings.languageWrites);
      assertEquals(1, settings.themeWrites);

      controller.pause().resume();
      assertEquals(1, settings.languageWrites);
      assertEquals(1, settings.themeWrites);
    }
  }

  @Test
  public void technicalSummaryDisplaysThePackagedVersion() throws Exception {
    RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir =
        RuntimeEnvironment.getApplication().getCacheDir().getAbsolutePath();
    try (var controller = Robolectric.buildActivity(FragmentActivity.class)) {
      FragmentActivity host = controller.get();
      host.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      SettingsFragment settings = new SettingsFragment();
      host.getSupportFragmentManager()
          .beginTransaction()
          .add(android.R.id.content, settings)
          .commitNow();
      String installed =
          host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
      assertEquals(BuildConfig.VERSION_NAME, installed);
      TextView summary = settings.requireView().findViewById(R.id.settings_ver);
      assertEquals(UiText.format("技术说明 · DSHA v%s", installed), summary.getText().toString());
    }
  }

  @Test
  public void compactWidthWithLargeFontHasNoHorizontalOverflow() {
    RuntimeEnvironment.setFontScale(1.3f);
    ScrollView scroll = inflateSettings();
    install(scroll, new GroupedList.Memory());
    int width = 320;
    scroll.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST));
    scroll.layout(0, 0, width, scroll.getMeasuredHeight());
    ShadowLooper.shadowMainLooper().idle();
    java.util.List<String> overflow = new java.util.ArrayList<>();
    collectOverflow(scroll, 0, width, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
    for (int id :
        new int[] {
          R.id.settings_section_environment,
          R.id.settings_section_experience,
          R.id.settings_section_support
        }) {
      TextView header = scroll.findViewById(id);
      assertNotNull(header);
      assertTrue(header.getMeasuredWidth() > 0);
    }
  }

  private ScrollView inflateSettings() {
    View view = LayoutInflater.from(context).inflate(R.layout.fragment_settings, null, false);
    return (ScrollView) view;
  }

  private static void observeWrites(TextView view, Runnable count) {
    view.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence text, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable text) {
            count.run();
          }
        });
  }

  private GroupedList install(ScrollView scroll, GroupedList.Memory memory) {
    return GroupedList.install(
        scroll,
        List.of(
            new GroupedList.Group(
                "environment",
                scroll.findViewById(R.id.settings_section_environment),
                scroll.findViewById(R.id.settings_tabs)),
            new GroupedList.Group(
                "experience",
                scroll.findViewById(R.id.settings_section_experience),
                scroll.findViewById(R.id.settings_group_experience)),
            new GroupedList.Group(
                "support",
                scroll.findViewById(R.id.settings_section_support),
                scroll.findViewById(R.id.settings_group_support))),
        memory,
        null);
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
    if (view instanceof android.view.ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++)
        collectOverflow(group.getChildAt(index), start - view.getScrollX(), width, out);
  }
}
