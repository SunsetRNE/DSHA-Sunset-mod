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
 * 界面语言选择（方案 A）：顶部「跟随系统」卡片写明系统当前解析成哪种语言；下面十种语言两列网格，
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

    View system =
        tile(
            ctx,
            dp,
            UiText.choose("跟随系统", "Follow system"),
            UiText.format("当前系统：%s", UiLanguagePreference.nativeName(systemLanguage)),
            followSystem);
    system.setOnClickListener(v -> pick.accept(UiLanguagePreference.SYSTEM));
    body.addView(system, new LinearLayout.LayoutParams(-1, -2));

    TextView label = new TextView(ctx);
    label.setText(UiText.choose("指定语言", "Choose a language"));
    label.setTextSize(12);
    label.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    label.setPadding(dp.applyAsInt(2), dp.applyAsInt(16), 0, dp.applyAsInt(8));
    body.addView(label);

    List<String> languages = UiLanguagePreference.LANGUAGES;
    // 平板宽弹窗三列，手机两列。
    int columns = ctx.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
    LinearLayout row = null;
    for (int i = 0; i < languages.size(); i++) {
      if (i % columns == 0) {
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        if (i > 0) rowParams.topMargin = dp.applyAsInt(8);
        body.addView(row, rowParams);
      }
      String language = languages.get(i);
      String nativeName = UiLanguagePreference.nativeName(language);
      String localized = localizedName(language, uiLanguage);
      View cell =
          tile(
              ctx,
              dp,
              nativeName,
              localized.equalsIgnoreCase(nativeName) ? "" : localized,
              !followSystem && language.equals(preference));
      cell.setOnClickListener(v -> pick.accept(language));
      LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -1, 1f);
      if (i % columns > 0) cellParams.setMarginStart(dp.applyAsInt(8));
      row.addView(cell, cellParams);
    }
    // 末行补齐空位，保持格子等宽。
    int remainder = languages.size() % columns;
    if (row != null && remainder > 0)
      for (int i = remainder; i < columns; i++) {
        View spacer = new View(ctx);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1f);
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
    tile.setMinimumHeight(dp.applyAsInt(60));
    tile.setPadding(dp.applyAsInt(14), dp.applyAsInt(10), dp.applyAsInt(10), dp.applyAsInt(10));
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
    copy.addView(name);
    if (!subtitle.isEmpty()) {
      TextView sub = new TextView(ctx);
      sub.setText(UiText.raw(subtitle));
      sub.setTextSize(12);
      sub.setTextColor(ctx.getColor(R.color.shell_text_muted));
      sub.setSingleLine(true);
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
      tile.addView(check, new LinearLayout.LayoutParams(dp.applyAsInt(20), dp.applyAsInt(20)));
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
