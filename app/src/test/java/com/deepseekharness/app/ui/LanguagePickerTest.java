package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.LocaleManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.SystemLanguage;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 语言选择网格：11 个选项（跟随系统 + 十种语言）都建出来，且在平板与手机尺寸下都能完整排下。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = LanguagePickerTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LanguagePickerTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    static final String SETTINGS = "settings";

    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      setContentView(new FrameLayout(this));
    }
  }

  private static void collect(View view, List<View> out) {
    if (view.getTag() instanceof String && view.isClickable()) out.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
  }

  private static ScrollView findScroll(View view) {
    if (view instanceof ScrollView scroll) return scroll;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView found = findScroll(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private void assertAllOptionsFit() {
    Host host = Robolectric.buildActivity(Host.class).setup().visible().get();
    LanguagePicker.show(host, UiLanguagePreference.SYSTEM);
    org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(
            Looper.getMainLooper())
        .idle();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    View decor = dialog.getWindow().getDecorView();
    List<View> tiles = new ArrayList<>();
    collect(decor, tiles);
    List<String> values = new ArrayList<>();
    for (View tile : tiles) values.add((String) tile.getTag());
    List<String> expected = new ArrayList<>();
    expected.add(UiLanguagePreference.SYSTEM);
    expected.addAll(UiLanguagePreference.LANGUAGES);
    assertEquals(expected, values);
    ScrollView scroll = findScroll(decor);
    assertNotNull(scroll);
    View content = scroll.getChildAt(0);
    // 内容不超过可视高度：最后一格（Français）不需要滚动就能看到。
    assertTrue(
        "content " + content.getHeight() + " > viewport " + scroll.getHeight(),
        content.getHeight() <= scroll.getHeight());
    for (View tile : tiles) assertTrue(tile.getHeight() > 0);
    dialog.dismiss();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-xhdpi")
  public void tabletShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
  public void smallPhoneShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }

  @Test
  @Config(qualifiers = "en-rUS-w400dp-h800dp-xhdpi")
  public void phoneShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-xhdpi")
  public void sameEffectiveLocaleReopensTheCurrentSelectionOnTheSameSettingsView()
      throws Exception {
    var app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    var config = new ConfigStore(app);
    String original = config.getUiLanguagePreference();
    String originalUiText = UiText.language();
    Locale originalLocale = Locale.getDefault();
    var reset = SystemLanguage.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    var initialize =
        SystemLanguage.class.getDeclaredMethod("initializeFromSystemTag", String.class);
    initialize.setAccessible(true);
    reset.invoke(null);
    initialize.invoke(null, "zh-CN");
    config.setUiLanguage(UiLanguagePreference.ZH);
    LanguageController.apply(app);
    try (var controller = Robolectric.buildActivity(Host.class).setup().visible()) {
      Host host = controller.get();
      SettingsFragment settings = new SettingsFragment();
      host.getSupportFragmentManager()
          .beginTransaction()
          .add(android.R.id.content, settings, Host.SETTINGS)
          .commitNow();
      View initialView = settings.requireView();
      assertEquals("简体中文", summary(initialView));

      pick(open(initialView, R.id.settings_appearance, "zh"), "system");
      assertEquals("system", config.getUiLanguagePreference());
      assertEquals("zh", config.getUiLanguage());
      assertSame(initialView, settings.requireView());
      assertEquals("简体中文", summary(initialView));

      // Reuse the same view and the other click target, as on the tablet after dismissing.
      pick(open(initialView, R.id.settings_language, "system"), "zh");
      assertEquals("zh", config.getUiLanguagePreference());
      assertSame(initialView, settings.requireView());
      assertEquals("简体中文", summary(initialView));
      open(initialView, R.id.settings_appearance, "zh").dismiss();
      ShadowLooper.shadowMainLooper().idle();
    } finally {
      config.setUiLanguage(original);
      LanguageController.apply(app);
      reset.invoke(null);
      Locale.setDefault(originalLocale);
      UiText.setLanguage(originalUiText);
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-xhdpi")
  public void differentEffectiveLocaleAppliesPlatformLocaleAndRestoredSettingsUsesIt()
      throws Exception {
    var app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    var config = new ConfigStore(app);
    String original = config.getUiLanguagePreference();
    String originalUiText = UiText.language();
    Locale originalLocale = Locale.getDefault();
    var reset = SystemLanguage.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    config.setUiLanguage("zh");
    LanguageController.apply(app);
    try (var controller = Robolectric.buildActivity(Host.class).setup().visible()) {
      Host host = controller.get();
      SettingsFragment settings = new SettingsFragment();
      host.getSupportFragmentManager()
          .beginTransaction()
          .add(android.R.id.content, settings, Host.SETTINGS)
          .commitNow();
      View initialView = settings.requireView();
      pick(open(initialView, R.id.settings_appearance, "zh"), "en");
      assertEquals("en", config.getUiLanguagePreference());
      assertEquals("en", UiText.language());
      assertEquals(
          "en", app.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());

      // Model the configuration-driven Activity recreation delivered by Android.
      controller.recreate();
      View restoredView =
          controller
              .get()
              .getSupportFragmentManager()
              .findFragmentByTag(Host.SETTINGS)
              .requireView();
      assertNotSame(initialView, restoredView);
      assertEquals("English", summary(restoredView));
      open(restoredView, R.id.settings_appearance, "en").dismiss();
      ShadowLooper.shadowMainLooper().idle();
    } finally {
      config.setUiLanguage(original);
      LanguageController.apply(app);
      reset.invoke(null);
      Locale.setDefault(originalLocale);
      UiText.setLanguage(originalUiText);
    }
  }

  private static String summary(View root) {
    return ((TextView) root.findViewById(R.id.settings_language)).getText().toString();
  }

  private static AlertDialog open(View root, int id, String selected) {
    assertTrue(root.findViewById(id).performClick());
    ShadowLooper.shadowMainLooper().idle();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertTrue(dialog.isShowing());
    List<View> tiles = new ArrayList<>();
    collect(dialog.getWindow().getDecorView(), tiles);
    assertEquals(1 + UiLanguagePreference.LANGUAGES.size(), tiles.size());
    List<Object> checked = new ArrayList<>();
    for (View tile : tiles) {
      if (tile.createAccessibilityNodeInfo().isChecked()) checked.add(tile.getTag());
    }
    assertEquals(List.of(selected), checked);
    return dialog;
  }

  private static void pick(AlertDialog dialog, String preference) {
    List<View> tiles = new ArrayList<>();
    collect(dialog.getWindow().getDecorView(), tiles);
    View match =
        tiles.stream().filter(tile -> preference.equals(tile.getTag())).findFirst().orElseThrow();
    assertTrue(match.performClick());
    ShadowLooper.shadowMainLooper().idle();
    assertFalse(dialog.isShowing());
  }
}
