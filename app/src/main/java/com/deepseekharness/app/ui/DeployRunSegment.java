package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 部署分段条里“正在进行”的那一段：浅强调色底上一道光带沿阅读方向循环扫过，表示该阶段仍在运行。
 *
 * <p>只表达“正在运行”，不推算阶段内进度。动画按系统时钟取相位、随绘制自驱动：视图被重建（部署快照刷新）时
 * 光带位置连续，不持有无限 ValueAnimator；窗口不可见、视图隐藏或系统关闭动画时画成实色、不再重绘。
 */
final class DeployRunSegment extends View {
  /** 光带扫过一次的周期。 */
  private static final long PERIOD_MS = 1400L;

  private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF bounds = new RectF();
  private final Matrix shift = new Matrix();
  private final float radius;
  private LinearGradient gradient;
  private int gradientWidth = -1;
  private final int accent;
  private boolean animationsEnabled = true;
  private boolean windowVisible = true;

  DeployRunSegment(Context context, int accentColor) {
    super(context);
    accent = accentColor;
    radius = 3f * context.getResources().getDisplayMetrics().density;
    base.setColor(withAlpha(accentColor, 0.32f));
    solid.setColor(accentColor);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    animationsEnabled = UiMotion.animationsEnabled(getContext());
    invalidate();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    windowVisible = visibility == VISIBLE;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int width = getWidth(), height = getHeight();
    if (width <= 0 || height <= 0) return;
    bounds.set(0, 0, width, height);
    boolean moving = animationsEnabled && windowVisible && isShown();
    if (!moving) {
      canvas.drawRoundRect(bounds, radius, radius, solid);
      return;
    }
    canvas.drawRoundRect(bounds, radius, radius, base);
    if (gradientWidth != width) {
      // 光带宽为分段的 60%，两端透明、中间实色。
      float bandWidth = width * 0.6f;
      int clear = withAlpha(accent, 0f);
      gradient =
          new LinearGradient(
              0f,
              0f,
              bandWidth,
              0f,
              new int[] {clear, accent, clear},
              new float[] {0f, 0.5f, 1f},
              Shader.TileMode.CLAMP);
      band.setShader(gradient);
      gradientWidth = width;
    }
    float bandWidth = width * 0.6f;
    float phase = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
    // 从完全在左侧外面扫到完全在右侧外面；RTL 下反向。
    float x = -bandWidth + phase * (width + bandWidth);
    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = width - bandWidth - x;
    shift.setTranslate(x, 0f);
    gradient.setLocalMatrix(shift);
    canvas.drawRoundRect(bounds, radius, radius, band);
    postInvalidateOnAnimation();
  }

  private static int withAlpha(int color, float alpha) {
    return (Math.round(alpha * 255f) << 24) | (color & 0x00FFFFFF);
  }
}
