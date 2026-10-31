package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.SummaryText;
import com.deepseekharness.app.util.UiText;

/**
 * UI v5 方案 A：行内只保留一句摘要，完整功能说明收进 ⓘ 弹窗。
 *
 * <p>文案来源仍是原有已翻译的字符串资源或 UiText，不新增翻译键；只在显示层拆句。只有一句时
 * 原样显示、不加图标、不可点，避免给短说明制造多余入口。
 */
public final class InfoHint {
  private InfoHint() {}

  /** 用 TextView 现有文字（通常来自布局里的 @string）作为完整说明。 */
  public static void attach(TextView view, @Nullable CharSequence title) {
    if (view == null) return;
    bind(view, title, view.getText());
  }

  public static void bind(
      TextView view, @Nullable CharSequence title, @Nullable CharSequence full) {
    if (view == null) return;
    SummaryText.Split split = SummaryText.split(full == null ? "" : full.toString());
    view.setText(split.summary);
    if (!split.hasMore) {
      view.setCompoundDrawablesRelative(null, null, null, null);
      view.setOnClickListener(null);
      view.setClickable(false);
      view.setContentDescription(null);
      return;
    }
    Context context = view.getContext();
    view.setMaxLines(2);
    view.setEllipsize(TextUtils.TruncateAt.END);
    view.setCompoundDrawablesRelative(null, null, icon(context, view), null);
    android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params != null && params.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
      params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
      view.setLayoutParams(params);
    }
    view.setCompoundDrawablePadding(dp(context, 6));
    view.setBackgroundResource(R.drawable.bg_action_plain);
    view.setMinHeight(dp(context, 32));
    view.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
    String tapHint = UiText.choose("（点按查看完整说明）", "(tap for details)");
    view.setContentDescription(UiText.format("%s %s", split.summary, tapHint));
    view.setOnClickListener(v -> show(context, title, split.full));
  }

  public static void show(Context context, @Nullable CharSequence title, CharSequence full) {
    new DshaDialogBuilder(context)
        .setTitle(title == null || title.length() == 0 ? UiText.text("说明") : title)
        .setMessage(full)
        .setPositiveButton(UiText.text("知道了"), (d, w) -> d.dismiss())
        .show();
  }

  private static Drawable icon(Context context, TextView view) {
    Drawable icon = context.getDrawable(R.drawable.ic_onb_info);
    if (icon == null) return null;
    icon = icon.mutate();
    int size = Math.max(dp(context, 14), Math.round(view.getTextSize() * 1.1f));
    icon.setBounds(0, 0, size, size);
    icon.setTintList(ColorStateList.valueOf(context.getColor(R.color.shell_accent)));
    return icon;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
