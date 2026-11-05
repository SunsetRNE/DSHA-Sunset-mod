package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;

/**
 * 宿主私有目录的路径归一化。
 *
 * <p>Android 上 {@code /data/data/<pkg>} 在多数设备是 {@code /data/user/0/<pkg>} 的符号链接，
 * {@link File#getCanonicalFile()} 因此能给出稳定身份。但：
 *
 * <ul>
 *   <li>部分 ROM 与 AOSP 模拟器把 {@code /data/data} 做成真实目录，且 SELinux 禁止逐级
 *       打开它（{@code lstat}/{@code open} 返回 EINVAL）；</li>
 *   <li>{@code getCanonicalFile()} 在这种环境必然失败，整条维护链路会把环境误判成
 *       「记录无法读取」而拒绝恢复。</li>
 * </ul>
 *
 * <p>本方法在 canonical 解析不可用时退回绝对路径：两者指向同一目录，调用方仍应继续
 * 用 NOFOLLOW + 逐级 fd 校验（{@code AndroidBackupFileSystem}）做真正的边界检查，
 * 这里只是把「路径怎么归一化」与「能不能读」分开。
 */
public final class HostPaths {

  private HostPaths() {}

  /** 归一化路径；父目录不可遍历时退回绝对路径（不改变指向的目录）。 */
  public static File normalize(File file) {
    if (file == null) return null;
    try {
      return file.getCanonicalFile();
    } catch (IOException | RuntimeException error) {
      return file.getAbsoluteFile();
    }
  }
}
