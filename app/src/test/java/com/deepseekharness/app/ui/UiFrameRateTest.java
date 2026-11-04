package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.deepseekharness.app.DshaApp;

import java.util.ArrayList;
import java.util.List;

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
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDisplayManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** 实际 Application 生命周期注册、窗口属性、显示/系统设置事件；不代表面板实测。 */
@RunWith(UiFrameRateTest.NoApkRunner.class)
@Config(
    sdk = {34, 35},
    manifest = Config.NONE,
    application = UiFrameRateTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiFrameRateTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {
      // 使用生产 Application 所持有的同一个 owner，隔离其他运行环境/服务启动副作用。
      uiFrameRate().install();
    }
  }

  public static final class NativePage extends Activity {
    boolean picture;

    @Override
    public void onCreate(Bundle state) {
      super.onCreate(state);
      setContentView(new FrameLayout(this));
    }

    @Override
    public boolean isInPictureInPictureMode() {
      return picture;
    }
  }

  private App app;
  private final List<ActivityController<NativePage>> controllers = new ArrayList<>();

  @Before
  public void prepare() {
    app = (App) RuntimeEnvironment.getApplication();
    modes(Display.DEFAULT_DISPLAY, 60f, 90f, 120f);
    peak(120f);
  }

  @After
  public void finish() {
    for (ActivityController<NativePage> controller : controllers) {
      if (!controller.get().isDestroyed()) controller.pause().stop().destroy();
    }
    idle();
  }

  private ActivityController<NativePage> nativePage() {
    ActivityController<NativePage> controller =
        Robolectric.buildActivity(NativePage.class).create().start().resume().visible();
    controllers.add(controller);
    return controller;
  }

  private void peak(float peak) {
    Settings.System.putFloat(app.getContentResolver(), "peak_refresh_rate", peak);
    app.getContentResolver().notifyChange(Settings.System.getUriFor("peak_refresh_rate"), null);
    idle();
  }

  private static void idle() {
    ((ShadowLooper) Shadow.extract(Looper.getMainLooper())).idle();
  }

  private static float rate(Activity activity) {
    return activity.getWindow().getAttributes().preferredRefreshRate;
  }

  private static void attachedDisplay(Activity activity, Display display) {
    // 模拟窗口管理器迁移已 attach 窗口的显示器事实；生产仍调用真实 View.getDisplay()。
    Object attachInfo =
        ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
    assertNotNull(attachInfo);
    ReflectionHelpers.setField(attachInfo, "mDisplay", display);
  }

  private static void focus(Activity activity, boolean focused) {
    ReflectionHelpers.callInstanceMethod(
        activity.getWindow().getDecorView().getViewTreeObserver(),
        "dispatchOnWindowFocusChange",
        ClassParameter.from(boolean.class, focused));
  }

  private static Display.Mode mode(int id, int width, int height, float rate) {
    return ReflectionHelpers.callConstructor(
        Display.Mode.class,
        ClassParameter.from(int.class, id),
        ClassParameter.from(int.class, width),
        ClassParameter.from(int.class, height),
        ClassParameter.from(float.class, rate));
  }

  private void modes(int id, float... rates) {
    Display display = app.getSystemService(DisplayManager.class).getDisplay(id);
    Display.Mode current = display.getMode();
    Display.Mode[] modes = new Display.Mode[rates.length];
    for (int index = 0; index < rates.length; index++)
      modes[index] =
          mode(
              index == 0 ? current.getModeId() : current.getModeId() + index,
              current.getPhysicalWidth(),
              current.getPhysicalHeight(),
              rates[index]);
    ShadowDisplayManager.setSupportedModes(id, modes);
    idle();
  }

  @Test
  public void unavailableDisplayBeforeAttachClearsAndActualAttachRestoresTheRequest() {
    var controller = Robolectric.buildActivity(NativePage.class).create().start().resume();
    controllers.add(controller);
    Activity activity = controller.get();
    assertNull(activity.getWindow().getDecorView().getDisplay());
    UiFrameRate.refresh(activity);
    assertEquals(0f, rate(activity), 0f);
    controller.visible();
    assertTrue(activity.getWindow().getDecorView().isAttachedToWindow());
    assertNotNull(activity.getWindow().getDecorView().getDisplay());
    assertEquals(120f, rate(activity), 0f);
    attachedDisplay(activity, null);
    UiFrameRate.refresh(activity);
    assertEquals(0f, rate(activity), 0f);
    attachedDisplay(
        activity, app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY));
    UiFrameRate.refresh(activity);
    assertEquals(120f, rate(activity), 0f);
  }

  @Test
  public void nativeLifecycleRequests120ClearsOnPauseAndRecomputesOnResume() {
    var controller = nativePage();
    assertEquals(120f, rate(controller.get()), 0f);
    controller.pause();
    assertEquals(0f, rate(controller.get()), 0f);
    peak(90f);
    controller.resume();
    assertEquals(90f, rate(controller.get()), 0f);
    controller.pause().stop().destroy();
    assertEquals(0f, rate(controller.get()), 0f);
    assertTrue(
        ((ShadowContentResolver) Shadow.extract(app.getContentResolver()))
            .getContentObservers(Settings.System.getUriFor("peak_refresh_rate"))
            .isEmpty());
  }

  @Test
  public void peakAndPowerEventsUpdateAlreadyVisibleNativeWindow() {
    Activity activity = nativePage().get();
    peak(90f);
    assertEquals(90f, rate(activity), 0f);
    peak(60f);
    assertEquals(0f, rate(activity), 0f);
    peak(120f);
    PowerManager power = app.getSystemService(PowerManager.class);
    ((ShadowPowerManager) Shadow.extract(power)).setIsPowerSaveMode(true);
    app.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    idle();
    assertEquals(0f, rate(activity), 0f);
    ((ShadowPowerManager) Shadow.extract(power)).setIsPowerSaveMode(false);
    app.sendBroadcast(new Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    idle();
    assertEquals(120f, rate(activity), 0f);
  }

  @Test
  public void displayChangeUsesSameResolutionAndNeverSetsAModeId() {
    Activity activity = nativePage().get();
    Display.Mode current = activity.getWindow().getDecorView().getDisplay().getMode();
    ShadowDisplayManager.setSupportedModes(
        Display.DEFAULT_DISPLAY,
        mode(current.getModeId(), current.getPhysicalWidth(), current.getPhysicalHeight(), 60f),
        mode(current.getModeId() + 1, current.getPhysicalWidth(), current.getPhysicalHeight(), 90f),
        mode(
            current.getModeId() + 2,
            current.getPhysicalWidth() * 2,
            current.getPhysicalHeight() * 2,
            120f));
    idle();
    assertEquals(90f, rate(activity), 0f);
    assertEquals(0, activity.getWindow().getAttributes().preferredDisplayModeId);
  }

  @Test
  public void migratedWindowUsesItsDisplayAndInvalidDisplayClearsOldVote() {
    NativePage activity = nativePage().get();
    int externalId = ShadowDisplayManager.addDisplay("w1920dp-h1080dp");
    modes(externalId, 60f, 90f);
    attachedDisplay(activity, app.getSystemService(DisplayManager.class).getDisplay(externalId));
    focus(activity, true);
    assertEquals(90f, rate(activity), 0f);
    ShadowDisplayManager.removeDisplay(externalId);
    idle();
    assertEquals(0f, rate(activity), 0f);
    attachedDisplay(
        activity, app.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY));
    focus(activity, true);
    assertEquals(120f, rate(activity), 0f);
  }

  @Test
  public void pictureInPictureClearsAndFullscreenRestoresWithoutRecreatingContent() {
    NativePage activity = nativePage().get();
    WebView page = new WebView(activity);
    UiFrameRate.bind(activity, page);
    activity.picture = true;
    UiFrameRate.refresh(activity);
    assertEquals(0f, rate(activity), 0f);
    activity.picture = false;
    UiFrameRate.refresh(activity);
    assertEquals(120f, rate(activity), 0f);
    UiFrameRate.unbind(activity, page);
    page.destroy();
  }

  @Test
  public void twoResumedWindowsKeepIndependentVotesWhenOnePauses() {
    var first = nativePage();
    var second = nativePage();
    ((ShadowActivity) Shadow.extract(first.get())).setInMultiWindowMode(true);
    focus(first.get(), false);
    peak(90f);
    assertEquals(90f, rate(first.get()), 0f);
    assertEquals(90f, rate(second.get()), 0f);
    first.pause();
    peak(120f);
    assertEquals(0f, rate(first.get()), 0f);
    assertEquals(120f, rate(second.get()), 0f);
  }

  @Test
  @Config(sdk = 35)
  public void numericViewVoteFollowsPeakAndOldHostCannotClearRetainedWebView() {
    var first = nativePage();
    WebView retained = new WebView(app);
    UiFrameRate.bind(first.get(), retained);
    assertEquals(120f, retained.getRequestedFrameRate(), 0f);
    var second = nativePage();
    UiFrameRate.bind(second.get(), retained);
    first.pause().stop().destroy();
    UiFrameRate.unbind(first.get(), retained);
    assertEquals(120f, retained.getRequestedFrameRate(), 0f);
    peak(90f);
    assertEquals(90f, retained.getRequestedFrameRate(), 0f);
    assertEquals(90f, second.get().getWindow().getDecorView().getRequestedFrameRate(), 0f);
    second.pause();
    assertEquals(
        View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE, retained.getRequestedFrameRate(), 0f);
    retained.destroy();
  }

  @Test
  @Config(sdk = 35)
  public void destroyingOnlyWebContentDoesNotCancelNativeErrorPanelWindowVote() {
    Activity activity = nativePage().get();
    WebView content = new WebView(activity);
    UiFrameRate.bind(activity, content);
    UiFrameRate.unbind(activity, content);
    assertEquals(120f, rate(activity), 0f);
    assertEquals(
        View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE, content.getRequestedFrameRate(), 0f);
    content.destroy();
  }
}
