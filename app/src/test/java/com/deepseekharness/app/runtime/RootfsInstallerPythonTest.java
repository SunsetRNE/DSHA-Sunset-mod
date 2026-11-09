package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * rootfs 自带 Python 的判据：主程序非空 + encodings 齐全才算「rootfs 提供」。
 *
 * <p>x86 模拟器包里 Python 资产是空哨兵，这条判据是唯一放行路径；写错就等于
 * 模拟器目标永远部署失败，而 ARM 侧不会有任何反馈。
 */
public final class RootfsInstallerPythonTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void completeRootfsProvidesPython() throws Exception {
    File stage = stageWith("usr/bin/python3.12", "usr/lib/python3.12/encodings/__init__.py");
    assertTrue(RootfsInstaller.rootfsProvidesPython(stage));
  }

  @Test
  public void missingEncodingsIsNotProvided() throws Exception {
    File stage = stageWith("usr/bin/python3.12", null);
    assertFalse(RootfsInstaller.rootfsProvidesPython(stage));
  }

  @Test
  public void zeroLengthPythonIsNotProvided() throws Exception {
    File stage = stageWith(null, "usr/lib/python3.12/encodings/__init__.py");
    File python = new File(stage, "usr/bin/python3.12");
    assertTrue(python.getParentFile().mkdirs());
    Files.write(python.toPath(), new byte[0]);
    assertFalse("零长的主程序等同于没装", RootfsInstaller.rootfsProvidesPython(stage));
  }

  @Test
  public void emptyStageIsNotProvided() throws Exception {
    assertFalse(RootfsInstaller.rootfsProvidesPython(temp.newFolder("empty")));
  }

  /** 按相对路径建出 stage；内容为 null 时不创建该文件。 */
  private File stageWith(String python, String encodings) throws Exception {
    File stage = temp.newFolder("stage-" + System.nanoTime());
    if (python != null) write(stage, python, "#!/bin/sh\n");
    if (encodings != null) write(stage, encodings, "# encodings\n");
    return stage;
  }

  private static void write(File stage, String relative, String body) throws Exception {
    File file = new File(stage, relative);
    File parent = file.getParentFile();
    assertTrue(parent == null || parent.isDirectory() || parent.mkdirs());
    Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));
  }
}
