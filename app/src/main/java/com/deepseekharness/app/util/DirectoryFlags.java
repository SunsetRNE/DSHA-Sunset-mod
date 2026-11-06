package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * {@code O_DIRECTORY} 的取值按 ABI 分流 —— 这个常量在两个 ABI 家族里不是同一个数。
 *
 * <p>实测（arm64 真机、{@code app_process} 直接调 {@code android.system.Os.open}）：
 *
 * <ul>
 *   <li>{@code open(file, O_RDONLY | 040000)} → ENOTDIR，说明 arm64 上 040000 就是目录位；
 *   <li>{@code open("/system", O_RDONLY | 0x10000)} → EINVAL，说明 0x10000 在 arm64 上是
 *       O_DIRECT，用它打开目录会被内核拒绝；
 *   <li>{@code /data} 是 f2fs 时容忍目录 O_DIRECT，所以那条 EINVAL 在某些设备上不会暴露 ——
 *       这正是一次误判的来源：模拟器（x86_64）上 0x10000 是对的，搬到 arm64 就变成错的。
 * </ul>
 *
 * <p>来源：bionic 的 {@code aarch64-linux-android/asm/fcntl.h} 写的是
 * {@code O_DIRECTORY 040000}、{@code O_DIRECT 0200000}；arm32 同值。只有 x86/x86_64 走
 * {@code asm-generic/fcntl.h}，那里 {@code O_DIRECTORY 00200000}、{@code O_DIRECT 00040000}。
 *
 * <p>默认返回 {@link #ARM_O_DIRECTORY}：未知 ABI 时按唯一生产 ABI（arm64）处理，宁可保守。
 */
public final class DirectoryFlags {

  /** arm / arm64：{@code O_DIRECTORY = 040000}（八进制），即 {@code 0x4000}。 */
  public static final int ARM_O_DIRECTORY = 040000;

  /** x86 / x86_64（asm-generic）：{@code O_DIRECTORY = 00200000}（八进制），即 {@code 0x10000}。 */
  public static final int ASM_GENERIC_O_DIRECTORY = 00200000;

  private DirectoryFlags() {}

  /** 按单个 ABI 名取目录位；null / 空 / 未知一律按 arm 处理。 */
  public static int directory(String abi) {
    if (abi == null) return ARM_O_DIRECTORY;
    String normalized = abi.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) return ARM_O_DIRECTORY;
    return normalized.startsWith("x86") ? ASM_GENERIC_O_DIRECTORY : ARM_O_DIRECTORY;
  }

  /** 按 {@code Build.SUPPORTED_ABIS} 取首个 ABI 的目录位；数组为空时按 arm 处理。 */
  public static int directoryOf(String[] abis) {
    if (abis == null || abis.length == 0) return ARM_O_DIRECTORY;
    return directory(abis[0]);
  }
}
