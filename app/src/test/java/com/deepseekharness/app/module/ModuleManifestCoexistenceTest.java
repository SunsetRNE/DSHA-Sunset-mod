package com.deepseekharness.app.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 同 id 的卡片与声明式清单可以并存（{@link ModuleCatalog#load} 明确允许），
 * 两条路径都必须把这个前提当真：
 *
 * <ul>
 *   <li>导入/更新卡片不能删掉同目录的 {@code module.json}（数据丢失）；
 *   <li>移除卡片必须报成功并按卡片撤销授权，不能因为「目录还在」就说失败。
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleManifestCoexistenceTest {

  private final Context context = RuntimeEnvironment.getApplication();

  private static String manifest(String id) {
    return "{\"schema\":1,\"id\":\""
        + id
        + "\",\"name\":\"清单\",\"version\":\"1.0.0\",\"type\":\"frontend\","
        + "\"requires\":{\"minApi\":30},\"contributes\":{\"tokens\":{\"accent\":\"#6aa4ff\"}}}";
  }

  private static String card(String id, String name) {
    return "{\"schema\":1,\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"actions\":[]}";
  }

  private static File moduleDir(Context context, String id) {
    return new File(new File(context.getFilesDir(), "modules"), id);
  }

  @Before
  public void clearStorage() throws Exception {
    File modules = new File(context.getFilesDir(), "modules");
    File[] entries = modules.listFiles();
    if (entries != null) for (File entry : entries) deleteTree(entry);
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }

  @Test
  public void importingCardKeepsSameIdManifest() throws Exception {
    ModuleRegistry.installUserManifest(context, manifest("both"));
    assertTrue(new File(moduleDir(context, "both"), "module.json").isFile());

    // 目录里只有清单、没有 card.json —— 旧实现把它当「装到一半的残骸」整个挪走删掉。
    ModuleCardStore.importCard(context, card("both", "卡片"));

    assertTrue("清单必须还在", new File(moduleDir(context, "both"), "module.json").isFile());
    assertTrue(new File(moduleDir(context, "both"), "card.json").isFile());
    assertNotNull(
        "目录条目仍然要能看到清单", ModuleCatalog.load(context).byId("both", ModuleCatalog.Kind.MANIFEST));
  }

  @Test
  public void updatingCardTwiceKeepsManifest() throws Exception {
    ModuleCardStore.importCard(context, card("both", "v1"));
    ModuleRegistry.installUserManifest(context, manifest("both"));
    ModuleCardStore.importCard(context, card("both", "v2"));
    ModuleCardStore.importCard(context, card("both", "v3"));

    // 只留最近一份备份，所以清单不能只存在备份区里。
    assertTrue(new File(moduleDir(context, "both"), "module.json").isFile());
    assertNotNull("更新后清单仍然可读", ModuleRegistry.load(context).modules().get("both"));
  }

  @Test
  public void removingCardWithSiblingManifestReportsSuccessAndRevokesGrants() throws Exception {
    ModuleCardStore.importCard(context, card("both", "卡片"));
    ModuleRegistry.installUserManifest(context, manifest("both"));

    String digest = ModuleCardStore.contentDigest(moduleDir(context, "both"));
    ModuleExec.setAllowed(context, "both", digest);
    ModuleSurfaceAuth.setAllowed(context, "both", ModuleSurfaceAuth.APP, digest);

    ModuleCatalog.Entry entry = ModuleCatalog.load(context).byId("both", ModuleCatalog.Kind.CARD);
    assertNotNull(entry);
    assertTrue("卡片移除必须报成功", ModuleCatalog.remove(context, entry));

    assertFalse(new File(moduleDir(context, "both"), "card.json").exists());
    assertTrue("清单不属于这张卡片", new File(moduleDir(context, "both"), "module.json").isFile());
    assertFalse("移除后执行授权必须撤销", ModuleExec.isAllowed(context, "both", digest));
    assertFalse(
        "移除后界面授权必须撤销", ModuleSurfaceAuth.isAllowed(context, "both", ModuleSurfaceAuth.APP, digest));
  }
}
