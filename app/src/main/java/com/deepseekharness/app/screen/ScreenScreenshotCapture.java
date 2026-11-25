package com.deepseekharness.app.screen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.deepseekharness.app.util.ScreenCaptureDelivery;
import com.deepseekharness.app.util.ScreenPixelFingerprint;
import com.deepseekharness.app.util.ScreenWindowRegion;
import java.util.concurrent.TimeUnit;

/** 截图读取、像素指纹及系统缓冲区所有权；不登记“已观察”。 */
public final class ScreenScreenshotCapture {
  /** The existing API code plus the actual callback cause; a system status alone has no cause. */
  static final class CaptureFailure extends IllegalStateException {
    CaptureFailure(String code, Throwable cause) {
      super(code, cause);
    }
  }

  private static final java.util.concurrent.Executor CALLBACKS =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(8),
          r -> {
            Thread thread = new Thread(r, "screen-capture-callback");
            thread.setDaemon(true);
            return thread;
          },
          new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

  public static final class Shot implements AutoCloseable {
    public final Bitmap bitmap;
    public final int rotation;
    public final String fingerprint;
    public final ScreenWindowRegion region;

    Shot(Bitmap bitmap, int rotation, ScreenWindowRegion region) throws Exception {
      this.bitmap = bitmap;
      this.rotation = rotation;
      this.region = region;
      requireVisibleContent(region);
      int width = bitmap.getWidth(), height = bitmap.getHeight();
      if (!region.within(width, height))
        throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
      var content = region.content();
      // Retain physical coordinates for gestures. Only the approved content rectangle has pixels.
      Canvas canvas = new Canvas(bitmap);
      Paint black = new Paint();
      black.setColor(Color.BLACK);
      canvas.drawRect(0, 0, width, content.top(), black);
      canvas.drawRect(0, content.bottom(), width, height, black);
      canvas.drawRect(0, content.top(), content.left(), content.bottom(), black);
      canvas.drawRect(content.right(), content.top(), width, content.bottom(), black);
      for (var exclusion : region.exclusions()) {
        var b = exclusion.bounds();
        canvas.drawRect(b.left(), b.top(), b.right(), b.bottom(), black);
      }
      fingerprint =
          ScreenPixelFingerprint.hash(
              width,
              height,
              content,
              (row, left, y, count) -> bitmap.getPixels(row, 0, count, left, y, count, 1));
    }

    @Override
    public void close() {
      bitmap.recycle();
    }
  }

  private static void requireVisibleContent(ScreenWindowRegion region) {
    if (region == null || region.content() == null || !region.content().valid())
      throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
    if (!region.hasUnexcludedArea(region.content()))
      throw new IllegalStateException("SCREEN_TARGET_WINDOW_OCCLUDED");
  }

  // Window captures are local to the window. Publish in the same physical coordinate space as
  // input.
  static Bitmap positionWindow(Bitmap window, ScreenWindowRegion region, Point displaySize) {
    var bounds = region.window();
    if (!bounds.within(displaySize.x, displaySize.y)
        || window.getWidth() != bounds.right() - bounds.left()
        || window.getHeight() != bounds.bottom() - bounds.top())
      throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
    if (displaySize.x == window.getWidth() && displaySize.y == window.getHeight()) return window;
    Bitmap positioned = Bitmap.createBitmap(displaySize.x, displaySize.y, Bitmap.Config.ARGB_8888);
    try {
      positioned.eraseColor(Color.BLACK);
      new Canvas(positioned)
          .drawBitmap(
              window,
              null,
              new Rect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom()),
              null);
      return positioned;
    } catch (RuntimeException | Error failure) {
      positioned.recycle();
      throw failure;
    }
  }

  public Shot capture(ScreenRequestContext context) throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 34)
      throw new IllegalStateException("SCREEN_WINDOW_CAPTURE_UNSUPPORTED");
    final ScreenWindowRegion region = context.currentRegionIfKnown();
    requireVisibleContent(region);
    int displayId = context.target().displayId;
    DisplayManager displays = context.service().getSystemService(DisplayManager.class);
    Display display = displays == null ? null : displays.getDisplay(displayId);
    if (display == null) throw new IllegalStateException("SCREEN_DISPLAY_UNAVAILABLE");
    int rotation = display.getRotation();
    Point displaySize = new Point();
    display.getRealSize(displaySize);
    if (displaySize.x <= 0
        || displaySize.y <= 0
        || (long) displaySize.x * displaySize.y > 16 * 1024 * 1024)
      throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
    ScreenCaptureDelivery<Shot> pending =
        new ScreenCaptureDelivery<>(
            context::current,
            Shot::close,
            failure ->
                MainScreenActions.recordFailure(
                    context,
                    MainScreenActions.FailureStage.LATE_CAPTURE,
                    failure instanceof Exception error
                        ? safeCode(error)
                        : "SCREEN_OPERATION_UNAVAILABLE",
                    failure,
                    true));
    var waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
    AccessibilityService.TakeScreenshotCallback callback =
        new AccessibilityService.TakeScreenshotCallback() {
          @Override
          public void onSuccess(AccessibilityService.ScreenshotResult result) {
            try {
              CALLBACKS.execute(() -> decode(result));
            } catch (java.util.concurrent.RejectedExecutionException full) {
              result.getHardwareBuffer().close();
              pending.fail("SCREENSHOT_BUSY");
            }
          }

          private void decode(AccessibilityService.ScreenshotResult result) {
            Bitmap hardware = null, software = null;
            Shot shot = null;
            try {
              if (!waiting.get()) return;
              if (!context.current()) throw new IllegalStateException("SCREENSHOT_RUN_CHANGED");
              hardware =
                  Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
              if (hardware == null) throw new IllegalStateException("SCREENSHOT_DECODE_FAILED");
              if ((long) hardware.getWidth() * hardware.getHeight() > 16 * 1024 * 1024)
                throw new IllegalStateException("SCREENSHOT_TOO_LARGE");
              software = hardware.copy(Bitmap.Config.ARGB_8888, true);
              if (software == null) throw new IllegalStateException("SCREENSHOT_DECODE_FAILED");
              Bitmap positioned = positionWindow(software, region, displaySize);
              if (positioned != software) software.recycle();
              software = positioned;
              shot = new Shot(software, rotation, region);
              software = null;
              pending.complete(shot);
              shot = null;
            } catch (Exception failure) {
              pending.fail(safeCode(failure), failure);
            } finally {
              if (shot != null) shot.close();
              if (software != null) software.recycle();
              if (hardware != null) hardware.recycle();
              result.getHardwareBuffer().close();
            }
          }

          @Override
          public void onFailure(int code) {
            pending.fail("SCREENSHOT_SYSTEM_" + code);
          }
        };
    // The callback hands off the owned buffer. Decoding runs on the bounded worker above.
    context.service().takeScreenshotOfWindow(context.target().windowId, Runnable::run, callback);
    Shot result;
    try {
      long remaining =
          Math.min(10_000L, context.request().deadlineElapsedRealtimeMillis - context.nowMillis());
      result = pending.await(remaining);
      if (result == null) throw new CaptureFailure(pending.error(), pending.takeFailure());
    } finally {
      waiting.set(false);
      pending.close();
    }
    try {
      if (!java.util.Objects.equals(region, context.currentRegionIfKnown()))
        throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_CHANGED");
      Display latest = displays.getDisplay(displayId);
      if (latest == null || latest.getRotation() != rotation)
        throw new IllegalStateException("SCREEN_GEOMETRY_CHANGED");
      return result;
    } catch (Exception rejected) {
      result.close();
      throw rejected;
    }
  }

  static String safeCode(Exception error) {
    String message = error.getMessage();
    return message != null && message.matches("[A-Z][A-Z0-9_]{0,80}")
        ? message
        : "SCREEN_OPERATION_UNAVAILABLE";
  }
}
