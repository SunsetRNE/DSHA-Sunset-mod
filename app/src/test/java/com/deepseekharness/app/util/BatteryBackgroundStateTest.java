package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class BatteryBackgroundStateTest {
  @Test
  public void exemptionWithoutRestrictionIsUnrestricted() {
    var state = BatteryBackgroundState.of(true, false);
    assertEquals(BatteryBackgroundState.State.UNRESTRICTED, state);
    assertTrue(BatteryBackgroundState.granted(state));
  }

  @Test
  public void neitherSignalIsUnconfirmedNotDenied() {
    // 厂商“允许后台运行”开关应用读不到：不能报成“未设置”，也不能算已开启。
    var state = BatteryBackgroundState.of(false, false);
    assertEquals(BatteryBackgroundState.State.UNCONFIRMED, state);
    assertFalse(BatteryBackgroundState.granted(state));
  }

  @Test
  public void explicitRestrictionWinsOverExemption() {
    assertEquals(BatteryBackgroundState.State.RESTRICTED, BatteryBackgroundState.of(false, true));
    assertEquals(BatteryBackgroundState.State.RESTRICTED, BatteryBackgroundState.of(true, true));
    assertFalse(BatteryBackgroundState.granted(BatteryBackgroundState.State.RESTRICTED));
  }
}
