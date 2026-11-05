package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleData;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 声明式视图渲染器：模块自己描述界面，宿主负责画。
 *
 * <p>没有这一层，模块想要个新版式就得我来写 Java；有了它，模块写
 * {@code "view": [{"type":"row","children":[...]}]} 就够了。
 *
 * <p>支持的节点：
 * <ul>
 *   <li>{@code text}：{@code text} 或 {@code bind}（数据源），可带 size/bold/color/align；</li>
 *   <li>{@code image}：{@code src}（模块目录里的图片）+ {@code h} 高度 dp；</li>
 *   <li>{@code row}：{@code children} 横向排列，可带 gap；</li>
 *   <li>{@code space}：纯留白 {@code h}；</li>
 *   <li>{@code button}：{@code label} + {@code action}（{@code type:参数}，与 sequence 步骤同语法），
 *       另有一组<b>安全样式 token</b>（见 {@link #button}）。</li>
 * </ul>
 *
 * <p>不认识或不安全的节点会被跳过并记一行说明，不会让整张卡挂掉。
 */
final class ModuleViewRenderer {

  /** 节点里的按钮交回宿主执行，渲染器自己不懂任何动作语义。 */
  interface ActionSink {
    void run(String type, String value);
  }

  /** 需要定时刷新的绑定文本。 */
  static final class Bound {
    final TextView view;
    final String source;
    final String label;

    Bound(TextView view, String source, String label) {
      this.view = view;
      this.source = source;
      this.label = label;
    }

    void refresh(Context context) {
      String value = ModuleData.value(context, source);
      if (value.isEmpty()) return;
      view.setText(label.isEmpty() ? value : label + " " + value);
    }
  }

  private ModuleViewRenderer() {}

  static List<Bound> render(
      Context context, ModuleCard card, JSONArray nodes, ViewGroup parent, ActionSink sink) {
    List<Bound> bounds = new ArrayList<>();
    append(context, card, nodes, parent, sink, bounds, 0);
    return bounds;
  }

  private static void append(
      Context context,
      ModuleCard card,
      JSONArray nodes,
      ViewGroup parent,
      ActionSink sink,
      List<Bound> bounds,
      int depth) {
    if (nodes == null || depth > 4) return;
    for (int i = 0; i < nodes.length(); i++) {
      JSONObject node = nodes.optJSONObject(i);
      if (node == null) continue;
      String type = node.optString("type", "text");
      switch (type) {
        case "text":
          parent.addView(textView(context, node, bounds));
          break;
        case "image":
          View image = imageView(context, card, node);
          if (image != null) parent.addView(image);
          break;
        case "space":
          parent.addView(space(context, node));
          break;
        case "button":
          parent.addView(button(context, card, node, sink));
          break;
        case "row":
          parent.addView(row(context, card, node, sink, bounds, depth));
          break;
        case "stack":
        case "column":
          parent.addView(column(context, card, node, sink, bounds, depth));
          break;
        case "card":
          parent.addView(cardBox(context, card, node, sink, bounds, depth));
          break;
        case "progress":
          parent.addView(progress(context, card, node));
          break;
        case "web":
          parent.addView(web(context, card, node));
          break;
        case "segmented":
          parent.addView(segmented(context, card, node, sink));
          break;
        case "badge":
          parent.addView(badge(context, card, node));
          break;
        case "link":
          parent.addView(link(context, card, node, sink));
          break;
        default:
          // 不认识的节点类型直接跳过：模块可能来自更新版本的宿主。
          break;
      }
    }
  }

  private static TextView textView(Context context, JSONObject node, List<Bound> bounds) {
    TextView view = new TextView(context);
    // 与其它节点一致把字号收进安全范围：模块写错单位（999 之类）不该把卡片撑爆。
    view.setTextSize(clamp(node.optInt("size", 13), 6, 40));
    view.setIncludeFontPadding(false);
    view.setTextColor(color(context, node.optString("color", ""), R.color.shell_text));
    if (node.optBoolean("bold", false)) view.setTypeface(null, Typeface.BOLD);
    String align = node.optString("align", "");
    if ("center".equals(align)) view.setGravity(Gravity.CENTER_HORIZONTAL);
    else if ("end".equals(align)) view.setGravity(Gravity.END);

    String bind = node.optString("bind", "").trim();
    if (!bind.isEmpty()) {
      String label = "";
      String source = bind;
      int bar = bind.indexOf('|');
      if (bar > 0) {
        label = bind.substring(0, bar).trim();
        source = bind.substring(bar + 1).trim();
      }
      Bound bound = new Bound(view, source, label);
      bound.refresh(context);
      bounds.add(bound);
    } else {
      view.setText(node.optString("text", ""));
    }
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 4));
    view.setLayoutParams(params);
    return view;
  }

  private static View imageView(Context context, ModuleCard card, JSONObject node) {
    java.io.File file = ModuleCardStore.assetOf(context, card, node.optString("src", ""));
    if (file == null) return null;
    ImageView view = new ImageView(context);
    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
    android.graphics.Bitmap bitmap = decode(context, file, node.optInt("h", 120));
    if (bitmap == null) return null;
    view.setImageBitmap(bitmap);
    int height = dp(context, node.optInt("h", 120));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    params.topMargin = dp(context, node.optInt("gap", 8));
    if (node.optBoolean("round", true)) {
      final int radius = dp(context, 12);
      view.setClipToOutline(true);
      view.setOutlineProvider(
          new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View target, android.graphics.Outline outline) {
              outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radius);
            }
          });
    }
    view.setLayoutParams(params);
    return view;
  }

  private static View space(Context context, JSONObject node) {
    View view = new View(context);
    view.setLayoutParams(
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, node.optInt("h", 8))));
    return view;
  }

  /**
   * 按钮：动作语义交回宿主，样式只开放一组<b>安全 token</b>。
   *
   * <p>token 全部可选、只收颜色字符串和数字，不支持任何 drawable 路径、资源名或原生代码：
   * <ul>
   *   <li>{@code fg}：前景色（文字与描边），{@code #RRGGBB} / {@code #AARRGGBB}；
   *       解析失败回退卡片强调色。</li>
   *   <li>{@code bg}：背景填充色；解析失败回退默认的强调色淡染。</li>
   *   <li>{@code radius}：圆角 dp，收在 0–32；没写保持默认胶囊形，写了非法值（负数/非数字）
   *       回退默认。</li>
   *   <li>{@code size}：文字 sp，收在 9–20，默认 12。</li>
   *   <li>{@code pad} / {@code padY}：内边距 dp，分别收在 4–28 / 2–20，默认 14 / 8。</li>
   * </ul>
   * 一个 token 都没写的按钮和旧版外观逐字节一致。
   */
  private static TextView button(
      Context context, ModuleCard card, JSONObject node, ActionSink sink) {
    TextView button = new TextView(context);
    button.setText(node.optString("label", "·"));
    button.setTextSize(clamp(node.optInt("size", 12), 9, 20));
    int accent = accent(context, card);
    int fg = colorToken(context, node.optString("fg", ""), accent);
    button.setTextColor(fg);
    button.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    int radius = 999;
    if (node.has("radius")) {
      int requested = node.optInt("radius", -1);
      if (requested >= 0) radius = clamp(requested, 0, 32);
    }
    shape.setCornerRadius(dp(context, radius));
    int fill = colorToken(context, node.optString("bg", ""), (fg & 0x00FFFFFF) | 0x14000000);
    shape.setColor(fill);
    shape.setStroke(1, (fg & 0x00FFFFFF) | 0x66000000);
    button.setBackground(shape);
    int padH = dp(context, clamp(node.optInt("pad", 14), 4, 28));
    int padV = dp(context, clamp(node.optInt("padY", 8), 2, 20));
    button.setPadding(padH, padV, padH, padV);
    // 可选图片图标：只收卡片声明过的安全图片名；图片缺失时保持纯文字按钮。
    String iconName = node.optString("image", "").trim();
    if (!iconName.isEmpty()) {
      java.io.File iconFile = ModuleCardStore.assetOf(context, card, iconName);
      if (iconFile != null) {
        int iconSize = dp(context, clamp(node.optInt("imageSize", 18), 12, 64));
        android.graphics.Bitmap iconBitmap =
            decode(context, iconFile, clamp(node.optInt("imageSize", 18), 12, 64));
        if (iconBitmap != null) {
          android.graphics.drawable.BitmapDrawable icon =
              new android.graphics.drawable.BitmapDrawable(context.getResources(), iconBitmap);
          icon.setBounds(0, 0, iconSize, iconSize);
          String position = node.optString("imagePos", "left");
          switch (position) {
            case "top" -> button.setCompoundDrawables(null, icon, null, null);
            case "right" -> button.setCompoundDrawables(null, null, icon, null);
            case "bottom" -> button.setCompoundDrawables(null, null, null, icon);
            default -> button.setCompoundDrawables(icon, null, null, null);
          }
          button.setCompoundDrawablePadding(dp(context, clamp(node.optInt("imagePad", 4), 0, 12)));
        }
      }
    }
    String action = node.optString("action", "").trim();
    int colon = action.indexOf(':');
    if (colon > 0) {
      final String type = action.substring(0, colon).trim();
      final String value = action.substring(colon + 1);
      button.setOnClickListener(v -> sink.run(type, value.trim()));
    }
    UiMotion.pressScale(button);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 10));
    params.rightMargin = dp(context, 8);
    button.setLayoutParams(params);
    return button;
  }

  /** 数字 token 收进合理范围；JSON 里写了非数字时 optInt 已回退到调用给的默认值。 */
  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static View row(
      Context context,
      ModuleCard card,
      JSONObject node,
      ActionSink sink,
      List<Bound> bounds,
      int depth) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    int gap = dp(context, node.optInt("gap", 10));
    row.setPadding(0, 0, 0, 0);
    JSONArray children = node.optJSONArray("children");
    if (children != null) {
      for (int i = 0; i < children.length(); i++) {
        JSONObject child = children.optJSONObject(i);
        if (child == null) continue;
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        int weight = Math.max(1, child.optInt("weight", 1));
        append(context, card, new JSONArray().put(child), cell, sink, bounds, depth + 1);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (i > 0) params.leftMargin = gap;
        row.addView(cell, params);
      }
    }
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 6));
    row.setLayoutParams(params);
    return row;
  }

  /** 纵向容器：模块用来替代系统列表页的简单排版。 */
  private static View column(
      Context context,
      ModuleCard card,
      JSONObject node,
      ActionSink sink,
      List<Bound> bounds,
      int depth) {
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setGravity(Gravity.CENTER_HORIZONTAL);
    column.setPadding(
        dp(context, node.optInt("padH", 0)),
        dp(context, node.optInt("padV", 0)),
        dp(context, node.optInt("padH", 0)),
        dp(context, node.optInt("padV", 0)));
    JSONArray children = node.optJSONArray("children");
    if (children != null) append(context, card, children, column, sink, bounds, depth + 1);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 0));
    column.setLayoutParams(params);
    return column;
  }

  /** 分组卡片：有底色、圆角和可选标题，模块可以把一段界面包成一块。 */
  private static View cardBox(
      Context context,
      ModuleCard card,
      JSONObject node,
      ActionSink sink,
      List<Bound> bounds,
      int depth) {
    LinearLayout box = new LinearLayout(context);
    box.setOrientation(LinearLayout.VERTICAL);
    int radius = dp(context, clamp(node.optInt("radius", 14), 0, 32));
    int bg =
        colorToken(
            context, node.optString("bg", ""), (accent(context, card) & 0x00FFFFFF) | 0x16000000);
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(radius);
    shape.setColor(bg);
    shape.setStroke(1, (accent(context, card) & 0x00FFFFFF) | 0x33000000);
    box.setBackground(shape);
    int pad = dp(context, clamp(node.optInt("pad", 12), 4, 28));
    box.setPadding(pad, pad, pad, pad);
    String title = node.optString("title", "").trim();
    if (!title.isEmpty()) {
      TextView heading = new TextView(context);
      heading.setText(title);
      heading.setTextSize(clamp(node.optInt("titleSize", 13), 10, 20));
      heading.setTypeface(null, Typeface.BOLD);
      heading.setTextColor(color(context, node.optString("titleColor", ""), R.color.shell_text));
      box.addView(heading);
      View divider = new View(context);
      divider.setBackgroundColor((accent(context, card) & 0x00FFFFFF) | 0x22000000);
      box.addView(divider, new LinearLayout.LayoutParams(-1, dp(context, 1)));
    }
    JSONArray children = node.optJSONArray("children");
    if (children != null) append(context, card, children, box, sink, bounds, depth + 1);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 8));
    box.setLayoutParams(params);
    return box;
  }

  /**
   * 进度条：模块声明外观，宿主画动画。变种见 {@link ModuleProgressView}。
   *
   * <p>颜色只收 {@code #RRGGBB} / {@code #AARRGGBB}，数值都有限制；不认识的变种回退细线。
   */
  private static View progress(Context context, ModuleCard card, JSONObject node) {
    ModuleProgressView view = new ModuleProgressView(context);
    int accent = accent(context, card);
    int track =
        colorToken(context, node.optString("track", ""), (accent & 0x00FFFFFF) | 0x22000000);
    int fill = colorToken(context, node.optString("fill", ""), accent);
    int fill2 = 0x00FFFFFF;
    String gradient = node.optString("gradient", "").trim();
    if (!gradient.isEmpty()) fill2 = colorToken(context, gradient, accent);
    int glow = colorToken(context, node.optString("glow", ""), (fill & 0x00FFFFFF) | 0x66000000);
    int success = colorToken(context, node.optString("success", ""), 0xFF35C759);
    int error = colorToken(context, node.optString("error", ""), 0xFFFF453A);
    view.configure(
        node.optString("variant", ""),
        node.optBoolean("indeterminate", false),
        (float) node.optDouble("value", 0.65),
        clamp(node.optInt("height", 4), 1, ModuleProgressView.MAX_HEIGHT_DP),
        clamp(node.optInt("radius", 2), 0, ModuleProgressView.MAX_HEIGHT_DP / 2),
        track,
        fill,
        fill2,
        glow,
        node.optBoolean("glow", true),
        node.optBoolean("showValue", false),
        clamp(node.optInt("segments", 5), 2, ModuleProgressView.MAX_SEGMENTS),
        clamp(node.optInt("duration", 1600), 200, 5000),
        success,
        error,
        node.optString("state", ""));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(context, node.optInt("gap", 6));
    params.leftMargin = dp(context, clamp(node.optInt("marginH", 0), 0, 24));
    params.rightMargin = dp(context, clamp(node.optInt("marginH", 0), 0, 24));
    view.setLayoutParams(params);
    return view;
  }

  /**
   * 模块网页：自带 HTML/CSS/JS 的任意界面，宿主只提供沙箱和 dsha 桥。
   *
   * <p>{@code src} 必须出现在模块的 {@code module.web.files} 里；{@code h} 是 160-1200 dp，
   * {@code fill} 为 true 时在可分配的父容器里占满剩余高度。
   */
  private static View web(Context context, ModuleCard card, JSONObject node) {
    String src = node.optString("src", card.web == null ? "" : card.web.entry).trim();
    if (card.web == null || !card.isWebAsset(src)) {
      TextView missing = new TextView(context);
      missing.setText(
          com.deepseekharness.app.util.UiText.format(
              com.deepseekharness.app.util.UiText.choose(
                  "模块网页资源缺失：%s", "Missing module web assets: %s"),
              src));
      missing.setTextSize(11);
      missing.setTextColor(context.getColor(R.color.shell_bad));
      return missing;
    }
    ModuleWebView view = new ModuleWebView(context, card, src);
    boolean fill = node.optBoolean("fill", false);
    LinearLayout.LayoutParams params =
        fill
            ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            : new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, clamp(node.optInt("h", 360), 160, 1200)));
    params.topMargin = dp(context, node.optInt("gap", 8));
    view.setLayoutParams(params);
    return view;
  }

  /**
   * 分段选择：一组互斥 chip。选中/未选中都是模块给的配色；点中即触发宿主动作。
   */
  private static View segmented(
      Context context, ModuleCard card, JSONObject node, ActionSink sink) {
    LinearLayout bar = new LinearLayout(context);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    int accent = accent(context, card);
    int selectedColor = colorToken(context, node.optString("selected", ""), accent);
    int normalColor = colorToken(context, node.optString("fg", ""), accent);
    JSONArray options = node.optJSONArray("options");
    java.util.List<TextView> tabs = new ArrayList<>();
    if (options != null) {
      for (int i = 0; i < Math.min(6, options.length()); i++) {
        JSONObject option = options.optJSONObject(i);
        if (option == null) continue;
        String label = option.optString("label", "·").trim();
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(clamp(option.optInt("size", 12), 9, 18));
        chip.setGravity(Gravity.CENTER);
        int padH = dp(context, clamp(option.optInt("pad", 12), 4, 28));
        int padV = dp(context, clamp(option.optInt("padY", 6), 2, 16));
        chip.setPadding(padH, padV, padH, padV);
        applyTabStyle(
            context, chip, option.optBoolean("selected", i == 0), selectedColor, normalColor);
        tabs.add(chip);
        String action = option.optString("action", "").trim();
        int colon = action.indexOf(':');
        if (colon > 0) {
          final String type = action.substring(0, colon).trim();
          final String value = action.substring(colon + 1);
          final int selectedIndex = i;
          chip.setOnClickListener(
              v -> {
                for (int index = 0; index < tabs.size(); index++)
                  applyTabStyle(
                      context, tabs.get(index), index == selectedIndex, selectedColor, normalColor);
                sink.run(
                    type,
                    value
                        .replace("{index}", String.valueOf(selectedIndex))
                        .replace("{label}", label)
                        .trim());
              });
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (i > 0) params.leftMargin = dp(context, clamp(node.optInt("gap", 8), 0, 24));
        bar.addView(chip, params);
      }
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    params.topMargin = dp(context, node.optInt("gap", 6));
    bar.setLayoutParams(params);
    return bar;
  }

  private static void applyTabStyle(
      Context context, TextView chip, boolean selected, int selectedColor, int normalColor) {
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(dp(context, 999));
    if (selected) {
      chip.setTextColor(0xFFFFFFFF);
      shape.setColor(selectedColor);
    } else {
      chip.setTextColor(normalColor);
      shape.setColor((selectedColor & 0x00FFFFFF) | 0x16000000);
      shape.setStroke(1, (selectedColor & 0x00FFFFFF) | 0x44000000);
    }
    chip.setBackground(shape);
  }

  /** 小徽标：模块在标题/按钮旁放的状态点。 */
  private static View badge(Context context, ModuleCard card, JSONObject node) {
    TextView badge = new TextView(context);
    String text = node.optString("text", "").trim();
    badge.setText(text.isEmpty() ? "·" : text);
    badge.setTextSize(clamp(node.optInt("size", 10), 8, 16));
    badge.setGravity(Gravity.CENTER);
    int accent = accent(context, card);
    int bg = colorToken(context, node.optString("bg", ""), accent);
    badge.setTextColor(colorToken(context, node.optString("fg", ""), 0xFFFFFFFF));
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(dp(context, clamp(node.optInt("radius", 999), 0, 32)));
    shape.setColor(bg);
    badge.setBackground(shape);
    int padH = dp(context, clamp(node.optInt("pad", 8), 2, 24));
    int padV = dp(context, clamp(node.optInt("padY", 3), 1, 12));
    badge.setPadding(padH, padV, padH, padV);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    params.topMargin = dp(context, node.optInt("gap", 4));
    badge.setLayoutParams(params);
    return badge;
  }

  /** 文字链接：下划线 + 强调色，点开宿主动作（page / toast / url 等）。 */
  private static View link(Context context, ModuleCard card, JSONObject node, ActionSink sink) {
    TextView link = new TextView(context);
    link.setText(node.optString("text", com.deepseekharness.app.util.UiText.choose("链接", "Link")));
    link.setTextSize(clamp(node.optInt("size", 13), 9, 20));
    int accent = accent(context, card);
    link.setTextColor(colorToken(context, node.optString("color", ""), accent));
    link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    link.setGravity(Gravity.CENTER_VERTICAL);
    String action = node.optString("action", "").trim();
    int colon = action.indexOf(':');
    if (colon > 0) {
      final String type = action.substring(0, colon).trim();
      final String value = action.substring(colon + 1);
      link.setOnClickListener(v -> sink.run(type, value.trim()));
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
    params.topMargin = dp(context, node.optInt("gap", 4));
    link.setLayoutParams(params);
    return link;
  }

  // ------------------------------------------------------------ 杂项

  private static int accent(Context context, ModuleCard card) {
    if (card.accent != null && !card.accent.isEmpty()) {
      try {
        return Color.parseColor(card.accent);
      } catch (RuntimeException ignored) {
        // 落到主题色
      }
    }
    return context.getColor(R.color.shell_accent);
  }

  private static int color(Context context, String raw, int fallback) {
    if (raw != null && !raw.isEmpty()) {
      try {
        return Color.parseColor(raw);
      } catch (RuntimeException ignored) {
        // 落到默认
      }
    }
    return context.getColor(fallback);
  }

  /**
   * 按钮 token 专用：{@code fallback} 是<b>颜色 int</b>（不是资源 id），未写或解析失败时
   * 原样返回。不能用 {@link #color}，它会把 fallback 当资源 id 去 {@code getColor}，
   * 对颜色 int 必然抛 NotFoundException。
   */
  private static int colorToken(Context context, String raw, int fallback) {
    if (raw != null && !raw.isEmpty()) {
      try {
        return Color.parseColor(raw);
      } catch (RuntimeException ignored) {
        // 非法颜色串回退默认，不让一个坏 token 挂掉整张卡
      }
    }
    return fallback;
  }

  private static android.graphics.Bitmap decode(Context context, java.io.File file, int dpHeight) {
    try {
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int target = Math.max(1, dp(context, Math.max(40, dpHeight * 2)));
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= target) sample *= 2;
      android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
      options.inSampleSize = sample;
      return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException | OutOfMemoryError error) {
      return null;
    }
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
