package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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
 * 界面语言选择（方案 A）：「跟随系统」作为第一格并写明系统当前解析的语言，其后十种语言；平板四列、手机两列，
 * 每格是母语名 + 当前界面语言里的名称。点选即生效，与原单选列表行为一致（关闭弹窗后切换）。
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
    body.setPadding(dp.applyAsInt(20), dp.applyAsInt(4), dp.applyAsInt(20), dp.applyAsInt(4));
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

    // 「跟随系统」作为网格第一格；平板四列（3 行放下 11 项），手机两列。
    List<String> languages = UiLanguagePreference.LANGUAGES;
    int count = languages.size() + 1;
    int columns = ctx.getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
    LinearLayout row = null;
    for (int i = 0; i < count; i++) {
      if (i % columns == 0) {
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        if (i > 0) rowParams.topMargin = dp.applyAsInt(8);
        body.addView(row, rowParams);
      }
      View cell;
      if (i == 0) {
        cell =
            tile(
                ctx,
                dp,
                UiText.choose("跟随系统", "Follow system"),
                UiText.format("系统：%s", UiLanguagePreference.nativeName(systemLanguage)),
                followSystem);
        cell.setOnClickListener(v -> pick.accept(UiLanguagePreference.SYSTEM));
      } else {
        String language = languages.get(i - 1);
        String nativeName = UiLanguagePreference.nativeName(language);
        String localized = localizedName(language, uiLanguage);
        cell =
            tile(
                ctx,
                dp,
                nativeName,
                localized.equalsIgnoreCase(nativeName) ? "" : localized,
                !followSystem && language.equals(preference));
        cell.setOnClickListener(v -> pick.accept(language));
      }
      cell.setTag(i == 0 ? UiLanguagePreference.SYSTEM : languages.get(i - 1));
      LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -1, 1f);
      if (i % columns > 0) cellParams.setMarginStart(dp.applyAsInt(8));
      row.addView(cell, cellParams);
    }
    // 末行补齐空位，保持格子等宽（高度跟随同行格子）。
    int remainder = count % columns;
    if (row != null && remainder > 0)
      for (int i = remainder; i < columns; i++) {
        View spacer = new View(ctx);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, -1, 1f);
        spacerParams.setMarginStart(dp.applyAsInt(8));
        row.addView(spacer, spacerParams);
      }

    ScrollView scroll = new ScrollView(ctx);
    scroll.addView(body);
    holder[0] =
        new DshaDialogBuilder(ctx)
            .setTitle(UiText.choose("界面语言 / Interface language", "Interface language"))
            .setView(scroll)
            .setNegativeButton(UiText.choose("取消", "Cancel"), null)
            .show();
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

  /** 选项格：主名称 + 副标题，选中时强调色描边、浅底与对勾。 */
  private static View tile(
      Context ctx,
      java.util.function.IntUnaryOperator dp,
      String title,
      String subtitle,
      boolean selected) {
    LinearLayout tile = new LinearLayout(ctx);
    tile.setOrientation(LinearLayout.HORIZONTAL);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setMinimumHeight(dp.applyAsInt(56));
    tile.setPadding(dp.applyAsInt(12), dp.applyAsInt(10), dp.applyAsInt(8), dp.applyAsInt(10));
    tile.setClickable(true);
    tile.setFocusable(true);
    tile.setForeground(ctx.getDrawable(R.drawable.bg_action_plain));
    int accent = ctx.getColor(R.color.shell_accent);
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(dp.applyAsInt(14));
    shape.setColor(
        selected
            ? androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1A)
            : ctx.getColor(R.color.shell_surface));
    shape.setStroke(
        dp.applyAsInt(selected ? 2 : 1),
        selected ? accent : ctx.getColor(R.color.shell_line_strong));
    tile.setBackground(shape);
    LinearLayout copy = new LinearLayout(ctx);
    copy.setOrientation(LinearLayout.VERTICAL);
    TextView name = new TextView(ctx);
    name.setText(UiText.raw(title));
    name.setTextSize(15);
    name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
    name.setTextColor(ctx.getColor(selected ? R.color.shell_accent : R.color.shell_text));
    name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    name.setIncludeFontPadding(false);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    copy.addView(name);
    if (!subtitle.isEmpty()) {
      TextView sub = new TextView(ctx);
      sub.setText(UiText.raw(subtitle));
      sub.setTextSize(12);
      sub.setTextColor(ctx.getColor(R.color.shell_text_muted));
      sub.setMaxLines(2);
      sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
      subParams.topMargin = dp.applyAsInt(2);
      copy.addView(sub, subParams);
    }
    tile.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    if (selected) {
      ImageView check = new ImageView(ctx);
      check.setImageResource(R.drawable.ic_onb_check);
      check.setColorFilter(accent);
      check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      tile.addView(check, new LinearLayout.LayoutParams(dp.applyAsInt(18), dp.applyAsInt(18)));
    }
    tile.setContentDescription(UiText.raw(subtitle.isEmpty() ? title : title + ", " + subtitle));
    tile.setAccessibilityDelegate(
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
    return tile;
  }
}
