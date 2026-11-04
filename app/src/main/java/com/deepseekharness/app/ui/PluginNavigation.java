package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.deepseekharness.app.util.PluginInstallLink;

/** 两个网页内核共用插件导航；包、路径、摘要和事务核验后自动提交启用，保留维护屏障。 */
final class PluginNavigation {
  private PluginNavigation() {}

  static boolean open(Activity activity, String url) {
    return open(activity, url, false);
  }

  /**
   * 只允许「带手势的主框架导航」打开插件安装/管理页，且必须是 dsha.cc 的精确路径。
   *
   * <p>防的是网页脚本/跨源 iframe 用 {@code dsha://install} 或 window.open 无手势拉起
   * {@link PluginInstallActivity}（打开即 inspect/下载）。
   */
  static boolean open(Activity activity, String url, boolean gesture) {
    if (!gesture || !allowed(url)) return false;
    if (PluginInstallLink.management(url)) {
      com.deepseekharness.app.core.PluginRepository.get(activity).invalidateInstalledState();
      activity.startActivity(
          MainActivity.navigationIntent(activity).putExtra("open_plugins", true));
      return true;
    }
    try {
      PluginInstallLink.parse(url);
    } catch (IllegalArgumentException ordinary) {
      return false;
    }
    activity.startActivity(
        new Intent(activity, PluginInstallActivity.class).setData(Uri.parse(url)));
    return true;
  }

  /** 插件导航白名单：仅 https://dsha.cc 的 /install 与 /app/plugins（含子路径）。 */
  static boolean allowed(String url) {
    if (url == null) return false;
    android.net.Uri uri = android.net.Uri.parse(url);
    if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
    if (!"dsha.cc".equalsIgnoreCase(uri.getHost())) return false;
    String path = uri.getPath();
    if (path == null) return false;
    return path.equals("/install")
        || path.startsWith("/install/")
        || path.equals("/app/plugins")
        || path.startsWith("/app/plugins/");
  }
}
