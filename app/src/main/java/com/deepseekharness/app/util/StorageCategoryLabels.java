package com.deepseekharness.app.util;

import java.util.Map;

/** Display identity follows storage keys; unknown categories remain visible as their original key. */
public final class StorageCategoryLabels {
  private StorageCategoryLabels() {}

  private static final Map<String, String[]> LABELS =
      Map.ofEntries(
          Map.entry("linux", new String[] {"当前 Linux 与 DSH", "Current Linux and DSH"}),
          Map.entry("host-runtime-operations", new String[] {"运行时回退副本", "Runtime rollback copies"}),
          Map.entry(
              "host-environment-operations",
              new String[] {"环境重建保护副本", "Environment protection copies"}),
          Map.entry("runtime-updates", new String[] {"旧版更新记录", "Legacy update records"}),
          Map.entry("host-backup-operations", new String[] {"备份副本", "Backup copies"}),
          Map.entry("mozilla", new String[] {"兼容浏览器数据", "Compatibility browser data"}),
          Map.entry("user-data-v5", new String[] {"宿主个人数据", "Persistent user data"}),
          Map.entry("cache", new String[] {"应用缓存", "App cache"}),
          Map.entry("webview", new String[] {"WebView 数据", "WebView data"}),
          Map.entry("historical-audit-fixtures", new String[] {"历史验收资料", "Historical audit files"}),
          Map.entry("other-private", new String[] {"其他应用私有文件", "Other private app files"}));

  public static String label(String key, boolean english) {
    if (key == null) return null;
    String[] names = LABELS.get(key);
    return names == null ? key : names[english ? 1 : 0];
  }

  public static String label(String key) {
    if (key == null) return null;
    String[] names = LABELS.get(key);
    return names == null ? key : UiText.choose(names[0], names[1]);
  }
}
