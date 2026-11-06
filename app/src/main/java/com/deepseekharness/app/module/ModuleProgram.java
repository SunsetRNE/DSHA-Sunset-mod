package com.deepseekharness.app.module;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 受管程序的静态校验：文件名、SHA-256、ELF 架构与设备 ABI。
 *
 * <p>这些检查发生在导入和每次运行前，缺一不可。校验不过只报人话原因，绝不“先跑再说”。
 * 程序只能随模块包进入应用私有目录，再由宿主绑定进 rootfs 固定路径执行；宿主不 exec
 * 私有目录里的 ELF（Android W^X）。
 */
public final class ModuleProgram {

  /** 单个程序文件上限；图片仍只有 4MB。 */
  public static final int MAX_BYTES = 64 * 1024 * 1024;

  private ModuleProgram() {}

  /** 校验字节内容是否匹配声明的摘要与 ABI。 */
  public static String validate(ModuleCard.Program program, byte[] bytes, String[] supportedAbis) {
    if (program == null) return "缺少程序声明";
    if (bytes == null || bytes.length == 0) return "程序文件为空";
    if (bytes.length > MAX_BYTES) return "程序文件超过 64MB";
    if (!sha256(bytes).equals(program.sha256)) return "程序 SHA-256 与声明不一致";
    if (!elfMatches(bytes, program.abi)) return "程序不是声明的 ABI（" + program.abi + "）";
    if (!contains(supportedAbis, program.abi)) return "当前设备不支持 " + program.abi;
    return null;
  }

  public static String validate(Context context, ModuleCard.Program program, File file) {
    if (file == null || !file.isFile()) return "程序文件不存在";
    if (file.length() > MAX_BYTES) return "程序文件超过 64MB";
    try {
      return validate(program, read(file), android.os.Build.SUPPORTED_ABIS);
    } catch (Exception error) {
      return "程序读取失败：" + error.getMessage();
    }
  }

  /** ELF 头：64 位、machine 0xB7（AArch64）或 0x3E（x86_64）。 */
  public static boolean elfMatches(byte[] bytes, String abi) {
    if (bytes == null || bytes.length < 20) return false;
    if (bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return false;
    if (bytes[4] != 2) return false;
    int machine = (bytes[18] & 0xff) | ((bytes[19] & 0xff) << 8);
    if ("arm64-v8a".equals(abi)) return machine == 0xB7;
    if ("x86_64".equals(abi)) return machine == 0x3E;
    return false;
  }

  /** 设备是否真的支持声明 ABI；不能拿 Native Bridge 冒充。 */
  public static boolean supported(Context context, String abi) {
    return contains(android.os.Build.SUPPORTED_ABIS, abi);
  }

  public static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest)
        value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static boolean contains(String[] values, String target) {
    if (values == null || target == null) return false;
    for (String value : values) if (target.equals(value)) return true;
    return false;
  }

  private static byte[] read(File file) throws Exception {
    try (FileInputStream input = new FileInputStream(file)) {
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1; ) {
        output.write(buffer, 0, count);
        if (output.size() > MAX_BYTES) throw new java.io.IOException("程序文件超过 64MB");
      }
      return output.toByteArray();
    }
  }
}
