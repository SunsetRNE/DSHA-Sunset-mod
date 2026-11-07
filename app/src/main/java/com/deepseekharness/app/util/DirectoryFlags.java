package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * {@code O_DIRECTORY} 的取值按 ABI 分流 —— 这个常量在两个 ABI 家族里不是同一个数。
 *
 * <p>实测数据来自审查会话（review 5800077381，arm64 真机、{@code app_process} 直接调
 * {@code android.system.Os.open}），不是本分支跑的：
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
 * <p>未知 ABI 直接抛出：错拿另一个家族的常量会把 O_DIRECT 当目录位（或相反），
 * 宁可启动失败也不要静默写错标志。当前 abiFilters 只出 arm64-v8a；x86 线见 PR #8。
 */
public final class DirectoryFlags {

  /** arm / arm64：{@code O_DIRECTORY = 040000}（八进制），即 {@code 0x4000}。 */
  public static final int ARM_O_DIRECTORY = 040000;

  /** x86 / x86_64（asm-generic）：{@code O_DIRECTORY = 00200000}（八进制），即 {@code 0x10000}。 */
  public static final int ASM_GENERIC_O_DIRECTORY = 00200000;

  private DirectoryFlags() {}

  /** 按单个 ABI 名取目录位；只认 arm/arm64 与 x86/x86_64，未知值 fail-closed。 */
  public static int directory(String abi) {
    String normalized = abi == null ? "" : abi.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("arm")) return ARM_O_DIRECTORY;
    if (normalized.startsWith("x86")) return ASM_GENERIC_O_DIRECTORY;
    throw new IllegalArgumentException("DIRECTORY_FLAGS_UNSUPPORTED_ABI: " + abi);
  }

  /** 按 {@code Build.SUPPORTED_ABIS} 取首个 ABI 的目录位；数组为空/未知同样 fail-closed。 */
  public static int directoryOf(String[] abis) {
    if (abis == null || abis.length == 0)
      throw new IllegalArgumentException("DIRECTORY_FLAGS_UNSUPPORTED_ABI: <empty>");
    return directory(abis[0]);
  }
}
