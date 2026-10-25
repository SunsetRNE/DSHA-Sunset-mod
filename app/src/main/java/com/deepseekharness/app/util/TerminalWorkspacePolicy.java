package com.deepseekharness.app.util;

/** 纯展示策略：页面返回、进程结束和退出核验绝不互相替代。 */
public final class TerminalWorkspacePolicy {
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

  public static boolean sidebar(boolean workbench, int widthDp) {
    return workbench && widthDp >= 840;
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
