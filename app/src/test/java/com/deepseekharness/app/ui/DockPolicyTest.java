package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 横屏 Dock 的纯策略单测：UA 选择（重开横屏生效且保留真实版本）、弹回入口与退避、scheme 策略。
 *
 * <p>这些逻辑原本嵌在 Activity 里，审查要求有独立行为测试，因此抽成静态函数后在此覆盖。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DockPolicyTest {
  private static final String DEFAULT_UA =
      "Mozilla/5.0 (Linux; Android 16; V2425A) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile";

  @Test
  public void desktopUaSwitchKeepsTheRealChromeVersion() {
    String desktop = DockDisplayActivity.userAgentFor(true, DEFAULT_UA);
    assertTrue("桌面 UA 只替换平台段", desktop.contains("(X11; Linux x86_64)"));
    assertTrue("必须保留真实 Chrome 版本，不能伪装新内核", desktop.contains("Chrome/131.0.0.0"));
    assertFalse(desktop.contains("Android 16"));
    assertFalse("桌面 UA 不保留 Mobile 标记", desktop.contains("Mobile Safari/"));
    assertEquals(
        "desktopUa=false 必须回退 WebView 默认 UA",
        DEFAULT_UA,
        DockDisplayActivity.userAgentFor(false, DEFAULT_UA));
  }

  @Test
  public void bounceReloadsTheAuthenticatedEntryInsteadOfTheTokenUrl() {
    assertEquals(
        "优先 baseUrl（cookie 已就位，不带 token）",
        "http://127.0.0.1:3200/",
        DockDisplayActivity.bounceEntry(
            "http://127.0.0.1:3200/", "http://127.0.0.1:3200/dsh?token=x"));
    assertEquals(
        "baseUrl 为空时才退回 authUrl",
        "http://127.0.0.1:3200/dsh?token=x",
        DockDisplayActivity.bounceEntry("", "http://127.0.0.1:3200/dsh?token=x"));
    assertEquals(
        "null baseUrl 同样退回 authUrl",
        "http://127.0.0.1:3200/dsh?token=x",
        DockDisplayActivity.bounceEntry(null, "http://127.0.0.1:3200/dsh?token=x"));
  }

  @Test
  public void bounceBackoffStopsAfterThreeRapidHopsAndResetsLater() {
    long window = 10_000L;
    int max = 3;
    assertEquals(1, DockDisplayActivity.nextBounceCount(1_000L, 0L, 0, window, max));
    assertEquals(2, DockDisplayActivity.nextBounceCount(2_000L, 1_000L, 1, window, max));
    assertEquals(3, DockDisplayActivity.nextBounceCount(3_000L, 2_000L, 2, window, max));
    assertEquals(-1, DockDisplayActivity.nextBounceCount(4_000L, 3_000L, 3, window, max));
    assertEquals(1, DockDisplayActivity.nextBounceCount(30_000L, 4_000L, 3, window, max));
  }

  @Test
  public void intentSchemeIsRejectedRegardlessOfCase() {
    assertTrue(DockDisplayActivity.isIntentScheme("intent://scan/#Intent;scheme=zxing;end"));
    assertTrue(DockDisplayActivity.isIntentScheme("INTENT://scan/#Intent;scheme=zxing;end"));
    assertTrue(DockDisplayActivity.isIntentScheme("Intent://anything"));
    assertFalse(DockDisplayActivity.isIntentScheme("https://example.com"));
    assertFalse(DockDisplayActivity.isIntentScheme(null));
  }

  @Test
  public void webViewSchemesAreTheOnlyOnesLoadedInPlace() {
    assertTrue(DockDisplayActivity.isWebViewScheme("https"));
    assertTrue(DockDisplayActivity.isWebViewScheme("HTTPS"));
    assertTrue(DockDisplayActivity.isWebViewScheme("about"));
    assertTrue(DockDisplayActivity.isWebViewScheme("blob"));
    assertFalse("dsh 深链不从网页启动", DockDisplayActivity.isWebViewScheme("dsh"));
    assertFalse("dsha 深链不从网页启动", DockDisplayActivity.isWebViewScheme("DSHA"));
    assertFalse(DockDisplayActivity.isWebViewScheme("mailto"));
    assertFalse(DockDisplayActivity.isWebViewScheme(null));
  }

  @Test
  public void schemeParsingIsCaseInsensitive() {
    assertEquals("https", DockDisplayActivity.schemeOf("HTTPS://example.com/x"));
    assertEquals("dsha", DockDisplayActivity.schemeOf("dsha://install?plugin=x"));
    assertEquals("", DockDisplayActivity.schemeOf("no-scheme"));
    assertEquals("", DockDisplayActivity.schemeOf(null));
  }

  @Test
  public void reloadKeepsManualModeUnlessDefaultModeChanged() {
    // defaultMode 没变：手动选的档位保留。
    assertEquals(2, DockDisplayActivity.modeAfterReload(2, 0, 0));
    assertEquals(0, DockDisplayActivity.modeAfterReload(0, 0, 0));
    // defaultMode 变了：重置到新默认档（并做范围钳制）。
    assertEquals(1, DockDisplayActivity.modeAfterReload(2, 0, 1));
    assertEquals(2, DockDisplayActivity.modeAfterReload(2, 1, 99));
  }

  @Test
  public void bridgeRequiresSupportedFeatureAndNonEmptyOrigin() {
    assertTrue(DockDisplayActivity.bridgeEnabled(true, "http://127.0.0.1:3200"));
    assertFalse("origin 为空时不能认为桥可用", DockDisplayActivity.bridgeEnabled(true, ""));
    assertFalse(DockDisplayActivity.bridgeEnabled(true, null));
    assertFalse("特性不支持时一律不可用", DockDisplayActivity.bridgeEnabled(false, "http://127.0.0.1:3200"));
  }

  @Test
  public void cutoutInsetsShrinkTheUsableAreaAndOffsetTheStage() {
    org.junit.Assert.assertArrayEquals(
        new int[] {1000, 500, 0, 0}, DockDisplayActivity.insetArea(1000, 500, 0, 0, 0, 0));
    org.junit.Assert.assertArrayEquals(
        new int[] {1000 - 80 - 20, 500 - 40 - 10, 80, 40},
        DockDisplayActivity.insetArea(1000, 500, 80, 40, 20, 10));
    org.junit.Assert.assertArrayEquals(
        "异常（负）安全区按 0 处理",
        new int[] {1000, 500, 0, 0},
        DockDisplayActivity.insetArea(1000, 500, -5, -5, -5, -5));
  }

  @Test
  public void compatibilityScriptSetsOAuthMarkerButNotPluginBridge() {
    String script = DockDisplayActivity.compatibilityScript(RuntimeEnvironment.getApplication());
    assertTrue("必须设置 __DSHA_NATIVE_OAUTH__", script.contains("window.__DSHA_NATIVE_OAUTH__=true"));
    assertFalse("Dock 不承载内置插件桥", script.contains("__DSHA_NATIVE_PLUGINS__"));
    assertTrue("语言标记也应在脚本里", script.contains("__DSHA_LANGUAGE__"));
    assertTrue("主题标记也应在脚本里", script.contains("__DSHA_THEME__"));
    // es-compat/compat/startup 从与竖屏相同的 assets/web-integration 树读取（由 inputs/contracts
    // 门禁校验资产登记），Robolectric 下资产读取依赖测试环境，故这里只校验脚本骨架标记。
  }

  @Test
  public void compatibilityScriptIncludesEsCompatAsset() throws Exception {
    java.io.File esCompat =
        new java.io.File(System.getProperty("dsha.assetsDir"), "web-integration/es-compat.js");
    assertTrue("测试资源缺失: " + esCompat, esCompat.isFile());
    String raw =
        new String(
            java.nio.file.Files.readAllBytes(esCompat.toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue("es-compat 资产应含 AbortSignal 兼容层", raw.contains("AbortSignal"));

    String script =
        DockDisplayActivity.compatibilityScript(
            RuntimeEnvironment.getApplication(), name -> name.endsWith("es-compat.js") ? raw : "");
    assertTrue("es-compat 修复后必须出现在兼容脚本里", script.contains("AbortSignal"));
    assertTrue(script.contains("window.__DSHA_NATIVE_OAUTH__=true"));
    assertFalse(script.contains("__DSHA_NATIVE_PLUGINS__"));
  }

  @Test
  public void pluginNavigationNeedsGestureAndDshaCcWhitelist() {
    assertTrue(DockDisplayActivity.pluginNavigationAllowed("https", true));
    assertTrue(DockDisplayActivity.pluginNavigationAllowed("http", true));
    assertFalse(
        "dsha 深链不从 WebView 路径进安装页", DockDisplayActivity.pluginNavigationAllowed("dsha", true));
    assertFalse(DockDisplayActivity.pluginNavigationAllowed("dsh", true));
    assertFalse("无手势不触发插件安装/管理", DockDisplayActivity.pluginNavigationAllowed("https", false));
    assertFalse(DockDisplayActivity.pluginNavigationAllowed(null, true));

    assertTrue(PluginNavigation.allowed("https://dsha.cc/install?plugin=x"));
    assertTrue(PluginNavigation.allowed("https://dsha.cc/app/plugins"));
    assertTrue(PluginNavigation.allowed("https://dsha.cc/app/plugins/"));
    assertFalse(PluginNavigation.allowed("dsha://install?plugin=x"));
    assertFalse(PluginNavigation.allowed("https://dsha.cc.evil/install"));
    assertFalse(PluginNavigation.allowed("https://evil.com/install"));
    assertFalse(PluginNavigation.allowed("https://dsha.cc/other"));
    assertFalse(PluginNavigation.allowed(null));
  }
}
