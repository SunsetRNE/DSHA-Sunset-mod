package com.deepseekharness.app.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.deepseekharness.app.util.LanNetworkState;

/** Android 17 的局域网授权。仅在开启 LAN / ADB 功能时由界面发起请求。 */
public final class LocalNetworkAccess {
  public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

  private LocalNetworkAccess() {}

  public static boolean granted(Context context) {
    return Build.VERSION.SDK_INT < 37
        || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
  }

  /** Prefer the default physical LAN, also allowing a local-only Ethernet or Wi-Fi network. */
  @SuppressWarnings("deprecation")
  public static LanNetworkState currentLan(Context context) {
    if (context == null) return null;
    try {
      ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
      if (manager == null) return null;
      Network active = manager.getActiveNetwork();
      LanNetworkState best = null;
      for (Network network : manager.getAllNetworks()) {
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps == null
            || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_USB))) continue;
        LinkProperties links = manager.getLinkProperties(network);
        if (links == null) continue;
        LanNetworkState candidate = null;
        for (var link : links.getLinkAddresses()) {
          LanNetworkState route =
              new LanNetworkState(
                  network.getNetworkHandle(),
                  links.getInterfaceName(),
                  link.getAddress().getHostAddress(),
                  link.getPrefixLength());
          if (route.eligible()
              && (candidate == null || route.address().compareTo(candidate.address()) < 0))
            candidate = route;
        }
        if (candidate == null) continue;
        if (network.equals(active)) return candidate;
        if (best == null || candidate.handle() < best.handle()) best = candidate;
      }
      return best;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  public static void applyConfiguredFeatures(Context context) {
    if (!granted(context)) return;
    Context app = context.getApplicationContext();
    if (com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(app))
      com.deepseekharness.app.DeviceBridgeService.apply(app);
    com.deepseekharness.app.core.ConfigStore config =
        new com.deepseekharness.app.core.ConfigStore(app);
    if (config.isLanMode()) {
      com.deepseekharness.app.core.HarnessController controller =
          com.deepseekharness.app.core.HarnessController.get(app);
      com.deepseekharness.app.LanProxyService.start(
          controller.proot().getRootfsDir().getAbsolutePath(),
          app,
          controller.getWebPort(),
          controller.getWebGeneration());
    }
  }
}
