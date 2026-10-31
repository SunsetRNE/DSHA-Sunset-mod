package com.deepseekharness.app.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;

/**
 * 单张授权卡片的版式：图标 + 标题 + 状态标签；下方全宽为用途说明、可解锁能力、状态原文、
 * 离开 DSHA 的提示与按钮行。卡片只负责展示与按钮回调，状态由 PermissionPanel 每次重读后重绘。
 */
final class PermissionCard {
  /** 一次渲染所需的数据。 */
  static final class Model {
    int icon;
    String title, why, status, outside, note;
    String[] unlocks = new String[0];
    String pill;
    OnboardingKit.Tone tone = OnboardingKit.Tone.NEUTRAL;
    boolean ok, recommended;
    View[] actions = new View[0];

    /** 卡片正文之后追加的自定义视图（例如通道分页）。 */
    View extra;
  }

  private PermissionCard() {}

  static LinearLayout build(OnboardingKit kit, Model m) {
    LinearLayout card = kit.column();
    card.setBackgroundResource(m.recommended ? R.drawable.bg_onb_perm_rec : R.drawable.bg_onb_perm);
    card.setPadding(kit.dp(14), kit.dp(14), kit.dp(14), kit.dp(14));

    LinearLayout head = kit.row();
    head.addView(kit.iconBox(m.icon, m.ok, 40, 20));
    LinearLayout words = kit.column();
    LinearLayout top = kit.row();
    TextView title = kit.bold(m.title, 15.5f, R.color.shell_text);
    title.setAccessibilityHeading(true);
    top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    if (m.pill != null && !m.pill.isEmpty()) {
      LinearLayout.LayoutParams pp =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      pp.leftMargin = kit.dp(8);
      top.addView(kit.pill(m.pill, m.tone), pp);
    }
    words.addView(top);
    if (m.recommended) {
      TextView rec =
          kit.bold(
              com.deepseekharness.app.util.UiText.choose("按你的用途推荐", "Recommended for your use"),
              11,
              R.color.shell_accent);
      words.addView(rec, kit.topMargin(3));
    }
    LinearLayout.LayoutParams wp =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    wp.leftMargin = kit.dp(12);
    head.addView(words, wp);
    card.addView(head);

    if (m.why != null && !m.why.isEmpty())
      kit.add(card, kit.text(m.why, 13, R.color.shell_text_secondary), 10);
    if (m.unlocks.length > 0) {
      OnboardingKit.FlowRow tags =
          new OnboardingKit.FlowRow(kit.context, kit.dp(5), kit.dp(5), android.view.Gravity.START);
      for (String u : m.unlocks) {
        TextView tag = kit.text(u, 11.5f, R.color.shell_text_secondary);
        tag.setBackgroundResource(R.drawable.bg_onb_pill_neutral);
        tag.setPadding(kit.dp(8), kit.dp(2), kit.dp(8), kit.dp(2));
        tags.addView(
            tag,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      }
      kit.add(card, tags, 8);
    }
    if (m.extra != null) kit.add(card, m.extra, 10);
    if (m.status != null && !m.status.isEmpty()) {
      TextView status =
          kit.text(m.status, 12.5f, m.ok ? R.color.shell_good : R.color.shell_text_secondary);
      status.setBackgroundResource(R.drawable.bg_onb_status_box);
      status.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
      status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      kit.add(card, status, 10);
    }
    if (!m.ok && m.outside != null && !m.outside.isEmpty()) {
      LinearLayout out = kit.row();
      out.setGravity(android.view.Gravity.TOP);
      LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(kit.dp(14), kit.dp(14));
      ip.topMargin = kit.dp(2);
      out.addView(kit.icon(R.drawable.ic_onb_external, R.color.shell_text_muted, 14), ip);
      LinearLayout.LayoutParams tp =
          new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
      tp.leftMargin = kit.dp(6);
      out.addView(kit.text(m.outside, 12, R.color.shell_text_muted), tp);
      kit.add(card, out, 8);
    }
    if (m.note != null && !m.note.isEmpty())
      kit.add(card, kit.text(m.note, 12, R.color.shell_text_muted), 8);
    if (m.actions.length > 0) kit.add(card, kit.actionRow(m.actions), 10);
    card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return card;
  }
}
