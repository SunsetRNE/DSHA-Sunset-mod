package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/** 受管程序的静态校验：ELF 架构、摘要、设备 ABI。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleProgramTest {

  private static byte[] elf(String abi) {
    byte[] bytes = new byte[64];
    bytes[0] = 0x7f;
    bytes[1] = 'E';
    bytes[2] = 'L';
    bytes[3] = 'F';
    bytes[4] = 2;
    int machine = "arm64-v8a".equals(abi) ? 0xB7 : 0x3E;
    bytes[18] = (byte) (machine & 0xff);
    bytes[19] = (byte) ((machine >> 8) & 0xff);
    return bytes;
  }

  @Test
  public void sha256MatchesKnownVector() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ModuleProgram.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  public void elfMachineMustMatchDeclaredAbi() {
    assertTrue(ModuleProgram.elfMatches(elf("arm64-v8a"), "arm64-v8a"));
    assertTrue(ModuleProgram.elfMatches(elf("x86_64"), "x86_64"));
    assertFalse(ModuleProgram.elfMatches(elf("arm64-v8a"), "x86_64"));
    assertFalse(ModuleProgram.elfMatches(elf("x86_64"), "arm64-v8a"));
    assertFalse(ModuleProgram.elfMatches(new byte[] {1, 2, 3}, "x86_64"));
  }

  @Test
  public void validateRejectsDigestAbiAndDeviceMismatch() {
    byte[] x86 = elf("x86_64");
    String sha = ModuleProgram.sha256(x86);
    ModuleCard card =
        ModuleCard.parse(
            "{\"schema\":1,\"id\":\"p.m\",\"name\":\"P\",\"actions\":[],\"provides\":"
                + "{\"module.program\":{\"programs\":[{\"id\":\"hello\",\"file\":\"hello\","
                + "\"sha256\":\""
                + sha
                + "\",\"abi\":\"x86_64\"}]}}}");
    ModuleCard.Program program = card.programs.get("hello");
    assertNull(ModuleProgram.validate(program, x86, new String[] {"x86_64"}));
    byte[] arm = elf("arm64-v8a");
    ModuleCard armCard =
        ModuleCard.parse(
            "{\"schema\":1,\"id\":\"p.m\",\"name\":\"P\",\"actions\":[],\"provides\":"
                + "{\"module.program\":{\"programs\":[{\"id\":\"hello\",\"file\":\"hello\","
                + "\"sha256\":\""
                + ModuleProgram.sha256(arm)
                + "\",\"abi\":\"x86_64\"}]}}}");
    assertTrue(
        ModuleProgram.validate(armCard.programs.get("hello"), arm, new String[] {"x86_64"})
            .contains("ABI"));
    assertTrue(ModuleProgram.validate(program, x86, new String[] {"arm64-v8a"}).contains("不支持"));
    byte[] tampered = x86.clone();
    tampered[20] = 1;
    assertTrue(
        ModuleProgram.validate(program, tampered, new String[] {"x86_64"}).contains("SHA-256"));
  }
}
