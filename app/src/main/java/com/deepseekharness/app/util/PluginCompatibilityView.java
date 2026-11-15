package com.deepseekharness.app.util;

/** 与 guest 兼容性回执共用的纯状态投影；未知状态不伪装成已核验。 */
public final class PluginCompatibilityView {
  public enum State {
    COMPATIBLE,
    UNCONSTRAINED,
    EXEMPTED,
    INCOMPATIBLE,
    UNSUPPORTED_PLATFORM,
    CHECK_FAILED,
    UNKNOWN
  }

  private PluginCompatibilityView() {}

  public static State state(String value) {
    if (value == null) return State.UNKNOWN;
    return switch (value) {
      case "compatible" -> State.COMPATIBLE;
      case "unconstrained" -> State.UNCONSTRAINED;
      case "exempted" -> State.EXEMPTED;
      case "incompatible" -> State.INCOMPATIBLE;
      case "unsupported-platform" -> State.UNSUPPORTED_PLATFORM;
      case "check-failed" -> State.CHECK_FAILED;
      default -> State.UNKNOWN;
    };
  }

  public static boolean blocked(String value) {
    return switch (state(value)) {
      case INCOMPATIBLE, UNSUPPORTED_PLATFORM, CHECK_FAILED -> true;
      default -> false;
    };
  }

  /** 没有约束与精确豁免是独立事实，不能一概显示为兼容。 */
  public static boolean checked(String value) {
    return switch (state(value)) {
      case COMPATIBLE, UNCONSTRAINED, EXEMPTED -> true;
      default -> false;
    };
  }
}
