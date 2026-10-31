package com.deepseekharness.app.util;

/**
 * 电池 / 后台运行状态的显示判定（纯逻辑）。Android 只开放两项可读事实：
 *
 * <ul>
 *   <li>{@code PowerManager.isIgnoringBatteryOptimizations}：是否在 Doze 豁免名单（“不优化 / 不受限制”）；
 *   <li>{@code ActivityManager.isBackgroundRestricted}：用户是否对本应用显式限制了后台（“受限”）。
 * </ul>
 *
 * 厂商系统（如联想 ZUI、MIUI、ColorOS）另有“允许后台运行 / 自启动 / 应用速冻”等开关，应用无法读取。
 * 因此两项都为否时只能说“未确认”，不能断言用户没有设置；也不能把它当作已允许。
 */
public final class BatteryBackgroundState {
  private BatteryBackgroundState() {}

  public enum State {
    /** 已在电池优化豁免名单：系统层面不因优化而限制后台。 */
    UNRESTRICTED,
    /** 用户显式限制了本应用的后台活动：Web、终端与保活很可能在后台被结束。 */
    RESTRICTED,
    /** 两项均未命中：处于系统默认优化，厂商后台开关无法读取。 */
    UNCONFIRMED
  }

  /** 显式限制优先于豁免：两者同时出现时按受限提示（厂商实现偶有不一致，取保守结论）。 */
  public static State of(boolean ignoringOptimizations, boolean backgroundRestricted) {
    if (backgroundRestricted) return State.RESTRICTED;
    if (ignoringOptimizations) return State.UNRESTRICTED;
    return State.UNCONFIRMED;
  }

  /** 只有确认豁免才计入“已开启”，未确认与受限均不计。 */
  public static boolean granted(State state) {
    return state == State.UNRESTRICTED;
  }
}
