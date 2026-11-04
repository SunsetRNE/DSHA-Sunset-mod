package com.deepseekharness.app.util;

/**
 * 横屏 DSH 显示的纯几何：三档 CSS 视口、缩放与居中。
 *
 * <p>WebView 的布局尺寸 = CSS 视口 × density，再用 View 缩放压进可用区域；
 * 先按 density 倍率渲染再缩小 = 超采样，清晰度最好（与 b-3 Dock 同一套做法）。
 *
 * <p>这里只放数字，不 import 任何 Android API，便于单测。
 */
public final class DshStageGeometry {
  /**
   * 三档纯放大：1280×720（×1.00）/ 1024×576（×1.25）/ 854×480（×1.50）。
   *
   * <p>三档都是 16:9，所以每一档都铺满舞台；字号倍率 = 1280 / 宽度，
   * 与屏幕密度无关（密度在缩放里约掉了），换机型关系不变。
   */
  public static final int[] MODE_W = {1280, 1024, 854};

  public static final int[] MODE_H = {720, 576, 480};

  public static final int MODE_COUNT = MODE_W.length;

  public static final int DEFAULT_MODE = 0;

  private DshStageGeometry() {}

  public static int clampMode(int mode) {
    return mode < 0 ? 0 : Math.min(mode, MODE_COUNT - 1);
  }

  public static int cssWidth(int mode) {
    return MODE_W[clampMode(mode)];
  }

  public static int cssHeight(int mode) {
    return MODE_H[clampMode(mode)];
  }

  /** WebView 布局宽度（物理 px）。CSS 宽度 × density。 */
  public static int layoutWidth(int mode, float density) {
    return Math.round(cssWidth(mode) * density);
  }

  /** WebView 布局高度（物理 px）。CSS 高度 × density。 */
  public static int layoutHeight(int mode, float density) {
    return Math.round(cssHeight(mode) * density);
  }

  /**
   * 缩放比例：把「布局尺寸」塞进可用区域，取 min，保证不变形、多余方向留边。
   *
   * @return 0 表示参数无效（调用方应跳过这一帧）
   */
  public static float scale(int mode, float density, int areaW, int areaH) {
    if (areaW <= 0 || areaH <= 0 || density <= 0) return 0f;
    return Math.min(
        areaW / (float) layoutWidth(mode, density), areaH / (float) layoutHeight(mode, density));
  }

  /** 居中偏移（物理 px）：{left, top}。 */
  public static int[] centerOffset(int mode, float density, int areaW, int areaH) {
    float scale = scale(mode, density, areaW, areaH);
    int viewW = Math.round(layoutWidth(mode, density) * scale);
    int viewH = Math.round(layoutHeight(mode, density) * scale);
    return new int[] {Math.max(0, (areaW - viewW) / 2), Math.max(0, (areaH - viewH) / 2)};
  }

  /** 文字相对模式1的放大倍数（模式高度越小，字越大）。 */
  public static float textScale(int mode) {
    return MODE_H[DEFAULT_MODE] / (float) cssHeight(mode);
  }

  /** 16:9 舞台比例。 */
  private static final float RATIO = 16f / 9f;

  /** 判断「窗口被键盘压矮」的容差（dp）。 */
  private static final int RESIZE_TOLERANCE_DP = 8;

  /** 键盘态保底可见高度（dp）。 */
  private static final int MIN_VISIBLE_DP = 120;

  /** 一帧布局的全部结果：WebView 布局尺寸、缩放、缩略视口与落位。 */
  public static final class Stage {
    public final boolean keyboard;
    public final int layoutW;
    public final int stageH;
    public final float scale;
    public final int left;
    public final int top;
    public final int viewW;
    public final int viewH;

    Stage(
        boolean keyboard,
        int layoutW,
        int stageH,
        float scale,
        int left,
        int top,
        int viewW,
        int viewH) {
      this.keyboard = keyboard;
      this.layoutW = layoutW;
      this.stageH = stageH;
      this.scale = scale;
      this.left = left;
      this.top = top;
      this.viewW = viewW;
      this.viewH = viewH;
    }
  }

  /**
   * 生产布局的唯一实现：把窗口/键盘参数换成 {@link Stage}。
   *
   * <p>纯函数：不读 Android、不写状态。{@code lastScale} 是上一帧的缩放，
   * 只用于键盘态沿用缩放（让网页自己 reflow），首次进入键盘态前传 0。
   *
   * @return {@code null} 表示参数无效（调用方跳过这一帧）
   */
  public static Stage compute(
      int mode,
      float density,
      int windowW,
      int windowH,
      int imeBottom,
      int fullScreenH,
      float lastScale) {
    if (density <= 0f || windowW <= 0 || windowH <= 0) return null;
    int ww = layoutWidth(mode, density);
    int wh = layoutHeight(mode, density);
    int ime = Math.max(0, imeBottom);

    // 键盘把窗口压矮时，16:9 适配区按满屏高度算；没有键盘时用窗口高度，
    // 分屏/小窗下不能被撑出界。
    int fitH = ime > 0 ? Math.max(windowH, fullScreenH) : windowH;
    if (fitH <= 0) return null;
    int nw;
    int nh;
    if ((float) windowW / (float) fitH > RATIO) {
      nh = fitH;
      nw = (int) (fitH * RATIO);
    } else {
      nw = windowW;
      nh = (int) (windowW / RATIO);
    }

    boolean windowResized =
        fullScreenH > 0 && windowH < fullScreenH - dp(RESIZE_TOLERANCE_DP, density);
    int availH = windowH;
    if (ime > 0 && !windowResized) {
      availH = Math.max(dp(MIN_VISIBLE_DP, density), windowH - ime);
    }

    boolean keyboard = ime > 0 && lastScale > 0f;
    float scale;
    int webH;
    if (keyboard) {
      scale = lastScale;
      webH = Math.max(dp(MIN_VISIBLE_DP, density), Math.round(availH / scale));
    } else {
      scale = Math.min(nw / (float) ww, nh / (float) wh);
      if (scale <= 0f) return null;
      webH = wh;
    }

    int viewH = Math.round(webH * scale);
    int top = viewH >= availH ? availH - viewH : Math.round((availH - viewH) / 2f);
    int viewW = Math.round(ww * scale);
    int left = Math.round((windowW - viewW) / 2f);
    return new Stage(keyboard, ww, webH, scale, left, top, viewW, viewH);
  }

  /** dp → 物理 px。 */
  public static int dp(int value, float density) {
    return Math.round(value * density);
  }
}
