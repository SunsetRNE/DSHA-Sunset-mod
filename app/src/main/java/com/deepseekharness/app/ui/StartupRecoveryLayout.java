package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/**
 * 恢复页的原生布局；静态展示可独立验收，不执行 Web 或修复操作。
 *
 * <p>2026-11-01 压缩：返回与标题同一行；操作改为分组内带分隔线的行（与设置子页一致），去掉泛化副标题；
 * 分组顺序为 恢复与修复 → 最近五次启动 → 配置快照 → 插件卸载 → 其他工具。行文字与无障碍描述保持原文，
 * 审计按文字查找可点行。
 */
final class StartupRecoveryLayout {
  final ScrollView root;
  final LinearLayout content, recovery, tools, attempts, snapshots, plugins;
  final View pluginCard;
  final TextView progress;
  private final Context context;

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  StartupRecoveryLayout(Context context, String reason, Runnable back) {
    this.context = context;
    root = new ScrollView(context);
    root.setFillViewport(true);
    root.setBackgroundColor(context.getColor(R.color.shell_bg));
    content = column();
    content.setPadding(dp(16), dp(8), dp(16), dp(24));
    root.addView(content);

    LinearLayout bar = new LinearLayout(context);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    content.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    ImageButton up = new ImageButton(context);
    up.setImageResource(R.drawable.ic_ui_back);
    up.setImageTintList(
        android.content.res.ColorStateList.valueOf(context.getColor(R.color.shell_text)));
    up.setBackgroundResource(R.drawable.bg_action_plain);
    up.setContentDescription(t("返回", "Back"));
    up.setOnClickListener(v -> back.run());
    bar.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
    TextView title = label(bar, t("启动恢复", "Startup recovery"), 22, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setAccessibilityHeading(true);
    ((LinearLayout.LayoutParams) title.getLayoutParams()).width = 0;
    ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1;
    ((LinearLayout.LayoutParams) title.getLayoutParams()).leftMargin = dp(4);

    TextView why =
        label(content, t("进入原因：", "Reason: ") + reason, 13, R.color.shell_text_secondary);
    why.setBackgroundResource(R.drawable.bg_onb_hint_warn);
    why.setPadding(dp(12), dp(10), dp(12), dp(10));
    why.setTextIsSelectable(true);
    ((LinearLayout.LayoutParams) why.getLayoutParams()).topMargin = dp(10);
    progress = label(content, "", 13, R.color.shell_accent);
    progress.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    ((LinearLayout.LayoutParams) progress.getLayoutParams()).topMargin = dp(8);
    progress.setVisibility(View.GONE);

    recovery = group(t("恢复与修复", "Recovery & repair"), "");
    attempts = group(t("最近五次启动", "Last five starts"), "");
    snapshots =
        group(
            t("配置快照", "Configuration snapshots"),
            t(
                "保留三次健康启动与三次修复前快照；仅保存配置，不复制会话、工作区或依赖目录。",
                "Keeps three healthy-start and three pre-repair snapshots. Saves configuration only, without sessions, workspaces, or dependency directories."));
    plugins = group(t("插件卸载", "Remove plugins"), "");
    pluginCard = (View) plugins.getParent();
    pluginCard.setVisibility(View.GONE);
    tools = group(t("其他工具", "Other tools"), "");
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private int dp(int n) {
    return Math.round(n * context.getResources().getDisplayMetrics().density);
  }

  private ImageView icon(int resource, int color) {
    androidx.appcompat.widget.AppCompatImageView icon =
        new androidx.appcompat.widget.AppCompatImageView(context);
    icon.setImageResource(resource);
    icon.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(color)));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return icon;
  }

  /**
   * 分组：小标题（可带一行说明）+ 细边框行容器。返回值是行容器；整组外壳是它的父视图，
   * 插件分组据此整体隐藏。
   */
  private LinearLayout group(String title, String subtitle) {
    LinearLayout section = column();
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(18);
    content.addView(section, params);
    TextView label = label(section, title, 12, R.color.shell_text_secondary);
    label.setTypeface(null, android.graphics.Typeface.BOLD);
    label.setLetterSpacing(0.03f);
    label.setAccessibilityHeading(true);
    label.setPadding(dp(4), 0, dp(4), dp(subtitle.isEmpty() ? 8 : 2));
    if (!subtitle.isEmpty()) {
      TextView note = label(section, subtitle, 11, R.color.shell_text_muted);
      note.setPadding(dp(4), 0, dp(4), dp(8));
    }
    LinearLayout entries = column();
    entries.setBackgroundResource(R.drawable.bg_settings_group);
    entries.setClipToOutline(true);
    section.addView(entries, new LinearLayout.LayoutParams(-1, -2));
    return entries;
  }

  TextView label(LinearLayout parent, String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(context.getColor(color));
    view.setLineSpacing(dp(2), 1);
    view.setPadding(0, dp(2), 0, dp(2));
    parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    return view;
  }

  /** 组内空状态 / 错误说明：与行文字左对齐。 */
  TextView note(LinearLayout parent, String value) {
    TextView view = label(parent, value, 13, R.color.shell_text_muted);
    view.setPadding(dp(12), dp(12), dp(12), dp(12));
    return view;
  }

  View action(LinearLayout parent, String label, int resource, Runnable click, boolean chevron) {
    View mark = null;
    if (resource != 0) {
      FrameLayout box = new FrameLayout(context);
      box.setBackgroundResource(R.drawable.bg_onb_icon);
      box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      box.addView(
          icon(resource, R.color.shell_accent),
          new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
      mark = box;
    }
    return row(parent, label, mark, mark == null ? 0 : 30, click, chevron);
  }

  /** 启动记录行：状态圆点 + 原文标签（日期 · 状态 · 耗时）。 */
  View statusAction(LinearLayout parent, String label, int dot, Runnable click) {
    View mark = new View(context);
    mark.setBackgroundResource(dot);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return row(parent, label, mark, 8, click, true);
  }

  private View row(
      LinearLayout parent, String label, View mark, int markDp, Runnable click, boolean chevron) {
    if (parent.getChildCount() > 0) {
      View line = new View(context);
      line.setBackgroundColor(context.getColor(R.color.shell_line));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
      lp.setMarginStart(dp(mark == null ? 12 : markDp > 10 ? 54 : 32));
      parent.addView(line, lp);
    }
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(52));
    row.setPadding(dp(12), dp(8), dp(10), dp(8));
    android.util.TypedValue ripple = new android.util.TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);
    row.setFocusable(true);
    row.setClickable(true);
    row.setContentDescription(label);
    row.setOnClickListener(v -> click.run());
    if (mark != null) {
      LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(markDp), dp(markDp));
      mp.setMarginEnd(dp(12));
      row.addView(mark, mp);
    }
    TextView text = new TextView(context);
    text.setText(label);
    text.setTextSize(14);
    text.setIncludeFontPadding(false);
    text.setLineSpacing(dp(2), 1);
    text.setGravity(Gravity.CENTER_VERTICAL);
    text.setTextColor(
        androidx.core.content.ContextCompat.getColorStateList(context, R.color.button_text));
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    if (chevron) {
      ImageView next = icon(R.drawable.ic_ui2_chevron, R.color.shell_text_muted);
      LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(16), dp(16));
      nextParams.setMarginStart(dp(8));
      row.addView(next, nextParams);
    }
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    androidx.core.view.ViewCompat.setAccessibilityDelegate(
        row,
        new androidx.core.view.AccessibilityDelegateCompat() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
          }
        });
    return row;
  }

  static void enabled(View action, boolean enabled) {
    action.setEnabled(enabled);
    action.setAlpha(enabled ? 1f : 0.45f);
    if (action instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) action).getChildCount(); i++)
        ((ViewGroup) action).getChildAt(i).setEnabled(enabled);
  }
}
