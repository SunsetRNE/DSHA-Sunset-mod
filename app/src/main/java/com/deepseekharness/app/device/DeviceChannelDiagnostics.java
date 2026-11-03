package com.deepseekharness.app.device;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.util.DeviceChannelSnapshot;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设备通道卡住时的自动取证（T38）。
 *
 * <p>root 超时 / 滞留 / 槽位已满、ADB 保活连续失败、覆盖安装后，在后台把当时的通道状态和相关进程表
 * 写到 {@code files/diagnostics/device-channel/}，保留最近 5 份，按触发原因 5 分钟限频。
 * 卡死后重启手机会丢掉 logcat，这份记录留在私有目录里供下次排查。
 *
 * <p>进程表只经 Shizuku 读取（root 本身可能正是卡住的通道，不再向它追加进程）；Shizuku 也不可用时
 * 只记录原生状态。取证失败不影响调用方，也不重试。
 */
public final class DeviceChannelDiagnostics {
  private DeviceChannelDiagnostics() {}

  private static final String TAG = "DSHA-DEVCHAN";
  private static final long PROCESS_READ_TIMEOUT_MS = 10_000L;
  private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
  private static final AtomicBoolean RUNNING = new AtomicBoolean();
  private static volatile Context app;

  public static void init(Context context) {
    if (context != null && app == null) app = context.getApplicationContext();
  }

  /** 异步记录；同一触发原因 5 分钟内只记一次，同时只有一次取证在进行。 */
  public static void record(Context context, String trigger) {
    try {
      init(context);
      Context ctx = app;
      if (ctx == null || trigger == null) return;
      long now = System.currentTimeMillis();
      String key = trigger.contains(":") ? trigger.substring(0, trigger.indexOf(':')) : trigger;
      if (!DeviceChannelSnapshot.due(LAST.get(key), now)) return;
      if (!RUNNING.compareAndSet(false, true)) return;
      LAST.put(key, now);
      Thread worker =
          new Thread(
              () -> {
                try {
                  write(ctx, trigger, now);
                } catch (Throwable error) {
                  Log.w(TAG, "snapshot failed: " + SensitiveData.redact(String.valueOf(error)));
                } finally {
                  RUNNING.set(false);
                }
              },
              "dsha-device-channel-snapshot");
      worker.setDaemon(true);
      worker.start();
    } catch (Throwable ignored) {
      RUNNING.set(false);
    }
  }

  /** 延后记录（例如覆盖安装后等服务端重新拉起）；进程期间被结束则不记录。 */
  public static void recordLater(Context context, String trigger, long delayMs) {
    try {
      init(context);
      Thread delayed =
          new Thread(
              () -> {
                try {
                  Thread.sleep(Math.max(0L, delayMs));
                } catch (InterruptedException interrupted) {
                  return;
                }
                record(app, trigger);
              },
              "dsha-device-channel-delay");
      delayed.setDaemon(true);
      delayed.start();
    } catch (Throwable ignored) {
    }
  }

  /** 最近一次快照所在目录；仅供诊断展示。 */
  public static File directory(Context context) {
    return new File(context.getApplicationContext().getFilesDir(), "diagnostics/device-channel");
  }

  private static void write(Context ctx, String trigger, long now) throws Exception {
    Map<String, String> state = new LinkedHashMap<>();
    state.put("app.versionCode", String.valueOf(com.deepseekharness.app.BuildConfig.VERSION_CODE));
    state.put("app.pid", String.valueOf(android.os.Process.myPid()));
    state.put("root.enabled", String.valueOf(com.deepseekharness.app.RootShell.enabled(ctx)));
    state.put("root.slots", com.deepseekharness.app.RootShell.slotSnapshot());
    state.put("root.last", com.deepseekharness.app.RootShell.lastResultSummary());
    state.put("shizuku", com.deepseekharness.app.ShizukuShell.status());
    state.put(
        "adb.enabled",
        String.valueOf(com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(ctx)));
    state.put("adb.state", com.deepseekharness.app.DeviceBridgeService.adbState);
    state.put(
        "adb.detail", SensitiveData.redact(com.deepseekharness.app.DeviceBridgeService.adbDetail));
    state.put("adb.pairing", String.valueOf(com.deepseekharness.app.bridge.AdbBridge.isPairing()));
    state.put(
        "environment.exclusive", com.deepseekharness.app.util.EnvironmentTaskGate.activeKind());
    state.put(
        "environment.shared",
        com.deepseekharness.app.util.EnvironmentTaskGate.sharedCount()
            + " "
            + com.deepseekharness.app.util.EnvironmentTaskGate.sharedKinds());
    String processes = readProcesses();
    String body =
        DeviceChannelSnapshot.render(
            SensitiveData.redact(trigger),
            now,
            state,
            DeviceChannelSnapshot.filterProcesses(processes));
    File dir = directory(ctx);
    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("SNAPSHOT_DIR");
    File target = null;
    for (int sequence = 0; sequence < 100; sequence++) {
      File candidate = new File(dir, DeviceChannelSnapshot.fileName(now, sequence));
      if (!candidate.exists()) {
        target = candidate;
        break;
      }
    }
    if (target == null) throw new java.io.IOException("SNAPSHOT_NAME");
    File temporary = new File(dir, "." + target.getName() + ".tmp");
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
      out.write(body.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (!temporary.renameTo(target)) {
      temporary.delete();
      throw new java.io.IOException("SNAPSHOT_RENAME");
    }
    String[] names = dir.list();
    if (names != null)
      for (String name :
          DeviceChannelSnapshot.expired(
              new ArrayList<>(Arrays.asList(names)), DeviceChannelSnapshot.KEEP))
        new File(dir, name).delete();
    Log.i(TAG, "snapshot " + target.getName() + " trigger=" + SensitiveData.redact(trigger));
  }

  /** Shizuku 的 Binder 调用没有客户端超时；放到独立线程，等不到就放弃，不阻塞取证。 */
  private static String readProcesses() {
    if (!com.deepseekharness.app.ShizukuShell.hasPermission()) return "";
    String[] result = {""};
    Thread reader =
        new Thread(
            () -> {
              try {
                result[0] =
                    com.deepseekharness.app.ShizukuShell.exec("ps -A -o PID,PPID,UID,ETIME,NAME");
              } catch (Throwable ignored) {
              }
            },
            "dsha-device-channel-ps");
    reader.setDaemon(true);
    reader.start();
    try {
      reader.join(PROCESS_READ_TIMEOUT_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return reader.isAlive() ? "" : result[0];
  }
}
