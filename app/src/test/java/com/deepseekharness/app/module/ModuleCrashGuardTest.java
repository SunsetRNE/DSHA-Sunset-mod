package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 崩溃看门狗：记录崩溃模块 → 下次启动只停用它一次 → 正常启动清掉「启动中」标记。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCrashGuardTest {

  private static final String CARD =
      "{\"schema\":1,\"id\":\"a.card\",\"name\":\"卡片\",\"actions\":[]}";

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ModuleCardStore.importCard(context, CARD);
  }

  private android.content.SharedPreferences guard() {
    return context.getSharedPreferences("dsha_module_guard", 0);
  }

  @Test
  public void cleanStartKeepsModulesEnabledAndClearsBootFlag() {
    assertTrue(ModuleCrashGuard.recover(context).isEmpty());
    assertTrue(ModuleState.isEnabled(context, "a.card"));
    ModuleCrashGuard.bootCompleted(context);
    assertFalse(guard().getBoolean("boot_pending", true));
  }

  @Test
  public void recordedCrashDisablesTheModuleExactlyOnce() {
    // 崩溃现场：进程没走完，偏好里留着模块 id。
    guard().edit().putString("crashed", "a.card").putBoolean("boot_pending", true).commit();
    assertEquals("a.card", ModuleCrashGuard.recover(context));
    assertFalse(ModuleState.isEnabled(context, "a.card"));
    // 再启动一次不应该重复报告，也不该再动别的模块。
    assertEquals("", ModuleCrashGuard.recover(context));
    ModuleCrashGuard.bootCompleted(context);
    assertFalse(guard().getBoolean("boot_pending", true));
  }

  @Test
  public void crashWithoutBootMarkerIsIgnored() {
    // 正常退出后遗留的 id 不能当成崩溃（boot_pending 仍为 false）。
    guard().edit().putString("crashed", "a.card").putBoolean("boot_pending", false).commit();
    assertTrue(ModuleCrashGuard.recover(context).isEmpty());
    assertTrue(ModuleState.isEnabled(context, "a.card"));
  }

  @Test
  public void labelUsesCardNameAndFallsBackToId() {
    assertEquals("卡片（a.card）", ModuleCrashGuard.labelOf(context, "a.card"));
    assertEquals("missing.card", ModuleCrashGuard.labelOf(context, "missing.card"));
  }
}
