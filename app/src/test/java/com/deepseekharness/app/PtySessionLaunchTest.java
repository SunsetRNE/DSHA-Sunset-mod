package com.deepseekharness.app;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest;
import com.deepseekharness.app.util.DshaCliPreset;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = RuntimeToolsRegistryAndroidTest.OwnedApplication.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {RuntimeToolsRegistryAndroidTest.HostFileSystem.class})
public final class PtySessionLaunchTest {
  @Test
  public void defaultSessionReachesKotlinLaunchPreparation() {
    assertReachesLaunchPreparation(null);
  }

  @Test
  public void cliSessionReachesKotlinLaunchPreparation() {
    assertReachesLaunchPreparation(DshaCliPreset.guestCommand("en"));
  }

  private static void assertReachesLaunchPreparation(String[] command) {
    var app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().nativeLibraryDir = app.getFilesDir().getPath();
    var reachedLaunchPreparation = new IllegalStateException("PTY_LAUNCH_PREPARATION_REACHED");
    var proot =
        new ProotBootstrap(app) {
          private boolean readyChecked;

          @Override
          public boolean isEnvironmentReady() {
            // 第一次检查来自 PtySession，第二次进入 TerminalRuntime 时在准备原生依赖前终止。
            if (readyChecked) throw reachedLaunchPreparation;
            readyChecked = true;
            return true;
          }
        };
    assertSame(
        reachedLaunchPreparation,
        assertThrows(
            IllegalStateException.class,
            () -> PtySession.start(proot, 80, 24, null, null, null, null, null, command)));
  }
}
