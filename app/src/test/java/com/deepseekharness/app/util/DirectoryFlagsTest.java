package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@code O_DIRECTORY} 的 ABI 映射，锁住“两个 ABI 家族不是同一个数”。
 *
 * <p>这组断言来自 arm64 真机实测：{@code open(file, O_RDONLY | 040000)} 返回 ENOTDIR
 * （040000 是目录位），{@code open("/system", O_RDONLY | 0x10000)} 返回 EINVAL
 * （0x10000 在 arm64 上是 O_DIRECT）。两者互相印证，不能靠“看着像”来猜。
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
  public void unknownOrMissingAbiFallsBackToArm() {
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directory(null));
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directory(""));
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directory("  "));
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directory("riscv64"));
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directoryOf(null));
    assertEquals(DirectoryFlags.ARM_O_DIRECTORY, DirectoryFlags.directoryOf(new String[0]));
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
