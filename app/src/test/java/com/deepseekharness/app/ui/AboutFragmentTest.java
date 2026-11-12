package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.contract.ContributionView;
import com.deepseekharness.app.contract.ModuleRegistry;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 关于页第三方许可：随包清单、可读名称、点击正文、缺失资产降级与仓库内在线声明入口。
 * 只验证原生视图与对话框，不访问网络、不依赖设备。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = AboutFragmentTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class AboutFragmentTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void bundledLicensesAreListedAndRenderedWithReadableNames() throws Exception {
    List<AboutLicenses.Entry> entries = bundledLicenses();
    assertFalse("随包许可清单不能为空", entries.isEmpty());

    AboutLicenses.Entry gpl =
        entries.stream()
            .filter(entry -> "GPL-3.0.txt".equals(entry.assetName()))
            .findFirst()
            .orElse(null);
    assertNotNull("GPL-3.0.txt 应随包", gpl);
    assertEquals("GNU GPL v3.0", gpl.displayName());
    assertNotEquals("列表标题不能直接显示文件名", gpl.assetName(), gpl.displayName());
    assertTrue(
        "DSHA-MIT.txt 应随包",
        entries.stream().anyMatch(entry -> "DSHA-MIT.txt".equals(entry.assetName())));
    assertTrue(
        "MPL-2.0.txt 应随包",
        entries.stream().anyMatch(entry -> "MPL-2.0.txt".equals(entry.assetName())));

    View root = showAbout().requireView();
    for (AboutLicenses.Entry entry : entries) {
      View label = findByText(root, entry.displayName());
      assertNotNull("缺少许可行：" + entry.assetName(), label);
      assertNotNull("许可行必须可点击：" + entry.assetName(), clickableAncestor(label));
    }
    assertNotNull(findByText(root, "第三方许可"));
    assertNotNull(findByText(root, "许可声明全文（在线）"));
  }

  @Test
  public void pageTitleComesFromBuiltinAboutContribution() {
    var application = RuntimeEnvironment.getApplication();
    String title = AboutFragment.builtinAboutTitle(application);
    assertEquals(BuiltinModules.ABOUT_TITLE, title);

    ModuleRegistry registry = BuiltinModules.ensureRegistered(application);
    ContributionView about =
        registry.contributions().stream()
            .filter(
                contribution -> BuiltinModules.ABOUT_CONTRIBUTION_KEY.equals(contribution.key()))
            .findFirst()
            .orElse(null);
    assertNotNull("内置 about 贡献必须登记", about);
    assertEquals(BuiltinModules.ABOUT_TITLE, about.title());
    assertEquals(BuiltinModules.ABOUT_TITLE_EN, about.titleEn());
    View root = showAbout().requireView();
    assertNotNull("页标题应渲染模块贡献标题", findByText(root, title));
    assertNull("页标题不应再使用旧的固定文案", findByText(root, "关于 DSHA"));
  }

  @Test
  public void hiddenAboutCardKeepsHostLicenseRows() throws Exception {
    List<AboutLicenses.Entry> entries = bundledLicenses();
    ModuleRegistry registry = BuiltinModules.ensureRegistered(RuntimeEnvironment.getApplication());
    assertTrue(
        "隐藏内置卡片失败",
        registry.setContributionHidden(BuiltinModules.ABOUT_CONTRIBUTION_KEY, true).accepted());

    View root = showAbout().requireView();
    for (AboutLicenses.Entry entry : entries) {
      View label = findByText(root, entry.displayName());
      assertNotNull("卡片隐藏后仍应渲染法定许可行：" + entry.assetName(), label);
      assertNotNull("卡片隐藏后许可行仍应可点击：" + entry.assetName(), clickableAncestor(label));
    }
  }

  @Test
  public void clickingLicenseShowsDialogWithTitleAndBody() {
    bundledLicenses(); // source-only 模式未生成随包 assets 时跳过，完整构建验证
    AboutFragment fragment = showAbout();
    View row = clickableAncestor(findByText(fragment.requireView(), "GNU GPL v3.0"));
    assertNotNull("GPL 许可行应可点击", row);
    row.performClick();
    ShadowLooper.shadowMainLooper().idle();

    Dialog dialog = ShadowDialog.getLatestDialog();
    assertNotNull("点击许可后应打开对话框", dialog);
    String text = allText(dialog.getWindow().getDecorView());
    assertTrue("对话框标题缺少许可名称", text.contains("GNU GPL v3.0"));
    assertTrue("对话框正文缺少 GPL 全文", text.contains("GNU GENERAL PUBLIC LICENSE"));
    assertTrue("对话框正文不是完整正文", text.contains("Version 3, 29 June 2007"));
    dialog.dismiss();
  }

  @Test
  public void missingLicenseAssetDegradesToTypedMessage() {
    var assets = RuntimeEnvironment.getApplication().getAssets();
    AboutLicenses.ReadResult direct = AboutLicenses.read(assets, "Missing-LICENSE.txt");
    assertFalse(direct.ok());
    assertEquals(AboutLicenses.Failure.MISSING, direct.failure());

    AboutFragment fragment = showAbout();
    fragment.showLicense(new AboutLicenses.Entry("Missing-LICENSE.txt", "Missing license"));
    ShadowLooper.shadowMainLooper().idle();
    Dialog dialog = ShadowDialog.getLatestDialog();
    assertNotNull("缺失资产也应给出提示对话框", dialog);
    String text = allText(dialog.getWindow().getDecorView());
    assertTrue("提示应保留许可名称", text.contains("Missing license"));
    assertTrue("提示应为类型化的缺失说明", text.contains("不在此构建中"));
    dialog.dismiss();
  }

  @Test
  public void everyBundledLicenseReadsAsUtf8WithinTheLimit() throws Exception {
    var assets = RuntimeEnvironment.getApplication().getAssets();
    for (AboutLicenses.Entry entry : bundledLicenses()) {
      AboutLicenses.ReadResult result = AboutLicenses.read(assets, entry.assetName());
      assertNull("许可读取失败：" + entry.assetName(), result.failure());
      assertFalse("许可正文不能为空：" + entry.assetName(), result.text().isBlank());
      assertTrue(
          "许可正文不应超过 256 KiB：" + entry.assetName(),
          result.text().getBytes(StandardCharsets.UTF_8).length <= AboutLicenses.MAX_BYTES);
    }
  }

  @Test
  public void licenseNamesCannotEscapeTheAssetDirectory() {
    var assets = RuntimeEnvironment.getApplication().getAssets();
    assertEquals(
        AboutLicenses.Failure.MISSING,
        AboutLicenses.read(assets, "../AndroidManifest.xml").failure());
    assertEquals(
        AboutLicenses.Failure.MISSING, AboutLicenses.read(assets, "nested/GPL-3.0.txt").failure());
  }

  @Test
  public void decodedLicenseRejectsOversizedPayloadAsTooLarge() {
    byte[] oversized = new byte[AboutLicenses.MAX_BYTES + 1];
    assertEquals(AboutLicenses.Failure.TOO_LARGE, AboutLicenses.decode(oversized).failure());
    assertEquals(
        AboutLicenses.Failure.TOO_LARGE,
        AboutLicenses.read(new ByteArrayInputStream(oversized)).failure());
  }

  @Test
  public void decodedLicenseRejectsEmptyPayload() {
    assertEquals(AboutLicenses.Failure.EMPTY, AboutLicenses.decode(new byte[0]).failure());
    assertEquals(
        AboutLicenses.Failure.EMPTY,
        AboutLicenses.read(new ByteArrayInputStream(new byte[0])).failure());
  }

  @Test
  public void decodedLicenseRejectsInvalidUtf8() {
    byte[] invalid = {(byte) 0xFF};
    assertEquals(AboutLicenses.Failure.INVALID_UTF8, AboutLicenses.decode(invalid).failure());
    assertEquals(
        AboutLicenses.Failure.INVALID_UTF8,
        AboutLicenses.read(new ByteArrayInputStream(invalid)).failure());
  }

  @Test
  public void unreadableStreamReturnsTypedFailure() {
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read failed");
          }
        };
    assertEquals(AboutLicenses.Failure.UNREADABLE, AboutLicenses.read(broken).failure());
  }

  @Test
  public void licenseIndexRejectsUnreadableOrOversizedListings() {
    assertThrows(IOException.class, () -> AboutLicenses.index(null));
    String[] oversized = new String[AboutLicenses.MAX_ENTRIES + 1];
    for (int index = 0; index < oversized.length; index++)
      oversized[index] = "license-" + index + ".txt";
    assertThrows(IOException.class, () -> AboutLicenses.index(oversized));
  }

  @Test
  public void licenseIndexKeepsOnlyTxtNames() throws Exception {
    List<AboutLicenses.Entry> entries =
        AboutLicenses.index(new String[] {"GPL-3.0.txt", "../escape.txt", "nested", "notes.md"});
    assertEquals(1, entries.size());
    assertEquals("GPL-3.0.txt", entries.get(0).assetName());
  }

  @Test
  public void decodedLicenseAcceptsUtf8AndStripsBom() {
    byte[] bytes = ("\uFEFF" + "GPL text").getBytes(StandardCharsets.UTF_8);
    AboutLicenses.ReadResult decoded = AboutLicenses.decode(bytes);
    assertTrue(decoded.ok());
    assertEquals("GPL text", decoded.text());
    assertEquals(
        "GPL text",
        AboutLicenses.read(new ByteArrayInputStream("GPL text".getBytes(StandardCharsets.UTF_8)))
            .text());
  }

  @Test
  public void noticesRowOpensTheRepositoryDocumentInABrowser() {
    AboutFragment fragment = showAbout();
    View row = clickableAncestor(findByText(fragment.requireView(), "许可声明全文（在线）"));
    assertNotNull("在线许可入口应可点击", row);
    row.performClick();

    Intent started = Shadow.<ShadowActivity>extract(host.get()).getNextStartedActivity();
    assertNotNull("应打开外部浏览器", started);
    assertEquals(Intent.ACTION_VIEW, started.getAction());
    assertEquals(AboutDialog.THIRD_PARTY_NOTICES_URL, started.getDataString());
    assertEquals(
        "https://github.com/SunsetRNE/DSHA-Sunset-mod/blob/main/THIRD_PARTY_NOTICES.md",
        AboutDialog.THIRD_PARTY_NOTICES_URL);
  }

  private AboutFragment showAbout() {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    AboutFragment fragment = new AboutFragment();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, fragment)
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
    return fragment;
  }

  private static View findByText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findByText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static View clickableAncestor(View view) {
    View current = view;
    while (current != null && !current.isClickable()) {
      var parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
    return current;
  }

  /**
   * 随包 licenses 只在完整构建（prepareStandardAssets）后存在；source-only 单测跳过，
   * 由完整构建/发布门禁执行。真实模式仍要求清单非空。
   */
  private static List<AboutLicenses.Entry> bundledLicenses() {
    var assets = RuntimeEnvironment.getApplication().getAssets();
    List<AboutLicenses.Entry> entries;
    try {
      entries = AboutLicenses.list(assets);
    } catch (java.io.IOException error) {
      throw new AssertionError("无法读取随包许可清单", error);
    }
    if ("true".equals(System.getProperty("dsha.sourceOnlyTests")) && entries.isEmpty())
      org.junit.Assume.assumeTrue("source-only: 未生成随包 assets/licenses；由完整构建验证", false);
    return entries;
  }

  private static String allText(View view) {
    StringBuilder text = new StringBuilder();
    collectText(view, text);
    return text.toString();
  }

  private static void collectText(View view, StringBuilder out) {
    if (view instanceof TextView text) out.append(text.getText()).append('\n');
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
  }
}
