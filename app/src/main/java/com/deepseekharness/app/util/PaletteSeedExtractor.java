package com.deepseekharness.app.util;

/**
 * 从位图像素里提取调色板种子：一个色相与一个饱和度，交给 {@link DynamicPalette} 推导整套配色。
 *
 * <p>为什么自己做而不读系统动态色：`system_accent1_*` 只有 AOSP / Pixel 一类 ROM 真的随壁纸更新，
 * 小米 HyperOS、OPPO ColorOS、vivo OriginOS 等都不向第三方 App 提供真实壁纸色（实测 HyperOS 上
 * {@code theme_customization_overlay_packages} 没有任何 color overlay 包）。自己做取色才能一套代码
 * 覆盖所有 ROM。
 *
 * <p>算法刻意保持可解释、可测：按 15 度分 24 个色相桶，用饱和度为权重累加，桶内用
 * cos / sin 加权平均求色相（避免红 0/359 两侧被拆成两桶），最后用覆盖率门槛判断“这张图到底有没有彩色”。
 * 不做黑箱聚类：壁纸取色一旦取偏，用户看到的是一整套跑掉的主题，必须能解释为什么取到这个色。
 *
 * <p>纯逻辑：不 import 任何 Android API，输入是已经解码好的 ARGB 像素。
 */
public final class PaletteSeedExtractor {
  /** 色相桶数：每桶 15 度，够粗到抗噪，够细到能区分相邻色系。 */
  private static final int HUE_BUCKETS = 24;

  /** 低于此饱和度的像素视为无彩色（灰、白、黑），不参与色相投票。 */
  private static final double MIN_PIXEL_SATURATION = 0.10;

  /** 过暗的像素色相不可靠（深灰蓝会被读成蓝），过亮的像素多是白底。 */
  private static final double MIN_PIXEL_LIGHTNESS = 0.14;

  private static final double MAX_PIXEL_LIGHTNESS = 0.90;

  /** 彩色像素占比门槛：低于千分之四认为整图基本无彩，交给回退路径而不是硬造一个色。 */
  private static final double MIN_COLORFUL_SHARE = 0.004;

  /** 推导器在低饱和下也能出图，但太灰的种子会让“取色”看起来没生效，因此给一个可见下限。 */
  private static final double MIN_SEED_SATURATION = 0.35;

  private static final double MAX_SEED_SATURATION = 0.95;

  private PaletteSeedExtractor() {}

  /** 取色结果。{@code found} 为 false 时其余字段无意义，调用方应回退到标准色或让用户自选图片。 */
  public static final class Seed {
    public final boolean found;
    public final double hue;
    public final double saturation;

    /** 有效彩色像素占全部不透明像素的比例，用于诊断与“颜色很淡”提示。 */
    public final double colorfulShare;

    Seed(boolean found, double hue, double saturation, double colorfulShare) {
      this.found = found;
      this.hue = hue;
      this.saturation = saturation;
      this.colorfulShare = colorfulShare;
    }
  }

  private static final Seed MISSING = new Seed(false, 0, 0, 0);

  /** 对整幅像素取色；{@code pixels} 为 ARGB_8888 打包整数。 */
  public static Seed extract(int[] pixels) {
    return extract(pixels, 0, pixels.length);
  }

  /** 对像素区间取色：[start, end)，便于大图分块抽样。 */
  public static Seed extract(int[] pixels, int start, int end) {
    double[] weight = new double[HUE_BUCKETS];
    double[] saturationSum = new double[HUE_BUCKETS];
    double[] cosSum = new double[HUE_BUCKETS];
    double[] sinSum = new double[HUE_BUCKETS];
    long opaque = 0;
    long colorful = 0;
    int from = Math.max(0, start);
    int to = Math.min(pixels.length, end);
    for (int index = from; index < to; index++) {
      int argb = pixels[index];
      if ((argb >>> 24) < 128) continue;
      opaque++;
      double[] hls =
          DynamicPalette.rgbToHls(
              new double[] {
                ((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0
              });
      double lightness = hls[1];
      double saturation = hls[2];
      if (saturation < MIN_PIXEL_SATURATION) continue;
      if (lightness < MIN_PIXEL_LIGHTNESS || lightness > MAX_PIXEL_LIGHTNESS) continue;
      colorful++;
      double hueInDegrees = hls[0] * 360;
      int bucket = Math.min(HUE_BUCKETS - 1, (int) (hueInDegrees / (360.0 / HUE_BUCKETS)));
      weight[bucket] += saturation;
      saturationSum[bucket] += saturation * saturation;
      cosSum[bucket] += Math.cos(Math.toRadians(hueInDegrees)) * saturation;
      sinSum[bucket] += Math.sin(Math.toRadians(hueInDegrees)) * saturation;
    }
    if (opaque == 0) return MISSING;
    double share = colorful / (double) opaque;
    if (share < MIN_COLORFUL_SHARE) return new Seed(false, 0, 0, share);
    int best = 0;
    for (int index = 1; index < HUE_BUCKETS; index++)
      if (weight[index] > weight[best]) best = index;
    if (weight[best] <= 0) return new Seed(false, 0, 0, share);
    double hue = Math.toDegrees(Math.atan2(sinSum[best], cosSum[best]));
    if (hue < 0) hue += 360;
    double saturation = saturationSum[best] / weight[best];
    double tuned = clamp(saturation * 1.15, MIN_SEED_SATURATION, MAX_SEED_SATURATION);
    return new Seed(true, Math.round(hue * 10) / 10.0, Math.round(tuned * 1000) / 1000.0, share);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
