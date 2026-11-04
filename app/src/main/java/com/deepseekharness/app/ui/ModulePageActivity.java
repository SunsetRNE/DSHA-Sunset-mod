package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleExec;
import com.deepseekharness.app.module.ModuleScript;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;

/**
 * 卡片二级页面：渲染 {@code pages} 里的 view 节点，动作仍由宿主执行。
 *
 * <p>页面只属于打开它的那张卡片：page.open 不能跳到别的模块，也不能启动任意 Activity。
 * 动作权限与主页卡片同一套：exec/script/sequence 需要这张卡片的运行授权。
 */
public final class ModulePageActivity extends AppCompatActivity {

  private static final String EXTRA_CARD_ID = "card_id";
  private static final String EXTRA_PAGE_ID = "page_id";

  private final Handler handler = new Handler(Looper.getMainLooper());
  private ModuleCard card;
  private ModuleCard.Page page;

  public static void open(Context context, ModuleCard card, String pageId) {
    if (context == null || card == null || pageId == null || pageId.isEmpty()) return;
    Intent intent =
        new Intent(context, ModulePageActivity.class)
            .putExtra(EXTRA_CARD_ID, card.id)
            .putExtra(EXTRA_PAGE_ID, pageId);
    if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    String cardId = getIntent().getStringExtra(EXTRA_CARD_ID);
    String pageId = getIntent().getStringExtra(EXTRA_PAGE_ID);
    for (ModuleCard item : ModuleCardStore.loadAll(this)) {
      if (item.id.equals(cardId)) {
        card = item;
        break;
      }
    }
    if (card == null || !card.pages.containsKey(pageId)) {
      finish();
      return;
    }
    page = card.pages.get(pageId);
    CardPage shell = new CardPage(this, page.title, card.name + " · v" + card.version);
    LinearLayout body = shell.column();
    body.setPadding(shell.dp(16), shell.dp(14), shell.dp(16), shell.dp(14));
    body.setBackgroundResource(R.drawable.bg_shell_card);
    shell.content.addView(body, new LinearLayout.LayoutParams(-1, -2));
    ModuleViewRenderer.render(this, card, page.view, body, (type, value) -> runAction(type, value));
    shell.button(shell.footer, UiText.choose("返回", "Back"), false, this::finish);
    setContentView(shell.root);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (card != null && card.lifecycle != null) runLifecycle(card.lifecycle.onShow);
  }

  @Override
  protected void onPause() {
    if (card != null && card.lifecycle != null) runLifecycle(card.lifecycle.onHide);
    super.onPause();
  }

  private void runLifecycle(String code) {
    if (code == null || code.isEmpty()) return;
    ModuleScript.run(
        this,
        card,
        code,
        java.util.Map.of(),
        null,
        (ok, output) -> {
          // 生命周期脚本失败不打断页面。
        });
  }

  private void runAction(String type, String value) {
    switch (type) {
      case "toast" -> toast(value);
      case "copy" -> copy(value);
      case "url" -> openUrl(value);
      case "modules" -> startActivity(new Intent(this, ModuleCenterActivity.class));
      case "settings" -> openSettings();
      case "terminal" -> openTerminal();
      case "page" -> {
        if (card.pages.containsKey(value.trim())) open(this, card, value.trim());
      }
      case "exec" -> runCommand(value);
      case "script" -> runScript(value);
      case "sequence" -> runSequence(value);
      default -> {
        // 不认识的类型由渲染器/解析层挡住，这里保持静默。
      }
    }
  }

  private void runSequence(String raw) {
    List<String[]> steps = new ArrayList<>();
    for (String line : raw.split("\n")) {
      String step = line.trim();
      if (step.isEmpty()) continue;
      int colon = step.indexOf(':');
      if (colon <= 0) continue;
      steps.add(new String[] {step.substring(0, colon).trim(), step.substring(colon + 1)});
    }
    if (steps.isEmpty()) {
      toast(UiText.choose("没有可执行的步骤", "Nothing to run"));
      return;
    }
    boolean privileged = false;
    for (String[] step : steps)
      if ("exec".equals(step[0]) || "script".equals(step[0])) privileged = true;
    if (!privileged || ModuleExec.isAllowed(this, card.id)) {
      executeSteps(steps, 0, new StringBuilder());
    } else {
      requestPermission(raw, () -> executeSteps(steps, 0, new StringBuilder()));
    }
  }

  private void executeSteps(List<String[]> steps, int index, StringBuilder log) {
    if (index >= steps.size()) {
      String text = log.toString().trim();
      new DshaDialogBuilder(this)
          .setTitle(UiText.choose("执行完成", "Done"))
          .setMessage(text.isEmpty() ? UiText.choose("（无输出）", "(no output)") : text)
          .setPositiveButton(UiText.choose("复制", "Copy"), (d, w) -> copy(text))
          .setNegativeButton(UiText.choose("关闭", "Close"), null)
          .show();
      return;
    }
    String[] step = steps.get(index);
    String type = step[0];
    String value = step[1] == null ? "" : step[1].trim();
    if ("exec".equals(type)) {
      toast(UiText.choose("正在执行…", "Running…"));
      new Thread(
              () -> {
                ModuleExec.Result result = ModuleExec.run(this, value);
                log.append("$ ").append(value).append('\n').append(result.output).append('\n');
                handler.post(
                    () -> {
                      if (!result.ok) {
                        log.append(UiText.choose("已停止：这一步失败", "Stopped: step failed"));
                        executeSteps(steps, steps.size(), log);
                      } else {
                        executeSteps(steps, index + 1, log);
                      }
                    });
              },
              "module-page-exec")
          .start();
      return;
    }
    if ("script".equals(type)) {
      ModuleScript.run(
          this,
          card,
          value,
          java.util.Map.of(),
          null,
          (ok, output) -> {
            log.append("{ ").append(value).append(" }\n").append(output).append('\n');
            if (!ok) log.append(UiText.choose("已停止：脚本出错", "Stopped: script failed"));
            executeSteps(steps, ok ? index + 1 : steps.size(), log);
          });
      return;
    }
    runAction(type, value);
    log.append("· ").append(type).append('：').append(value).append('\n');
    executeSteps(steps, index + 1, log);
  }

  private void runCommand(String command) {
    String value = command == null ? "" : command.trim();
    if (value.isEmpty()) {
      toast(UiText.choose("命令为空", "Empty command"));
      return;
    }
    if (!ModuleExec.isAllowed(this, card.id)) {
      requestPermission(value, () -> runCommand(value));
      return;
    }
    toast(UiText.choose("正在执行…", "Running…"));
    new Thread(
            () -> {
              ModuleExec.Result result = ModuleExec.run(this, value);
              handler.post(
                  () ->
                      new DshaDialogBuilder(this)
                          .setTitle(
                              result.ok
                                  ? UiText.choose("执行完成", "Done")
                                  : UiText.choose("执行失败", "Failed"))
                          .setMessage(result.output)
                          .setPositiveButton(
                              UiText.choose("复制", "Copy"), (d, w) -> copy(result.output))
                          .setNegativeButton(UiText.choose("关闭", "Close"), null)
                          .show());
            },
            "module-page-exec")
        .start();
  }

  private void runScript(String code) {
    if (!ModuleExec.isAllowed(this, card.id)) {
      requestPermission(code, () -> runScript(code));
      return;
    }
    ModuleScript.run(
        this,
        card,
        code,
        java.util.Map.of(),
        null,
        (ok, output) ->
            new DshaDialogBuilder(this)
                .setTitle(
                    ok
                        ? UiText.choose("脚本结果", "Script result")
                        : UiText.choose("脚本失败", "Script failed"))
                .setMessage(output)
                .setPositiveButton(UiText.choose("复制", "Copy"), (d, w) -> copy(output))
                .setNegativeButton(UiText.choose("关闭", "Close"), null)
                .show());
  }

  private void requestPermission(String preview, Runnable onAllowed) {
    String shown = preview.length() > 700 ? preview.substring(0, 700) + "…" : preview;
    new DshaDialogBuilder(this)
        .setTitle(UiText.choose("允许「" + card.name + "」运行？", "Allow \"" + card.name + "\" to run?"))
        .setMessage(
            UiText.choose(
                "这个页面将执行：\n\n" + shown + "\n\n命令在你的容器里运行，脚本在隔离的 JS 环境里运行。只对这张卡片生效，随时可在模块中心撤销。",
                "This page will run:\n\n"
                    + shown
                    + "\n\nCommands run inside your container, scripts in an isolated JS sandbox. Applies to this card only."))
        .setNegativeButton(UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            UiText.choose("允许", "Allow"),
            (dialog, which) -> {
              ModuleExec.setAllowed(this, card.id, true);
              onAllowed.run();
            })
        .show();
  }

  private void openSettings() {
    MainActivity main = MainActivity.active();
    if (main != null) {
      main.openSettings();
      finish();
    } else {
      startActivity(MainActivity.homeIntent(this));
      toast(UiText.choose("已回到主页，请点设置", "Back on home; tap Settings"));
    }
  }

  private void openTerminal() {
    MainActivity main = MainActivity.active();
    if (main != null) {
      main.openTerminal();
      finish();
    } else {
      startActivity(MainActivity.homeIntent(this));
      toast(UiText.choose("已回到主页，请点终端", "Back on home; tap Terminal"));
    }
  }

  private void toast(String text) {
    Toast.makeText(this, text == null ? "" : text, Toast.LENGTH_SHORT).show();
  }

  private void copy(String text) {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null)
      clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", text == null ? "" : text));
    toast(UiText.choose("已复制", "Copied"));
  }

  private void openUrl(String value) {
    String url = value == null ? "" : value.trim();
    if (!url.startsWith("https://") && !url.startsWith("http://")) {
      toast(UiText.choose("只支持 http(s)", "Only http(s) is supported"));
      return;
    }
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException error) {
      toast(UiText.choose("没有应用能打开这个链接", "No app can open this link"));
    }
  }
}
