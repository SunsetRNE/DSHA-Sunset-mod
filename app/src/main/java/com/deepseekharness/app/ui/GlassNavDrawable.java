package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.deepseekharness.app.R;

/**
 * 底部胶囊导航的毛玻璃底：把壁纸按窗口 centerCrop 的同一套映射先模糊、再裁到胶囊里，
 * 上面压一层 75% 主题底色，最后补 1dp 描边与顶部高光。
 *
 * <p>为什么不是「真·实时模糊」：导航浮动在页面内容之上，内容随时在滚；
 * 逐帧截取内容做 RenderEffect 在低端机上代价太高。壁纸本身是固定的，
 * 对壁纸做一次性模糊（缓存复用）已经能得到稳定的毛玻璃观感，
 * 而且和 {@link WindowBackdrop} 的裁切完全对齐，不会出现错位。
 */
final class GlassNavDrawable extends Drawable {

  private static Bitmap cachedBlur;
  private static boolean cachedNight;

  private final Bitmap blur;
  private final int tint;
  private final int border;
  private final int gloss;
  private final float radius;

  /** 模糊层只做「纹理」：45% 叠加，保证胶囊整体是半透明，能透出下面的内容。 */
  private static final int BLUR_ALPHA = 140;
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
  private final Paint flat = new Paint(Paint.DITHER_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path clip = new Path();
  private final RectF box = new RectF();

  private GlassNavDrawable(Context context, Bitmap blur, float radiusDp) {
    this.blur = blur;
    this.radius = radiusDp * context.getResources().getDisplayMetrics().density;
    this.tint = context.getColor(R.color.shell_nav_glass);
    paint.setAlpha(BLUR_ALPHA);
    this.border = context.getColor(R.color.shell_line_strong);
    this.gloss = context.getColor(R.color.shell_nav_sheen);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(context.getResources().getDisplayMetrics().density);
    // 玻璃的边是「受光」，不是「描深色线」：用很淡的白色内亮边代替深色描边。
    stroke.setColor(context.getColor(R.color.shell_nav_edge));
  }

  static Drawable create(Context context, float radiusDp) {
    Bitmap blur = blurredWallpaper(context);
    if (blur == null) return new ColorDrawable(context.getColor(R.color.shell_nav_bg));
    return new GlassNavDrawable(context, blur, radiusDp);
  }

  /** 半尺寸解码 → 缩到 1/6 → 两遍方框模糊；只做一次并按日夜缓存。 */
  private static Bitmap blurredWallpaper(Context context) {
    boolean night =
        (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    if (cachedBlur != null && !cachedBlur.isRecycled() && cachedNight == night) return cachedBlur;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = 2;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded =
        BitmapFactory.decodeResource(context.getResources(), R.drawable.wallpaper_dsha, options);
    if (decoded == null) return null;
    int w = Math.max(1, decoded.getWidth() / 6);
    int h = Math.max(1, decoded.getHeight() / 6);
    Bitmap small = Bitmap.createScaledBitmap(decoded, w, h, true);
    if (small != decoded) decoded.recycle();
    Bitmap blurred = boxBlur(boxBlur(small, 6), 4);
    if (blurred != small) small.recycle();
    cachedBlur = blurred;
    cachedNight = night;
    return blurred;
  }

  /** 可分离方框模糊：先横后竖，够画出毛玻璃的柔度，也不占启动时间。 */
  private static Bitmap boxBlur(Bitmap src, int radius) {
    int w = src.getWidth(), h = src.getHeight();
    int[] in = new int[w * h];
    src.getPixels(in, 0, w, 0, 0, w, h);
    int[] tmp = new int[w * h];
    int[] out = new int[w * h];
    int span = radius * 2 + 1;
    // 横向
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int a = 0, r = 0, g = 0, b = 0, n = 0;
        for (int k = -radius; k <= radius; k++) {
          int xx = x + k;
          if (xx < 0 || xx >= w) continue;
          int c = in[y * w + xx];
          a += Color.alpha(c);
          r += Color.red(c);
          g += Color.green(c);
          b += Color.blue(c);
          n++;
        }
        tmp[y * w + x] = Color.argb(a / n, r / n, g / n, b / n);
      }
    }
    // 纵向
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int a = 0, r = 0, g = 0, b = 0, n = 0;
        for (int k = -radius; k <= radius; k++) {
          int yy = y + k;
          if (yy < 0 || yy >= h) continue;
          int c = tmp[yy * w + x];
          a += Color.alpha(c);
          r += Color.red(c);
          g += Color.green(c);
          b += Color.blue(c);
          n++;
        }
        out[y * w + x] = Color.argb(a / n, r / n, g / n, b / n);
      }
    }
    Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    result.setPixels(out, 0, w, 0, 0, w, h);
    if (span <= 0) return result;
    return result;
  }

  @Nullable
  private View host() {
    Object callback = getCallback();
    return callback instanceof View view ? view : null;
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty() || blur.isRecycled()) return;
    box.set(bounds);
    clip.reset();
    clip.addRoundRect(box, radius, radius, Path.Direction.CW);

    canvas.save();
    canvas.clipPath(clip);
    boolean drewBackdrop = false;
    View view = host();
    View root = view == null ? null : view.getRootView();
    if (!drewBackdrop && view != null && root != null && root.getWidth() > 0 && root.getHeight() > 0) {
      int[] offset = new int[2];
      view.getLocationInWindow(offset);
      float scale =
          Math.max(root.getWidth() / (float) blur.getWidth(), root.getHeight() / (float) blur.getHeight());
      float left = (root.getWidth() - blur.getWidth() * scale) / 2f;
      float top = (root.getHeight() - blur.getHeight() * scale) / 2f;
      // 把「窗口里这一块」换算成壁纸坐标，模糊层与底图完全对齐。
      float sx = (offset[0] - left) / scale;
      float sy = (offset[1] - top) / scale;
      float sw = bounds.width() / scale;
      float sh = bounds.height() / scale;
      RectF src = new RectF(sx, sy, sx + sw, sy + sh);
      canvas.drawBitmap(blur, new Rect((int) src.left, (int) src.top, (int) src.right, (int) src.bottom),
          bounds, paint);
      drewBackdrop = true;
    }
    if (!drewBackdrop) canvas.drawRect(bounds, flat);

    flat.setColor(tint);
    canvas.drawRect(bounds, flat);
    sheen.setShader(
        new LinearGradient(
            bounds.left,
            bounds.top,
            bounds.left,
            bounds.top + bounds.height() * 0.55f,
            gloss,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP));
    canvas.drawRect(bounds, sheen);
    canvas.restore();

    canvas.drawRoundRect(box, radius, radius, stroke);
  }

  @Override
  public void setAlpha(int alpha) {}

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {}

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
