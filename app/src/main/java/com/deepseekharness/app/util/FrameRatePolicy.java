package com.deepseekharness.app.util;

/** 原生界面与 Web 工作台共用的高刷新率选择策略；不依赖 Android。 */
public final class FrameRatePolicy {
  private static final float DEFAULT_CAP_HZ = 120f;
  private static final float HIGH_REFRESH_THRESHOLD_HZ = 60.5f;
  private static final float RATE_TOLERANCE_HZ = 0.01f;

  private FrameRatePolicy() {}

  /**
   * 返回应向系统请求的刷新率；0 表示保持系统默认策略。
   *
   * <p>请求不超过用户峰值和 120 Hz；容差仅用于面板浮点舍入。读不到设置也不请求
   * 144/165 Hz。省电模式和仅支持 60 Hz 时均交还系统管理。</p>
   */
  public static float select(
      boolean powerSaveMode, float configuredPeakHz, float[] supportedRatesHz) {
    if (powerSaveMode || supportedRatesHz == null || supportedRatesHz.length == 0) return 0f;
    float cap =
        finite(configuredPeakHz) && configuredPeakHz > 0f
            ? Math.min(configuredPeakHz, DEFAULT_CAP_HZ)
            : DEFAULT_CAP_HZ;
    float selected = 0f;
    for (float rate : supportedRatesHz) {
      if (!finite(rate) || rate <= HIGH_REFRESH_THRESHOLD_HZ) continue;
      if (rate <= cap + RATE_TOLERANCE_HZ && rate > selected) selected = rate;
    }
    return selected;
  }

  private static boolean finite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }
}
