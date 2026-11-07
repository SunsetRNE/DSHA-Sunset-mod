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
    // CONTAINER_DIR 是 root/.dsha-modules：父目录必须存在，否则建链接会抛 NoSuchFileException
    // 而被 assume 吞掉，这个用例等于没跑（上一轮就是这样漏掉的）。
    assertTrue(new File(rootfs, "root").mkdirs());
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

  @Test
  public void intermediateDirectorySymlinkIsRefusedToo() throws Exception {
    File rootfs = new File(context.getCacheDir(), "rootfs-mid");
    File outside = new File(context.getCacheDir(), "outside-root");
    assertTrue(outside.mkdirs());
    assertTrue(rootfs.mkdirs());
    try {
      Files.createSymbolicLink(new File(rootfs, "root").toPath(), outside.toPath());
    } catch (Exception unsupported) {
      assumeNoException("本机不能创建符号链接", unsupported);
      return;
    }

    // root 本身是链接：收件箱当作不存在，宿主既不读也不往里写技能文件。
    assertEquals(0, ModuleCardStore.scanContainerInbox(context, rootfs));
    ModuleCardStore.ensureSkill(rootfs);
    assertEquals(0, outside.list() == null ? 0 : outside.list().length);
  }

  @Test
  public void cardSizeLimitIsCountedInBytesNotCharacters() {
    // 11000 个汉字只有 1.1 万字符、却占 3.3 万字节：按字符判会放行，落盘后被截断，
    // 卡片「导入成功」却永远解析失败，用户只会看到「不是合法 JSON」。
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 11000; i++) name.append('字');
    String raw = "{\"schema\":1,\"id\":\"cjk.big\",\"name\":\"" + name + "\",\"actions\":[]}";
    assertTrue("构造前提：字符数没超", raw.length() < ModuleCardStore.MAX_BYTES);
    assertTrue(
        "构造前提：字节数已超", raw.getBytes(StandardCharsets.UTF_8).length > ModuleCardStore.MAX_BYTES);
    try {
      ModuleImport.importAny(context, raw);
      throw new AssertionError("应当按字节拒绝超限卡片");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("32KB"));
    }
  }

  @Test
  public void topLevelImageAndSkinImageShareOneNameRule() {
    // "_bg.png" 以前顶层合法、皮肤图被拒；现在两处走同一个 isImageName。
    String top =
        "{\"schema\":1,\"id\":\"img.top\",\"name\":\"图\",\"image\":\"_bg.png\",\"actions\":[]}";
    assertTrue(ModuleCard.parse(top).assetNames().contains("_bg.png"));
    String skin =
        "{\"schema\":1,\"id\":\"img.skin\",\"name\":\"皮肤\",\"actions\":[],"
            + "\"provides\":{\"ui.skin\":{\"home.cards\":{\"*\":{\"image\":\"_bg.png\"}}}}}";
    assertTrue(ModuleCard.parse(skin).assetNames().contains("_bg.png"));
  }

  @Test
  public void removesBusInboxWithTheCard() {
    String raw = "{\"schema\":1,\"id\":\"removed.card\",\"name\":\"待删\",\"actions\":[]}";
    ModuleCardStore.importCard(context, raw, (java.util.Map<String, byte[]>) null);
    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "removed.card"));
    assertEquals("ok", ModuleBus.send(context, "other.card", "removed.card", "ping", "{}"));

    // 走统一移除入口（ModuleCardStore.remove 是底层实现，不负责撤销授权与状态）。
    ModuleCatalog.Entry entry =
        ModuleCatalog.load(context).byId("removed.card", ModuleCatalog.Kind.CARD);
    assertNotNull(entry);
    assertTrue(ModuleCatalog.remove(context, entry));

    // 同 id 重装：不能收到上一份安装的消息（含别人的 from），否则是跨安装串话。
    ModuleCardStore.importCard(context, raw, (java.util.Map<String, byte[]>) null);
    assertEquals("[]", ModuleBus.poll(context, "removed.card"));
  }
}
