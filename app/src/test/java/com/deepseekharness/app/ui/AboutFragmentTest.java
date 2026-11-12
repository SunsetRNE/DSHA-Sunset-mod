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
import com.deepseekharness.app.util.UiText;
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
    var assets = RuntimeEnvironment.getApplication().getAssets();
    List<AboutLicenses.Entry> entries = AboutLicenses.list(assets);
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
    for (AboutLicenses.Entry entry : entries)
      assertNotNull("缺少许可行：" + entry.assetName(), findByText(root, entry.displayName()));
    assertNotNull(findByText(root, "第三方许可"));
    assertNotNull(findByText(root, "许可声明全文（在线）"));
  }

  @Test
  public void clickingLicenseShowsDialogWithTitleAndBody() {
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
    for (AboutLicenses.Entry entry : AboutLicenses.list(assets)) {
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
