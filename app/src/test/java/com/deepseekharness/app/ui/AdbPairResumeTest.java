package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

/** Regression for the retained device-channel view after closing ADB in the pairing activity. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DirectWebEntryTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      DirectWebEntryTest.Controller.class,
      MainEntryLayoutLifecycleTest.Environment.class,
      com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdbPairResumeTest {
  @Before
  public void prepare() throws Exception {
    new DirectWebEntryTest().prepare();
  }

  @Test
  public void actualPairCloseRefreshesRetainedAdbSwitchWithoutWritingAgain() throws Exception {
    var app = RuntimeEnvironment.getApplication();
    var oldLanguage = UiText.language();
    UiText.setLanguage("zh");
    Intent launcher =
        new Intent(app, MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER);
    var mainDriver =
        Robolectric.buildActivity(MainActivity.class, launcher).create().start().resume();
    ActivityController<AdbPairActivity> pairDriver = null;
    try {
      MainActivity main = mainDriver.get();
      assertTrue(ConfigStore.setAdbEnabled(main, true, true));
      var manager = main.getSupportFragmentManager();
      var retainedFragment = new DeviceChannelsFragment();
      manager.beginTransaction().replace(R.id.fragment_container, retainedFragment).commitNow();
      View retained = retainedFragment.requireView();
      CompoundButton switchView = retained.findViewById(R.id.config_adb_enable);
      assertTrue(switchView.isChecked());
      ShadowActivity mainShadow = Shadow.extract(main);
      while (mainShadow.getNextStartedActivity() != null) {}
      retained.findViewById(R.id.config_adb_pair).performClick();
      Intent pairIntent = mainShadow.getNextStartedActivity();
      assertNotNull(pairIntent);
      assertEquals(AdbPairActivity.class.getName(), pairIntent.getComponent().getClassName());
      mainDriver.pause().stop();
      pairDriver =
          Robolectric.buildActivity(AdbPairActivity.class, pairIntent).create().start().resume();
      var pair = pairDriver.get();
      pair.findViewById(R.id.adb_pair_disconnect).performClick();
      var looper = ShadowLooper.shadowMainLooper();
      looper.idleFor(350, TimeUnit.MILLISECONDS);
      TextView pairStatus = pair.findViewById(R.id.adb_pair_status);
      assertTrue(
          pairStatus.getText().toString(), pairStatus.getText().toString().contains("配对密钥保留"));
      var prefs = app.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
      assertFalse(prefs.getBoolean("adb_enabled", true));
      var field = DeviceBridgeService.class.getDeclaredField("ADB_INTENT_EPOCH");
      field.setAccessible(true);
      var epoch = (AtomicLong) field.get(null);
      long afterClose = epoch.get();
      pair.findViewById(R.id.adb_pair_back).performClick();
      assertTrue(pair.isFinishing());
      pairDriver.pause().stop().destroy();
      pairDriver = null;
      while (mainShadow.getNextStartedActivity() != null) {}
      ShadowApplication appShadow = Shadow.extract(app);
      while (appShadow.getNextStartedService() != null) {}
      mainDriver.restart().start().resume();
      assertSame(retained, retainedFragment.requireView());
      TextView label = retained.findViewById(R.id.config_adb_status);
      for (int sample = 0;
          sample < 80 && !label.getText().toString().contains("ADB 已关闭");
          sample++) {
        looper.idle();
        Thread.sleep(10);
      }
      assertTrue(label.getText().toString(), label.getText().toString().contains("ADB 已关闭"));
      boolean retainedChecked = switchView.isChecked();
      boolean preferenceAfterResume = prefs.getBoolean("adb_enabled", true);
      long afterResume = epoch.get();
      assertNull("Resume must not start another activity", mainShadow.getNextStartedActivity());
      assertNull("Resume must not start another service", appShadow.getNextStartedService());
      assertFalse(prefs.getBoolean("local_network_permission_asked", false));
      var recreated = new DeviceChannelsFragment();
      manager.beginTransaction().replace(R.id.fragment_container, recreated).commitNow();
      CompoundButton recreatedSwitch = recreated.requireView().findViewById(R.id.config_adb_enable);
      System.out.printf(
          "ADB-RESUME retainedChecked=%s retainedStatus=%s persisted=%s reopenedChecked=%s epochAfterClose=%d epochAfterResume=%d%n",
          retainedChecked,
          label.getText(),
          preferenceAfterResume,
          recreatedSwitch.isChecked(),
          afterClose,
          afterResume);
      assertFalse(preferenceAfterResume);
      assertFalse(recreatedSwitch.isChecked());
      assertEquals(
          "Repainting the switch must not go through the real change listener",
          afterClose,
          afterResume);
      assertFalse(
          "The retained switch must agree with the closed preference and status", retainedChecked);
    } finally {
      if (pairDriver != null) pairDriver.pause().stop().destroy();
      mainDriver.pause().stop().destroy();
      UiText.setLanguage(oldLanguage);
    }
  }
}
