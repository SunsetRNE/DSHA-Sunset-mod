package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleScript;
import com.deepseekharness.app.module.ModuleState;
import com.deepseekharness.app.util.UiText;
import org.json.JSONArray;

/**
 * 模块主页宿主：一张启用的卡片可以用 {@code ui.app} 接管主页内容区。
 *
 * <p>这是「会写模块就能改 App」的落地：模块自己排版、放动效组件、分段控件、徽标和链接，
 * 宿主只负责渲染和权限。安全边界不拆：底栏、模块中心和设置仍在宿主手里，用户永远能撤销模块。
 * 用户点「退出模块主页」后记住选择，模块换版本（摘要变化）会重新出现。
 */
final class ModuleAppHost {

  private final Context context;
  private final FrameLayout host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private ModuleCard card;
  private String shownKey = "";
  private String surface = "home";

  ModuleAppHost(Context context, FrameLayout host) {
    this(context, host, "home");
  }

  ModuleAppHost(Context context, FrameLayout host, String surface) {
    this.context = context;
    this.host = host;
    this.surface = surface == null || surface.isEmpty() ? "home" : surface;
  }

  void setSurface(String value) {
    String next = value == null || value.isEmpty() ? "home" : value;
    if (next.equals(surface)) return;
    surface = next;
    card = null;
    shownKey = "";
  }

  private String keyOf(ModuleCard card) {
    return card.layoutId() + "#" + card.digest + "#" + surface;
  }

  /** Web 页打开时让出内容区；回到主页再 sync 会重新按规则显示。 */
  void hide() {
    if (host != null) host.setVisibility(View.GONE);
  }

  void sync() {
    if (host == null) return;
    ModuleCard next = null;
    for (ModuleCard item : ModuleCardStore.load(context)) {
      if (item.app != null
          && item.app.targets.contains(surface)
          && !ModuleState.appDismissed(context, keyOf(item))) {
        next = item;
        break;
      }
    }
    if (next == null) {
      host.setVisibility(View.GONE);
      host.removeAllViews();
      card = null;
      shownKey = "";
      return;
    }
    if (next == card && keyOf(next).equals(shownKey) && host.getChildCount() > 0) {
      host.setVisibility(View.VISIBLE);
      return;
    }
    card = next;
    shownKey = keyOf(next);
    render();
    host.setVisibility(View.VISIBLE);
  }

  private void render() {
    host.removeAllViews();
    int background = color(card.app.background, context.getColor(R.color.shell_bg));
    int titleColor = contrastText(background);
    int subtitleColor =
        isDark(background) ? 0xFFAAB6C8 : context.getColor(R.color.shell_text_secondary);
    host.setBackgroundColor(background);
    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(background);
    host.addView(root, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout header = new LinearLayout(context);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(20), dp(22), dp(20), dp(8));
    root.addView(header, new LinearLayout.LayoutParams(-1, -2));

    TextView title = new TextView(context);
    title.setText(card.app.title.isEmpty() ? card.name : card.app.title);
    title.setTextSize(26);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setTextColor(titleColor);
    header.addView(title);

    if (!card.app.subtitle.isEmpty()) {
      TextView subtitle = new TextView(context);
      subtitle.setText(card.app.subtitle);
      subtitle.setTextSize(13);
      subtitle.setTextColor(subtitleColor);
      subtitle.setPadding(0, dp(4), 0, 0);
      header.addView(subtitle);
    }

    LinearLayout tools = new LinearLayout(context);
    tools.setOrientation(LinearLayout.HORIZONTAL);
    tools.setGravity(Gravity.CENTER_VERTICAL);
    tools.setPadding(0, dp(10), 0, 0);
    String exit =
        card.app.exitLabel.isEmpty()
            ? UiText.choose("退出模块主页", "Exit module home")
            : card.app.exitLabel;
    TextView close = chip(exit, context.getColor(R.color.shell_accent));
    close.setOnClickListener(
        v -> {
          ModuleState.dismissApp(context, keyOf(card));
          host.removeAllViews();
          host.setVisibility(View.GONE);
          shownKey = "";
          card = null;
        });
    tools.addView(close);
    TextView manage =
        chip(
            UiText.choose("模块中心", "Module center"), context.getColor(R.color.shell_text_secondary));
    manage.setOnClickListener(
        v -> context.startActivity(new Intent(context, ModuleCenterActivity.class)));
    LinearLayout.LayoutParams manageParams = new LinearLayout.LayoutParams(-2, -2);
    manageParams.leftMargin = dp(8);
    tools.addView(manage, manageParams);
    header.addView(tools);

    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    JSONArray nodes = card.app.view;
    boolean webOnly =
        nodes.length() == 1
            && nodes.optJSONObject(0) != null
            && "web".equals(nodes.optJSONObject(0).optString("type"));
    if (webOnly) {
      // 单网页节点直接占满剩余高度，不再套一层 ScrollView，让模块网页自己滚动。
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
    } else {
      ScrollView scroll = new ScrollView(context);
      scroll.setFillViewport(true);
      root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
      body.setPadding(dp(20), dp(4), dp(20), dp(28));
      scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
    }
    ModuleViewRenderer.render(context, card, nodes, body, this::runAction);
  }

  private TextView chip(String text, int color) {
    TextView chip = new TextView(context);
    chip.setText(text);
    chip.setTextSize(12);
    chip.setTextColor(color);
    chip.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(dp(999));
    shape.setColor((color & 0x00FFFFFF) | 0x16000000);
    shape.setStroke(1, (color & 0x00FFFFFF) | 0x55000000);
    chip.setBackground(shape);
    chip.setPadding(dp(12), dp(7), dp(12), dp(7));
    return chip;
  }

  private void runAction(String type, String value) {
    String text = value == null ? "" : value;
    switch (type) {
      case "toast" -> toast(text);
      case "copy" -> copy(text);
      case "url" -> openUrl(text);
      case "modules" -> context.startActivity(new Intent(context, ModuleCenterActivity.class));
      case "settings" -> openSettings();
      case "terminal" -> openTerminal();
      case "page" -> {
        if (card != null && card.pages.containsKey(text.trim()))
          ModulePageActivity.open(context, card, text.trim());
        else toast(UiText.choose("页面不存在", "Page not found"));
      }
      case "script" -> runScript(text);
      default -> toast(UiText.choose("这个动作需要在模块页面里执行", "Run this action in a module page"));
    }
  }

  private void runScript(String code) {
    if (card == null) return;
    ModuleScript.run(
        context,
        card,
        code,
        java.util.Map.of(),
        null,
        (ok, output) -> {
          if (!ok) toast(output);
        });
  }

  private void openSettings() {
    MainActivity main = MainActivity.active();
    if (main != null) main.openSettings();
    else toast(UiText.choose("先回到主页再点设置", "Return home and tap Settings"));
  }

  private void openTerminal() {
    MainActivity main = MainActivity.active();
    if (main != null) main.openTerminal();
    else toast(UiText.choose("先回到主页再点终端", "Return home and tap Terminal"));
  }

  private void openUrl(String raw) {
    // 与 ModuleServices.ui.open / ModuleScript 桥同一道闸：只放 http(s)，
    // 挡掉 intent://、market:// 之类的隐式跳转。
    String target = raw == null ? "" : raw.trim();
    if (!target.startsWith("https://") && !target.startsWith("http://")) {
      toast(UiText.choose("只支持 http(s) 链接", "Only http(s) links are supported"));
      return;
    }
    try {
      context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
    } catch (RuntimeException error) {
      toast(UiText.choose("链接无效", "Invalid link"));
    }
  }

  private void copy(String text) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", text));
    toast(UiText.choose("已复制", "Copied"));
  }

  private void toast(String text) {
    main.post(
        () ->
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show());
  }

  private static boolean isDark(int color) {
    return androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.5;
  }

  private int contrastText(int background) {
    return isDark(background) ? 0xFFF5F7FB : context.getColor(R.color.shell_text);
  }

  private int color(String raw, int fallback) {
    if (raw != null && !raw.isEmpty()) {
      try {
        return Color.parseColor(raw);
      } catch (RuntimeException ignored) {
        // 非法颜色回退宿主底色
      }
    }
    return fallback;
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
