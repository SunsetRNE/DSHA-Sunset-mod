package com.deepseekharness.app.util;

import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 容器 SSH 随包资产的纯校验逻辑：清单契约、成员契约、ELF 头。
 *
 * <p>不 import Android，不碰文件与资产流——所有判定只吃内存对象，负控可以在
 * JVM 上逐项构造。字节读取、原子落盘与 LaunchSpec 绑定留在
 * {@code runtime/ContainerSshAssets}。
 */
public final class ContainerSshPayloadPolicy {
  public static final int SCHEMA = 1;
  public static final int MANIFEST_LIMIT = 16384;
  public static final int MIN_MEMBER_BYTES = 20;
  public static final int MAX_MEMBER_BYTES = 4 * 1024 * 1024;

  private ContainerSshPayloadPolicy() {}

  /** 目标 ABI 是否是 x86 家族（模拟器调试包才可能为真）。 */
  public static boolean isX86(String[] supportedAbis) {
    return supportedAbis != null
        && supportedAbis.length > 0
        && supportedAbis[0] != null
        && supportedAbis[0].toLowerCase(Locale.ROOT).startsWith("x86");
  }

  /** 清单声明的目标架构；与随包 payload 的实际 ELF 架构一一对应。 */
  public static String expectedArchitecture(boolean x86) {
    return x86 ? "x86_64" : "aarch64";
  }

  /** 清单契约：schema、架构；不符合整体拒绝，绝不部分安装。 */
  public static void validateManifest(JSONObject manifest, boolean x86)
      throws IllegalArgumentException {
    if (manifest == null) throw new IllegalArgumentException("SSH_MANIFEST_INVALID");
    if (manifest.optInt("schema") != SCHEMA
        || !expectedArchitecture(x86).equals(manifest.optString("architecture")))
      throw new IllegalArgumentException("SSH_ASSET_ARCHITECTURE");
  }

  /**
   * 成员契约：asset 名受控、target 必须在已知目的地表内、不得重复、
   * 摘要是 64 位十六进制、大小在两档之间。返回该成员绑定的 guest 路径。
   */
  public static String validateRow(JSONObject row, Map<String, String> destinations)
      throws IllegalArgumentException {
    if (row == null || destinations == null)
      throw new IllegalArgumentException("SSH_ASSET_CONTRACT");
    String asset = row.optString("asset", null);
    String target = row.optString("target", null);
    String sha256 = row.optString("sha256", null);
    int size = row.optInt("bytes", -1);
    if (asset == null
        || target == null
        || sha256 == null
        || !asset.matches("payload-[0-9]+\\.bin")
        || !destinations.containsKey(target)
        || !sha256.matches("[a-f0-9]{64}")
        || size < MIN_MEMBER_BYTES
        || size > MAX_MEMBER_BYTES) throw new IllegalArgumentException("SSH_ASSET_CONTRACT");
    return destinations.get(target);
  }

  /** 成员数量与目的地表一致（3 个 payload 对 3 个目标）。 */
  public static void validateMemberCount(JSONArray files, int destinationCount)
      throws IllegalArgumentException {
    if (files == null || files.length() != destinationCount)
      throw new IllegalArgumentException("SSH_ASSET_MEMBERS");
  }

  /** ELF 头：magic、ELFCLASS64、小端、e_type（EXEC 或 DYN/PIE）、e_machine 按目标架构。 */
  public static void validateElf(byte[] elf, boolean x86) throws IllegalArgumentException {
    if (elf == null
        || elf.length < 20
        || elf[0] != 127
        || elf[1] != 'E'
        || elf[2] != 'L'
        || elf[3] != 'F'
        || elf[4] != 2 // ELFCLASS64
        || elf[5] != 1 // 小端
        || (eType(elf) != 2 && eType(elf) != 3) // ET_EXEC 或 ET_DYN
        || (elf[18] & 255) != (x86 ? 62 : 183)
        || elf[19] != 0) throw new IllegalArgumentException("SSH_ASSET_BYTES");
  }

  /** ELF 头 e_type（小端 16 位，偏移 0x10）。 */
  public static int eType(byte[] elf) {
    return (elf[16] & 255) | ((elf[17] & 255) << 8);
  }
}
