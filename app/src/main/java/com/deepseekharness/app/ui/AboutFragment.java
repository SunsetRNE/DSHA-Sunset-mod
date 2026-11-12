package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.content.Intent;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.*;
import com.deepseekharness.app.contract.BuiltinModules;
import com.deepseekharness.app.contract.ContributionView;
import com.deepseekharness.app.contract.ModuleRegistry;
import com.deepseekharness.app.util.UiText;

public final class AboutFragment extends Fragment {
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    CardPage ui = new CardPage(requireContext(), builtinAboutTitle(requireContext()), "");
    android.widget.FrameLayout emblem = new android.widget.FrameLayout(requireContext());
    android.widget.ImageView mark = new android.widget.ImageView(requireContext());
    mark.setImageResource(R.drawable.ic_ui2_prompt);
    mark.setBackgroundResource(R.drawable.bg_ui2_mark);
    mark.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
    emblem.addView(
        mark, new android.widget.FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER));
    ui.content.addView(emblem, new LinearLayout.LayoutParams(-1, ui.dp(84)));
    TextView brand = ui.text("DSHA", 30, R.color.text);
    brand.setTypeface(null, android.graphics.Typeface.BOLD);
    brand.setGravity(Gravity.CENTER);
    brand.setPadding(0, ui.dp(16), 0, ui.dp(10));
    ui.content.addView(brand);
    TextView version =
        ui.text(
            "DeepSeek Harness for Android\n"
                + BuildConfig.VERSION_NAME
                + " · "
                + BuildConfig.VERSION_CODE,
            12,
            R.color.text_secondary);
    version.setGravity(Gravity.CENTER);
    version.setPadding(0, 0, 0, ui.dp(24));
    ui.content.addView(version);
    LinearLayout metadata = ui.card();
    ui.kv(metadata, UiText.choose("包名", "Package"), BuildConfig.APPLICATION_ID);
    ui.kv(
        metadata,
        UiText.choose("当前版本", "Edition"),
        false
            ? UiText.choose("兼容版 · Android 6+", "Compatibility · Android 6+")
            : UiText.choose("标准版 · Android 11+", "Standard · Android 11+"));
    ui.kv(metadata, "DSH", com.deepseekharness.app.util.Constants.DSH_VERSION);
    ui.kv(metadata, UiText.choose("架构", "Architecture"), "arm64-v8a");
    ui.kv(metadata, UiText.choose("开源许可", "License"), "MIT");
    ui.label(UiText.choose("了解更多", "Learn more"));
    LinearLayout links = ui.card();
    ui.entry(
        links,
        UiText.choose("项目仓库", "Repository"),
        android.net.Uri.parse(AboutDialog.GITHUB_URL).getHost()
            + android.net.Uri.parse(AboutDialog.GITHUB_URL).getPath(),
        R.drawable.ic_ui_link,
        () -> AboutDialog.openBrowser(requireContext(), AboutDialog.GITHUB_URL));
    ui.entry(
        links,
        UiText.choose("交流与反馈", "Community and feedback"),
        UiText.format("QQ群 %s · 问题反馈", AboutDialog.QQ_GROUP),
        R.drawable.ic_ui_chat,
        () ->
            new DshaDialogBuilder(requireContext())
                .setTitle(UiText.choose("交流与反馈", "Community and feedback"))
                .setItems(
                    new String[] {
                      UiText.choose("打开 QQ 群", "Open QQ group"),
                      UiText.choose("打开问题反馈", "Open issue tracker")
                    },
                    (d, i) -> {
                      if (i == 0) AboutDialog.openQQGroup(requireContext());
                      else
                        AboutDialog.openBrowser(
                            requireContext(), AboutDialog.GITHUB_URL + "/issues");
                    })
                .show());
    addLicenses(ui);
    ui.footer.setVisibility(View.GONE);
    return ui.root;
  }

  /**
   * 页标题来自内置 about 模块贡献（经 {@link BuiltinModules#ensureRegistered}），让 contract/ 有真实
   * 宿主消费者；契约层异常时回退固定标题。许可清单与入口始终由宿主固定渲染，不依赖卡片是否可见。
   */
  static String builtinAboutTitle(android.content.Context context) {
    try {
      ModuleRegistry registry = BuiltinModules.ensureRegistered(context);
      for (ContributionView contribution : registry.contributions()) {
        if (BuiltinModules.ABOUT_CONTRIBUTION_KEY.equals(contribution.key()))
          return UiText.choose(contribution.title(), contribution.titleEn());
      }
    } catch (RuntimeException unavailable) {
      // 契约层不能挡住法定许可入口；回退固定标题继续渲染。
    }
    return UiText.choose(BuiltinModules.ABOUT_TITLE, BuiltinModules.ABOUT_TITLE_EN);
  }

  /** 第三方许可区块：随包全文逐项可点，另给仓库内完整声明的在线入口。 */
  private void addLicenses(CardPage ui) {
    ui.label(UiText.choose("第三方许可", "Third-party licenses"));
    LinearLayout licenses = ui.card();
    java.util.List<AboutLicenses.Entry> entries;
    try {
      entries = AboutLicenses.list(requireContext().getAssets());
    } catch (java.io.IOException failure) {
      entries = java.util.List.of();
      TextView unavailable =
          ui.text(
              UiText.choose(
                  "无法读取随包许可清单，可在线查看完整声明。",
                  "Unable to read the bundled license list. The full notices are available online."),
              12,
              R.color.shell_text_muted);
      unavailable.setPadding(0, ui.dp(6), 0, ui.dp(8));
      licenses.addView(unavailable);
    }
    for (AboutLicenses.Entry entry : entries) {
      ui.entry(
          licenses,
          entry.displayName(),
          entry.assetName(),
          R.drawable.ic_recovery_document,
          () -> showLicense(entry));
    }
    ui.entry(
        licenses,
        UiText.choose("许可声明全文（在线）", "Full license notices (online)"),
        "THIRD_PARTY_NOTICES.md",
        R.drawable.ic_ui_link,
        () -> AboutDialog.openBrowser(requireContext(), AboutDialog.THIRD_PARTY_NOTICES_URL));
  }

  /**
   * 点击许可行：同步读取一次（上限 256 KiB，毫秒级），正文在对话框内滚动；失败显示类型化提示。
   * 包内既有的许可正文都在上限内，因此正常情况下不会阻塞输入。
   */
  void showLicense(AboutLicenses.Entry entry) {
    if (entry == null || !isAdded()) return;
    AboutLicenses.ReadResult result =
        AboutLicenses.read(requireContext().getAssets(), entry.assetName());
    String body = result.ok() ? result.text() : failureText(result.failure());
    showLicenseDialog(entry.displayName(), body);
  }

  private void showLicenseDialog(String title, String body) {
    android.content.Context context = requireContext();
    float density = context.getResources().getDisplayMetrics().density;
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(density, 20), dp(density, 4), dp(density, 20), 0);
    TextView text = new TextView(context);
    text.setText(body);
    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
    text.setTextColor(context.getColor(R.color.shell_text_secondary));
    text.setTextIsSelectable(true);
    text.setLineSpacing(dp(density, 4), 1);
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(false);
    scroll.addView(text, new LinearLayout.LayoutParams(-1, -2));
    // 固定 50% 屏高（160–420dp）保证长许可可滚动，短屏与 1.3 倍字体下也不压住按钮。
    int cap =
        Math.round(
            Math.min(
                420f,
                Math.max(160f, context.getResources().getConfiguration().screenHeightDp * 0.5f)));
    content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(density, cap)));
    new DshaDialogBuilder(context)
        .setTitle(title)
        .setView(content)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private static int dp(float density, int value) {
    return Math.round(value * density);
  }

  /** 类型化失败提示：不包含资产路径或底层异常文本。 */
  static String failureText(AboutLicenses.Failure failure) {
    if (failure == null) return "";
    switch (failure) {
      case MISSING:
        return UiText.choose(
            "此许可文件不在此构建中（可能已被裁剪），无法显示全文。",
            "This license file is not in this build (it may have been trimmed), so the full text cannot be shown.");
      case TOO_LARGE:
        return UiText.choose(
            "许可正文超过 256 KiB 上限，已停止读取。",
            "The license text exceeds the 256 KiB limit and was not read.");
      case INVALID_UTF8:
        return UiText.choose("许可正文不是有效的 UTF-8 文本。", "The license text is not valid UTF-8.");
      case EMPTY:
        return UiText.choose("许可文件为空。", "The license file is empty.");
      case UNREADABLE:
      default:
        return UiText.choose(
            "无法读取此许可全文，请稍后重试。", "Unable to read this license text. Try again later.");
    }
  }
}
