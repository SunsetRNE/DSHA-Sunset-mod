package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 横屏 Dock 配置口子的安全边界：私有目录、符号链接、超限与「不执行磁盘代码」。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class LandscapeConfigTest {
  private static File directory() {
    File dir = new File(RuntimeEnvironment.getApplication().getFilesDir(), "landscape");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    return dir;
  }

  private static void write(File dir, String name, String content) throws Exception {
    Files.write(new File(dir, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void missingFileKeepsSafeDefaults() {
    LandscapeConfig config = LandscapeConfig.fromDirectory(directory());
    assertEquals(0, config.defaultMode);
    assertEquals(LandscapeConfig.CAPSULE_RIGHT, config.capsule);
    assertTrue(config.chrome);
    assertTrue(config.desktopUa);
    assertTrue(config.scrollLock);
  }

  @Test
  public void validFileOnlyChangesDataSwitches() throws Exception {
    File dir = directory();
    write(
        dir,
        "dsh-a-landscape.json",
        "{\"defaultMode\":2,\"capsule\":\"left\",\"chrome\":false,\"desktopUa\":false,"
            + "\"scrollLock\":false}");
    LandscapeConfig config = LandscapeConfig.fromDirectory(dir);
    assertEquals(2, config.defaultMode);
    assertEquals(LandscapeConfig.CAPSULE_LEFT, config.capsule);
    assertFalse(config.chrome);
    assertFalse(config.desktopUa);
    assertFalse(config.scrollLock);
  }

  @Test
  public void malformedFileFallsBackToDefaults() throws Exception {
    File dir = directory();
    write(dir, "dsh-a-landscape.json", "{not json");
    LandscapeConfig config = LandscapeConfig.fromDirectory(dir);
    assertEquals(0, config.defaultMode);
    assertEquals(LandscapeConfig.CAPSULE_RIGHT, config.capsule);
  }

  @Test
  public void oversizedFileIsRejected() throws Exception {
    File dir = directory();
    byte[] big = new byte[64 * 1024 + 1];
    Arrays.fill(big, (byte) ' ');
    Files.write(new File(dir, "dsh-a-landscape.json").toPath(), big);
    assertEquals("", LandscapeConfig.read(dir, "dsh-a-landscape.json"));
    assertEquals(0, LandscapeConfig.fromDirectory(dir).defaultMode);
  }

  @Test
  public void symbolicLinkIsRejected() throws Exception {
    File dir = directory();
    File outside = new File(dir.getParentFile(), "outside-landscape.json");
    Files.write(outside.toPath(), "{\"defaultMode\":2}".getBytes(StandardCharsets.UTF_8));
    File link = new File(dir, "dsh-a-landscape.json");
    Files.deleteIfExists(link.toPath());
    try {
      Files.createSymbolicLink(link.toPath(), outside.toPath());
    } catch (UnsupportedOperationException | java.io.IOException unsupported) {
      return; // 文件系统不支持符号链接时跳过（CI 的 Linux 支持）
    }
    assertEquals("", LandscapeConfig.read(dir, "dsh-a-landscape.json"));
    assertEquals(0, LandscapeConfig.fromDirectory(dir).defaultMode);
  }

  @Test
  public void runtimeStyleAndScriptInjectionIsGone() {
    // P0-1：配置口子只保留数据开关，不再有 css()/js() 这类把磁盘内容送进页面的入口。
    assertFalse(
        Arrays.stream(LandscapeConfig.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("css") || m.getName().equals("js")));
  }

  @Test
  public void permissionOriginAllowsOnlyDshServiceFrames() {
    String base = "http://127.0.0.1:3200/";
    assertTrue(
        DockDisplayActivity.allowDshOrigin(
            base, android.net.Uri.parse("http://127.0.0.1:3200"), "http://127.0.0.1:3200/chat"));
    // 跨源 iframe：即使页面本身是 DSH，请求 origin 不是本机服务也必须拒绝。
    assertFalse(
        DockDisplayActivity.allowDshOrigin(
            base, android.net.Uri.parse("https://evil.example"), "http://127.0.0.1:3200/chat"));
    // 非回环端口 / 空值 / 空 baseUrl 一律拒绝。
    assertFalse(
        DockDisplayActivity.allowDshOrigin(
            base, android.net.Uri.parse("http://127.0.0.1:3999"), "http://127.0.0.1:3200/chat"));
    assertFalse(DockDisplayActivity.allowDshOrigin(base, null, "http://127.0.0.1:3200/chat"));
    assertFalse(
        DockDisplayActivity.allowDshOrigin(
            base, android.net.Uri.parse("http://127.0.0.1:3200"), null));
    assertFalse(
        DockDisplayActivity.allowDshOrigin(
            "", android.net.Uri.parse("http://127.0.0.1:3200"), "http://127.0.0.1:3200/chat"));
  }
}
