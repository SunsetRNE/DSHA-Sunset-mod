package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 模块 id 允许 {@code .}，键结构必须保证 {@code a} 与 {@code a.b} 并存时互不清除。
 *
 * <p>旧版按 {@code <id>.} 前缀清理：删掉 {@code a} 会顺带清掉 {@code a.b} 的开关、
 * 授权与界面授权。键分隔符换成 id 不允许的 {@code |} 之后，这些用例锁住正确行为。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleStateKeyIsolationTest {

  private final Context context = RuntimeEnvironment.getApplication();

  @Test
  public void clearDoesNotTouchDotSiblings() {
    ModuleState.setEnabled(context, ModuleState.SCOPE_CARD, "a", true);
    ModuleState.setEnabled(context, ModuleState.SCOPE_CARD, "a.b", true);
    ModuleState.setSlot(context, "a", "line1", "A");
    ModuleState.setSlot(context, "a.b", "line1", "AB");
    ModuleState.dismissApp(context, "a");

    ModuleState.clear(context, "a");

    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "a.b"));
    assertFalse(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "a"));
    assertEquals("AB", ModuleState.slot(context, "a.b", "line1"));
    assertNull(ModuleState.slot(context, "a", "line1"));
    assertFalse(ModuleState.appDismissed(context, "a"));
  }

  @Test
  public void manifestUninstallLeavesCardScopedDataAlone() {
    ModuleState.setEnabled(context, ModuleState.SCOPE_CARD, "x", true);
    ModuleState.setEnabled(context, ModuleState.SCOPE_MANIFEST, "x", true);
    ModuleState.setField(context, "x", "name", "v");

    ModuleState.clearScope(context, ModuleState.SCOPE_MANIFEST, "x");

    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "x"));
    assertFalse(ModuleState.isEnabled(context, ModuleState.SCOPE_MANIFEST, "x"));
    assertEquals("v", ModuleState.field(context, "x", "name"));
  }

  @Test
  public void programRevocationIsExactPerId() {
    ModuleProgramAuth.setAllowed(context, "a", "tool", "digest-a");
    ModuleProgramAuth.setAllowed(context, "a.b", "tool", "digest-ab");

    ModuleProgramAuth.revokeAll(context, "a");

    assertFalse(ModuleProgramAuth.isAllowed(context, "a", "tool", "digest-a"));
    assertTrue(ModuleProgramAuth.isAllowed(context, "a.b", "tool", "digest-ab"));
  }

  @Test
  public void surfaceRevocationIsExactPerId() {
    ModuleSurfaceAuth.setAllowed(context, "app", ModuleSurfaceAuth.APP, "digest-app");
    ModuleSurfaceAuth.setAllowed(context, "app.x", ModuleSurfaceAuth.APP, "digest-appx");

    // 旧实现按 endsWith("." + id) 清理：撤销 app.x 会命中 app 键的 ui.app.app.x。
    ModuleSurfaceAuth.revokeAll(context, "app.x");

    assertTrue(ModuleSurfaceAuth.isAllowed(context, "app", ModuleSurfaceAuth.APP, "digest-app"));
    assertFalse(
        ModuleSurfaceAuth.isAllowed(context, "app.x", ModuleSurfaceAuth.APP, "digest-appx"));
  }
}
