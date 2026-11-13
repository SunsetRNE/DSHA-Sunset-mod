package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ExternalToolPathsTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private static String canonical(File file) throws Exception {
    return file.getCanonicalPath();
  }

  private File root() throws Exception {
    File root = temp.newFolder("rootfs");
    assertTrue(new File(root, "usr/lib/aarch64-linux-gnu/android").mkdirs());
    return root;
  }

  @Test
  public void androidLibrariesFollowTheirParentAndPrecedeTheLegacyLibDirectory() throws Exception {
    File root = root();
    assertTrue(new File(root, "lib/aarch64-linux-gnu").mkdirs());
    assertEquals(
        String.join(
            ":",
            canonical(new File(root, "usr/lib/aarch64-linux-gnu")),
            canonical(new File(root, "usr/lib/aarch64-linux-gnu/android")),
            canonical(new File(root, "lib/aarch64-linux-gnu"))),
        ExternalToolPaths.libraries(root));
  }

  @Test
  public void mergedUsrAliasDoesNotRepeatTheSameDirectory() throws Exception {
    File root = root();
    // Windows 上创建符号链接需要额外权限；不可用时本用例跳过，不把平台差异写成通过。
    try {
      Files.createSymbolicLink(new File(root, "lib").toPath(), new File(root, "usr/lib").toPath());
    } catch (UnsupportedOperationException | java.io.IOException unsupported) {
      org.junit.Assume.assumeTrue("当前文件系统不支持符号链接，跳过 merged-usr 去重用例", false);
    }
    assertEquals(
        String.join(
            ":",
            canonical(new File(root, "usr/lib/aarch64-linux-gnu")),
            canonical(new File(root, "usr/lib/aarch64-linux-gnu/android"))),
        ExternalToolPaths.libraries(root));
  }
}
