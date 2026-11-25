package com.deepseekharness.app.vscreen;

import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.deepseekharness.app.util.PrivilegedScreenPolicy;
import com.deepseekharness.app.util.PrivilegedScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenFailures;
import java.util.Map;
import org.json.JSONObject;

/**
 * Metadata and lifecycle for the physical channel. Package approval cannot authorize a display
 * mirror.
 */
final class PhysicalScreenCore {
  static final int DISPLAY_ID = 0;
  private static final int TASK_QUERY_LIMIT = 100;

  private static PhysicalScreenDisplaySession session;
  private static PrivilegedScreenPolicy.FrameConfig config =
      PrivilegedScreenPolicy.FrameConfig.defaults();

  private PhysicalScreenCore() {}

  static synchronized JSONObject route(String path, Map<String, String> query) {
    if (PrivilegedScreenRoutes.corePing(path)) {
      JSONObject alive = new JSONObject();
      try {
        alive.put("ok", true);
      } catch (Exception ignored) {
      }
      return alive;
    }
    String operation = PrivilegedScreenRoutes.coreOperation(path);
    if (operation.isEmpty()) return failure("UNKNOWN_ROUTE", "NOT_SENT", false);
    try {
      if (operation.equals("config")) return configure(query);
      if (operation.equals("close")) {
        release();
        return new JSONObject().put("ok", true).put("state", "COMPLETED");
      }
      if (PrivilegedScreenRoutes.needsMirrorObservation(operation))
        return failure(PrivilegedScreenRoutes.WINDOW_SOURCE_REQUIRED, "NOT_SENT", false)
            .put(
                "nextAction",
                PrivilegedScreenRoutes.windowSourceNextAction(android.os.Build.VERSION.SDK_INT));
      PhysicalScreenDisplaySession active = ensureSession();
      if (active == null) return failure("PRIV_DISPLAY_UNAVAILABLE", "NOT_SENT", false);
      return active.status();
    } catch (Throwable error) {
      return failure(VirtualScreenFailures.code(error), "NOT_SENT", false);
    }
  }

  private static JSONObject configure(Map<String, String> query) throws Exception {
    Integer maxWidth = optionalInt(query, "maxWidth");
    Integer quality = optionalInt(query, "jpegQuality");
    Integer fps = optionalInt(query, "maxFps");
    String invalid = PrivilegedScreenPolicy.configRejection(maxWidth, quality, fps);
    if (invalid != null) return failure(invalid, "NOT_SENT", false);
    PrivilegedScreenPolicy.FrameConfig next =
        new PrivilegedScreenPolicy.FrameConfig(
            maxWidth == null ? config.maxWidth() : maxWidth,
            quality == null ? config.jpegQuality() : quality,
            fps == null ? config.maxFps() : fps);
    if (!next.equals(config)) {
      config = next;
      release();
    }
    return new JSONObject()
        .put("ok", true)
        .put("state", "COMPLETED")
        .put("frameConfig", frameConfigJson())
        .put("recreated", true)
        .put("accessibilityRequired", false);
  }

  /** 当前显示尺寸变化（旋转、分屏、分辨率切换）或帧配置变化时重建镜像并更换代次。 */
  private static PhysicalScreenDisplaySession ensureSession() throws Exception {
    Point size = currentDisplaySize();
    if (size == null) return null;
    if (session != null && session.matches(size, config)) return session;
    release();
    session = PhysicalScreenDisplaySession.create(config);
    return session;
  }

  /** A full display mirror cannot be attributed to either of two visible application tasks. */
  static String currentPackage() {
    try {
      var context = VirtualScreenCore.privilegedContext();
      var manager =
          (android.app.ActivityManager)
              context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
      return singleVisiblePackage(manager.getRunningTasks(TASK_QUERY_LIMIT));
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  static String singleVisiblePackage(
      java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks) {
    // A full page may omit another visible task. Its contents cannot establish exclusivity.
    if (tasks == null || tasks.size() >= TASK_QUERY_LIMIT) return "";
    String owner = "";
    try {
      for (var task : tasks) {
        if (task.getClass().getField("displayId").getInt(task) != DISPLAY_ID) continue;
        boolean visible =
            android.os.Build.VERSION.SDK_INT >= 32
                ? task.isVisible()
                : task.getClass().getField("isVisible").getBoolean(task);
        if (!visible) continue;
        if (task.topActivity == null) return "";
        String name = task.topActivity.getPackageName();
        if (name.isEmpty() || !owner.isEmpty() && !owner.equals(name)) return "";
        owner = name;
      }
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return "";
    }
    return owner;
  }

  private static Point currentDisplaySize() {
    try {
      android.content.Context context = VirtualScreenCore.privilegedContext();
      DisplayManager manager =
          (DisplayManager) context.getSystemService(android.content.Context.DISPLAY_SERVICE);
      Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
      if (display == null) return null;
      Point size = new Point();
      display.getRealSize(size);
      return size.x > 0 && size.y > 0 ? size : null;
    } catch (Throwable unavailable) {
      return null;
    }
  }

  static synchronized void release() {
    session = null;
  }

  static synchronized JSONObject frameConfigJson() throws Exception {
    return new JSONObject()
        .put("maxWidth", config.maxWidth())
        .put("jpegQuality", config.jpegQuality())
        .put("maxFps", config.maxFps());
  }

  private static Integer optionalInt(Map<String, String> query, String name) {
    String value = query.get(name);
    if (value == null || value.isEmpty()) return null;
    if (!value.matches("[0-9]{1,6}")) throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Integer.parseInt(value);
  }

  private static JSONObject failure(String code, String state, boolean unknown) {
    JSONObject value = VirtualScreenTransport.failure(code, unknown);
    try {
      value
          .put("state", state)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false);
    } catch (Throwable ignored) {
      // VirtualScreenTransport.failure 保证 ok/error 已经写入。
    }
    return value;
  }
}
