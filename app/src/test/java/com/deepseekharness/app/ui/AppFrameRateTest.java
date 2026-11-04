package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** 验证窗口请求而非虚构面板 FPS；硬件帧率与掉帧仍由设备验收。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppFrameRateTest {
  public static class Page extends Activity {}

  @Test
  public void visiblePageReactsToPeakAndPowerSaverAndClearsOnPause() {
    var controller = Robolectric.buildActivity(Page.class).setup();
    Page page = controller.get();
    page.setContentView(new FrameLayout(page));
    Display display = page.getWindowManager().getDefaultDisplay();
    Display.Mode current = display.getMode();
    Display.Mode[] modes = {
      mode(current.getModeId(), 60f, current), mode(2, 90f, current),
      mode(3, 120f, current), mode(4, 144f, current)
    };
    ShadowDisplayManager.setSupportedModes(display.getDisplayId(), modes);
    Settings.System.putFloat(page.getContentResolver(), "peak_refresh_rate", 144f);
    AppFrameRate rates = new AppFrameRate();
    rates.resume(page);
    try {
      assertEquals(144f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      Settings.System.putFloat(page.getContentResolver(), "peak_refresh_rate", 90f);
      idle();
      assertEquals(90f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      PowerManager power = page.getSystemService(PowerManager.class);
      ((ShadowPowerManager) Shadow.extract(power)).setIsPowerSaveMode(true);
      RuntimeEnvironment.getApplication()
          .sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
      idle();
      assertEquals(0f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      ((ShadowPowerManager) Shadow.extract(power)).setIsPowerSaveMode(false);
      RuntimeEnvironment.getApplication()
          .sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
      idle();
      assertEquals(90f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      rates.pause(page);
      assertEquals(0f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      Settings.System.putFloat(page.getContentResolver(), "peak_refresh_rate", 120f);
      idle();
      assertEquals(0f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      rates.resume(page);
      assertEquals(120f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
      ShadowDisplayManager.setSupportedModes(display.getDisplayId(), modes[0]);
      idle();
      assertEquals(0f, page.getWindow().getAttributes().preferredRefreshRate, .01f);
    } finally {
      rates.pause(page);
      controller.pause().stop().destroy();
    }
  }

  private static void idle() {
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  private static Display.Mode mode(int id, float rate, Display.Mode current) {
    return ReflectionHelpers.callStaticMethod(
        ShadowDisplayManager.class,
        "displayModeOf",
        ClassParameter.from(int.class, id),
        ClassParameter.from(int.class, current.getPhysicalWidth()),
        ClassParameter.from(int.class, current.getPhysicalHeight()),
        ClassParameter.from(float.class, rate));
  }
}
