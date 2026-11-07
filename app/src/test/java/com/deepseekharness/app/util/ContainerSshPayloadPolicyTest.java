package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 随包 payload 的纯校验矩阵：清单、成员、ELF 头逐项负控。
 * 字节流读取与 LaunchSpec 绑定在 runtime 侧，这里的对象全部可以手工构造。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(
    sdk = 34,
    manifest = org.robolectric.annotation.Config.NONE,
    application = android.app.Application.class)
public final class ContainerSshPayloadPolicyTest {

  private static final Map<String, String> DESTS =
      Map.of(
          "sshd", "/usr/sbin/sshd",
          "ssh-keygen", "/usr/bin/ssh-keygen",
          "lib/libwrap.so.0", "/usr/lib/aarch64-linux-gnu/libwrap.so.0");

  private static JSONObject manifest(String architecture) throws Exception {
    return new JSONObject().put("schema", 1).put("architecture", architecture);
  }

  private static byte[] elf(int machine, int eType) {
    byte[] elf = new byte[64];
    elf[0] = 127;
    elf[1] = 'E';
    elf[2] = 'L';
    elf[3] = 'F';
    elf[4] = 2;
    elf[5] = 1;
    elf[16] = (byte) eType;
    elf[17] = (byte) (eType >> 8);
    elf[18] = (byte) machine;
    elf[19] = (byte) (machine >> 8);
    return elf;
  }

  @Test
  public void manifestAcceptsOnlyMatchingSchemaAndArchitecture() throws Exception {
    ContainerSshPayloadPolicy.validateManifest(manifest("aarch64"), false);
    ContainerSshPayloadPolicy.validateManifest(manifest("x86_64"), true);
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateManifest(manifest("x86_64"), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateManifest(manifest("aarch64"), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSshPayloadPolicy.validateManifest(
                new JSONObject().put("schema", 2).put("architecture", "aarch64"), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateManifest(null, false));
  }

  @Test
  public void architectureFollowsAbiFamily() {
    assertTrue(ContainerSshPayloadPolicy.isX86(new String[] {"x86_64", "x86"}));
    assertTrue(ContainerSshPayloadPolicy.isX86(new String[] {"X86"}));
    assertEquals("x86_64", ContainerSshPayloadPolicy.expectedArchitecture(true));
    assertEquals("aarch64", ContainerSshPayloadPolicy.expectedArchitecture(false));
  }

  @Test
  public void rowValidationRejectsContractViolations() throws Exception {
    JSONObject good =
        new JSONObject()
            .put("asset", "payload-0.bin")
            .put("target", "sshd")
            .put("sha256", "a".repeat(64))
            .put("bytes", 1024);
    assertEquals("/usr/sbin/sshd", ContainerSshPayloadPolicy.validateRow(good, DESTS));

    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateRow(good.put("asset", "payload.bin"), DESTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSshPayloadPolicy.validateRow(
                new JSONObject(good.toString()).put("target", "/etc/passwd"), DESTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSshPayloadPolicy.validateRow(
                new JSONObject(good.toString()).put("sha256", "short"), DESTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSshPayloadPolicy.validateRow(
                new JSONObject(good.toString()).put("bytes", 4), DESTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ContainerSshPayloadPolicy.validateRow(
                new JSONObject(good.toString()).put("bytes", 5 * 1024 * 1024), DESTS));
  }

  @Test
  public void elfHeaderMatrix() throws Exception {
    ContainerSshPayloadPolicy.validateElf(elf(183, 3), false); // aarch64 ET_DYN
    ContainerSshPayloadPolicy.validateElf(elf(183, 2), false); // ET_EXEC
    ContainerSshPayloadPolicy.validateElf(elf(62, 3), true); // x86_64
    // e_machine 与目标架构不符
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateElf(elf(62, 3), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateElf(elf(183, 3), true));
    // e_type 不是 EXEC/DYN
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateElf(elf(183, 1), false));
    // ELFCLASS32
    byte[] class32 = elf(183, 3);
    class32[4] = 1;
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateElf(class32, false));
    // 大端
    byte[] bigEndian = elf(183, 3);
    bigEndian[5] = 2;
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerSshPayloadPolicy.validateElf(bigEndian, false));
    // magic 损坏
    byte[] broken = elf(183, 3);
    broken[0] = 'N';
    assertThrows(
        IllegalArgumentException.class, () -> ContainerSshPayloadPolicy.validateElf(broken, false));
  }

  @Test
  public void eTypeReadsLittleEndianHalfwordAt0x10() throws Exception {
    assertEquals(3, ContainerSshPayloadPolicy.eType(elf(183, 3)));
    assertEquals(2, ContainerSshPayloadPolicy.eType(elf(183, 2)));
  }
}
