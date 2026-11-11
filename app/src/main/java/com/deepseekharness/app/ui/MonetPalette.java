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

  /** 进程内缓存：每个 Activity 创建都解一次壁纸会让冷启动明显变慢，而壁纸几乎不变。 */
  private static String[][] cached;

  private static boolean cacheKnown;

  /**
   * 缓存对应的系统壁纸 id：每次设置新壁纸 id 必变，是比广播可靠得多的自动失效信号
   * （各 ROM 广播时机不一，很多国产 ROM 根本不发），见 {@link #palette}。
   */
  private static int cachedWallpaperId;

  /**
   * 丢掉全部进程级缓存，主要给测试用。
   *
   * <p>为什么需要它：缓存是 static，Robolectric 在同一 sandbox 内跨测试类共享这些字段，
   * 一个测试留下的「已缓存 = 某张壁纸的结果」会被下一个测试当成自己的缓存命中，测试之间
   * 就不再互相独立。生产代码不需要调用它。
   */
  static void resetCacheForTest() {
    cached = null;
    cacheKnown = false;
    cachedWallpaperId = 0;
    ThemePaletteOverride.resetForTest();
  }

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
      // 能走到这里的 sample 一定是本类自己造出来的（缩放副本 / copy 副本 / Canvas 位图），
      // recycle 不会碰 WallpaperManager 拥有的原壁纸位图。
      if (sample != null && !sample.isRecycled()) sample.recycle();
    }
  }

  /**
   * 直接拿到可用的整套配色；取不到色或推导不可达时返回 null（调用方回退标准色）。
   *
   * <p>推导用的是与构建期生成器同一套算法，所以结果一定满足对比度门禁，不需要二次校正。
   *
   * <p>缓存键带系统壁纸 id（{@code getWallpaperId} 是 API 24+，minSdk 30 可直接用）：id 由
   * WallpaperManagerService 分配，换一次壁纸必变一次。每次调用先查 id，变了自动作废重取 ——
   * 用户换完壁纸回到应用就能看到新配色，不必点「重新取色」，也不依赖时机不一的壁纸变更广播。
   */
  public static String[][] palette(Context context) {
    int wallpaperId = currentWallpaperId(context);
    if (cacheKnown && wallpaperId == cachedWallpaperId) return cached;
    // 先算完再写缓存：原实现把 cacheKnown 置位放在解码之前，一旦解码或推导中途失败，
    // 就会留下「已缓存 = true 但值是 null」的永久空缓存（壁纸 id 不变就再也不会重试），
    // 表现为取色功能对该张壁纸彻底失效。现在失败也照常写入（null 是合法结果），
    // 但绝不会出现「标志已置位、值还没算出来」的中间态。
    String[][] computed;
    try {
      PaletteSeedExtractor.Seed seed = readSeed(context);
      computed =
          seed == null || !seed.found
              ? null
              : DynamicPalette.deriveWithinReach(seed.hue, seed.saturation);
    } catch (Throwable error) {
      computed = null;
    }
    cached = computed;
    cachedWallpaperId = wallpaperId;
    cacheKnown = true;
    return cached;
  }

  /**
   * 丢掉缓存：换壁纸或用户手动「重新取色」后，必须让下一次应用真的重新解码。
   *
   * <p>换壁纸的自动失效已由 {@link #palette} 里的壁纸 id 检查兜住；这里保留给用户手动触发，
   * 清掉后下一次 palette 必然重新解码壁纸。
   */
  static void invalidate() {
    cacheKnown = false;
    cached = null;
    ThemePaletteOverride.invalidate();
  }

  /**
   * 系统壁纸的当前 id；读不到（个别 ROM 限制或服务异常）返回 0。返回 0 只损失自动失效
   * （缓存沿用到最后一次成功取色或手动重取），不影响取色本身，所以吞掉异常。
   */
  private static int currentWallpaperId(Context context) {
    try {
      return WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
    } catch (Throwable error) {
      return 0;
    }
  }

  private static Bitmap toSample(Drawable wallpaper) {
    if (wallpaper instanceof BitmapDrawable) {
      Bitmap source = ((BitmapDrawable) wallpaper).getBitmap();
      if (source == null || source.isRecycled()) return null;
      // createScaledBitmap 在源图尺寸恰好等于目标尺寸时按 Android 文档约定原样返回入参（不是副本）。
      // 直接把返回值交给调用方，readSeed 的 finally 会把 WallpaperManager 拥有的壁纸位图 recycle 掉，
      // 之后系统再画壁纸就崩。所以相等时先 copy 出自己的副本再参与后续；copy 失败返回 null 走回退。
      Bitmap scaled = Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true);
      if (scaled == source) return source.copy(Bitmap.Config.ARGB_8888, false);
      return scaled;
    }
    Bitmap bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    wallpaper.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
    wallpaper.draw(canvas);
    return bitmap;
  }
}
