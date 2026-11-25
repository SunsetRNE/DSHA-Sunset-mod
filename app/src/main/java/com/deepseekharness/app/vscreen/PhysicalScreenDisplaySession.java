package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import com.deepseekharness.app.util.PrivilegedScreenPolicy;
import com.deepseekharness.app.util.PrivilegedScreenRoutes;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Physical channel metadata. Starting or inspecting this channel never starts a display capture.
 */
final class PhysicalScreenDisplaySession {
  private final Point size;
  private final int density;
  private final PrivilegedScreenPolicy.FrameConfig config;
  private final String generation = UUID.randomUUID().toString();

  private PhysicalScreenDisplaySession(
      Point size, int density, PrivilegedScreenPolicy.FrameConfig config) {
    this.size = size;
    this.density = density;
    this.config = config;
  }

  static PhysicalScreenDisplaySession create(PrivilegedScreenPolicy.FrameConfig config) {
    Context context = VirtualScreenCore.privilegedContext();
    DisplayManager manager =
        context == null ? null : context.getSystemService(DisplayManager.class);
    Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
    if (display == null) return null;
    Point size = new Point();
    display.getRealSize(size);
    if (size.x <= 0 || size.y <= 0) return null;
    DisplayMetrics metrics = new DisplayMetrics();
    display.getRealMetrics(metrics);
    return new PhysicalScreenDisplaySession(size, metrics.densityDpi, config);
  }

  boolean matches(Point currentSize, PrivilegedScreenPolicy.FrameConfig candidate) {
    return size.equals(currentSize) && config.equals(candidate);
  }

  JSONObject status() throws Exception {
    String pkg = PhysicalScreenCore.currentPackage();
    int[] capture = PrivilegedScreenPolicy.captureSize(size.x, size.y, config.maxWidth());
    return new JSONObject()
        .put("ok", true)
        .put("state", "COMPLETED")
        .put("active", true)
        .put("physical", true)
        .put("displayId", Display.DEFAULT_DISPLAY)
        .put("coreInstance", VirtualScreenCore.coreInstanceId())
        .put("generation", generation)
        .put("screenWidth", size.x)
        .put("screenHeight", size.y)
        .put("densityDpi", density)
        .put("width", capture[0])
        .put("height", capture[1])
        .put("package", pkg)
        .put("packageVerified", !pkg.isEmpty())
        .put("frameConfig", PhysicalScreenCore.frameConfigJson())
        .put("observationUnavailable", PrivilegedScreenRoutes.WINDOW_SOURCE_REQUIRED)
        .put(
            "nextAction",
            PrivilegedScreenRoutes.windowSourceNextAction(android.os.Build.VERSION.SDK_INT))
        .put("accessibilityRequired", false);
  }
}
