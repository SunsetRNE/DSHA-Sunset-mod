package com.deepseekharness.app.screen;

import android.content.Context;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.util.ScreenCapabilityGate;

/** 采样识屏准入所需的设备状态；判定交给纯函数 {@link ScreenCapabilityGate}。 */
public final class ScreenCapabilities {
  private ScreenCapabilities() {}

  public static ScreenCapabilityGate.Inputs sample(Context context) {
    Context app =
        context.getApplicationContext() == null ? context : context.getApplicationContext();
    var authority = com.deepseekharness.app.core.ConfigStore.deviceAuthorization(app);
    // 平台事实在锁外采样；采样期间意图变化时，不发布旧开关组合。
    boolean accessibility =
        safe(() -> DshaAccessibilityService.isConnected() || DshaAccessibilityService.enabled(app));
    boolean rootPresent = safe(RootShell::present);
    var shizuku = shizuku();
    boolean localNetwork = safe(() -> LocalNetworkAccess.granted(app));
    boolean adbConnected = "connected".equals(DeviceBridgeService.adbState);
    boolean overlay = safe(() -> android.provider.Settings.canDrawOverlays(app));
    boolean current = com.deepseekharness.app.util.DeviceAuthorization.shared().current(authority);
    return new ScreenCapabilityGate.Inputs(
        android.os.Build.VERSION.SDK_INT,
        // 冷启动时服务可能尚未连上；实际读屏仍由服务实例把关。
        accessibility,
        current && authority.rootAllowed(),
        rootPresent,
        shizuku,
        current && authority.adbAllowed(),
        localNetwork,
        adbConnected,
        overlay);
  }

  private static ScreenCapabilityGate.Shizuku shizuku() {
    if (safe(ShizukuShell::isReady)) return ScreenCapabilityGate.Shizuku.READY;
    if (safe(ShizukuShell::hasPermission)) return ScreenCapabilityGate.Shizuku.PERMITTED;
    return ScreenCapabilityGate.Shizuku.NONE;
  }

  private static boolean safe(java.util.function.BooleanSupplier probe) {
    try {
      return probe.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
