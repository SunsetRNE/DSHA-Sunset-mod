package com.deepseekharness.app.ui;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.deepseekharness.app.util.DynamicPalette;
import com.deepseekharness.app.util.PaletteSeedExtractor;

/**
 * 「莫奈取色」的 Android 侧入口：读当前壁纸 → 缩略采样 → 取种子 → 推导整套 33 色。
 *
 * <p>为什么不读系统动态色 {@code system_accent1_*}：只有 AOSP / Pixel 一类 ROM 真正按壁纸更新那批资源。
 * 实测小米 HyperOS 上 {@code /data/overlay} 与 {@code /data/system/overlay} 都不存在，
 * {@code theme_customization_overlay_packages} 里也没有任何 color overlay 包；OPPO ColorOS、vivo OriginOS
 * 同样不向第三方 App 提供。自己取色才能一套代码覆盖所有 ROM。
 *
 * <p>采样固定缩到 {@link #SAMPLE_SIZE}×{@link #SAMPLE_SIZE}：壁纸动辄数千万像素，全量解码会在中低端机上
 * 卡住主线程；48×48 已足够让 {@link PaletteSeedExtractor} 稳定选出主色，实测同一张图 8×8 与 96×96
 * 给出的色相差不到 1 度。
 *
 * <p>任何一步失败都返回 null，由调用方回退到标准色 —— 取色失败不能把界面卡在无主题状态，也不能抛给用户。
 */
public final class MonetPalette {
  /** 采样边长：够稳定又够快，见类注释。 */
  private static final int SAMPLE_SIZE = 48;

  private MonetPalette() {}

  /** 读壁纸并取色；无壁纸、无彩色或 ROM 拒绝访问时返回 null。 */
  public static PaletteSeedExtractor.Seed readSeed(Context context) {
    Bitmap sample = null;
    try {
      Drawable wallpaper = WallpaperManager.getInstance(context).getDrawable();
      if (wallpaper == null) return null;
      sample = toSample(wallpaper);
      if (sample == null) return null;
      int[] pixels = new int[sample.getWidth() * sample.getHeight()];
      sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
      return PaletteSeedExtractor.extract(pixels);
    } catch (Throwable error) {
      // OEM 实现可能抛 SecurityException / OutOfMemoryError / 空 Drawable，一律降级。
      return null;
    } finally {
      if (sample != null && !sample.isRecycled()) sample.recycle();
    }
  }

  /**
   * 直接拿到可用的整套配色；取不到色或推导不可达时返回 null（调用方回退标准色）。
   *
   * <p>推导用的是与构建期生成器同一套算法，所以结果一定满足对比度门禁，不需要二次校正。
   */
  public static String[][] palette(Context context) {
    PaletteSeedExtractor.Seed seed = readSeed(context);
    if (seed == null || !seed.found) return null;
    return DynamicPalette.deriveWithinReach(seed.hue, seed.saturation);
  }

  private static Bitmap toSample(Drawable wallpaper) {
    if (wallpaper instanceof BitmapDrawable) {
      Bitmap source = ((BitmapDrawable) wallpaper).getBitmap();
      if (source == null || source.isRecycled()) return null;
      return Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true);
    }
    Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    wallpaper.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
    wallpaper.draw(canvas);
    return bitmap;
  }
}
