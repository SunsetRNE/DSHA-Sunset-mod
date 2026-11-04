package com.deepseekharness.app.module;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 卡片能绑定的真实数据源（只读白名单）。
 *
 * <p>这是「卡片能显示真实状态」的全部来源：宿主自己算好，卡片只负责展示。
 * 模块无法自定义数据源、无法读任意文件、无法发网络请求 —— 只能从这几项里挑。
 */
public final class ModuleData {

  public static final String DSH_STATUS = "dsh.status";
  public static final String DSH_PORT = "dsh.port";
  public static final String CLOCK_TIME = "clock.time";
  public static final String DATE_TODAY = "date.today";
  public static final String MODULES_COUNT = "modules.count";
  public static final String STORAGE_FREE = "storage.free";
  public static final String DSH_RUNS = "dsh.runs";
  public static final String SYSTEM_LOAD = "system.load";
  public static final String SYSTEM_MEMORY = "system.memory";

  public static final List<String> SOURCES =
      Collections.unmodifiableList(
          Arrays.asList(
              DSH_STATUS,
              DSH_PORT,
              CLOCK_TIME,
              DATE_TODAY,
              MODULES_COUNT,
              STORAGE_FREE,
              DSH_RUNS,
              SYSTEM_LOAD,
              SYSTEM_MEMORY));

  private ModuleData() {}

  /** 取某个数据源的当前值；不认识或取不到时返回空串，由卡片决定怎么显示。 */
  public static String value(Context context, String source) {
    try {
      switch (source) {
        case DSH_STATUS:
          return dshStatus(context);
        case DSH_PORT:
          int port = com.deepseekharness.app.core.HarnessController.get(context).getWebPort();
          return port > 0 ? String.valueOf(port) : "—";
        case CLOCK_TIME:
          return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        case DATE_TODAY:
          return new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date());
        case MODULES_COUNT:
          return String.valueOf(ModuleCardStore.load(context).size());
        case STORAGE_FREE:
          return freeSpace(context);
        case DSH_RUNS:
          return String.valueOf(runsToday(context));
        case SYSTEM_LOAD:
          return systemLoad(context);
        case SYSTEM_MEMORY:
          return systemMemory();
        default:
          return "";
      }
    } catch (RuntimeException error) {
      return "";
    }
  }

  private static String dshStatus(Context context) {
    com.deepseekharness.app.core.HarnessController controller =
        com.deepseekharness.app.core.HarnessController.get(context);
    if (controller.isStarting()) return "启动中";
    if (controller.isStopping()) return "停止中";
    if (controller.isWebRunning()) return "运行中";
    return controller.isEnvironmentReady() ? "未运行" : "环境未就绪";
  }

  private static final String RUN_PREFS = "dsha_module_stats";

  /** 启动成功一次记一次；按本地日期分桶，跨天自动从 0 开始。 */
  public static void recordRun(Context context) {
    try {
      String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
      android.content.SharedPreferences prefs =
          context.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE);
      if (!today.equals(prefs.getString("run_date", ""))) {
        prefs.edit().putString("run_date", today).putInt("run_count", 1).apply();
        return;
      }
      prefs.edit().putInt("run_count", prefs.getInt("run_count", 0) + 1).apply();
    } catch (RuntimeException ignored) {
      // 统计失败不影响启动
    }
  }

  public static int runsToday(Context context) {
    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    android.content.SharedPreferences prefs =
        context.getSharedPreferences(RUN_PREFS, Context.MODE_PRIVATE);
    if (!today.equals(prefs.getString("run_date", ""))) return 0;
    return prefs.getInt("run_count", 0);
  }

  /**
   * 系统压力。
   *
   * <p>Android 9 起普通应用读不到 {@code /proc/loadavg}（SELinux 挡），
   * 所以退回用 ActivityManager 的真实内存占用 —— 同样是「这台机器现在有多累」的指标。
   */
  private static String systemLoad(Context context) {
    try {
      android.app.ActivityManager manager =
          (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null) return "—";
      android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
      manager.getMemoryInfo(info);
      if (info.totalMem <= 0) return "—";
      long used = info.totalMem - info.availMem;
      // 只给数字：卡片自己会加「内存|」这类标签，别重复。
      return String.format(Locale.US, "%d%%", Math.round(used * 100d / info.totalMem));
    } catch (RuntimeException error) {
      return "—";
    }
  }

  /** 可用内存（MB），同样取自 /proc/meminfo。 */
  private static String systemMemory() {
    try {
      java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
      try {
        for (String line; (line = reader.readLine()) != null; ) {
          if (line.startsWith("MemAvailable:")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
              long kb = Long.parseLong(parts[1]);
              return String.format(Locale.US, "%.1f GB", kb / 1024d / 1024d);
            }
          }
        }
      } finally {
        reader.close();
      }
    } catch (Exception ignored) {
      // 读不到就显示占位
    }
    return "—";
  }

  private static String firstLine(java.io.File file) {
    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
      return reader.readLine();
    } catch (Exception error) {
      return null;
    }
  }

  private static String freeSpace(Context context) {
    long bytes = context.getFilesDir().getUsableSpace();
    if (bytes <= 0) return "—";
    return String.format(Locale.US, "%.1f GB", bytes / 1024d / 1024d / 1024d);
  }
}
