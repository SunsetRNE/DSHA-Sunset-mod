package com.deepseekharness.app.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/** 手机底部导航的降采样背景模糊；只采集内容区，不采集导航自身。 */
final class BottomNavigationBlur {
  /**
   * 采样倍率与节流。
   *
   * <p>旧实现每 160ms 在主线程 {@code content.draw()} 重绘一次整页，滚动时等于
   * 每帧多画一遍全页 —— 这就是「底栏后面一卡一卡」的根因。现在改成：
   * 滚动期间完全不采样，停下 260ms 才采一次；静止时 1.2s 兜底刷新一次。
   */
  private static final int SAMPLE_SCALE = 6;

  private static final long REFRESH_INTERVAL_MS = 1200;
  private static final long SETTLE_DELAY_MS = 260;
  private static final float CORNER_DP = 22f;
  private static final int SAMPLE_PADDING = 4;

  private final Activity activity;
  private final View content;
  private final View navigation;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final java.util.concurrent.ExecutorService worker =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "dsha-navigation-background-blur");
            thread.setDaemon(true);
            return thread;
          });
  private final Runnable refresh = this::capture;

  /** 整个窗口的滚动都算「还在动」：滚动中不采样，避免和滚动抢主线程。 */
  private final ViewTreeObserver.OnScrollChangedListener scrollListener =
      () -> lastScrollAt = android.os.SystemClock.uptimeMillis();

  private final View.OnAttachStateChangeListener attachment =
      new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
          scheduleCapture();
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
          generation++;
          handler.removeCallbacks(refresh);
          captureScheduled = false;
          release();
        }
      };

  private boolean enabled;
  private boolean closed;
  private boolean captureScheduled;
  private boolean inFlight;
  private long generation;
  private long lastCapture;
  private long lastScrollAt;
  private Bitmap sample;
  private GlassDrawable glass;

  BottomNavigationBlur(Activity activity, View content, View navigation) {
    this.activity = activity;
    this.content = content;
    this.navigation = navigation;
    navigation.addOnAttachStateChangeListener(attachment);
    ViewTreeObserver observer = content.getViewTreeObserver();
    if (observer.isAlive()) observer.addOnScrollChangedListener(scrollListener);
  }

  void setEnabled(boolean enabled) {
    if (closed) return;
    // Background glass is drawn by GlassDrawable; never attach an effect to the navigation View.
    if (this.enabled == enabled) {
      if (enabled) scheduleCapture();
      return;
    }
    this.enabled = enabled;
    generation++;
    handler.removeCallbacks(refresh);
    captureScheduled = false;
    release();
    navigation.setBackgroundResource(com.deepseekharness.app.R.drawable.bg_shell_nav);
    if (enabled) scheduleCapture();
  }

  void refresh() {
    scheduleCapture();
  }

  void close() {
    if (closed) return;
    closed = true;
    generation++;
    handler.removeCallbacks(refresh);
    captureScheduled = false;
    ViewTreeObserver observer = content.getViewTreeObserver();
    if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollListener);
    navigation.removeOnAttachStateChangeListener(attachment);
    worker.shutdown();
    release();
    navigation.setBackgroundResource(com.deepseekharness.app.R.drawable.bg_shell_nav);
  }

  private void scheduleCapture() {
    if (!enabled
        || closed
        || inFlight
        || captureScheduled
        || !content.isShown()
        || !navigation.isShown()) return;
    long now = android.os.SystemClock.uptimeMillis();
    // 刚滚动过就等它停稳，不要每帧抢一次绘制。
    long sinceScroll = now - lastScrollAt;
    long waitForScroll = sinceScroll < SETTLE_DELAY_MS ? SETTLE_DELAY_MS - sinceScroll : 0;
    long sinceCapture = now - lastCapture;
    long waitForInterval = Math.max(0, REFRESH_INTERVAL_MS - sinceCapture);
    captureScheduled = true;
    handler.postDelayed(refresh, Math.max(waitForScroll, waitForInterval));
  }

  private void capture() {
    captureScheduled = false;
    if (!enabled || closed || inFlight || !content.isShown() || !navigation.isShown()) return;
    if (content.getWidth() <= 0
        || content.getHeight() <= 0
        || navigation.getWidth() <= 0
        || navigation.getHeight() <= 0) return;
    int width = Math.max(1, (navigation.getWidth() + SAMPLE_SCALE - 1) / SAMPLE_SCALE);
    int height = Math.max(1, (navigation.getHeight() + SAMPLE_SCALE - 1) / SAMPLE_SCALE);
    Bitmap low = null;
    try {
      // 只分配底栏背后的局部采样，不再分配整页截图或在主线程卷积。
      low =
          Bitmap.createBitmap(
              width + SAMPLE_PADDING * 2, height + SAMPLE_PADDING * 2, Bitmap.Config.ARGB_8888);
      int[] contentLocation = new int[2], navLocation = new int[2];
      content.getLocationInWindow(contentLocation);
      navigation.getLocationInWindow(navLocation);
      Canvas canvas = new Canvas(low);
      canvas.translate(SAMPLE_PADDING, SAMPLE_PADDING);
      canvas.scale(1f / SAMPLE_SCALE, 1f / SAMPLE_SCALE);
      canvas.translate(contentLocation[0] - navLocation[0], contentLocation[1] - navLocation[1]);
      content.draw(canvas);
      Bitmap input = low;
      long ticket = generation;
      inFlight = true;
      lastCapture = android.os.SystemClock.uptimeMillis();
      worker.execute(
          () -> {
            Bitmap blurred = null;
            try {
              blurred = gaussianBlur(input);
            } catch (RuntimeException ignored) {
              // 失败保留清晰的不透明底色，不向前景应用模糊。
            } finally {
              input.recycle();
            }
            Bitmap result = blurred;
            handler.post(
                () -> {
                  inFlight = false;
                  if (result != null) {
                    if (!closed
                        && enabled
                        && ticket == generation
                        && navigation.isAttachedToWindow()) replaceSample(result);
                    else result.recycle();
                  }
                  // 静止时按 REFRESH_INTERVAL_MS 兜底刷新，保证状态变化也能跟上。
                  scheduleCapture();
                });
          });
    } catch (RuntimeException ignored) {
      inFlight = false;
      if (low != null && !low.isRecycled()) low.recycle();
    }
  }

  private void replaceSample(Bitmap bitmap) {
    if (sample != null && !sample.isRecycled()) sample.recycle();
    sample = bitmap;
    if (glass == null) {
      glass = new GlassDrawable(activity, () -> sample);
      navigation.setBackground(glass);
    } else glass.invalidateSelf();
  }

  private void release() {
    // 先解绑 Drawable，再回收旧采样；重新开启必须重新挂载背景层。
    navigation.setBackgroundResource(com.deepseekharness.app.R.drawable.bg_shell_nav);
    glass = null;
    if (sample != null && !sample.isRecycled()) sample.recycle();
    sample = null;
  }

  static Bitmap gaussianBlur(Bitmap source) {
    int width = source.getWidth();
    int height = source.getHeight();
    int[] input = new int[width * height];
    int[] output = new int[input.length];
    source.getPixels(input, 0, width, 0, 0, width, height);
    int radius = 4;
    float sigma = 2.2f;
    float[] kernel = new float[radius * 2 + 1];
    float total = 0;
    for (int i = -radius; i <= radius; i++) {
      float weight = (float) Math.exp(-(i * i) / (2f * sigma * sigma));
      kernel[i + radius] = weight;
      total += weight;
    }
    for (int i = 0; i < kernel.length; i++) kernel[i] /= total;
    blurPass(input, output, width, height, kernel, radius, true);
    blurPass(output, input, width, height, kernel, radius, false);
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    result.setPixels(input, 0, width, 0, 0, width, height);
    return result;
  }

  private static void blurPass(
      int[] input,
      int[] output,
      int width,
      int height,
      float[] kernel,
      int radius,
      boolean horizontal) {
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        float a = 0, r = 0, g = 0, b = 0;
        for (int offset = -radius; offset <= radius; offset++) {
          int sx = horizontal ? clamp(x + offset, width) : x;
          int sy = horizontal ? y : clamp(y + offset, height);
          int color = input[sy * width + sx];
          float weight = kernel[offset + radius];
          a += Color.alpha(color) * weight;
          r += Color.red(color) * weight;
          g += Color.green(color) * weight;
          b += Color.blue(color) * weight;
        }
        output[y * width + x] =
            Color.argb(Math.round(a), Math.round(r), Math.round(g), Math.round(b));
      }
    }
  }

  private static int clamp(int value, int limit) {
    if (limit <= 0) return 0;
    return Math.max(0, Math.min(limit - 1, value));
  }

  private static final class GlassDrawable extends Drawable {
    private final Activity activity;
    private final java.util.function.Supplier<Bitmap> bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();

    GlassDrawable(Activity activity, java.util.function.Supplier<Bitmap> bitmap) {
      this.activity = activity;
      this.bitmap = bitmap;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
      RectF bounds = new RectF(getBounds());
      float radius = activity.getResources().getDisplayMetrics().density * CORNER_DP;
      clip.reset();
      clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
      int save = canvas.save();
      canvas.clipPath(clip);
      Bitmap image = bitmap.get();
      if (image != null && !image.isRecycled()) {
        paint.setAlpha(255);
        canvas.drawBitmap(
            image,
            new android.graphics.Rect(
                SAMPLE_PADDING,
                SAMPLE_PADDING,
                image.getWidth() - SAMPLE_PADDING,
                image.getHeight() - SAMPLE_PADDING),
            bounds,
            paint);
      }
      @ColorInt int themeColor = activity.getColor(com.deepseekharness.app.R.color.shell_nav_bg);
      int overlay =
          Color.argb(
              ThemeController.isDark(activity) ? 150 : 158,
              Color.red(themeColor),
              Color.green(themeColor),
              Color.blue(themeColor));
      paint.setColor(overlay);
      paint.setAlpha(Color.alpha(overlay));
      canvas.drawRect(bounds, paint);
      paint.setColor(activity.getColor(com.deepseekharness.app.R.color.shell_line_strong));
      paint.setAlpha(255);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(activity.getResources().getDisplayMetrics().density);
      canvas.drawRoundRect(bounds, radius, radius, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {
      paint.setColorFilter(filter);
      invalidateSelf();
    }

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }
}
