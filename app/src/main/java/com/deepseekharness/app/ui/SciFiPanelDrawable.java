package com.deepseekharness.app.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 横屏右侧胶囊的底板（212 起）：深色玻璃 + 冷色细描边 + 四角括号。
 *
 * <p>理由：横屏默认壁纸换成深色科幻图之后，原来的白色玻璃胶囊（{@code bg_dock}）在深底上
 * 像一张贴纸；这里把胶囊压成半透明深色面板，边框和四角括号跟三档按钮的 C 款同语言，
 * 整块横屏界面看起来是一套 HUD。
 */
final class SciFiPanelDrawable extends Drawable {

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int alpha = 255;
  private final RectF rect = new RectF();
  private final float density;

  SciFiPanelDrawable(float density) {
    this.density = Math.max(1f, density);
    paint.setStyle(Paint.Style.FILL);
  }

  @Override
  public void draw(Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) return;
    float inset = dp(0.5f);
    rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);

    // ① 深色底
    paint.setStyle(Paint.Style.FILL);
    color(LandscapePalette.PANEL_FILL);
    canvas.drawRect(rect, paint);

    // ② 冷色细描边
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1f));
    color(LandscapePalette.PANEL_BORDER);
    canvas.drawRect(rect, paint);

    // ③ 四角括号
    paint.setStrokeWidth(dp(1.6f));
    color(LandscapePalette.PANEL_BRACKET);
    float len = dp(10f);
    canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, paint);
    canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, paint);
    canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, paint);
    canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, paint);
    canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, paint);
    canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, paint);
    canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, paint);
    canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, paint);
  }

  private float dp(float value) {
    return value * density;
  }

  @Override
  public void setAlpha(int alpha) {
    this.alpha = alpha;
    invalidateSelf();
  }

  /** 每次换色都重新套上整体 alpha，避免 setColor 覆盖掉 setAlpha 的效果。 */
  private void color(int value) {
    paint.setColor(value);
    paint.setAlpha(alpha);
  }

  /** 测试用：draw 之后画笔上实际生效的 alpha（验证 color() 每次都会重新套上整体 alpha）。 */
  int paintAlphaForTest() {
    return paint.getAlpha();
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {
    paint.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
