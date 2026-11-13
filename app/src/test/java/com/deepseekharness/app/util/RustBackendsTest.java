package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 运行层开关与后端选择测试（不要求加载 .so；Rust 不可用时的类型化原因也要成立）。
 *
 * <p>R1 默认关闭；显式开启才尝试 Rust；{@code setEnabled(false)} 即一键回 JVM。
 * 加载失败不抛异常、不重试，也不把 JVM 成功伪装成 Rust 成功。
 */
public class RustBackendsTest {
  @Before
  public void resetBefore() {
    RustBackends.setEnabled(false);
  }

  @After
  public void resetAfter() {
    RustBackends.setEnabled(false);
  }

  @Test
  public void defaultsToJvmUntilExplicitlyEnabled() {
    assertFalse(RustBackends.enabled());
    RustBackends.Selection selection = RustBackends.digestSelection();
    assertFalse(selection.useRust());
    assertEquals(RustBackends.SelectionReason.SWITCH_OFF, selection.reason());
    assertEquals("SWITCH_OFF", selection.reasonCode());
  }

  @Test
  public void explicitSwitchEnablesSelectionOnlyWhenTheLibraryIsUsable() {
    RustBackends.setEnabled(true);
    assertTrue(RustBackends.enabled());
    RustBackends.Selection selection = RustBackends.digestSelection();
    if (RustNative.isAvailable()) {
      assertTrue(selection.useRust());
      assertEquals(RustBackends.SelectionReason.RUST_ENABLED, selection.reason());
    } else {
      // 测试进程未加载 .so：留在 JVM，原因必须是类型化的加载失败，而不是“成功”。
      assertFalse(selection.useRust());
      switch (selection.reason()) {
        case LIBRARY_MISSING:
        case LOAD_FAILED:
        case ABI_MISMATCH:
        case NOT_LOADED:
          break;
        default:
          fail("unexpected selection reason: " + selection.reason());
      }
      assertNotEquals(RustBackends.SelectionReason.RUST_ENABLED, selection.reason());
    }
  }

  @Test
  public void oneKeyRollbackKeepsTheSameJvmBehaviour() {
    RustBackends.setEnabled(true);
    RustBackends.setEnabled(false);
    assertFalse(RustBackends.enabled());
    RustBackends.Selection selection = RustBackends.digestSelection();
    assertFalse(selection.useRust());
    assertEquals(RustBackends.SelectionReason.SWITCH_OFF, selection.reason());
  }

  @Test
  public void nativeStatusIsTypedAndNeverCarriesPaths() {
    RustNative.Status status = RustNative.status();
    String code = RustNative.statusCode();
    assertEquals(status.name(), code);
    for (RustNative.Status value : RustNative.Status.values()) {
      // 状态码只能是枚举名：不允许出现 / 或设备路径。
      assertFalse(value.name().contains("/"));
    }
    assertFalse(code.contains("/"));
  }
}
