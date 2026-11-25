package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebCrashReport;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebStartProtection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowAppTask;
import org.robolectric.util.ReflectionHelpers;

/** Inflates the ordinary production error panel with isolated in-process controller state. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WebBackgroundPageLifecycleTest.App.class,
    shadows = WebBackgroundPageLifecycleTest.Controller.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebErrorRecoveryPanelTest {
  private static final PreviewPageSession.Identity READY =
      new PreviewPageSession.Identity(
          7, "22345678-1234-1234-1234-123456789abc", "http://127.0.0.1:41821/");

  @Before
  public void useSyntheticRuntime() {
    var controller = WebBackgroundPageLifecycleTest.Controller.class;
    ReflectionHelpers.setStaticField(
        controller, "value", Shadow.newInstanceOf(HarnessController.class));
    ReflectionHelpers.setStaticField(controller, "ready", READY);
    ReflectionHelpers.setStaticField(controller, "exited", false);
    ReflectionHelpers.setStaticField(controller, "retained", null);
    ReflectionHelpers.setStaticField(
        controller,
        "protection",
        new WebStartProtection(new WebLifecycle(), android.os.SystemClock::elapsedRealtime));
    BrowserTasks.nativeNavigation();
  }

  private ActivityController<WebPreviewActivity> workbench() {
    Context context = RuntimeEnvironment.getApplication();
    Intent intent = WebPreviewActivity.intent(context, READY.authUrl(), null);
    return Robolectric.buildActivity(WebPreviewActivity.class, intent).create().start().resume();
  }

  private static void show(WebPreviewActivity activity, WebCrashReport.Kind kind) {
    WebCrashPanel panel = ReflectionHelpers.callInstanceMethod(activity, "crashPanel");
    panel.show(kind, null, null, new WebCrashReport.Facts(), "", true);
  }

  private static void taskWithNativeMain(WebPreviewActivity page) {
    ActivityManager manager = (ActivityManager) page.getSystemService(Context.ACTIVITY_SERVICE);
    var info = new ActivityManager.RecentTaskInfo();
    info.id = page.getTaskId();
    info.baseActivity = new ComponentName(page, MainActivity.class);
    info.numActivities = 2;
    var task = ShadowAppTask.newInstance();
    Shadow.<ShadowAppTask>extract(task).setTaskInfo(info);
    Shadow.<ShadowActivityManager>extract(manager).setAppTasks(List.of(task));
  }

  @org.robolectric.annotation.Implements(value = PreviewAuth.class, isInAndroidSdk = false)
  public static final class NightAuth {
    @org.robolectric.annotation.Implementation
    protected void refresh(PreviewAuth.Callback callback) {
      callback.complete(READY.authUrl(), "", null);
    }
  }

  @Test
  @Config(shadows = NightAuth.class)
  public void equalNightModeAndSyncStorageKeepTheExistingPageAndWebColor() throws Exception {
    var app = RuntimeEnvironment.getApplication();
    var config = new ConfigStore(app);
    String previous = config.getUiTheme();
    boolean previousSync = config.isWebThemeSync();
    try {
      ThemeController.select(app, "light");
      config.setWebThemeSync(false);
      try (var lifecycle = workbench().visible()) {
        var activity = lifecycle.get();
        var keeper = BrowserPageKeeper.forApplication(activity);
        var original = keeper.page();
        assertNotNull(original);
        android.webkit.WebView view = original.view;
        assertNotNull(view);
        assertSame(activity, original.owner.get());
        assertEquals(READY, original.pageSession.identity());
        int verifiedPageColor = 0xFF223344;
        original.themeColor = verifiedPageColor;
        WebFullscreenUi.applyThemeColor(activity, verifiedPageColor);
        var page = (org.robolectric.shadows.ShadowWebView) Shadow.extract(view);
        int reloads = page.getReloadInvocations();
        View faultView = activity.findViewById(R.id.web_error_panel);
        ThemeController.select(activity, "light");
        activity.onConfigurationChanged(
            new Configuration(activity.getResources().getConfiguration()));
        assertSame(faultView, activity.findViewById(R.id.web_error_panel));
        lifecycle.pause().stop().start().resume().visible();
        Shadow.<org.robolectric.shadows.ShadowLooper>extract(android.os.Looper.getMainLooper())
            .idle();
        assertSame(activity, lifecycle.get());
        assertSame(activity, keeper.page().owner.get());
        assertSame(view, keeper.page().view);
        assertEquals(READY, keeper.page().pageSession.identity());
        assertEquals(reloads, page.getReloadInvocations());
        assertEquals(verifiedPageColor, activity.getWindow().getNavigationBarColor());
        assertTrue(WebPageScripts.theme(activity).contains("preference:'light',sync:false"));
        assertFalse(config.isWebThemeSync());
        System.out.println("NIGHT_CONTROL\\tsame-owner\\tsame-view\\tverified-chrome\\tsync=false");
      }
    } finally {
      config.setWebThemeSync(previousSync);
      ThemeController.select(app, previous);
    }
  }

  @Test
  @Config(shadows = NightAuth.class)
  public void appCompatNightChangePreservesOwnerPageAndFaultDetails() throws Exception {
    var app = RuntimeEnvironment.getApplication();
    var config = new ConfigStore(app);
    var info =
        app.getPackageManager()
            .getActivityInfo(new ComponentName(app, WebPreviewActivity.class), 0);
    boolean handlesUiMode =
        (info.configChanges & android.content.pm.ActivityInfo.CONFIG_UI_MODE) != 0;
    String previous = config.getUiTheme();
    boolean previousSync = config.isWebThemeSync();
    try {
      ThemeController.select(app, "light");
      config.setWebThemeSync(false);
      try (var lifecycle = workbench().visible()) {
        var before = lifecycle.get();
        assertFalse(ThemeController.isDark(before));
        var keeper = BrowserPageKeeper.forApplication(before);
        var original = keeper.page();
        android.webkit.WebView view = original.view;
        assertNotNull(view);
        String userAgent = view.getSettings().getUserAgentString();
        assertEquals(READY, original.pageSession.identity());
        var fault = ReflectionHelpers.<WebCrashPanel>callInstanceMethod(before, "crashPanel");
        fault.show(
            WebCrashReport.Kind.BLOCKED,
            null,
            null,
            new WebCrashReport.Facts(),
            "night-probe-fault-detail",
            true);
        assertTrue(before.findViewById(R.id.web_error_details_row).performClick());
        View pageParent = (View) view.getParent();
        View originalOverlay = before.findViewById(R.id.web_error_panel);
        int lightBackdrop = ((ColorDrawable) originalOverlay.getBackground()).getColor();
        int lightSurface = before.getColor(R.color.surface);
        before.onWindowFocusChanged(true);
        assertEquals(lightSurface, before.getWindow().getNavigationBarColor());
        int detaches = keeper.detaches();
        int retires = keeper.retires();

        ThemeController.select(before, "dark");
        Shadow.<org.robolectric.shadows.ShadowLooper>extract(android.os.Looper.getMainLooper())
            .idle();
        var after = lifecycle.get();
        var actualFault = ReflectionHelpers.<WebCrashPanel>callInstanceMethod(after, "crashPanel");
        var current = keeper.page();
        int contentBar = after.getWindow().getNavigationBarColor();
        System.out.printf(
            "NIGHT_APPCOMPAT\thandlesUiMode=%s\tsameActivity=%s\tsameOwner=%s\tsameView=%s\tdark=%s\tdetaches=%d\tretires=%d\tfault=%s\tlightBar=%08x\tactiveBar=%08x\texpectedDarkSurface=%08x\tsync=%s%n",
            handlesUiMode,
            before == after,
            current != null && current.owner.get() == before,
            current != null && current.view == view,
            ThemeController.isDark(after),
            keeper.detaches() - detaches,
            keeper.retires() - retires,
            actualFault.kind(),
            lightSurface,
            contentBar,
            after.getColor(R.color.surface),
            config.isWebThemeSync());
        assertTrue("AppCompat must deliver actual dark resources", ThemeController.isDark(after));
        assertTrue("Production manifest must explicitly handle uiMode", handlesUiMode);
        assertSame("Effective night change must keep the same host Activity", before, after);
        assertSame(before, keeper.page().owner.get());
        assertSame(view, keeper.page().view);
        assertSame(pageParent, view.getParent());
        assertEquals(userAgent, view.getSettings().getUserAgentString());
        assertEquals(READY, keeper.page().pageSession.identity());
        assertEquals(detaches, keeper.detaches());
        assertEquals(retires, keeper.retires());
        assertEquals(WebCrashReport.Kind.BLOCKED, actualFault.kind());
        assertEquals(View.VISIBLE, after.findViewById(R.id.web_error_details_box).getVisibility());
        assertEquals(
            "night-probe-fault-detail",
            ((TextView) after.findViewById(R.id.web_error_details_text)).getText().toString());
        assertEquals(after.getColor(R.color.surface), contentBar);
        assertNotEquals(lightSurface, contentBar);
        View updatedOverlay = after.findViewById(R.id.web_error_panel);
        int darkBackdrop = ((ColorDrawable) updatedOverlay.getBackground()).getColor();
        assertNotSame(originalOverlay, updatedOverlay);
        assertEquals(after.getColor(R.color.shell_bg), darkBackdrop);
        assertNotEquals(lightBackdrop, darkBackdrop);
        TextView title = after.findViewById(R.id.web_error_title);
        assertEquals(after.getColor(R.color.shell_text), title.getCurrentTextColor());
        assertFalse(config.isWebThemeSync());
      }
    } finally {
      config.setWebThemeSync(previousSync);
      ThemeController.select(app, previous);
    }
  }

  @Test
  @Config(shadows = NightAuth.class)
  public void oldActivityCannotConsumeAValidThemeMessageForTheRetainedWebView() throws Exception {
    var firstController = workbench().visible();
    var old = firstController.get();
    var keeper = BrowserPageKeeper.forApplication(old);
    var state = keeper.page();
    android.webkit.WebView view = state.view;
    var gate = state.themeGate;
    assertNotNull(gate);
    assertSame(old, state.owner.get());
    firstController.pause().stop().destroy();
    assertTrue(keeper.hasDetachedPage());
    try (var secondController = workbench().visible()) {
      var current = secondController.get();
      assertNotSame(old, current);
      assertSame(view, keeper.page().view);
      assertSame(gate, keeper.page().themeGate);
      assertSame(current, keeper.page().owner.get());
      assertEquals(READY, keeper.page().pageSession.identity());
      int baseline = current.browserThemeColor();
      String accepted =
          new org.json.JSONObject()
              .put("type", "theme-color")
              .put("nonce", gate.nonce())
              .put("generation", READY.generation())
              .put("instance", READY.instanceId())
              .put("sequence", 1)
              .put("color", "#224466")
              .toString();
      dispatchThemeMessage(old, view, accepted, READY.authUrl());
      assertNull(state.themeColor);
      assertEquals(baseline, current.browserThemeColor());
      dispatchThemeMessage(current, view, accepted, READY.authUrl());
      assertEquals(Integer.valueOf(0xFF224466), state.themeColor);
      assertEquals(0xFF224466, current.getWindow().getNavigationBarColor());
      String wrongOrigin =
          new org.json.JSONObject(accepted).put("sequence", 2).put("color", "#663311").toString();
      dispatchThemeMessage(current, view, wrongOrigin, "http://127.0.0.1:41822/");
      assertEquals(Integer.valueOf(0xFF224466), state.themeColor);
      assertEquals(0xFF224466, current.getWindow().getNavigationBarColor());
      System.out.println(
          "NIGHT_OWNER_CONTROL\\told-owner-rejected\\tcurrent-owner-rendered\\twrong-origin-rejected");
    }
  }

  private static void dispatchThemeMessage(
      WebPreviewActivity owner, android.webkit.WebView view, String payload, String url) {
    ReflectionHelpers.callInstanceMethod(
        owner,
        "receivePresentation",
        ReflectionHelpers.ClassParameter.from(android.webkit.WebView.class, view),
        ReflectionHelpers.ClassParameter.from(String.class, payload),
        ReflectionHelpers.ClassParameter.from(boolean.class, true),
        ReflectionHelpers.ClassParameter.from(String.class, url));
  }

  @Test
  @Config(shadows = NightAuth.class)
  public void nightCallbackBeforeThePendingLocaleStillUsesDeliveredRtlDirection() throws Exception {
    var app = RuntimeEnvironment.getApplication();
    var config = new ConfigStore(app);
    String previousTheme = config.getUiTheme();
    String previousLanguage = config.getUiLanguagePreference();
    String previousText = UiText.language();
    try {
      config.setUiLanguage("en");
      UiText.setLanguage("en");
      ThemeController.select(app, "light");
      try (var lifecycle = workbench().visible()) {
        var owner = lifecycle.get();
        var keeper = BrowserPageKeeper.forApplication(owner);
        var retained = keeper.page();
        assertNotNull(retained);
        android.webkit.WebView view = retained.view;
        assertNotNull(view);
        View container = owner.findViewById(R.id.web_container);
        assertEquals(View.LAYOUT_DIRECTION_LTR, container.getLayoutDirection());
        var fault = ReflectionHelpers.<WebCrashPanel>callInstanceMethod(owner, "crashPanel");
        fault.show(
            WebCrashReport.Kind.BLOCKED,
            null,
            null,
            new WebCrashReport.Facts(),
            "night-locale-fault-detail",
            true);
        assertTrue(owner.findViewById(R.id.web_error_details_row).performClick());

        // The app has persisted the locale preference, before Android delivers its resource
        // configuration.
        config.setUiLanguage("ar");
        UiText.setLanguage("ar");
        ThemeController.select(owner, "dark");
        Shadow.<org.robolectric.shadows.ShadowLooper>extract(android.os.Looper.getMainLooper())
            .idle();
        assertSame("Night is handled on the existing host", owner, lifecycle.get());
        assertTrue(ThemeController.isDark(owner));
        int interim = container.getLayoutDirection();
        assertEquals(
            "Night callback still carried English resources",
            View.LAYOUT_DIRECTION_LTR,
            owner.getResources().getConfiguration().getLayoutDirection());
        Configuration arabic = new Configuration(owner.getResources().getConfiguration());
        arabic.setLocale(Locale.forLanguageTag("ar"));
        assertEquals(View.LAYOUT_DIRECTION_RTL, arabic.getLayoutDirection());
        lifecycle.configurationChange(arabic);
        Shadow.<org.robolectric.shadows.ShadowLooper>extract(android.os.Looper.getMainLooper())
            .idle();
        System.out.printf(
            "NIGHT_LOCALE_ORDER\tdelivered=%d\tinterim=%d\tcontainer=%d\tresources=%d\tpreference=%s\tsameOwner=%s\tsamePage=%s%n",
            arabic.getLayoutDirection(),
            interim,
            container.getLayoutDirection(),
            owner.getResources().getConfiguration().getLayoutDirection(),
            config.getUiLanguage(),
            owner == lifecycle.get(),
            keeper.page() != null && keeper.page().view == view);
        assertSame(owner, lifecycle.get());
        assertSame(view, keeper.page().view);
        assertEquals(READY, keeper.page().pageSession.identity());
        assertEquals(
            View.LAYOUT_DIRECTION_RTL,
            owner.getResources().getConfiguration().getLayoutDirection());
        assertEquals(
            "The container must follow the delivered direction even when the language preference is already observed",
            View.LAYOUT_DIRECTION_RTL,
            container.getLayoutDirection());
        assertEquals(
            "night-locale-fault-detail",
            ((TextView) owner.findViewById(R.id.web_error_details_text)).getText().toString());

        config.setUiLanguage("en");
        UiText.setLanguage("en");
        lifecycle.pause().stop();
        ThemeController.select(app, "light");
        lifecycle.start().resume().visible();
        assertSame(owner, lifecycle.get());
        assertFalse(ThemeController.isDark(owner));
        assertEquals(View.LAYOUT_DIRECTION_RTL, container.getLayoutDirection());
        Configuration english = new Configuration(owner.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH);
        lifecycle.configurationChange(english);
        assertSame(owner, lifecycle.get());
        assertSame(view, keeper.page().view);
        assertSame(owner, keeper.page().owner.get());
        assertEquals(View.LAYOUT_DIRECTION_LTR, container.getLayoutDirection());
        View faultView = owner.findViewById(R.id.web_error_panel);
        owner.onConfigurationChanged(new Configuration(english));
        assertSame(faultView, owner.findViewById(R.id.web_error_panel));
        assertEquals(View.LAYOUT_DIRECTION_LTR, container.getLayoutDirection());
        System.out.println(
            "NIGHT_LOCALE_REVERSE\tbackground-same-owner\tdelivered-ltr\tsame-value-no-op");
      }
    } finally {
      config.setUiLanguage(previousLanguage);
      UiText.setLanguage(previousText);
      ThemeController.select(app, previousTheme);
    }
  }

  @Test
  public void blockedPrimaryClickOpensRecoveryAndDefersNativeMainUntilBrowserResumes() {
    try (var controller = workbench()) {
      WebPreviewActivity page = controller.get();
      taskWithNativeMain(page);
      ShadowActivity window = Shadow.extract(page);
      show(page, WebCrashReport.Kind.BLOCKED);
      assertTrue(page.findViewById(R.id.web_retry).performClick());
      Intent front = window.getNextStartedActivity();
      Intent earlier = window.getNextStartedActivity();
      assertNotNull(front);
      System.out.println(
          "PANEL_DISPATCH newest="
              + front.getComponent().getShortClassName()
              + " earlier="
              + (earlier == null ? "none" : earlier.getComponent().getShortClassName()));
      assertEquals(StartupRecoveryActivity.class.getName(), front.getComponent().getClassName());
      assertNull(earlier);
      assertEquals(
          0, front.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      assertTrue(page.conversationHidden());
      assertFalse(page.isFinishing());
      page.findViewById(R.id.web_retry).performClick();
      assertNull(window.getNextStartedActivity());

      controller.pause().stop().restart().start().resume();
      Intent toNative = window.getNextStartedActivity();
      assertNotNull(toNative);
      assertEquals(MainActivity.class.getName(), toNative.getComponent().getClassName());
      assertTrue(toNative.getBooleanExtra("open_launch", false));
      assertEquals(
          0,
          toNative.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      assertNull(window.getNextStartedActivity());
      assertFalse(page.isFinishing());
    }
  }

  @Test
  public void rendererRetryStartsReauthenticationInTheCurrentWebPreview() {
    try (var controller = workbench()) {
      WebPreviewActivity page = controller.get();
      ReflectionHelpers.callInstanceMethod(page, "cancelPendingAuthentication");
      ShadowActivity window = Shadow.extract(page);
      show(page, WebCrashReport.Kind.RENDER_CRASH);
      View panel = page.findViewById(R.id.web_error_panel);
      assertEquals(View.VISIBLE, panel.getVisibility());
      assertTrue(page.findViewById(R.id.web_retry).performClick());
      assertEquals(View.GONE, panel.getVisibility());
      assertEquals(READY, ReflectionHelpers.getField(page, "pendingIdentity"));
      assertFalse(page.conversationHidden());
      assertNull(window.getNextStartedActivity());
    }
  }

  @Test
  public void secondaryReturnAndExternalActionKeepTheirExistingDestinations() {
    try (var controller = workbench()) {
      WebPreviewActivity page = controller.get();
      taskWithNativeMain(page);
      ShadowActivity window = Shadow.extract(page);
      show(page, WebCrashReport.Kind.RENDER_CRASH);
      assertTrue(page.findViewById(R.id.web_error_browser).performClick());
      Intent browser = window.getNextStartedActivity();
      assertNotNull(browser);
      assertEquals(Intent.ACTION_VIEW, browser.getAction());
      assertEquals("127.0.0.1", browser.getData().getHost());
      assertNull(window.getNextStartedActivity());
      assertTrue(page.findViewById(R.id.web_error_recovery).performClick());
      Intent nativeHome = window.getNextStartedActivity();
      assertNotNull(nativeHome);
      assertEquals(MainActivity.class.getName(), nativeHome.getComponent().getClassName());
      assertNull(window.getNextStartedActivity());
    }
  }

  @Test
  public void languageConfigurationKeepsFaultDetailsAndRecoveryDestination() {
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    String previous = config.getUiLanguagePreference();
    String previousUiText = UiText.language();
    config.setUiLanguage("zh");
    UiText.setLanguage("zh");
    try (var controller = workbench()) {
      WebPreviewActivity page = controller.get();
      taskWithNativeMain(page);
      WebCrashPanel panel = ReflectionHelpers.callInstanceMethod(page, "crashPanel");
      WebCrashReport.Facts facts = new WebCrashReport.Facts();
      facts.exitCode = 139;
      facts.failures = 7;
      panel.show(WebCrashReport.Kind.BLOCKED, null, null, facts, "original-tech-data", true);
      View detailsRow = page.findViewById(R.id.web_error_details_row);
      View detailsBox = page.findViewById(R.id.web_error_details_box);
      assertTrue(detailsRow.performClick());
      assertEquals("DSH 反复崩溃，已暂停自动重启", panelText(page, R.id.web_error_title));
      assertEquals("查看原因与修复", panelText(page, R.id.web_retry));
      assertEquals("返回启动页", panelText(page, R.id.web_error_secondary));
      assertEquals("已展开", detailsRow.getStateDescription());
      assertRowText(page, "已退出 · 退出码 139");
      assertRowText(page, "已暂停 · 连续失败 7 次");

      config.setUiLanguage("en");
      UiText.setLanguage("en");
      Configuration english = new Configuration(page.getResources().getConfiguration());
      english.setLocale(Locale.ENGLISH);
      page.onConfigurationChanged(english);

      assertEquals(WebCrashReport.Kind.BLOCKED, panel.kind());
      assertEquals(WebCrashReport.Action.RECOVERY, panel.primaryAction());
      assertEquals(View.VISIBLE, detailsBox.getVisibility());
      assertEquals("Expanded", detailsRow.getStateDescription());
      assertEquals(
          "DSH keeps crashing; auto-restart paused", panelText(page, R.id.web_error_title));
      assertEquals(
          "It failed to stay up after several starts. Review the cause, then start safely or repair.",
          panelText(page, R.id.web_error_detail));
      assertEquals("Review and repair", panelText(page, R.id.web_retry));
      assertEquals("Back to start page", panelText(page, R.id.web_error_secondary));
      assertEquals("Download error logs", panelText(page, R.id.web_error_logs_title));
      assertEquals("Open in browser", panelText(page, R.id.web_error_browser_title));
      assertEquals(
          "Back to Start: plugins / safe start", panelText(page, R.id.web_error_recovery_title));
      assertEquals("Technical details", panelText(page, R.id.web_error_details_title));
      assertEquals("original-tech-data", panelText(page, R.id.web_error_details_text));
      assertRowText(page, "Service");
      assertRowText(page, "Exited · code 139");
      assertRowText(page, "Segfault, usually a native module");
      assertRowText(page, "Paused · 7 failures in a row");

      assertTrue(detailsRow.performClick());
      assertEquals("Collapsed", detailsRow.getStateDescription());
      config.setUiLanguage("zh");
      UiText.setLanguage("zh");
      Configuration chinese = new Configuration(page.getResources().getConfiguration());
      chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
      page.onConfigurationChanged(chinese);
      assertEquals(View.GONE, detailsBox.getVisibility());
      assertEquals("已收起", detailsRow.getStateDescription());
      assertEquals("查看原因与修复", panelText(page, R.id.web_retry));
      assertEquals("original-tech-data", panelText(page, R.id.web_error_details_text));
      ShadowActivity window = Shadow.extract(page);
      assertNull(window.getNextStartedActivity());
      assertTrue(page.findViewById(R.id.web_retry).performClick());
      assertEquals(
          StartupRecoveryActivity.class.getName(),
          window.getNextStartedActivity().getComponent().getClassName());
      assertNull(window.getNextStartedActivity());
    } finally {
      config.setUiLanguage(previous);
      UiText.setLanguage(previousUiText);
    }
  }

  private static String panelText(WebPreviewActivity page, int id) {
    return page.<TextView>findViewById(id).getText().toString();
  }

  private static void assertRowText(WebPreviewActivity page, String expected) {
    ArrayList<View> matches = new ArrayList<>();
    page.findViewById(R.id.web_error_state)
        .findViewsWithText(matches, expected, View.FIND_VIEWS_WITH_TEXT);
    assertTrue(
        expected,
        matches.stream()
            .anyMatch(
                view -> view instanceof TextView label && expected.contentEquals(label.getText())));
  }
}
