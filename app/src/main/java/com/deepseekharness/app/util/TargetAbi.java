package com.deepseekharness.app.util;

import com.deepseekharness.app.BuildConfig;

/**
 * 部署目标的 ABI 分流：正式包只有 arm64；模拟器调试包（-Pdsha.emulatorX64）跑 x86_64。
 *
 * <p>glibc 的库目录随 ABI 变（aarch64-linux-gnu vs x86_64-linux-gnu），RootfsInstaller
 * 与 RuntimeTools 的就绪判据都按这里取值，不能再写死 aarch64。正式 ARM 包的
 * {@code EMULATOR_X64} 恒为 false，字节路径与上游一致。
 */
public final class TargetAbi {
  private TargetAbi() {}

  /** 当前构建是否面向 x86_64 模拟器调试目标。 */
  public static boolean emulatorX64() {
    return BuildConfig.EMULATOR_X64;
  }

  /** glibc 多架构库目录名（如 libsqlite3.so.0 所在）。 */
  public static String linuxTriplet() {
    return emulatorX64() ? "x86_64-linux-gnu" : "aarch64-linux-gnu";
  }

  /** rootfs 里的 Python 版本标记。 */
  public static String pythonMarker() {
    return emulatorX64() ? "3.12-glibc-x86_64\n" : "3.12-glibc-arm64\n";
  }
}
