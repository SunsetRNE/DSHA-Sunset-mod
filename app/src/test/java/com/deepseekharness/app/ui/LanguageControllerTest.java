package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.LocaleManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.SystemLanguage;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 原生本机语言、平台偏好和 RTL 实际同步，切换不依赖已退休的远程进程。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class LanguageControllerTest {
  private Locale original;
  private Context context;

  @Before
  public void prepare() throws Exception {
    original = Locale.getDefault();
    context = RuntimeEnvironment.getApplication();
    var reset = SystemLanguage.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    reset.invoke(null);
    var initialize =
        SystemLanguage.class.getDeclaredMethod("initializeFromSystemTag", String.class);
    initialize.setAccessible(true);
    initialize.invoke(null, "ja-JP");
  }

  @After
  public void reset() throws Exception {
    Locale.setDefault(original);
    UiText.setLanguage("zh");
    var reset = SystemLanguage.class.getDeclaredMethod("resetForTest");
    reset.setAccessible(true);
    reset.invoke(null);
  }

  @Test
  public void
      everySupportedLocalLanguageUpdatesPlatformPreferenceWithoutWritingActivityResources() {
    var config = new ConfigStore(context);
    LocaleManager manager = context.getSystemService(LocaleManager.class);
    var activity =
        new ContextWrapper(context) {
          @Override
          public Resources getResources() {
            throw new AssertionError("Only Android may update Activity resources on API 33+");
          }
        };
    for (String language : UiLanguagePreference.LANGUAGES) {
      config.setUiLanguage(language);
      LanguageController.apply(activity);
      assertEquals(language, config.getUiLanguagePreference());
      assertEquals(language, UiText.language());
      assertEquals(language, manager.getApplicationLocales().toLanguageTags());
    }
  }

  @Test
  public void switchingArabicAndFrenchKeepsNativeUiDirectionInSync() {
    LanguageController.select(context, "ar");
    assertTrue(UiText.isRtl());
    LanguageController.select(context, "fr");
    assertFalse(UiText.isRtl());
    assertEquals("fr", UiText.language());
  }

  @Test
  public void sameEffectiveLanguageStoresNewPreferenceWithoutReapplyingTheLocale() {
    var config = new ConfigStore(context);
    config.setUiLanguage("system");
    LanguageController.apply(context);
    var manager = context.getSystemService(LocaleManager.class);
    assertEquals("ja", manager.getApplicationLocales().toLanguageTags());
    var sameLanguage =
        new ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            throw new AssertionError("A preference-only change should not reapply the locale");
          }
        };
    LanguageController.select(sameLanguage, "ja");
    assertEquals("ja", config.getUiLanguagePreference());
    assertEquals("ja", UiText.language());
  }

  @Test
  public void followingSystemRemainsJapaneseAfterExplicitLanguageChanges() {
    LanguageController.select(context, "ar");
    LanguageController.select(context, "system");
    assertEquals("system", new ConfigStore(context).getUiLanguagePreference());
    assertEquals("ja", UiText.language());
    LanguageController.select(context, "fr");
    LanguageController.select(context, "system");
    assertEquals("ja", UiText.language());
    assertEquals(
        "ja",
        context.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags());
  }
}
