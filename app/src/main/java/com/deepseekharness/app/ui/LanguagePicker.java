package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SystemLanguage;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;

import java.util.List;
import java.util.Locale;

/**
 * 界面语言选择（弹窗）：顶部一条「跟随系统」，下面两列语言卡。
 *
 * <p>2026-11-01 重做卡片：去掉白底 + 灰描边（那是表单输入框的语汇），改用一层浅色底分层；
 * 选中才出现主色描边、6% 主色底和右上角对勾。母语名为主标题，当前界面语言里的名称作副标题；
 * 副标题始终占位，保证所有卡片等高。点选即生效，与原单选列表行为一致。
 */
final class LanguagePicker {
  private LanguagePicker() {}

  static void show(Context ctx, String preference) {
    float density = ctx.getResources().getDisplayMetrics().density;
    java.util.function.IntUnaryOperator dp = v -> Math.round(v * density);
    boolean followSystem = UiLanguagePreference.followsSystem(preference);
    String systemLanguage = UiLanguagePreference.resolveLanguage(SystemLanguage.tag());
    String uiLanguage = UiText.language();

    LinearLayout body = new LinearLayout(ctx);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp.applyAsInt(16), dp.applyAsInt(2), dp.applyAsInt(16), dp.applyAsInt(4));

    TextView hint = new TextView(ctx);
    hint.setText(
        UiText.choose(
            "点选即生效；跟随系统时随手机语言切换。",
            "Changes apply immediately. Follow system uses your phone language."));
    hint.setTextSize(12);
    hint.setTextColor(ctx.getColor(R.color.shell_text_muted));
    hint.setLineSpacing(dp.applyAsInt(3), 1f);
    LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
    hintParams.bottomMargin = dp.applyAsInt(12);
    body.addView(hint, hintParams);

    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    java.util.function.Consumer<String> pick =
        value -> {
          androidx.appcompat.app.AlertDialog dialog = holder[0];
          if (dialog == null) return;
          boolean same =
              UiLanguagePreference.followsSystem(value)
                  ? followSystem
                  : !followSystem && value.equals(preference);
          // 与原实现一致：先关弹窗，再在下一帧切换语言（切换会重建界面）。
          if (!same)
            dialog.setOnDismissListener(
                ignored ->
                    new Handler(Looper.getMainLooper())
                        .post(() -> LanguageController.select(ctx, value)));
          dialog.dismiss();
        };

    View system = systemRow(ctx, dp, systemLanguage, followSystem);
    system.setOnClickListener(v -> pick.accept(UiLanguagePreference.SYSTEM));
    body.addView(system, new LinearLayout.LayoutParams(-1, dp.applyAsInt(58)));

    List<String> languages = UiLanguagePreference.LANGUAGES;
    // 手机两列、平板四列；两列时一屏放得下 11 项，不靠滚动。
    int columns = ctx.getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
    LinearLayout row = null;
    for (int i = 0; i < languages.size(); i++) {
      if (i % columns == 0) {
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp.applyAsInt(9);
        body.addView(row, rowParams);
      }
      String language = languages.get(i);
      View cell = languageCell(ctx, dp, language, uiLanguage, !followSystem && language.equals(preference));
      cell.setTag(language);
      // 卡片自己负责点击：languageCell 里只设了 clickable，没有绑定监听器，
      // 2026-11-01 重做卡片时漏掉了这一行，表现就是「点语言没有反应」。
      cell.setOnClickListener(v -> pick.accept(language));
      LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1f);
      if (i % columns > 0) cellParams.setMarginStart(dp.applyAsInt(9));
      row.addView(cell, cellParams);
    }

    ScrollView scroll = new ScrollView(ctx);
    // 大字体或极矮屏仍可能超出：滚动条常显、边缘渐隐，提示下面还有选项。
    scroll.setScrollbarFadingEnabled(false);
    scroll.setVerticalFadingEdgeEnabled(true);
    scroll.setFadingEdgeLength(dp.applyAsInt(24));
    scroll.addView(body);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("界面语言", "Interface language"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .show();
  }

  /** 「跟随系统」整行：手机图标 + 名称 + 当前解析结果 + 选中勾。 */
  private static View systemRow(
      Context ctx, java.util.function.IntUnaryOperator dp, String systemLanguage, boolean selected) {
    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp.applyAsInt(14), dp.applyAsInt(12), dp.applyAsInt(12), dp.applyAsInt(12));
    row.setBackgroundResource(selected ? R.drawable.bg_lang_tile_selected : R.drawable.bg_lang_tile);
    row.setClickable(true);
    row.setFocusable(true);
    row.setForeground(ctx.getDrawable(R.drawable.bg_action_plain));

    FrameLayout iconBox = new FrameLayout(ctx);
    iconBox.setBackgroundResource(R.drawable.bg_lang_icon);
    ImageView icon = new ImageView(ctx);
    icon.setImageResource(R.drawable.ic_lang_system);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams iconParams =
        new FrameLayout.LayoutParams(dp.applyAsInt(17), dp.applyAsInt(17), Gravity.CENTER);
    iconBox.addView(icon, iconParams);
    LinearLayout.LayoutParams iconBoxParams =
        new LinearLayout.LayoutParams(dp.applyAsInt(32), dp.applyAsInt(32));
    iconBoxParams.setMarginEnd(dp.applyAsInt(12));
    row.addView(iconBox, iconBoxParams);

    LinearLayout copy = new LinearLayout(ctx);
    copy.setOrientation(LinearLayout.VERTICAL);
    TextView name = new TextView(ctx);
    name.setText(UiText.choose("跟随系统", "Follow system"));
    name.setTextSize(15);
    name.setTypeface(name.getTypeface(), Typeface.BOLD);
    name.setTextColor(ctx.getColor(R.color.shell_text));
    name.setIncludeFontPadding(false);
    copy.addView(name);
    TextView sub = new TextView(ctx);
    sub.setText(
        UiText.choose("当前：", "Current: ") + UiLanguagePreference.nativeName(systemLanguage));
    sub.setTextSize(11);
    sub.setTextColor(ctx.getColor(R.color.shell_text_muted));
    sub.setIncludeFontPadding(false);
    LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
    subParams.topMargin = dp.applyAsInt(3);
    copy.addView(sub, subParams);
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

    if (selected)
      row.addView(
          check(ctx, dp),
          new LinearLayout.LayoutParams(dp.applyAsInt(18), dp.applyAsInt(18)));
    row.setContentDescription(
        UiText.raw(
            UiText.choose("跟随系统", "Follow system")
                + "，"
                + UiText.choose("当前：", "Current: ")
                + UiLanguagePreference.nativeName(systemLanguage)));
    setRadioSemantics(row, selected);
    return row;
  }

  /** 语言卡：母语名 + 当前界面语言里的名称；选中时右上角对勾。 */
  private static View languageCell(
      Context ctx,
      java.util.function.IntUnaryOperator dp,
      String language,
      String uiLanguage,
      boolean selected) {
    String nativeName = UiLanguagePreference.nativeName(language);
    String localized = localizedName(language, uiLanguage);

    FrameLayout cell = new FrameLayout(ctx);
    cell.setMinimumHeight(dp.applyAsInt(60));
    cell.setBackgroundResource(selected ? R.drawable.bg_lang_tile_selected : R.drawable.bg_lang_tile);
    cell.setClickable(true);
    cell.setFocusable(true);
    cell.setForeground(ctx.getDrawable(R.drawable.bg_action_plain));

    LinearLayout copy = new LinearLayout(ctx);
    copy.setOrientation(LinearLayout.VERTICAL);
    copy.setPadding(
        dp.applyAsInt(13),
        dp.applyAsInt(12),
        dp.applyAsInt(selected ? 26 : 13),
        dp.applyAsInt(12));
    TextView name = new TextView(ctx);
    name.setText(UiText.raw(nativeName));
    name.setTextSize(15);
    name.setTypeface(name.getTypeface(), Typeface.BOLD);
    name.setTextColor(ctx.getColor(R.color.shell_text));
    name.setIncludeFontPadding(false);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    copy.addView(name);
    // 副标题始终占位：没有译名时留空行，保证同一行卡片等高。
    TextView sub = new TextView(ctx);
    sub.setText(UiText.raw(localized));
    sub.setTextSize(11);
    sub.setTextColor(ctx.getColor(R.color.shell_text_muted));
    sub.setIncludeFontPadding(false);
    sub.setSingleLine(true);
    sub.setEllipsize(TextUtils.TruncateAt.END);
    sub.setMinHeight(dp.applyAsInt(14));
    LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
    subParams.topMargin = dp.applyAsInt(3);
    copy.addView(sub, subParams);
    cell.addView(copy, new FrameLayout.LayoutParams(-1, -2));

    if (selected) {
      FrameLayout.LayoutParams checkParams =
          new FrameLayout.LayoutParams(dp.applyAsInt(18), dp.applyAsInt(18), Gravity.TOP | Gravity.END);
      checkParams.topMargin = dp.applyAsInt(10);
      checkParams.setMarginEnd(dp.applyAsInt(10));
      cell.addView(check(ctx, dp), checkParams);
    }

    cell.setContentDescription(
        UiText.raw(localized.isEmpty() ? nativeName : nativeName + ", " + localized));
    setRadioSemantics(cell, selected);
    return cell;
  }

  /** 18dp 主色圆 + 白色对勾；只作视觉标记，读屏状态由 radio 语义承担。 */
  private static View check(Context ctx, java.util.function.IntUnaryOperator dp) {
    FrameLayout box = new FrameLayout(ctx);
    box.setBackgroundResource(R.drawable.bg_lang_check);
    box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    ImageView tick = new ImageView(ctx);
    tick.setImageResource(R.drawable.ic_lang_check);
    tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    box.addView(
        tick,
        new FrameLayout.LayoutParams(dp.applyAsInt(10), dp.applyAsInt(10), Gravity.CENTER));
    return box;
  }

  private static void setRadioSemantics(View view, boolean selected) {
    view.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(android.widget.RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(selected);
          }
        });
  }

  /** 语言在当前界面语言里的名称（系统 ICU 提供），如中文界面下的 English → 英语。 */
  private static String localizedName(String language, String uiLanguage) {
    // 中文界面里「简体中文 / 繁體中文」本身就能读懂，副标题「中文（简体中文）」只是重复。
    boolean chineseUi =
        UiLanguagePreference.ZH.equals(uiLanguage)
            || UiLanguagePreference.ZH_HANT.equals(uiLanguage);
    boolean chineseOption =
        UiLanguagePreference.ZH.equals(language) || UiLanguagePreference.ZH_HANT.equals(language);
    if (chineseUi && chineseOption) return "";
    try {
      String tag = UiLanguagePreference.ZH.equals(language) ? "zh-Hans" : language;
      String display = UiLanguagePreference.ZH.equals(uiLanguage) ? "zh-Hans" : uiLanguage;
      Locale displayLocale = Locale.forLanguageTag(display);
      String name = Locale.forLanguageTag(tag).getDisplayName(displayLocale);
      if (name == null || name.isEmpty() || name.equalsIgnoreCase(tag)) return "";
      return name.substring(0, 1).toUpperCase(displayLocale) + name.substring(1);
    } catch (RuntimeException unavailable) {
      return "";
    }
  }
}
