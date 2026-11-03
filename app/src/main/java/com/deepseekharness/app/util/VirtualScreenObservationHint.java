package com.deepseekharness.app.util;

/**
 * 助手侧虚拟屏观察失败的细分说明（T35）。
 *
 * <p>Core 只回 {@code FRAME_NOT_READY} 一个码，它同时可能是「显示里还没有任何窗口」「应用刚启动、首帧未到」
 * 和「有窗口却长时间不出帧」。这里只依据宿主已有的只读事实（无障碍状态、该显示的窗口诊断、Core 报告的
 * 前台包名、距就绪或最近一次启动的时长）给出可区分的码和下一步建议；不改变任何门控、不触发重试、
 * 不切换屏幕。所有失败都是只读观察失败，重新观察是安全的。
 *
 * <p>纯逻辑：不 import Android API。
 */
public final class VirtualScreenObservationHint {
  private VirtualScreenObservationHint() {}

  /** Core 的兼容码；细分码通过 {@code legacyError} 保留它。 */
  public static final String FRAME_NOT_READY = "FRAME_NOT_READY";

  /** 显示里没有任何窗口：先把应用启动到虚拟屏。 */
  public static final String NO_WINDOW = "VSCREEN_NO_WINDOW";

  /** 有窗口（或刚启动/刚创建），首帧还没到：稍等后重新观察。 */
  public static final String FRAME_PENDING = "FRAME_PENDING";

  /** 有窗口但超过等待期仍没有任何帧：告诉用户，可在虚拟屏页查看或关闭后重建。 */
  public static final String FRAME_STALLED = "FRAME_STALLED";

  /** 拿不到窗口证据（无障碍不可用）且 Core 也报不出前台应用：无法判断是空屏还是首帧未到。 */
  public static final String FRAME_UNRESOLVED = FRAME_NOT_READY;

  public static final String LAUNCH_APP_FIRST = "LAUNCH_APP_FIRST";
  public static final String WAIT_AND_SEE_AGAIN = "WAIT_AND_SEE_AGAIN";
  public static final String LAUNCH_APP_OR_WAIT = "LAUNCH_APP_OR_WAIT";
  public static final String REPORT_TO_USER = "REPORT_TO_USER";
  public static final String ENABLE_ACCESSIBILITY = "ENABLE_ACCESSIBILITY";
  public static final String WAIT_ACCESSIBILITY_RECONNECT = "WAIT_ACCESSIBILITY_RECONNECT";
  public static final String SEE_AGAIN = "SEE_AGAIN";

  /** 首帧等待期：超过后有窗口仍无帧才判停滞。启动/创建/旋转都会重新计时。 */
  public static final long FRAME_PENDING_MS = 10_000L;

  /** 无障碍服务开启状态（与 DshaAccessibilityService.enabledState 的 YES/NO/UNKNOWN 对应）。 */
  public enum Accessibility {
    CONNECTED,
    ENABLED_NOT_CONNECTED,
    NOT_ENABLED,
    UNKNOWN_NOT_CONNECTED;

    public static Accessibility of(boolean connected, String enabledState) {
      if (connected) return CONNECTED;
      if ("NO".equals(enabledState)) return NOT_ENABLED;
      if ("YES".equals(enabledState)) return ENABLED_NOT_CONNECTED;
      return UNKNOWN_NOT_CONNECTED;
    }
  }

  public enum WindowState {
    NONE,
    PRESENT,
    UNKNOWN
  }

  /** 一次细分结果；字段都是稳定的 ASCII 码。 */
  public static final class Hint {
    public final String error, windowState, windowEvidence, nextAction;
    public final boolean retryable;

    Hint(
        String error,
        WindowState windowState,
        String windowEvidence,
        String nextAction,
        boolean retryable) {
      this.error = error;
      this.windowState = windowState.name();
      this.windowEvidence = windowEvidence;
      this.nextAction = nextAction;
      this.retryable = retryable;
    }
  }

  /**
   * 该显示的窗口状态。{@code windowDiagnostic} 是 ScreenTargetResolver 的诊断码（READABLE / NO_WINDOWS /
   * NO_UNIQUE_APPLICATION_WINDOW / ROOT_UNAVAILABLE / WINDOW_ID_MISMATCH / ACCESSIBILITY_UNAVAILABLE）；
   * 无障碍未连接时诊断不可信，只看 Core 报告的前台包名。
   */
  public static WindowState windowState(
      Accessibility accessibility, String windowDiagnostic, String foregroundPackage) {
    boolean hasPackage = foregroundPackage != null && !foregroundPackage.isEmpty();
    if (accessibility == Accessibility.CONNECTED && windowDiagnostic != null) {
      switch (windowDiagnostic) {
        case "NO_WINDOWS":
          // 窗口列表为空而 Core 仍看到任务：多半是刚启动、窗口尚未登记。
          return hasPackage ? WindowState.PRESENT : WindowState.NONE;
        case "READABLE":
        case "NO_UNIQUE_APPLICATION_WINDOW":
        case "ROOT_UNAVAILABLE":
        case "WINDOW_ID_MISMATCH":
          return WindowState.PRESENT;
        default:
          break;
      }
    }
    return hasPackage ? WindowState.PRESENT : WindowState.UNKNOWN;
  }

  private static String evidence(Accessibility accessibility, String windowDiagnostic) {
    return accessibility == Accessibility.CONNECTED
            && windowDiagnostic != null
            && !windowDiagnostic.equals("ACCESSIBILITY_UNAVAILABLE")
        ? "ACCESSIBILITY"
        : "CORE_TASKS";
  }

  /**
   * 把 Core 的 FRAME_NOT_READY 细分；其它码返回 null（调用方原样回传）。
   *
   * @param sinceStartMs 距显示就绪或最近一次 launch 的毫秒数，取较小者；未知传负数（按首帧等待处理）。
   */
  public static Hint frameNotReady(
      String coreError,
      Accessibility accessibility,
      String windowDiagnostic,
      String foregroundPackage,
      long sinceStartMs) {
    if (!FRAME_NOT_READY.equals(coreError)) return null;
    WindowState state = windowState(accessibility, windowDiagnostic, foregroundPackage);
    String source = evidence(accessibility, windowDiagnostic);
    if (state == WindowState.NONE)
      return new Hint(NO_WINDOW, state, source, LAUNCH_APP_FIRST, true);
    if (state == WindowState.UNKNOWN)
      return new Hint(FRAME_UNRESOLVED, state, source, LAUNCH_APP_OR_WAIT, true);
    if (sinceStartMs < 0 || sinceStartMs < FRAME_PENDING_MS)
      return new Hint(FRAME_PENDING, state, source, WAIT_AND_SEE_AGAIN, true);
    return new Hint(FRAME_STALLED, state, source, REPORT_TO_USER, true);
  }

  /**
   * see 降级为纯图片时 anchoredAvailable=false 的具体原因，替代笼统的 ANCHOR_ACCESSIBILITY_UNAVAILABLE。
   *
   * @param targetEvidence 本次请求是否带有虚拟屏窗口目标证据（windowId ≥ 0）
   * @param consentEvidence 本次请求是否带有确认代次证据
   */
  public static String anchorUnavailable(
      Accessibility accessibility,
      String windowDiagnostic,
      boolean explicitRequest,
      boolean targetEvidence,
      boolean consentEvidence) {
    if (accessibility == Accessibility.NOT_ENABLED) return "ANCHOR_ACCESSIBILITY_NOT_ENABLED";
    if (accessibility != Accessibility.CONNECTED) return "ANCHOR_ACCESSIBILITY_NOT_CONNECTED";
    if (!explicitRequest) return "ANCHOR_REQUEST_EVIDENCE_REQUIRED";
    if (!targetEvidence)
      return "NO_WINDOWS".equals(windowDiagnostic)
          ? "ANCHOR_NO_WINDOW"
          : "ANCHOR_TARGET_EVIDENCE_UNAVAILABLE";
    if (!consentEvidence) return "ANCHOR_CONSENT_EVIDENCE_UNAVAILABLE";
    return "ANCHOR_TARGET_EVIDENCE_UNAVAILABLE";
  }

  /** tree / node / editor 因拿不到虚拟屏窗口而未发送时的下一步建议。 */
  public static String semanticNextAction(Accessibility accessibility, String windowDiagnostic) {
    if (accessibility == Accessibility.NOT_ENABLED) return ENABLE_ACCESSIBILITY;
    if (accessibility != Accessibility.CONNECTED) return WAIT_ACCESSIBILITY_RECONNECT;
    if ("NO_WINDOWS".equals(windowDiagnostic)) return LAUNCH_APP_FIRST;
    if ("ROOT_UNAVAILABLE".equals(windowDiagnostic)) return WAIT_AND_SEE_AGAIN;
    return SEE_AGAIN;
  }

  /** tree 未发送时的细分原因；error 仍保留 ACCESSIBILITY_UNAVAILABLE 兼容旧调用方。 */
  public static String semanticReason(Accessibility accessibility, String windowDiagnostic) {
    if (accessibility == Accessibility.NOT_ENABLED) return "ACCESSIBILITY_NOT_ENABLED";
    if (accessibility != Accessibility.CONNECTED) return "ACCESSIBILITY_NOT_CONNECTED";
    if ("NO_WINDOWS".equals(windowDiagnostic)) return NO_WINDOW;
    return "VSCREEN_WINDOW_UNREADABLE";
  }
}
