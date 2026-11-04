package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import com.deepseekharness.app.backup.PortablePreferenceProjection;
import com.deepseekharness.app.util.Constants;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class ScreenFrameSettingsTest {
  @Test
  public void actualStoreDefaultsStrictAndRevocationCannotResurrectOldRevision() {
    var context = org.robolectric.RuntimeEnvironment.getApplication();
    var settings = new ConfigStore(context);
    assertEquals("", settings.screenAnimatedPackages());
    assertEquals(0, settings.screenAnimatedPackagesRevision());
    assertTrue(settings.setScreenAnimatedPackages("com.example.game"));
    var first = settings.screenFrameSettings();
    assertTrue(first.animated("com.example.game"));
    assertFalse(first.animated("com.example.game.billing"));
    assertTrue(settings.setScreenAnimatedPackages("com.example.game\ncom.example.game"));
    assertEquals(first, settings.screenFrameSettings());
    assertTrue(settings.setScreenAnimatedPackages(""));
    assertTrue(settings.setScreenAnimatedPackages("com.example.game"));
    assertTrue(settings.screenAnimatedPackagesRevision() > first.revision());
    assertNotEquals(first, settings.screenFrameSettings());
    assertThrows(IllegalArgumentException.class, () -> settings.setScreenAnimatedPackages("*"));
    assertEquals("com.example.game", settings.screenAnimatedPackages());
  }

  @Test
  public void malformedSettingsFailClosedAndPortableProjectionCannotTransferOptIn()
      throws Exception {
    var context = org.robolectric.RuntimeEnvironment.getApplication();
    var prefs = context.getSharedPreferences(Constants.PREFS, 0);
    prefs
        .edit()
        .putString("screen_animated_packages", "com.example.game")
        .putString("screen_animated_packages_revision", "forged")
        .commit();
    assertFalse(new ConfigStore(context).screenFrameSettings().animated("com.example.game"));
    assertFalse(PortablePreferenceProjection.transferable("screen_animated_packages"));
    assertFalse(PortablePreferenceProjection.transferable("screen_animated_packages_revision"));
    assertTrue(
        PortablePreferenceProjection.project(
                Map.of(
                    "screen_animated_packages",
                    "com.example.game",
                    "screen_animated_packages_revision",
                    2L))
            .isEmpty());
    var config = new ConfigStore(context);
    assertFalse(config.hostSettingsState().containsKey("screen_animated_packages"));
    assertThrows(
        java.io.IOException.class,
        () -> config.applyHostSettings(Map.of("screen_animated_packages", "com.example.game")));
  }
}
