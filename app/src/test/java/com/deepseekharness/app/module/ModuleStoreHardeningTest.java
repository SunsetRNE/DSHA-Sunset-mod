package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 收件箱、素材路径、移除与界面授权的负控。
 *
 * <p>对应审查意见：目录级 NOFOLLOW、素材名穿越、移除不清理素材、ui.app 无独立授权。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleStoreHardeningTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  private static void write(File file, String text) throws Exception {
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  private static final String SKIN_WITH_IMAGE =
      "{\"schema\":1,\"id\":\"skin.img\",\"name\":\"皮肤\",\"actions\":[],"
          + "\"provides\":{\"ui.skin\":{\"home.cards\":{\"*\":{\"image\":\"bg.png\"}}}}}";

  private static final String SKIN_TRAVERSAL =
      "{\"schema\":1,\"id\":\"skin.bad\",\"name\":\"坏图\",\"actions\":[],"
          + "\"provides\":{\"ui.skin\":{\"home.cards\":{\"*\":{\"image\":\"../escape.png\"}}}}}";

  private static final String APP_CARD =
      "{\"schema\":1,\"id\":\"app.take\",\"name\":\"接管\",\"actions\":[],"
          + "\"provides\":{\"ui.app\":{\"title\":\"T\",\"view\":[{\"type\":\"link\","
          + "\"text\":\"管理 ›\",\"action\":\"modules\"}],\"targets\":[\"home\"]}}}";

  @Test
  public void skinImageIsAcceptedWithoutTopLevelImage() {
    // 之前校验的是顶层 image（空串），合法配置会被判成非法。
    ModuleCard card = ModuleCard.parse(SKIN_WITH_IMAGE);
    assertTrue(card.skin.homeCards.get("*").image.equals("bg.png"));
    assertTrue(card.assetNames().contains("bg.png"));
  }

  @Test
  public void skinImageWithTraversalIsRejected() {
    try {
      ModuleCard.parse(SKIN_TRAVERSAL);
      throw new AssertionError("应当拒绝 ../ 素材名");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("image"));
    }
  }

  @Test
  public void importIgnoresTraversalAssetNames() throws Exception {
    java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
    assets.put("../escape.png", new byte[] {1, 2, 3});
    ModuleCardStore.importCard(context, SKIN_WITH_IMAGE, assets);
    // 卡片没有声明这个名字，所以它既不会写进模块目录，也不会写到模块目录外。
    File modules = new File(context.getFilesDir(), "modules");
    assertFalse(new File(context.getFilesDir(), "escape.png").exists());
    assertFalse(new File(modules.getParentFile(), "escape.png").exists());
  }

  @Test
  public void removeDeletesAssetFilesTogether() throws Exception {
    java.util.Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
    assets.put("bg.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9, 9});
    String id = ModuleCardStore.importCard(context, SKIN_WITH_IMAGE, assets);
    assertNotNull(id);
    File dir = new File(new File(context.getFilesDir(), "modules"), "skin.img");
    assertTrue(new File(dir, "bg.png").isFile());

    ModuleCardStore.remove(context, "skin.img");
    // 原先只 delete(card.json) + delete(dir)，非空目录删不掉，素材会一直留着。
    assertFalse(dir.exists());
  }

  @Test
  public void removeKeepsSiblingManifestDirectory() throws Exception {
    String id =
        ModuleCardStore.importCard(context, SKIN_WITH_IMAGE, (java.util.Map<String, byte[]>) null);
    assertNotNull(id);
    File dir = new File(new File(context.getFilesDir(), "modules"), "skin.img");
    write(new File(dir, "module.json"), "{\"id\":\"skin.img\"}");

    ModuleCardStore.remove(context, "skin.img");
    assertTrue("同目录的清单必须保留", new File(dir, "module.json").isFile());
    assertFalse(new File(dir, "card.json").exists());
  }

  @Test
  public void containerInboxSymlinkIsIgnored() throws Exception {
    File rootfs = new File(context.getCacheDir(), "rootfs-link");
    File hostTarget = new File(context.getCacheDir(), "host-outside");
    assertTrue(hostTarget.mkdirs());
    assertTrue(rootfs.mkdirs());
    Path inbox = new File(rootfs, ModuleCardStore.CONTAINER_DIR).toPath();
    try {
      Files.createSymbolicLink(inbox, hostTarget.toPath());
    } catch (Exception unsupported) {
      assumeNoException("本机不能创建符号链接", unsupported);
      return;
    }

    assertEquals(0, ModuleCardStore.scanContainerInbox(context, rootfs));
    ModuleCardStore.ensureSkill(rootfs);
    // 目标目录里不能被宿主写出 HOW-TO.md，也不能出现导入产物。
    assertEquals(0, hostTarget.list() == null ? 0 : hostTarget.list().length);
    assertFalse(new File(hostTarget, "HOW-TO.md").exists());
  }

  @Test
  public void appSurfaceNeedsItsOwnGrant() throws Exception {
    String id = ModuleCardStore.importCard(context, APP_CARD, (java.util.Map<String, byte[]>) null);
    assertNotNull(id);
    // 已有卡片、已启用，但没有 ui.app 授权 → 渲染层拿到的必须是 null。
    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "app.take"));
    assertNull(ModuleCardStore.appCard(context, "home"));

    File dir = new File(new File(context.getFilesDir(), "modules"), "app.take");
    String digest = ModuleCardStore.contentDigest(dir);
    ModuleSurfaceAuth.setAllowed(context, "app.take", ModuleSurfaceAuth.APP, digest);
    ModuleCard granted = ModuleCardStore.appCard(context, "home");
    assertNotNull(granted);
    assertEquals("app.take", granted.id);

    // 内容一变，旧授权不再放行。
    write(new File(dir, "extra.png"), "x");
    assertNull(ModuleCardStore.appCard(context, "home"));
  }
}
