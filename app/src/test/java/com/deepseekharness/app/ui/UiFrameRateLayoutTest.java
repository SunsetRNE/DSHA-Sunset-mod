package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** 实际布局/生命周期与系统事件，计数策略查询；不代表设备刷新率或性能测速。 */
@RunWith(UiFrameRateTest.NoApkRunner.class)
@Config(
    sdk = {34, 35},
    manifest = Config.NONE,
    application = UiFrameRateTest.App.class,
    shadows = UiFrameRateLayoutTest.Selection.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiFrameRateLayoutTest {
  @Implements(value = WebFrameRate.class, isInAndroidSdk = false)
  public static final class Selection {
    static int reads;
    static float nextRate;

    @Implementation
    protected static float select(Activity activity, View decor) {
      reads++;
      return nextRate;
    }
  }

  private ActivityController<UiFrameRateTest.NativePage> controller;
  private UiFrameRateTest.NativePage activity;
  private View decor;

  @Before
  public void prepare() {
    Selection.reads = 0;
    Selection.nextRate = 120f;
    controller =
        Robolectric.buildActivity(UiFrameRateTest.NativePage.class)
            .create()
            .start()
            .resume()
            .visible();
    activity = controller.get();
    decor = activity.getWindow().getDecorView();
    idle();
    assertEquals(120f, rate(), 0f);
    assertTrue(Selection.reads > 0);
    Selection.reads = 0;
  }

  @After
  public void finish() {
    controller.pause().stop().destroy();
    idle();
  }

  private static void idle() {
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  private float rate() {
    return activity.getWindow().getAttributes().preferredRefreshRate;
  }

  private void layouts() {
    for (int height = 200; height < 320; height++) decor.layout(0, 0, 800, height);
  }

  @Test
  public void continuousImeOrSplitScreenLayoutsReuseCurrent120HzPreference() {
    layouts();
    assertEquals(0, Selection.reads);
    assertEquals(120f, rate(), 0f);
    UiFrameRate.bind(activity, new View(activity));
    Selection.reads = 0;
    layouts();
    assertEquals(0, Selection.reads);
    assertEquals(120f, rate(), 0f);
  }

  @Test
  public void peakSettingEventInvalidatesCachedPolicyAndPreserves90Hz() {
    Selection.nextRate = 90f;
    layouts();
    assertEquals(120f, rate(), 0f);
    RuntimeEnvironment.getApplication()
        .getContentResolver()
        .notifyChange(Settings.System.getUriFor("peak_refresh_rate"), null);
    idle();
    assertTrue(Selection.reads > 0);
    assertEquals(90f, rate(), 0f);
    Selection.reads = 0;
    layouts();
    assertEquals(0, Selection.reads);
    assertEquals(90f, rate(), 0f);
  }

  @Test
  public void focusResumeAndPictureInPictureRecheckRatherThanRestoreAStaleVote() {
    Selection.nextRate = 90f;
    ReflectionHelpers.callInstanceMethod(
        decor.getViewTreeObserver(),
        "dispatchOnWindowFocusChange",
        ClassParameter.from(boolean.class, true));
    assertEquals(90f, rate(), 0f);
    activity.picture = true;
    UiFrameRate.refresh(activity);
    assertEquals(0f, rate(), 0f);
    Selection.nextRate = 120f;
    activity.picture = false;
    UiFrameRate.refresh(activity);
    assertEquals(120f, rate(), 0f);
    controller.pause();
    assertEquals(0f, rate(), 0f);
    Selection.reads = 0;
    layouts();
    assertEquals(0, Selection.reads);
    Selection.nextRate = 90f;
    controller.resume();
    assertTrue(Selection.reads > 0);
    assertEquals(90f, rate(), 0f);
  }

  @Test
  public void layoutOnDifferentDisplayRecomputesAndMissingDisplayClearsVote() {
    int external = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
    idle();
    Display display = activity.getSystemService(DisplayManager.class).getDisplay(external);
    Object attachInfo = ReflectionHelpers.getField(decor, "mAttachInfo");
    assertNotNull(attachInfo);
    Selection.nextRate = 90f;
    Selection.reads = 0;
    ReflectionHelpers.setField(attachInfo, "mDisplay", display);
    layouts();
    assertEquals(1, Selection.reads);
    assertEquals(90f, rate(), 0f);
    ReflectionHelpers.setField(attachInfo, "mDisplay", null);
    layouts();
    assertEquals(0f, rate(), 0f);
    Selection.nextRate = 120f;
    ReflectionHelpers.setField(attachInfo, "mDisplay", display);
    layouts();
    assertEquals(2, Selection.reads);
    assertEquals(120f, rate(), 0f);
    ShadowDisplayManager.removeDisplay(external);
    idle();
    assertEquals(0f, rate(), 0f);
  }
}
