package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public class RuntimeUpgradeStateTest {
  @Test
  public void compatibleButStaleRuntimeIsNotUpToDate() {
    // 环境兼容可见（旧树还能跑）但仍待受管补丁：维护页不能把它当“无事可做”。
    assertFalse(RuntimeUpgradeState.upToDate(true, false));
    assertTrue(RuntimeUpgradeState.upToDate(true, true));
    assertFalse(RuntimeUpgradeState.upToDate(false, true));
    assertFalse(RuntimeUpgradeState.upToDate(false, false));
  }

  @Test
  public void maintenanceRequiredCoversEnvironmentAndPatchGenerations() {
    assertTrue(RuntimeUpgradeState.maintenanceRequired(false, false));
    assertTrue(RuntimeUpgradeState.maintenanceRequired(false, true));
    // 补丁级覆盖：环境兼容可见，但内置受管补丁尚未落地，主界面仍要留出维护入口。
    assertTrue(RuntimeUpgradeState.maintenanceRequired(true, false));
    assertFalse(RuntimeUpgradeState.maintenanceRequired(true, true));
  }

  @Test
  public void pendingPatchMakesLimitedEntry() {
    // 补丁级未完成必须按受限身份进入主界面，否则主界面门禁会立刻送回维护页。
    assertTrue(RuntimeUpgradeState.limitedEntry(true, false, false, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(false, true, false, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(true, true, true, false));
    assertTrue(RuntimeUpgradeState.limitedEntry(true, true, false, true));
    assertFalse(RuntimeUpgradeState.limitedEntry(true, true, false, false));
  }
}
