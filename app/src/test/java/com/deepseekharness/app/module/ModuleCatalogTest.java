package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 统一目录：卡片与清单在同一份列表、同一套开关、同一套移除语义。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCatalogTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"a.card\",\"name\":\"卡片\",\"actions\":[]}");
  }

  private static String manifest(String id) {
    return "{\"schema\":1,\"id\":\""
        + id
        + "\",\"name\":\"清单\",\"version\":\"1.0.0\",\"type\":\"frontend\","
        + "\"requires\":{\"minApi\":30},\"contributes\":{\"tokens\":{\"accent\":\"#6aa4ff\"}}}";
  }

  @Test
  public void loadMergesCardAndManifestWithUnifiedEnableState() throws Exception {
    ModuleRegistry.installUserManifest(context, manifest("m.list"));
    ModuleCatalog.Snapshot snapshot = ModuleCatalog.load(context);
    assertEquals(2, snapshot.entries().size());
    ModuleCatalog.Entry card = snapshot.byId("a.card", ModuleCatalog.Kind.CARD);
    ModuleCatalog.Entry list = snapshot.byId("m.list", ModuleCatalog.Kind.MANIFEST);
    assertTrue(card != null && list != null);
    assertTrue(card.enabled);
    assertTrue(list.enabled);
    assertTrue(card.identitySummary(true).contains("未验证"));
    assertTrue(list.identitySummary(true).contains("用户安装"));

    ModuleCatalog.setEnabled(context, list, false);
    assertFalse(ModuleState.isEnabled(context, ModuleState.SCOPE_MANIFEST, "m.list"));
    assertFalse(ModuleRegistry.load(context).isEnabled("m.list"));
    assertEquals(
        false, ModuleCatalog.load(context).byId("m.list", ModuleCatalog.Kind.MANIFEST).enabled);
  }

  @Test
  public void removeManifestLeavesSameIdCardAlone() throws Exception {
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"both\",\"name\":\"双份卡片\",\"actions\":[]}");
    ModuleRegistry.installUserManifest(context, manifest("both"));
    ModuleCatalog.Snapshot snapshot = ModuleCatalog.load(context);
    assertTrue(snapshot.issues().stream().anyMatch(issue -> issue.message.contains("同一 id")));
    ModuleCatalog.Entry manifestEntry = snapshot.byId("both", ModuleCatalog.Kind.MANIFEST);
    assertTrue(ModuleCatalog.remove(context, manifestEntry));
    assertTrue(ModuleCardStore.loadAll(context).stream().anyMatch(card -> card.id.equals("both")));
    assertFalse(ModuleRegistry.load(context).modules().containsKey("both"));
  }

  @Test
  public void removeCardClearsProgramPermission() throws Exception {
    for (ModuleCard item : ModuleCardStore.loadAll(context)) {
      ModuleProgramAuth.setAllowed(context, item.id, "hello", "digest-1");
      ModuleCatalogsRemoval.remove(context, item);
      assertFalse(ModuleProgramAuth.isAllowed(context, item.id, "hello", "digest-1"));
    }
  }

  /** 小包装，避免测试直接构造 Entry 时依赖私有构造器。 */
  private static final class ModuleCatalogsRemoval {
    static void remove(Context context, ModuleCard card) {
      ModuleCatalog.Snapshot snapshot = ModuleCatalog.load(context);
      ModuleCatalog.Entry entry = snapshot.byId(card.id, ModuleCatalog.Kind.CARD);
      ModuleCatalog.remove(context, entry);
    }
  }
}
