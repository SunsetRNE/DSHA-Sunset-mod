package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@code O_DIRECTORY} 的 ABI 映射，锁住“两个 ABI 家族不是同一个数”。
 *
 * <p>数值依据是审查会话（review 5800077381）的 arm64 真机探针：{@code open(file, O_RDONLY | 040000)}
 * 返回 ENOTDIR（040000 是目录位），{@code open("/system", O_RDONLY | 0x10000)} 返回 EINVAL
 * （0x10000 在 arm64 上是 O_DIRECT）。该探针不是本分支跑的，本分支只锁映射。
 */
public final class DirectoryFlagsTest {

  @Test
  public void armAndArm64UseTheOctalDirectoryFlag() {
    assertEquals(0x4000, DirectoryFlags.directory("arm64-v8a"));
    assertEquals(0x4000, DirectoryFlags.directory("armeabi-v7a"));
    assertEquals(0x4000, DirectoryFlags.directory("ARM64-V8A"));
    assertEquals(040000, DirectoryFlags.directory("arm64-v8a"));
  }

  @Test
  public void x86AndX86_64UseTheAsmGenericDirectoryFlag() {
    assertEquals(0x10000, DirectoryFlags.directory("x86_64"));
    assertEquals(0x10000, DirectoryFlags.directory("x86"));
    assertEquals(00200000, DirectoryFlags.directory("x86_64"));
  }

  @Test
  public void unknownOrMissingAbiIsFailClosed() {
    // riscv64 等 asm-generic ABI 拿 arm 的 040000 会把 O_DIRECT 当目录位；
    // 与其静默写错标志，不如 fail-closed 让支持范围显式化。
    for (String bad : new String[] {null, "", "  ", "riscv64", "mips64"}) {
      try {
        DirectoryFlags.directory(bad);
        throw new AssertionError("expected IllegalArgumentException for " + bad);
      } catch (IllegalArgumentException expected) {
      }
    }
    for (String[] bad : new String[][] {null, new String[0]}) {
      try {
        DirectoryFlags.directoryOf(bad);
        throw new AssertionError("expected IllegalArgumentException for empty ABIs");
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void usesTheFirstSupportedAbi() {
    assertEquals(0x4000, DirectoryFlags.directoryOf(new String[] {"arm64-v8a", "armeabi-v7a"}));
    assertEquals(0x10000, DirectoryFlags.directoryOf(new String[] {"x86_64", "arm64-v8a"}));
  }

  @Test
  public void theTwoConstantsAreNotTheSameValue() {
    // 这条断言看着废话，但它正是被写错的那个前提：把两个值当成一个。
    assertEquals(0x4000, DirectoryFlags.ARM_O_DIRECTORY);
    assertEquals(0x10000, DirectoryFlags.ASM_GENERIC_O_DIRECTORY);
  }
}
