package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleData;
import com.deepseekharness.app.module.ModuleExec;
import com.deepseekharness.app.module.ModuleScript;
import com.deepseekharness.app.module.ModuleState;
import com.deepseekharness.app.util.UiText;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户卡片模块的主页视图。
 *
 * <p>三件事都发生在这一个类里：
 * <ul>
 *   <li><b>动态数据</b>：{@code bind} 指定的槽位每 5 秒向 {@link ModuleData} 取一次真实值；</li>
 *   <li><b>输入</b>：{@code fields} 渲染成输入框/开关，动作里的 {@code {{key}}} 替换成实际值；</li>
 *   <li><b>执行</b>：{@code exec} 要先拿到这张卡片的单独授权，再交给 {@link ModuleExec}。</li>
 * </ul>
 */
final class ModuleCardView extends LinearLayout {

  interface Host {
    /** 需要切到某个内置入口时交回宿主，卡片自己不碰导航。 */
    void openBuiltin(String target);
  }

  private static final long REFRESH_MS = 5000;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ModuleCard card;
  private final Host host;
  private final Map<String, TextView> slots = new LinkedHashMap<>();
  private final java.util.List<com.deepseekharness.app.ui.ModuleViewRenderer.Bound> viewBounds =
      new java.util.ArrayList<>();
  private final Map<String, String> values = new LinkedHashMap<>();
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          refreshBound();
          refreshViewBounds();
          handler.postDelayed(this, REFRESH_MS);
        }
      };

  /** 模块自定义的 tick：只在卡片可见时跑，离开页面立即停。 */
  private final Runnable lifecycleTicker =
      new Runnable() {
        @Override
        public void run() {
          if (card.lifecycle == null || card.lifecycle.tick.isEmpty()) return;
          runLifecycle(card.lifecycle.tick);
          if (isAttachedToWindow())
            handler.postDelayed(this, card.lifecycle.tickEverySeconds * 1000L);
        }
      };

  ModuleCardView(Context context, ModuleCard card, Host host) {
    super(context);
    this.card = card;
    this.host = host;
    // 建卡期间若模块自绘视图或脚本把进程弄崩，看门狗能记下是哪个模块。
    com.deepseekharness.app.module.ModuleCrashGuard.enter(card.id);
    try {
      setOrientation(VERTICAL);
      setBackgroundResource(R.drawable.bg_shell_card);
      int pad = dp(context, 16);
      setPadding(pad, pad, pad, dp(context, 14));

      // 模块自己描述了界面就整卡交给渲染器：版式由模块决定，宿主不再硬编码。
      if (card.view != null) {
        viewBounds.addAll(
            com.deepseekharness.app.ui.ModuleViewRenderer.render(
                context, card, card.view, this, this::runNodeAction));
        refreshViewBounds();
        return;
      }

      int accent = accentColor(context, card.accent);
      addPortrait(context, card);
      addHeader(context, card, accent);
      addBoundLines(context, card);
      addFields(context, card);
      addActions(context, card, accent);
      restoreSlots(context);
      refreshBound();
    } finally {
      com.deepseekharness.app.module.ModuleCrashGuard.leave(card.id);
    }
  }

  // ------------------------------------------------------------ 立绘

  private void addPortrait(Context context, ModuleCard card) {
    java.io.File portrait = com.deepseekharness.app.module.ModuleCardStore.imageOf(context, card);
    if (portrait == null) return;
    android.widget.ImageView art = new android.widget.ImageView(context);
    art.setClipToOutline(true);
    final int radius = dp(context, 14);
    art.setOutlineProvider(
        new android.view.ViewOutlineProvider() {
          @Override
          public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
          }
        });
    android.graphics.Bitmap bitmap = decode(context, portrait);
    if (bitmap == null) return;
    art.setImageBitmap(bitmap);
    art.setContentDescription(card.name);
    art.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
    art.post(
        () -> {
          int viewWidth = art.getWidth();
          int viewHeight = art.getHeight();
          if (viewWidth <= 0 || viewHeight <= 0) return;
          float scale =
              Math.max(
                  viewWidth / (float) bitmap.getWidth(), viewHeight / (float) bitmap.getHeight());
          float dx = (viewWidth - bitmap.getWidth() * scale) / 2f;
          // 竖立绘按人物上半身取景：垂直方向只下沉 30%，不居中裁到腰。
          float dy = (viewHeight - bitmap.getHeight() * scale) * 0.3f;
          android.graphics.Matrix matrix = new android.graphics.Matrix();
          matrix.setScale(scale, scale);
          matrix.postTranslate(dx, dy);
          art.setImageMatrix(matrix);
        });
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 168));
    params.bottomMargin = dp(context, 12);
    addView(art, params);
  }

  // ------------------------------------------------------------ 头部

  private void addHeader(Context context, ModuleCard card, int accent) {
    LinearLayout header = new LinearLayout(context);
    header.setOrientation(HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    addView(
        header,
        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    boolean hasImage =
        com.deepseekharness.app.module.ModuleCardStore.imageOf(context, card) != null;
    if (!hasImage) {
      TextView emoji = new TextView(context);
      emoji.setText(card.emoji.isEmpty() ? "◆" : card.emoji);
      emoji.setTextSize(20);
      emoji.setGravity(Gravity.CENTER);
      emoji.setBackground(rounded(accent, dp(context, 12)));
      int box = dp(context, 40);
      header.addView(emoji, new LinearLayout.LayoutParams(box, box));
    }

    LinearLayout words = new LinearLayout(context);
    words.setOrientation(VERTICAL);
    LinearLayout.LayoutParams wordsParams =
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    if (!hasImage) wordsParams.leftMargin = dp(context, 12);
    header.addView(words, wordsParams);

    TextView title = new TextView(context);
    title.setText(card.name);
    title.setTextSize(15);
    title.setTypeface(null, Typeface.BOLD);
    title.setIncludeFontPadding(false);
    title.setTextColor(context.getColor(R.color.shell_text));
    words.addView(title);

    TextView subtitle = new TextView(context);
    subtitle.setText(card.subtitle);
    subtitle.setTextSize(12);
    subtitle.setIncludeFontPadding(false);
    subtitle.setPadding(0, dp(context, 4), 0, 0);
    subtitle.setTextColor(context.getColor(R.color.shell_text_secondary));
    words.addView(subtitle);
    slots.put("subtitle", subtitle);
  }

  /** 额外两行：绑定了数据源就出现；有脚本动作也先建出来，等脚本往里写值。 */
  private void addBoundLines(Context context, ModuleCard card) {
    boolean hasScript = false;
    for (ModuleCard.Action action : card.actions)
      if ("script".equals(action.type) || "sequence".equals(action.type)) hasScript = true;
    for (String slot : new String[] {"line1", "line2"}) {
      if (!card.bind.containsKey(slot) && !hasScript) continue;
      TextView line = new TextView(context);
      line.setTextSize(12);
      line.setIncludeFontPadding(false);
      line.setTextColor(context.getColor(R.color.shell_text_secondary));
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.topMargin = dp(context, 6);
      addView(line, params);
      if (!card.bind.containsKey(slot)) line.setVisibility(View.GONE);
      slots.put(slot, line);
    }
  }

  // ------------------------------------------------------------ 输入

  private void addFields(Context context, ModuleCard card) {
    for (ModuleCard.Field field : card.fields) {
      LinearLayout row = new LinearLayout(context);
      row.setOrientation(HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams rowParams =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      rowParams.topMargin = dp(context, 10);
      addView(row, rowParams);

      TextView label = new TextView(context);
      label.setText(field.label);
      label.setTextSize(12);
      label.setIncludeFontPadding(false);
      label.setTextColor(context.getColor(R.color.shell_text_secondary));
      row.addView(label, new LinearLayout.LayoutParams(dp(context, 56), -2));

      // 上次填过的值优先，否则用模板给的默认值。
      String saved = ModuleState.field(context, card.id, field.key);
      String initial = saved == null ? field.defaultValue : saved;
      values.put(field.key, initial);
      if ("toggle".equals(field.type)) {
        com.google.android.material.materialswitch.MaterialSwitch toggle =
            new com.google.android.material.materialswitch.MaterialSwitch(context);
        toggle.setChecked("true".equals(initial));
        toggle.setOnCheckedChangeListener(
            (button, checked) -> {
              values.put(field.key, checked ? "true" : "false");
              ModuleState.setField(context, card.id, field.key, checked ? "true" : "false");
            });
        row.addView(toggle);
      } else {
        EditText input = new EditText(context);
        input.setText(initial);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setHint(field.placeholder);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackgroundResource(R.drawable.bg_shell_status);
        input.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        input.addTextChangedListener(
            new android.text.TextWatcher() {
              @Override
              public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

              @Override
              public void onTextChanged(CharSequence s, int a, int b, int c) {
                values.put(field.key, s.toString());
                ModuleState.setField(context, card.id, field.key, s.toString());
              }

              @Override
              public void afterTextChanged(android.text.Editable s) {}
            });
        LinearLayout.LayoutParams inputParams =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(input, inputParams);
      }
    }
  }

  // ------------------------------------------------------------ 动作

  private void addActions(Context context, ModuleCard card, int accent) {
    if (card.actions.isEmpty()) return;
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(HORIZONTAL);
    LinearLayout.LayoutParams rowParams =
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rowParams.topMargin = dp(context, 12);
    addView(row, rowParams);

    for (ModuleCard.Action action : card.actions) {
      TextView button = new TextView(context);
      button.setText(action.label);
      button.setTextSize(12);
      button.setTextColor(accent);
      button.setGravity(Gravity.CENTER);
      button.setBackground(roundedWithStroke(accent));
      button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
      button.setContentDescription(action.label);
      button.setOnClickListener(v -> run(context, action));
      UiMotion.pressScale(button);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      params.rightMargin = dp(context, 8);
      row.addView(button, params);
    }
  }

  private void run(Context context, ModuleCard.Action action) {
    // 脚本是代码，不能做 {{key}} 文本替换（会破坏 JS），入参走 dsha.field()。
    String value = "script".equals(action.type) ? action.value : interpolate(action.value);
    switch (action.type) {
      case "toast":
        toast(context, value);
        break;
      case "copy":
        copy(context, value);
        break;
      case "url":
        openUrl(context, value);
        break;
      case "modules":
        context.startActivity(new Intent(context, ModuleCenterActivity.class));
        break;
      case "settings":
        if (host != null) {
          host.openBuiltin("settings");
        } else {
          context.startActivity(
              new Intent(context, MainActivity.class).putExtra("open_settings", true));
        }
        break;
      case "terminal":
        if (host != null) host.openBuiltin("terminal");
        break;
      case "exec":
        runCommand(context, value);
        break;
      case "sequence":
        runSequence(context, action.value);
        break;
      case "script":
        runScript(context, action.value);
        break;
      case "page":
        ModulePageActivity.open(context, card, value.trim());
        break;
      default:
        break;
    }
  }

  /**
   * 一个按钮跑一串动作。
   *
   * <p>步骤里有 exec 或 script 时，要先拿到这张卡片的执行授权；纯 UI 步骤不需要。
   * 步骤严格按顺序执行，前一步失败就停，不会「继续往下跑」。
   */
  private void runSequence(Context context, String raw) {
    String body = interpolate(raw);
    java.util.List<String[]> steps = new java.util.ArrayList<>();
    for (String line : body.split("\n")) {
      String step = line.trim();
      if (step.isEmpty()) continue;
      int colon = step.indexOf(':');
      if (colon <= 0) continue;
      steps.add(new String[] {step.substring(0, colon).trim(), step.substring(colon + 1)});
    }
    if (steps.isEmpty()) {
      toast(context, UiText.choose("没有可执行的步骤", "Nothing to run"));
      return;
    }
    boolean privileged = false;
    for (String[] step : steps)
      if ("exec".equals(step[0]) || "script".equals(step[0])) privileged = true;
    if (!privileged || ModuleExec.isAllowed(context, card.id)) {
      executeSteps(context, steps, 0, new StringBuilder());
      return;
    }
    requestPermission(context, raw, () -> executeSteps(context, steps, 0, new StringBuilder()));
  }

  /** 授权弹窗：把要跑的东西原样摊给用户看，授权只对这张卡片生效。 */
  private void requestPermission(Context context, String preview, Runnable onAllowed) {
    if (!(context instanceof android.app.Activity)) return;
    String shown = preview.length() > 700 ? preview.substring(0, 700) + "…" : preview;
    new DshaDialogBuilder((android.app.Activity) context, true)
        .setTitle(UiText.format(UiText.choose("允许「%s」运行？", "Allow \"%s\" to run?"), card.name))
        .setMessage(
            UiText.format(
                UiText.choose(
                    "这张卡片将执行：\n\n%s\n\n命令在你的容器里运行，脚本在隔离的 JS 环境里运行。只对这张卡片生效，随时可在模块中心撤销。",
                    "This card will run:\n\n%s\n\nCommands run inside your container, scripts in an isolated JS sandbox. Applies to this card only."),
                shown))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            UiText.choose("允许", "Allow"),
            (dialog, which) -> {
              ModuleExec.setAllowed(context, card.id, true);
              onAllowed.run();
            })
        .show();
  }

  /** 顺序执行：非阻塞步骤直接跑，exec 丢后台，script 等回调。 */
  private void executeSteps(
      Context context, java.util.List<String[]> steps, int index, StringBuilder log) {
    if (index >= steps.size()) {
      String text = log.toString().trim();
      if (!(context instanceof android.app.Activity)) {
        toast(context, text.isEmpty() ? UiText.choose("完成", "Done") : text);
        return;
      }
      new DshaDialogBuilder((android.app.Activity) context)
          .setTitle(UiText.choose("执行完成", "Done"))
          .setMessage(text.isEmpty() ? UiText.choose("（无输出）", "(no output)") : text)
          .setPositiveButton(UiText.choose("复制", "Copy"), (d, w) -> copy(context, text))
          .setNegativeButton(UiText.choose("关闭", "Close"), null)
          .show();
      return;
    }
    String[] step = steps.get(index);
    String type = step[0];
    String value = step[1] == null ? "" : step[1];
    if ("exec".equals(type)) {
      toast(context, UiText.choose("正在执行…", "Running…"));
      new Thread(
              () -> {
                ModuleExec.Result result = ModuleExec.run(context, value.trim());
                log.append("$ ")
                    .append(value.trim())
                    .append('\n')
                    .append(result.output)
                    .append('\n');
                handler.post(
                    () -> {
                      if (!result.ok) {
                        log.append(UiText.choose("已停止：这一步失败", "Stopped: step failed"));
                        executeSteps(context, steps, steps.size(), log);
                      } else {
                        executeSteps(context, steps, index + 1, log);
                      }
                    });
              })
          .start();
      return;
    }
    if ("script".equals(type)) {
      final String code = value.trim();
      ModuleScript.run(
          context,
          card,
          code,
          values,
          this::setSlot,
          (ok, output) -> {
            log.append("{ ").append(code).append(" }\n").append(output).append('\n');
            if (!ok) log.append(UiText.choose("已停止：脚本出错", "Stopped: script failed"));
            executeSteps(context, steps, ok ? index + 1 : steps.size(), log);
          });
      return;
    }
    runStep(context, type, value.trim());
    log.append("· ").append(type).append('：').append(value.trim()).append('\n');
    executeSteps(context, steps, index + 1, log);
  }

  /** 非 exec 步骤直接在主线程跑（都是纯 UI 动作）。 */
  private void runStep(Context context, String type, String value) {
    switch (type) {
      case "toast":
        toast(context, value);
        break;
      case "copy":
        copy(context, value);
        break;
      case "url":
        openUrl(context, value);
        break;
      case "modules":
        context.startActivity(new Intent(context, ModuleCenterActivity.class));
        break;
      case "settings":
        if (host != null) host.openBuiltin("settings");
        break;
      case "terminal":
        if (host != null) host.openBuiltin("terminal");
        break;
      case "page":
        ModulePageActivity.open(context, card, value.trim());
        break;
      default:
        break;
    }
  }

  /** 脚本写槽位：写进去就显示出来，同时落盘，页面重建后还在。 */
  private void setSlot(String slot, String text) {
    TextView view = slots.get(slot);
    if (view == null) return;
    String value = text == null ? "" : text.trim();
    ModuleState.setSlot(getContext(), card.id, slot, value);
    if (value.isEmpty()) {
      view.setVisibility(View.GONE);
      return;
    }
    view.setText(value);
    view.setVisibility(View.VISIBLE);
  }

  /** 页面重建时把脚本上一次写的字贴回去。绑定数据的槽位交给 refreshBound() 管。 */
  private void restoreSlots(Context context) {
    for (Map.Entry<String, TextView> entry : slots.entrySet()) {
      if (card.bind.containsKey(entry.getKey())) continue;
      String saved = ModuleState.slot(context, card.id, entry.getKey());
      if (saved == null || saved.isEmpty()) continue;
      entry.getValue().setText(saved);
      entry.getValue().setVisibility(View.VISIBLE);
    }
  }

  /** 把 {{key}} 换成输入框里的实际值。 */
  private String interpolate(String template) {
    if (template == null || template.isEmpty()) return "";
    String result = template;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return result;
  }

  // ------------------------------------------------------------ 执行

  /**
   * 运行模块自带脚本。
   *
   * <p>脚本能读真实数据（dsha.get）、能弹提示/复制/开链接，只有调 dsha.exec 时才
   * 受执行授权约束。第一次运行整段脚本要用户点头，因为脚本内容对用户是不透明的。
   */
  private void runScript(Context context, String code) {
    if (code == null || code.trim().isEmpty()) {
      toast(context, UiText.choose("脚本为空", "Empty script"));
      return;
    }
    if (ModuleExec.isAllowed(context, card.id)) {
      executeScript(context, code);
      return;
    }
    requestPermission(context, code, () -> executeScript(context, code));
  }

  private void executeScript(Context context, String code) {
    toast(context, UiText.choose("正在运行脚本…", "Running script…"));
    ModuleScript.run(
        context,
        card,
        code,
        values,
        this::setSlot,
        (ok, output) -> {
          String raw = output == null ? "" : output;
          final String text = raw.isEmpty() ? UiText.choose("（脚本没有返回内容）", "(no output)") : raw;
          if (!(context instanceof android.app.Activity)) {
            toast(context, text);
            return;
          }
          new DshaDialogBuilder((android.app.Activity) context)
              .setTitle(
                  ok
                      ? UiText.choose("脚本结果", "Script result")
                      : UiText.choose("脚本出错", "Script failed"))
              .setMessage(text)
              .setPositiveButton(UiText.choose("复制", "Copy"), (d, w) -> copy(context, text))
              .setNegativeButton(UiText.choose("关闭", "Close"), null)
              .show();
        });
  }

  private void runCommand(Context context, String command) {
    if (command.isEmpty()) {
      toast(context, UiText.choose("没有可执行的命令", "Nothing to run"));
      return;
    }
    if (ModuleExec.isAllowed(context, card.id)) {
      execute(context, command);
      return;
    }
    if (!(context instanceof android.app.Activity)) return;
    new DshaDialogBuilder((android.app.Activity) context, true)
        .setTitle(
            UiText.format(UiText.choose("允许「%s」运行命令？", "Allow \"%s\" to run commands?"), card.name))
        .setMessage(
            UiText.format(
                UiText.choose(
                    "这张卡片将在你的容器里执行：\n\n%s\n\n只对这张卡片生效，可以随时在模块中心撤销。",
                    "This card will run the following inside your container:\n\n%s\n\nApplies to this card only and can be revoked in the module center."),
                command))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            UiText.choose("允许并执行", "Allow and run"),
            (dialog, which) -> {
              ModuleExec.setAllowed(context, card.id, true);
              execute(context, command);
            })
        .show();
  }

  private void execute(Context context, String command) {
    toast(context, UiText.choose("正在执行…", "Running…"));
    new Thread(
            () -> {
              ModuleExec.Result result = ModuleExec.run(context, command);
              handler.post(() -> showResult(context, command, result));
            })
        .start();
  }

  private void showResult(Context context, String command, ModuleExec.Result result) {
    if (!(context instanceof android.app.Activity)) {
      toast(context, result.output);
      return;
    }
    new DshaDialogBuilder((android.app.Activity) context)
        .setTitle(result.ok ? UiText.choose("执行完成", "Done") : UiText.choose("执行失败", "Failed"))
        .setMessage(command + "\n\n" + result.output)
        .setPositiveButton(
            UiText.choose("复制输出", "Copy output"), (d, w) -> copy(context, result.output))
        .setNegativeButton(UiText.choose("关闭", "Close"), null)
        .show();
  }

  // ------------------------------------------------------------ 声明式视图

  /** 声明式视图里的按钮：{@code type:参数}，直接复用卡片已有的动作语义。 */
  private void runNodeAction(String type, String value) {
    if (type == null || type.isEmpty()) return;
    String label = getContext() == null ? "" : type;
    run(getContext(), ModuleCard.action(label, type, value));
  }

  private void refreshViewBounds() {
    if (viewBounds.isEmpty()) return;
    com.deepseekharness.app.module.ModuleCrashGuard.enter(card.id);
    try {
      Context context = getContext();
      for (com.deepseekharness.app.ui.ModuleViewRenderer.Bound bound : viewBounds)
        bound.refresh(context);
    } finally {
      com.deepseekharness.app.module.ModuleCrashGuard.leave(card.id);
    }
  }

  // ------------------------------------------------------------ 数据绑定

  private void refreshBound() {
    if (card.bind.isEmpty()) return;
    com.deepseekharness.app.module.ModuleCrashGuard.enter(card.id);
    try {
      Context context = getContext();
      for (Map.Entry<String, String> entry : card.bind.entrySet()) {
        TextView view = slots.get(entry.getKey());
        if (view == null) continue;
        String value = ModuleData.value(context, entry.getValue());
        if (value.isEmpty()) continue;
        String label = card.bindLabels.get(entry.getKey());
        view.setText(label == null || label.isEmpty() ? value : label + " " + value);
      }
    } finally {
      com.deepseekharness.app.module.ModuleCrashGuard.leave(card.id);
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    handler.removeCallbacks(ticker);
    handler.postDelayed(ticker, REFRESH_MS);
    if (card.lifecycle != null) {
      runLifecycle(card.lifecycle.onShow);
      if (!card.lifecycle.tick.isEmpty())
        handler.postDelayed(lifecycleTicker, card.lifecycle.tickEverySeconds * 1000L);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    handler.removeCallbacks(ticker);
    handler.removeCallbacks(lifecycleTicker);
    if (card.lifecycle != null) runLifecycle(card.lifecycle.onHide);
    super.onDetachedFromWindow();
  }

  /** 生命周期脚本静默执行；结果只写槽位/日志，不弹窗打断用户。 */
  private void runLifecycle(String code) {
    if (code == null || code.isEmpty()) return;
    ModuleScript.run(
        getContext(),
        card,
        code,
        values,
        this::setSlot,
        (ok, output) -> {
          // 生命周期脚本失败不打断页面，诊断留给脚本自己的 dsha.log。
        });
  }

  // ------------------------------------------------------------ 杂项

  private static void toast(Context context, String text) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
  }

  private void copy(Context context, String value) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", value));
    toast(context, UiText.choose("已复制", "Copied"));
  }

  private static void openUrl(Context context, String value) {
    String url = value.trim();
    if (!url.startsWith("https://") && !url.startsWith("http://")) return;
    try {
      context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException ignored) {
      toast(context, UiText.choose("没有可打开的应用", "No app can open this"));
    }
  }

  /** 按目标宽度采样解码，避免大图直接 OOM。 */
  private static android.graphics.Bitmap decode(Context context, java.io.File file) {
    try {
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int target = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= target) sample *= 2;
      android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
      options.inSampleSize = sample;
      return android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException | OutOfMemoryError error) {
      return null;
    }
  }

  private static int accentColor(Context context, String accent) {
    if (accent != null && !accent.isEmpty()) {
      try {
        return Color.parseColor(accent);
      } catch (RuntimeException ignored) {
        // 落到主题色
      }
    }
    return context.getColor(R.color.shell_accent);
  }

  private static android.graphics.drawable.Drawable rounded(int color, int radius) {
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
    shape.setCornerRadius(radius);
    shape.setColor((color & 0x00FFFFFF) | 0x1F000000);
    return shape;
  }

  private static android.graphics.drawable.Drawable roundedWithStroke(int color) {
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
    shape.setCornerRadius(999);
    shape.setColor((color & 0x00FFFFFF) | 0x14000000);
    shape.setStroke(1, (color & 0x00FFFFFF) | 0x66000000);
    return shape;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
