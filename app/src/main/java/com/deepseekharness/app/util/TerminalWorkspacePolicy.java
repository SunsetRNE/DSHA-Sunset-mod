package com.deepseekharness.app.util;

/** 纯展示策略：页面返回、进程结束和退出核验绝不互相替代。 */
public final class TerminalWorkspacePolicy {
  private static final int INLINE_MIN_WIDTH_DP = 840;
  private static final int TERMINAL_MIN_WIDTH_DP = 480;
  private static final int COMPACT_HEIGHT_DP = 560;
  private static final int DENSE_FOOTER_HEIGHT_DP = 480;
  private static final int DRAWER_MAX_WIDTH_DP = 336;
  private static final int INLINE_MIN_WIDTH = 240;
  private static final int INLINE_MAX_WIDTH = 296;

  private TerminalWorkspacePolicy() {}

  public enum Back {
    DISMISS_PANEL,
    LEAVE_SELECTION,
    HIDE_IME,
    LEAVE_PAGE
  }

  public static Back back(boolean panel, boolean selection, boolean ime) {
    if (panel) return Back.DISMISS_PANEL;
    if (selection) return Back.LEAVE_SELECTION;
    if (ime) return Back.HIDE_IME;
    return Back.LEAVE_PAGE;
  }

  public static boolean restoreSelection(
      String savedEpoch, String currentEpoch, long id, boolean exists) {
    return savedEpoch != null && savedEpoch.equals(currentEpoch) && id > 0 && exists;
  }

  /**
   * 宽屏是否具备内联侧栏的空间：除 840dp 断点外，终端仍须保留最小可读宽度。 极简模式只在侧栏被显式打开时使用该形态；工作台宽屏则常驻。
   */
  public static boolean canInline(int widthDp) {
    return widthDp >= INLINE_MIN_WIDTH_DP
        && widthDp - inlineWidth(widthDp) >= TERMINAL_MIN_WIDTH_DP;
  }

  /** 内联侧栏宽度：约四分之一窗口，240–296dp，避免平板上的手机窄栏。 */
  public static int inlineWidth(int widthDp) {
    return Math.max(INLINE_MIN_WIDTH, Math.min(INLINE_MAX_WIDTH, Math.round(widthDp * .25f)));
  }

  /** 抽屉最大宽度：平板上仍保持可读；窄窗口由遮罩边距继续收缩。 */
  public static int drawerMaxWidth() {
    return DRAWER_MAX_WIDTH_DP;
  }

  /** 抽屉宽度：保留至少 marginPx 的遮罩/手势区；窗口尚未测量时用最大宽度。 */
  public static int drawerWidth(int availablePx, int maxPx, int marginPx) {
    int width = Math.min(maxPx, Math.max(0, availablePx - Math.max(0, marginPx)));
    return width > 0 ? width : maxPx;
  }

  /** 短窗口隐藏说明文字并收紧内边距，优先保证会话与底部动作可达。 */
  public static boolean compactHeight(int heightDp) {
    return heightDp < COMPACT_HEIGHT_DP;
  }

  /** 更短的窗口把底部动作改为三列两行，避免固定三行挤掉会话列表。 */
  public static boolean denseFooter(int heightDp) {
    return heightDp < DENSE_FOOTER_HEIGHT_DP;
  }

  public static float fraction(float value) {
    return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : .6f;
  }

  public static int ballY(int height, int size, int margin, int reservedBottom, float fraction) {
    int low = Math.min(margin, Math.max(0, height - size));
    int high = Math.max(low, height - size - margin - Math.max(0, reservedBottom));
    return low + Math.round((high - low) * fraction(fraction));
  }

  public static int ballX(int width, int size, int margin, boolean right) {
    int low = Math.min(margin, Math.max(0, width - size));
    return right ? Math.max(low, width - size - margin) : low;
  }
}
