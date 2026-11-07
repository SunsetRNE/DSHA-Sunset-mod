package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * 目标 ABI 的两个值：正式包恒为 arm64，x86_64 只在模拟器调试构建里出现。
 *
 * <p>单测里 {@code BuildConfig.EMULATOR_X64} 恒为 false，所以必须走 {@code (boolean)}
 * 重载才能覆盖 x86 分支——上一轮正是因为「测试里 x86 分支不可达」，接线等于没验证。
 */
public final class TargetAbiTest {

  @Test
  public void armBuildUsesAarch64Triplet() {
    assertEquals("aarch64-linux-gnu", TargetAbi.linuxTriplet(false));
    assertEquals("3.12-glibc-arm64\n", TargetAbi.pythonMarker(false));
  }

  @Test
  public void emulatorBuildUsesX8664Triplet() {
    assertEquals("x86_64-linux-gnu", TargetAbi.linuxTriplet(true));
    assertEquals("3.12-glibc-x86_64\n", TargetAbi.pythonMarker(true));
  }

  @Test
  public void markerKeepsItsTrailingNewline() {
    // 写进 root/.dsha-python-version 的是原始字节：去掉换行会让 ARM 侧字节变化，
    // 而没有任何消费方要求这么做。
    assertEquals(
        '\n', TargetAbi.pythonMarker(false).charAt(TargetAbi.pythonMarker(false).length() - 1));
    assertEquals(
        '\n', TargetAbi.pythonMarker(true).charAt(TargetAbi.pythonMarker(true).length() - 1));
  }

  @Test
  public void productionBuildIsNotTheEmulatorTarget() {
    // 单测构建不带 -Pdsha.emulatorX64=true，两个重载默认值必须与 ARM 一致。
    assertFalse(TargetAbi.emulatorX64());
    assertEquals(TargetAbi.linuxTriplet(false), TargetAbi.linuxTriplet());
    assertEquals(TargetAbi.pythonMarker(false), TargetAbi.pythonMarker());
  }
}
