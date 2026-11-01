package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.deepseekharness.app.R;

/**
 * 全屏壁纸底：把内置壁纸按 centerCrop 铺满窗口，再叠一层主题遮罩。
 *
 * <p>壁纸挂在 Activity 的 content 背景上，而不是塞进每个页面布局：主界面、内页、抽屉式页面和
 * 悬浮胶囊都共用同一张底图。页面根布局只要保持透明即可，不再各铺一套不透明底色。
 *
 * <p>本版只内置固定壁纸（白天 / 夜间各一张），不提供选图、裁剪与遮罩滑杆；用户自选壁纸是
 * 后续独立改动，不在这里预留半成品入口。
 */
final class WindowBackdrop extends Drawable {

  private static Bitmap cachedBitmap;
  private static boolean cachedNight;

  private final Bitmap bitmap;
  private final int scrim;
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

  private WindowBackdrop(Bitmap bitmap, int scrim) {
    this.bitmap = bitmap;
    this.scrim = scrim;
  }

  /** 创建一个跟随当前日夜配置的壁纸底；解不出图时退回页面兜底色，不抛异常。 */
  static Drawable create(Context context) {
    boolean night =
        (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    Bitmap bitmap = wallpaper(context, night);
    if (bitmap == null) {
      return new ColorDrawable(context.getColor(R.color.surface));
    }
    return new WindowBackdrop(bitmap, context.getColor(R.color.shell_backdrop_scrim));
  }

  /**
   * 壁纸按半尺寸解码后常驻：一张 1260×2800 的渐变图缩到 630×1400 约 3.5 MB，
   * 多个 Activity 共用同一份，避免每次创建页面都重新解码。
   */
  private static Bitmap wallpaper(Context context, boolean night) {
    if (cachedBitmap != null && !cachedBitmap.isRecycled() && cachedNight == night) {
      return cachedBitmap;
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 2;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded =
        BitmapFactory.decodeResource(context.getResources(), R.drawable.wallpaper_dsha, options);
    if (decoded != null) {
      cachedBitmap = decoded;
      cachedNight = night;
    }
    return decoded;
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty() || bitmap.isRecycled()) {
      return;
    }
    float scale =
        Math.max(
            bounds.width() / (float) bitmap.getWidth(),
            bounds.height() / (float) bitmap.getHeight());
    float left = bounds.left + (bounds.width() - bitmap.getWidth() * scale) / 2f;
    float top = bounds.top + (bounds.height() - bitmap.getHeight() * scale) / 2f;
    canvas.save();
    canvas.translate(left, top);
    canvas.scale(scale, scale);
    canvas.drawBitmap(bitmap, 0f, 0f, paint);
    canvas.restore();
    canvas.drawColor(scrim);
  }

  @Override
  public void setAlpha(int alpha) {
    // 壁纸底由遮罩控制观感，不接受外部再叠一层 alpha。
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    // 固定壁纸，不做着色。
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
