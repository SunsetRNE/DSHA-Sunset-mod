package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 收件箱收取的端到端行为（容器 rootfs 的 ~/.dsha-modules/）：
 * 成功导入并归档原件、同 id 更新替换、失败留原件且错误可见、超限拒绝、素材随卡一起装。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCardStoreInboxTest {

  private Context context;
  private File rootfs;
  private File inbox;

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    rootfs = new File(context.getCacheDir(), "fake-rootfs");
    inbox = new File(rootfs, ModuleCardStore.CONTAINER_DIR);
    assertTrue(inbox.mkdirs());
  }

  private static void write(File file, String content) throws Exception {
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  private File card(String fileName, String id, String extra) throws Exception {
    File file = new File(inbox, fileName);
    write(file, "{\"schema\":1,\"id\":\"" + id + "\",\"name\":\"测试\",\"actions\":[]" + extra + "}");
    return file;
  }

  /** 容器来源的卡片默认关闭，所以这里走 loadAll（模块中心的视角），不带开关过滤。 */
  private static ModuleCard installed(Context context, String id) {
    for (ModuleCard card : ModuleCardStore.loadAll(context)) if (id.equals(card.id)) return card;
    return null;
  }

  private static boolean hasIssue(String where) {
    for (ModuleCardStore.Issue issue : ModuleCardStore.issues()) {
      if (where.equals(issue.where)) return true;
    }
    return false;
  }

  @Test
  public void archiveRefusesWhenInboxBecomesSymlinkAndNeverTouchesTheTarget() throws Exception {
    // 审查 P1-2 的确定性回归：归档瞬间收件箱被换成指向外部目录的链接。
    // 旧实现（plainPath 失败仍走 file.delete()）会把链接目标里的同名文件删掉。
    File outside = new File(context.getCacheDir(), "inbox-link-target");
    assertTrue(outside.mkdirs());
    // 先把收件箱换成链接（setUp 建的是空目录），再经链接路径写卡片文件，
    // 这样 file 与 victim 走的是同一条路径，删除会落到外部目录。
    File link = new File(rootfs, ModuleCardStore.CONTAINER_DIR);
    assertTrue(link.delete());
    Files.createSymbolicLink(link.toPath(), outside.toPath());
    File victim = new File(outside, "pet.json");
    File original = card("pet.json", "pet.inbox", "");
    assertTrue(victim.isFile());
    assertTrue(original.isFile());
    assertFalse(ModuleCardStore.archiveInboxFile(link, rootfs, original, "pet.json"));
    assertTrue("链接目标里的同名文件必须原样保留", victim.isFile());
    assertEquals(
        "未被删除或改名，内容原样",
        new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8),
        new String(Files.readAllBytes(victim.toPath()), StandardCharsets.UTF_8));
    assertFalse(new File(link, "pet.json.done").exists());
    assertTrue("原件仍在（经由链接路径）", original.isFile());
  }

  @Test
  public void archiveRenamesToDoneInsideARealDirectory() throws Exception {
    File original = card("pet.json", "pet.inbox", "");
    assertTrue(ModuleCardStore.archiveInboxFile(inbox, rootfs, original, "pet.json"));
    assertFalse(original.exists());
    assertTrue(new File(inbox, "pet.json.done").isFile());
  }

  @Test
  public void successImportsArchivesAndRecordsContainerSource() throws Exception {
    File original = card("pet.json", "pet.inbox", "");
    assertEquals(1, ModuleCardStore.scanContainerInbox(context, rootfs));
    // 原件只在导入成功后归档：.json 不在了，收件箱里是 .done。
    assertFalse(original.exists());
    assertTrue(new File(inbox, "pet.json.done").isFile());
    // 已装进私有目录，来源由宿主记录为 container。
    File installed = new File(new File(context.getFilesDir(), "modules"), "pet.inbox/card.json");
    assertTrue(installed.isFile());
    assertEquals("container", ModuleCardStore.sourceOf(context, "pet.inbox"));
    // digest 是宿主对落盘原始 JSON 的 SHA-256。
    String stored = new String(Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8);
    ModuleCard loaded = installed(context, "pet.inbox");
    assertNotNull(loaded);
    assertEquals(ModuleCard.digestOf(stored), loaded.digest);
    assertTrue(loaded.digest.matches("[0-9a-f]{64}"));
    // 已归档的文件再扫一遍不会重复导入。
    assertEquals(0, ModuleCardStore.scanContainerInbox(context, rootfs));
  }

  @Test
  public void updateReplacesContentDigestAndKeepsPreviousBackup() throws Exception {
    card("pet.json", "pet.inbox", "");
    assertEquals(1, ModuleCardStore.scanContainerInbox(context, rootfs));
    File again = card("pet.json", "pet.inbox", ",\"version\":\"0.2.0\"");
    assertEquals(1, ModuleCardStore.scanContainerInbox(context, rootfs));
    assertFalse(again.exists());
    ModuleCard loaded = installed(context, "pet.inbox");
    assertNotNull(loaded);
    assertEquals("0.2.0", loaded.version);
    String stored =
        new String(
            Files.readAllBytes(
                new File(new File(context.getFilesDir(), "modules"), "pet.inbox/card.json")
                    .toPath()),
            StandardCharsets.UTF_8);
    assertEquals(ModuleCard.digestOf(stored), loaded.digest);
    // 更新前的旧版进备份区，可恢复。
    assertTrue(ModuleCardStore.hasPrevious(context, "pet.inbox"));
  }

  @Test
  public void failureKeepsOriginalAndReportsVisibleDiagnostics() throws Exception {
    File bad = new File(inbox, "broken.json");
    write(bad, "{\"schema\":2,\"id\":\"pet.bad\",\"name\":\"坏\"}");
    assertEquals(0, ModuleCardStore.scanContainerInbox(context, rootfs));
    // 原件留在原地等修正，绝不静默归档。
    assertTrue(bad.exists());
    assertFalse(new File(inbox, "broken.json.done").exists());
    assertTrue("失败原因必须可见", hasIssue("broken.json"));
  }

  @Test
  public void oversizeFileIsRejectedWithoutImport() throws Exception {
    StringBuilder huge = new StringBuilder("{\"schema\":1,\"id\":\"pet.huge\",\"name\":\"");
    while (huge.length() < 33 * 1024) huge.append('大');
    huge.append("\"}");
    File big = new File(inbox, "huge.json");
    write(big, huge.toString());
    assertTrue(big.length() > 32 * 1024);
    assertEquals(0, ModuleCardStore.scanContainerInbox(context, rootfs));
    assertTrue(big.exists());
    assertTrue(hasIssue("huge.json"));
  }

  @Test
  public void imageAssetsAreImportedTogetherWithTheCard() throws Exception {
    File json = card("art.json", "pet.art", ",\"image\":\"avatar.png\"");
    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    Files.write(new File(inbox, "avatar.png").toPath(), png);
    assertEquals(1, ModuleCardStore.scanContainerInbox(context, rootfs));
    ModuleCard loaded = installed(context, "pet.art");
    assertNotNull(loaded);
    assertEquals("avatar.png", loaded.image);
    File stored = ModuleCardStore.assetOf(context, loaded, "avatar.png");
    assertNotNull(stored);
    assertTrue(Arrays.equals(png, Files.readAllBytes(stored.toPath())));
    // 只有 .json 原件归档；图片按声明清单拷进私有目录。
    assertFalse(json.exists());
    assertTrue(new File(inbox, "avatar.png").isFile());
  }
}
