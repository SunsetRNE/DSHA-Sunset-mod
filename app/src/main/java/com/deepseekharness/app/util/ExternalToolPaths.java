package com.deepseekharness.app.util;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 外接 adb / fastboot 的 glibc 库搜索路径。
 *
 * <p>外接工具由宿主 root 边界内的 glibc loader 直接执行，可执行文件的 DT_RUNPATH 是
 * Ubuntu guest 内的绝对路径（/usr/lib/aarch64-linux-gnu/android），在 Android 宿主上
 * 不成立；adb 依赖的 libbase.so.0 / libcutils.so.0 / liblog.so.0 / libziparchive.so.0 等
 * 位于该 android 子目录，必须由 --library-path 显式给出，否则 server 以 127 退出。
 *
 * <p>路径按 Ubuntu 的搜索顺序给出，按规范化结果去重（merged-usr 的 /lib 指向 /usr/lib，
 * 同一目录不重复出现）。纯路径逻辑，不读取文件内容、不扩大任何权限。
 */
public final class ExternalToolPaths {
  private ExternalToolPaths() {}

  public static final String ANDROID_LIBRARIES = "usr/lib/aarch64-linux-gnu/android";

  public static String libraries(File root) {
    Set<String> directories = new LinkedHashSet<>();
    for (String path :
        new String[] {"usr/lib/aarch64-linux-gnu", ANDROID_LIBRARIES, "lib/aarch64-linux-gnu"})
      directories.add(canonical(new File(root, path)));
    return String.join(":", directories);
  }

  private static String canonical(File directory) {
    try {
      return directory.getCanonicalPath();
    } catch (java.io.IOException unresolved) {
      return directory.getAbsolutePath();
    }
  }
}
