package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.junit.runner.RunWith;

/** 卡片契约的硬边界：图片只能是文件名，动作只能是白名单里的六个。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleCardTest {

  private static String card(String extra) {
    return "{\"schema\":1,\"id\":\"my.pet\",\"name\":\"小蓝\",\"subtitle\":\"在写周报\"," + extra + "}";
  }

  @Test
  public void imageAcceptsPlainFileName() {
    ModuleCard parsed = ModuleCard.parse(card("\"image\":\"pet.png\",\"actions\":[]"));
    assertEquals("pet.png", parsed.image);
    assertEquals("user:my.pet", parsed.layoutId());
  }

  @Test
  public void imageRejectsPathTraversal() {
    assertRejected(card("\"image\":\"../pet.png\",\"actions\":[]"));
    assertRejected(card("\"image\":\"a/pet.png\",\"actions\":[]"));
    assertRejected(card("\"image\":\"pet.exe\",\"actions\":[]"));
  }

  @Test
  public void bindAcceptsLabelAndKnownSource() {
    ModuleCard parsed =
        ModuleCard.parse(card("\"bind\":{\"line1\":\"今日启动|dsh.runs\"},\"actions\":[]"));
    assertEquals("dsh.runs", parsed.bind.get("line1"));
    assertEquals("今日启动", parsed.bindLabels.get("line1"));
  }

  @Test
  public void bindRejectsUnknownSource() {
    assertRejected(card("\"bind\":{\"line1\":\"cpu.temp\"},\"actions\":[]"));
    assertRejected(card("\"bind\":{\"line9\":\"dsh.status\"},\"actions\":[]"));
  }

  @Test
  public void fieldsRejectBadKeyAndType() {
    assertRejected(card("\"fields\":[{\"key\":\"A-B\",\"label\":\"x\"}],\"actions\":[]"));
    assertRejected(
        card("\"fields\":[{\"key\":\"ok\",\"label\":\"x\",\"type\":\"slider\"}],\"actions\":[]"));
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"fields\":[{\"key\":\"text\",\"label\":\"内容\",\"type\":\"text\"}],"
                    + "\"actions\":[]"));
    assertEquals(1, parsed.fields.size());
  }

  @Test
  public void actionTypeMustBeWhitelisted() {
    // exec 现在是白名单动作，但需要用户授权；其它未列出的类型仍必须拒绝。
    assertRejected(card("\"actions\":[{\"label\":\"跑\",\"type\":\"shell\",\"value\":\"ls\"}]"));
    ModuleCard parsed =
        ModuleCard.parse(
            card("\"actions\":[{\"label\":\"喂食\",\"type\":\"toast\",\"value\":\"喵\"}]"));
    assertEquals(1, parsed.actions.size());
    assertEquals("toast", parsed.actions.get(0).type);
  }

  @Test
  public void actionNeedsValueExceptNavigationOnes() {
    assertRejected(card("\"actions\":[{\"label\":\"复制\",\"type\":\"copy\",\"value\":\"\"}]"));
    ModuleCard parsed =
        ModuleCard.parse(
            card("\"actions\":[{\"label\":\"设置\",\"type\":\"settings\",\"value\":\"\"}]"));
    assertTrue(parsed.actions.get(0).value.isEmpty());
  }

  @Test
  public void sequenceAcceptsMixedSteps() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[{\"label\":\"一键\",\"type\":\"sequence\","
                    + "\"value\":\"toast:你好\\nexec:uname -m\"}]"));
    assertEquals("sequence", parsed.actions.get(0).type);
  }

  @Test
  public void sequenceRejectsBadOrNestedStep() {
    // 缺冒号
    assertRejected(
        card("\"actions\":[{\"label\":\"x\",\"type\":\"sequence\",\"value\":\"justtext\"}]"));
    // 嵌套 sequence
    assertRejected(
        card(
            "\"actions\":[{\"label\":\"x\",\"type\":\"sequence\","
                + "\"value\":\"sequence:toast:hi\"}]"));
    // 不存在的步骤类型
    assertRejected(
        card("\"actions\":[{\"label\":\"x\",\"type\":\"sequence\",\"value\":\"rm:-rf /\"}]"));
  }

  /** 模板必须自己就是合法卡片：否则模型照抄必然失败。 */
  @Test
  public void templateIsItselfValidCard() {
    ModuleCard parsed = ModuleCard.parse(ModuleCard.TEMPLATE);
    assertEquals("demo.pet", parsed.id);
    assertEquals(3, parsed.actions.size());
    assertEquals("script", parsed.actions.get(0).type);
    assertEquals("sequence", parsed.actions.get(2).type);
    // 序列里那串 \n 必须解析成真换行，否则步骤拆不开。
    assertTrue(parsed.actions.get(2).value.contains("\n"));
    assertEquals(1, parsed.fields.size());
  }

  @Test
  public void scriptActionAcceptsCodeAndRejectsOverlong() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[{\"label\":\"跑\",\"type\":\"script\","
                    + "\"value\":\"dsha.slot('line1','你好'); return 'ok';\"}]"));
    assertEquals("script", parsed.actions.get(0).type);

    StringBuilder huge = new StringBuilder();
    for (int i = 0; i <= ModuleCard.MAX_SCRIPT; i++) huge.append('a');
    assertRejected(
        card("\"actions\":[{\"label\":\"跑\",\"type\":\"script\",\"value\":\"" + huge + "\"}]"));
  }

  @Test
  public void sequenceStepMayBeScript() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[{\"label\":\"一键\",\"type\":\"sequence\","
                    + "\"value\":\"script:dsha.toast('hi')\\ntoast:完\"}]"));
    assertEquals("sequence", parsed.actions.get(0).type);
  }

  /** 旧写法（顶层 overlay/theme）要折进 provides，不能因为换了架构就读不出老模块。 */
  @Test
  public void legacyOverlayAndThemeFoldIntoProvides() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"overlay\":{\"image\":\"pet.png\",\"size\":96},"
                    + "\"theme\":{\"image\":\"bg.png\",\"opacity\":0.5},\"actions\":[]"));
    assertEquals(2, parsed.provides.size());
    assertEquals(96, parsed.overlay.size);
    assertEquals(0.5f, parsed.theme.opacity, 0.001f);
  }

  /** 宿主不认识的扩展点要保留（标「未支持」），不能整卡拒绝 —— 否则以后加能力就破坏兼容。 */
  @Test
  public void unknownSurfaceIsKeptNotRejected() {
    ModuleCard parsed =
        ModuleCard.parse(card("\"provides\":{\"ui.widget\":{\"x\":1}},\"actions\":[]"));
    assertTrue(parsed.provides.containsKey("ui.widget"));
    assertTrue(parsed.assetNames().isEmpty());
  }

  @Test
  public void providesRejectBadNameAndNonObject() {
    assertRejected(card("\"provides\":{\"Bad Name\":{}},\"actions\":[]"));
    assertRejected(card("\"provides\":{\"ui.x\":1},\"actions\":[]"));
  }

  @Test
  public void overlayAndThemeValidateRanges() {
    assertRejected(card("\"overlay\":{\"image\":\"pet.png\",\"size\":10},\"actions\":[]"));
    assertRejected(card("\"overlay\":{\"image\":\"pet.exe\"},\"actions\":[]"));
    assertRejected(card("\"theme\":{\"image\":\"bg.png\",\"opacity\":0.99},\"actions\":[]"));
    assertRejected(card("\"theme\":{\"image\":\"bg.png\",\"scrim\":\"black\"},\"actions\":[]"));
  }

  @Test
  public void viewNodesAreBoundedAndImagesAreCollected() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"view\":[{\"type\":\"image\",\"src\":\"bg.png\"},"
                    + "{\"type\":\"text\",\"text\":\"hi\"}],\"actions\":[]"));
    assertTrue(parsed.assetNames().contains("bg.png"));

    StringBuilder nodes = new StringBuilder("[");
    for (int i = 0; i <= ModuleCard.MAX_VIEW_NODES; i++) {
      if (i > 0) nodes.append(',');
      nodes.append("{\"type\":\"text\",\"text\":\"x\"}");
    }
    nodes.append(']');
    assertRejected(card("\"view\":" + nodes + ",\"actions\":[]"));
  }

  @Test
  public void skillDocumentCarriesScriptApi() {
    assertTrue(ModuleCard.SKILL.contains("dsha.slot("));
    assertTrue(ModuleCard.SKILL.contains("dsha.exec("));
    assertTrue(ModuleCard.SKILL.contains("script"));
  }

  @Test
  public void skillDocumentCarriesTemplate() {
    assertTrue(ModuleCard.SKILL.contains("\"schema\": 1"));
    assertTrue(ModuleCard.SKILL.contains("~/.dsha-modules/"));
    assertTrue(ModuleCard.SKILL.contains("| `toast` |"));
  }

  /** 旧 schema1 模块没有 author/version：解析给默认值，不能因此读不出老卡片。 */
  @Test
  public void legacySchemaDefaultsAuthorAndVersion() {
    ModuleCard parsed = ModuleCard.parse(card("\"actions\":[]"));
    assertEquals("local", parsed.author);
    assertEquals("0.1.0", parsed.version);
    assertEquals("local/my.pet@0.1.0", parsed.identity());
  }

  /** author 只接受安全字符；空串回落到默认 local 而不是报错。 */
  @Test
  public void authorRules() {
    assertEquals(
        "作者名可以带 @",
        "someone@mail",
        ModuleCard.parse(card("\"author\":\"someone@mail\",\"actions\":[]")).author);
    assertRejected(card("\"author\":\"坏名字\",\"actions\":[]"));
    assertRejected(card("\"author\":\"a b\",\"actions\":[]"));
    StringBuilder longAuthor = new StringBuilder();
    for (int i = 0; i <= ModuleCard.MAX_AUTHOR; i++) longAuthor.append('a');
    assertRejected(card("\"author\":\"" + longAuthor + "\",\"actions\":[]"));
    // 显式空串按不合规拒绝；只有「没写 author」才默认 local
    assertRejected(card("\"author\":\"\",\"actions\":[]"));
  }

  /** version 必须是严格 SemVer：位数不够、前导零、前缀 v 都拒绝。 */
  @Test
  public void versionIsStrictSemVer() {
    assertEquals("1.2.3", ModuleCard.parse(card("\"version\":\"1.2.3\",\"actions\":[]")).version);
    assertEquals(
        "带预发布号合法",
        "1.0.0-rc.1",
        ModuleCard.parse(card("\"version\":\"1.0.0-rc.1\",\"actions\":[]")).version);
    assertRejected(card("\"version\":\"0.1\",\"actions\":[]"));
    assertRejected(card("\"version\":\"01.2.3\",\"actions\":[]"));
    assertRejected(card("\"version\":\"v1.0.0\",\"actions\":[]"));
    assertRejected(card("\"version\":\"1.0.0.1\",\"actions\":[]"));
    StringBuilder longVersion = new StringBuilder("1.0.0-");
    for (int i = 0; i < ModuleCard.MAX_VERSION; i++) longVersion.append('a');
    assertRejected(card("\"version\":\"" + longVersion + "\",\"actions\":[]"));
  }

  /** 来源是宿主记录的事实：JSON 里自己写 source 无效，非法来源直接拒绝。 */
  @Test
  public void sourceIsRecordedByHostNotByTheJson() {
    ModuleCard container = ModuleCard.parse(card("\"actions\":[]"), ModuleCard.SOURCE_CONTAINER);
    assertEquals("container", container.source);
    // JSON 自带 source 字段被忽略：模块声明不了自己的来源
    assertEquals(
        "local",
        ModuleCard.parse(card("\"source\":\"container\",\"actions\":[]"), ModuleCard.SOURCE_LOCAL)
            .source);
    assertEquals("unknown", ModuleCard.parse(card("\"actions\":[]"), null).source);
    assertEquals("unknown", ModuleCard.parse(card("\"actions\":[]"), "").source);
    assertEquals("local", ModuleCard.parse(card("\"actions\":[]")).source);
    assertSourceRejected(card("\"actions\":[]"), "network");
    assertSourceRejected(card("\"actions\":[]"), "LOCAL");
  }

  private void assertSourceRejected(String json, String source) {
    try {
      ModuleCard.parse(json, source);
      fail("should reject source: " + source);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
    }
  }

  /** SHA-256 helper：对标准测试向量取值正确（向量已用 certutil 独立核对）。 */
  @Test
  public void digestIsRealSha256Hex() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ModuleCard.digestOf("abc"));
    assertEquals(
        "空串向量",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ModuleCard.digestOf(""));
    // 含中文/多字节内容也要走 UTF-8
    assertEquals(64, ModuleCard.digestOf("喵").length());
  }

  /** parse 接线：digest 就是原始 JSON 字节的 SHA-256，小写十六进制 64 位。 */
  @Test
  public void parsedCardCarriesDigestOfRaw() {
    String raw = card("\"image\":\"pet.png\",\"actions\":[]");
    ModuleCard parsed = ModuleCard.parse(raw);
    assertEquals(ModuleCard.digestOf(raw), parsed.digest);
    assertTrue(parsed.digest.matches("[0-9a-f]{64}"));
    // 相同内容摘要一致；内容变一个字摘要就变
    assertEquals(ModuleCard.parse(raw).digest, parsed.digest);
    assertTrue(!ModuleCard.digestOf(raw).equals(ModuleCard.digestOf(raw + " ")));
  }

  @Test
  public void digestShortAndIdentityAreDisplayFriendly() {
    ModuleCard parsed = ModuleCard.parse(card("\"actions\":[]"));
    assertEquals(parsed.digest.substring(0, 12) + "…", parsed.digestShort());
    assertEquals("local/my.pet@0.1.0", parsed.identity());
  }

  @Test
  public void themeTokensAndNavAreParsedAndCollectedAsAssets() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[],\"view\":[{\"type\":\"button\",\"label\":\"刷新\",\"image\":\"btn.png\",\"action\":\"toast:ok\"}],"
                    + "\"provides\":{"
                    + "\"ui.theme\":{\"image\":\"bg.png\",\"opacity\":0.5,\"scrim\":\"#101010\","
                    + "\"tokens\":{\"accent\":\"#112233\",\"text\":\"#FAFAFA\",\"radius\":18,\"buttonRadius\":12,\"fontScale\":1.1}},"
                    + "\"ui.nav\":{\"blur\":true,\"items\":{\"launch\":{\"label\":\"首页\",\"image\":\"nav-launch.png\"},"
                    + "\"settings\":{\"label\":\"设置\"}}}}"));
    assertEquals("#112233", parsed.theme.tokens.accent);
    assertEquals("#FAFAFA", parsed.theme.tokens.text);
    assertEquals(18, parsed.theme.tokens.radius);
    assertEquals(12, parsed.theme.tokens.buttonRadius);
    assertEquals(1.1f, parsed.theme.tokens.fontScale, 0.001f);
    assertTrue("模块可自己开启底栏磨砂", parsed.nav.blur);
    assertEquals("首页", parsed.nav.items.get("launch").label);
    assertEquals("nav-launch.png", parsed.nav.items.get("launch").image);
    assertEquals("设置", parsed.nav.items.get("settings").label);
    java.util.List<String> assets = parsed.assetNames();
    assertTrue(assets.contains("bg.png"));
    assertTrue(assets.contains("nav-launch.png"));
    assertTrue(assets.contains("btn.png"));

    // 只写 blur、不写 items 也合法：模块可以用它单独打开底栏磨砂（宿主不再内置开关）。
    ModuleCard blurOnly =
        ModuleCard.parse(card("\"actions\":[],\"provides\":{\"ui.nav\":{\"blur\":true}}"));
    assertTrue(blurOnly.nav.blur);
    assertTrue(blurOnly.nav.items.isEmpty());
  }

  @Test
  public void invalidThemeTokensAndNavAreRejected() {
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.theme\":{\"image\":\"bg.png\",\"tokens\":{\"radius\":40}}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.theme\":{\"image\":\"bg.png\",\"tokens\":{\"accent\":\"red\"}}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.nav\":{\"items\":{\"unknown\":{\"label\":\"X\"}}}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.nav\":{\"items\":{\"launch\":{\"label\":\"一二三四五六七八九\"}}}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.nav\":{\"items\":{\"launch\":{\"image\":\"../nav.png\"}}}}"));
  }

  @Test
  public void pagesAndLifecycleAreParsedAndPageTargetsChecked() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[{\"label\":\"详情\",\"type\":\"page\",\"value\":\"detail\"}],"
                    + "\"pages\":[{\"id\":\"detail\",\"title\":\"详情\","
                    + "\"view\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"image\",\"src\":\"p.png\"}]}],"
                    + "\"provides\":{\"module.lifecycle\":{\"onShow\":\"script:dsha.log('show')\","
                    + "\"onHide\":\"dsha.log('hide')\",\"tick\":{\"every\":30,\"script\":\"dsha.log('tick')\"}}}}"));
    assertEquals("详情", parsed.pages.get("detail").title);
    assertEquals("detail", parsed.actions.get(0).value);
    assertEquals("dsha.log('show')", parsed.lifecycle.onShow);
    assertEquals("dsha.log('hide')", parsed.lifecycle.onHide);
    assertEquals(30, parsed.lifecycle.tickEverySeconds);
    assertEquals("dsha.log('tick')", parsed.lifecycle.tick);
    assertTrue(parsed.assetNames().contains("p.png"));
  }

  @Test
  public void invalidPagesAndLifecycleAreRejected() {
    assertRejected(
        card(
            "\"actions\":[{\"label\":\"X\",\"type\":\"page\",\"value\":\"missing\"}],\"pages\":["
                + "{\"id\":\"detail\",\"title\":\"详情\",\"view\":[{\"type\":\"text\",\"text\":\"hi\"}]}]"));
    assertRejected(
        card(
            "\"actions\":[],\"pages\":[{\"id\":\"detail\",\"title\":\"A\",\"view\":[{\"type\":\"text\",\"text\":\"a\"}]},"
                + "{\"id\":\"detail\",\"title\":\"B\",\"view\":[{\"type\":\"text\",\"text\":\"b\"}]}]"));
    assertRejected(
        card("\"actions\":[],\"pages\":[{\"id\":\"detail\",\"title\":\"详情\",\"view\":[]}]"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.lifecycle\":{\"tick\":{\"every\":5,\"script\":\"x\"}}}}"));
    assertRejected(
        card("\"actions\":[],\"provides\":{\"module.lifecycle\":{\"tick\":{\"every\":30}}}}"));
  }

  @Test
  public void managedProgramsAreParsedAndCollectedAsAssets() {
    String sha = "0".repeat(64);
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                    + "{\"id\":\"hello\",\"file\":\"hello.bin\",\"sha256\":\""
                    + sha
                    + "\","
                    + "\"abi\":\"x86_64\",\"entry\":\"hello.bin\",\"args\":[\"--x\"],\"timeout\":30}]}}"));
    ModuleCard.Program program = parsed.programs.get("hello");
    assertEquals("hello.bin", program.file);
    assertEquals("x86_64", program.abi);
    assertEquals(1, program.args.size());
    assertTrue(parsed.assetNames().contains("hello.bin"));
    assertTrue(parsed.isProgramAsset("hello.bin"));
  }

  @Test
  public void invalidManagedProgramsAreRejected() {
    String sha = "0".repeat(64);
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                + "{\"id\":\"hello\",\"file\":\"../hello\",\"sha256\":\""
                + sha
                + "\",\"abi\":\"x86_64\"}]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                + "{\"id\":\"hello\",\"file\":\"hello\",\"sha256\":\"short\",\"abi\":\"x86_64\"}]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                + "{\"id\":\"hello\",\"file\":\"hello\",\"sha256\":\""
                + sha
                + "\",\"abi\":\"armv7\"}]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                + "{\"id\":\"hello\",\"file\":\"hello\",\"sha256\":\""
                + sha
                + "\",\"abi\":\"x86_64\",\"entry\":\"other\"}]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.program\":{\"programs\":["
                + "{\"id\":\"hello\",\"file\":\"hello\",\"sha256\":\""
                + sha
                + "\",\"abi\":\"x86_64\",\"timeout\":0}]}}"));
  }

  @Test
  public void parsesHomeProgressSkin() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[],\"provides\":{\"ui.skin\":{\"home.progress\":{"
                    + "\"height\":6,\"radius\":3,\"idle\":\"#35C759\",\"running\":\"#7C9CFF\","
                    + "\"success\":\"#9CFF6E\",\"error\":\"#FF5A5F\",\"band\":0.5,\"sweep\":1200}}}"));
    assertNotNull(parsed.skin);
    assertEquals(6, parsed.skin.homeProgress.heightDp);
    assertEquals(3, parsed.skin.homeProgress.radiusDp);
    assertEquals(0.5f, parsed.skin.homeProgress.bandFraction, 0.001f);
    assertEquals(1200, parsed.skin.homeProgress.sweepMs);
  }

  @Test
  public void rejectsUnsafeHomeProgressSkin() {
    assertRejected(
        card("\"actions\":[],\"provides\":{\"ui.skin\":{\"home.progress\":{\"height\":99}}}"));
    assertRejected(
        card("\"actions\":[],\"provides\":{\"ui.skin\":{\"home.progress\":{\"band\":2}}}"));
    assertRejected(
        card("\"actions\":[],\"provides\":{\"ui.skin\":{\"home.progress\":{\"sweep\":1}}}"));
    assertRejected(
        card("\"actions\":[],\"provides\":{\"ui.skin\":{\"home.progress\":{\"idle\":\"red\"}}}"));
  }

  @Test
  public void parsesWebModuleAndCollectsDeclaredAssets() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[],\"provides\":{\"module.web\":{"
                    + "\"entry\":\"index.html\",\"files\":[\"index.html\",\"app.css\",\"app.js\"]}}}"));
    assertNotNull(parsed.web);
    assertEquals("index.html", parsed.web.entry);
    assertTrue(parsed.web.files.contains("app.js"));
    assertTrue(parsed.isWebAsset("app.css"));
    assertTrue(parsed.assetNames().contains("app.js"));
  }

  @Test
  public void rejectsUnsafeWebResources() {
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.web\":{\"entry\":\"app.js\","
                + "\"files\":[\"app.js\"]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.web\":{\"entry\":\"../index.html\","
                + "\"files\":[\"../index.html\"]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"module.web\":{\"entry\":\"index.html\","
                + "\"files\":[\"index.html\",\"evil.exe\"]}}"));
  }

  @Test
  public void parsesModuleHomeTakeoverWithEffects() {
    ModuleCard parsed =
        ModuleCard.parse(
            card(
                "\"actions\":[],\"provides\":{\"ui.app\":{"
                    + "\"title\":\"我的主页\",\"subtitle\":\"模块接管\",\"background\":\"#101820\","
                    + "\"view\":[{\"type\":\"progress\",\"variant\":\"waveCurve\",\"value\":65},"
                    + "{\"type\":\"segmented\",\"options\":[{\"label\":\"全部\"}]}]}}"));
    assertNotNull(parsed.app);
    assertEquals("我的主页", parsed.app.title);
    assertEquals("#101820", parsed.app.background);
    assertEquals(2, parsed.app.view.length());
    assertTrue(parsed.provides.containsKey(ModuleCard.APP_SURFACE));
  }

  @Test
  public void rejectsEmptyOrUnsafeModuleHome() {
    assertRejected(card("\"actions\":[],\"provides\":{\"ui.app\":{\"title\":\"x\",\"view\":[]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.app\":{\"background\":\"red\","
                + "\"view\":[{\"type\":\"text\",\"text\":\"x\"}]}}"));
    assertRejected(
        card(
            "\"actions\":[],\"provides\":{\"ui.app\":{\"title\":\"12345678901234567\","
                + "\"view\":[{\"type\":\"text\",\"text\":\"x\"}]}}"));
  }

  private void assertRejected(String json) {
    try {
      ModuleCard.parse(json);
      fail("should reject: " + json);
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
    }
  }
}
