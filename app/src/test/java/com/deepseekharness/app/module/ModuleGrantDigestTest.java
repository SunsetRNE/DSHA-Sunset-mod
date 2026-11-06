package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 授权与内容摘要绑定：卡片的 id 不变、内容变了，旧批准必须失效。
 *
 * <p>这是 P0 负控：只按 {@code cardId} 记授权时，同 id 自动更新会继承旧授权，
 * 新脚本在用户零感知下执行。这里锁住三条：
 * <ul>
 *   <li>内容变 → 摘要变；
 *   <li>用旧摘要查授权 → 拒绝；
 *   <li>空摘要（读不出来）→ 一律拒绝，不让异常路径变成放行。
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleGrantDigestTest {

  private Context context;
  private File cardDir;

  @Before
  public void setUp() throws Exception {
    context = RuntimeEnvironment.getApplication();
    cardDir = new File(context.getFilesDir(), "grant-probe");
    assertTrue(cardDir.mkdirs());
    write(new File(cardDir, "card.json"), "{\"schema\":1,\"id\":\"probe\",\"name\":\"探针\"}");
  }

  private static void write(File file, String content) throws Exception {
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void digestChangesWhenContentChanges() throws Exception {
    String before = ModuleCardStore.contentDigest(cardDir);
    assertTrue(before.matches("[0-9a-f]{64}"));
    assertEquals(
        "same bytes must give the same digest", before, ModuleCardStore.contentDigest(cardDir));

    write(new File(cardDir, "card.json"), "{\"schema\":1,\"id\":\"probe\",\"name\":\"探针2\"}");
    String after = ModuleCardStore.contentDigest(cardDir);
    assertNotEquals(before, after);
  }

  @Test
  public void execGrantIsBoundToTheDigestThatWasApproved() throws Exception {
    String approved = ModuleCardStore.contentDigest(cardDir);
    ModuleExec.setAllowed(context, "probe", approved);
    assertTrue(ModuleExec.isAllowed(context, "probe", approved));

    // 同一 id、内容已换：旧批准不能再放行。
    write(new File(cardDir, "card.json"), "{\"schema\":1,\"id\":\"probe\",\"name\":\"换过\"}");
    String updated = ModuleCardStore.contentDigest(cardDir);
    assertNotEquals(approved, updated);
    assertFalse(ModuleExec.isAllowed(context, "probe", updated));
  }

  @Test
  public void emptyDigestIsNeverAllowed() {
    ModuleExec.setAllowed(context, "probe", "abc");
    assertFalse(ModuleExec.isAllowed(context, "probe", ""));
    assertFalse(ModuleExec.isAllowed(context, "probe", null));
    // 写空摘要本身要被忽略，不能把"没有摘要"存成有效授权。
    ModuleExec.setAllowed(context, "probe", "");
    assertFalse(ModuleExec.isAllowed(context, "probe", ""));
  }

  @Test
  public void legacyBooleanGrantIsDroppedInsteadOfInherited() {
    context
        .getSharedPreferences("dsha_module_exec", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("allow_legacy", true)
        .commit();
    // 旧版本存的是 boolean，无法判断批准的是哪一版内容 → 拒绝，并且顺手清掉。
    assertFalse(ModuleExec.isAllowed(context, "legacy", "whatever"));
    assertFalse(
        context
            .getSharedPreferences("dsha_module_exec", Context.MODE_PRIVATE)
            .contains("allow_legacy"));
  }

  @Test
  public void programGrantIsBoundToTheDigestToo() throws Exception {
    String first = ModuleCardStore.contentDigest(cardDir);
    ModuleProgramAuth.setAllowed(context, "probe", "tool", first);
    assertTrue(ModuleProgramAuth.isAllowed(context, "probe", "tool", first));

    File bin = new File(cardDir, "bin");
    assertTrue(bin.mkdirs());
    write(new File(bin, "tool"), "binary-v2");
    String second = ModuleCardStore.contentDigest(cardDir);
    assertNotEquals(first, second);
    assertFalse(ModuleProgramAuth.isAllowed(context, "probe", "tool", second));
  }

  @Test
  public void containerCardsStartDisabledAndLocalCardsStartEnabled() throws Exception {
    ModuleCardStore.importCard(
        context,
        "{\"schema\":1,\"id\":\"from.container\",\"name\":\"容器\",\"actions\":[]}",
        (java.util.Map<String, byte[]>) null,
        ModuleCard.SOURCE_CONTAINER);
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"from.local\",\"name\":\"本地\",\"actions\":[]}");

    // 容器投递来的内容默认不加载，等用户确认。
    assertFalse(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "from.container"));
    // 用户自己挑的文件立即生效。
    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "from.local"));
  }

  @Test
  public void cardAndManifestWithTheSameIdDoNotShareTheSwitch() throws Exception {
    ModuleCardStore.importCard(
        context, "{\"schema\":1,\"id\":\"same.id\",\"name\":\"卡片\",\"actions\":[]}");
    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "same.id"));

    // 清单侧关掉，不能把同 id 的卡片一起关掉。
    ModuleState.setEnabled(context, ModuleState.SCOPE_MANIFEST, "same.id", false);
    assertTrue(ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, "same.id"));
    assertFalse(ModuleState.isEnabled(context, ModuleState.SCOPE_MANIFEST, "same.id"));
  }

  @Test
  public void execRefusesToRunWithoutAGrantForThisContent() throws Exception {
    // 未授权时执行入口必须自己拒绝：不依赖调用方是否记得先问。
    String digest = ModuleCardStore.contentDigest(cardDir);
    ModuleExec.Result refused = ModuleExec.run(context, "probe", digest, "echo hi");
    assertFalse(refused.ok);
    assertTrue(refused.output.contains("未授权"));

    // 授权的是旧内容，换了内容之后同样拒绝。
    ModuleExec.setAllowed(context, "probe", digest);
    write(new File(cardDir, "card.json"), "{\"schema\":1,\"id\":\"probe\",\"name\":\"v2\"}");
    String updated = ModuleCardStore.contentDigest(cardDir);
    ModuleExec.Result stale = ModuleExec.run(context, "probe", updated, "echo hi");
    assertFalse(stale.ok);
    assertTrue(stale.output.contains("未授权"));
  }

  @Test
  public void lifecycleHooksNeedTheirOwnDigestBoundGrant() throws Exception {
    String digest = ModuleCardStore.contentDigest(cardDir);
    assertFalse(ModuleLifecycleAuth.isAllowed(context, "probe", digest));
    ModuleLifecycleAuth.setAllowed(context, "probe", digest);
    assertTrue(ModuleLifecycleAuth.isAllowed(context, "probe", digest));

    write(new File(cardDir, "card.json"), "{\"schema\":1,\"id\":\"probe\",\"name\":\"v3\"}");
    String updated = ModuleCardStore.contentDigest(cardDir);
    assertFalse(ModuleLifecycleAuth.isAllowed(context, "probe", updated));
  }
}
