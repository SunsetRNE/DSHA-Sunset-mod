package com.deepseekharness.app.module;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * 模块脚本沙箱：让卡片从「只能挑动作」变成「能自己写逻辑」。
 *
 * <p>用系统自带的 WebView 跑 JS，不引第三方 JS 引擎 —— 代价是一个隐藏 WebView 实例，
 * 好处是零新依赖、和项目现有约束一致（只有 gson/bouncycastle/snakeyaml）。
 *
 * <p>沙箱边界（逐条都真的在代码里挡）：
 * <ul>
 *   <li>脚本只能看到注入的 {@code dsha} 对象，拿不到 Java 类，也拿不到 {@code DshaBridge} 之外的东西；</li>
 *   <li>断网：{@code setBlockNetworkLoads(true)} + 覆盖 {@code fetch/XHR/WebSocket/Worker}；</li>
 *   <li>断文件：{@code setAllowFileAccess(false)}、{@code setAllowContentAccess(false)}；</li>
 *   <li>{@code dsha.exec} 仍受这张卡片自己的执行授权约束，没有授权直接返回拒绝文案；</li>
 *   <li>单次执行 6 秒上限，超时销毁实例重来（JS 死循环不会拖死宿主主线程）。</li>
 * </ul>
 *
 * <p>能力真相：这里挡的是「模块偷偷联网/读文件/执行命令」，不是给容器加隔离。
 * 容器本身仍是 danger-full-access，别把这条写成安全沙箱。
 */
public final class ModuleScript {

  /** 单次脚本结果。回调一定在主线程。 */
  public interface Callback {
    void onResult(boolean ok, String output);
  }

  /** 脚本改写自己卡片上的槽位文字，让卡片能「活」起来。回调在主线程。 */
  public interface Slots {
    void set(String slot, String text);
  }

  private static final long TIMEOUT_MS = 6000;

  /** 一次最多排队 4 个请求，再多就拒绝，避免用户连点堆一串。 */
  private static final int MAX_QUEUE = 4;

  private static final class Request {
    /** 建 WebView 用宿主上下文（带主题），取数据用应用上下文。 */
    final Context host;

    final Context context;
    final ModuleCard card;
    final String cardId;
    final String cardName;
    final String code;
    final Map<String, String> values;
    final Slots slots;
    final Callback callback;

    Request(
        Context host,
        Context context,
        ModuleCard card,
        String cardId,
        String cardName,
        String code,
        Map<String, String> values,
        Slots slots,
        Callback callback) {
      this.host = host;
      this.context = context;
      this.card = card;
      this.cardId = cardId;
      this.cardName = cardName;
      this.code = code;
      this.values = values;
      this.slots = slots;
      this.callback = callback;
    }
  }

  private static Handler handler;
  private static WebView engine;
  private static boolean loading;
  private static boolean ready;
  private static final ArrayDeque<Request> queue = new ArrayDeque<>();
  private static Request running;
  private static String runningId;
  private static int counter;
  private static final StringBuilder console = new StringBuilder();

  /** 空闲 15 秒就销毁隐藏 WebView：它持有宿主上下文，长期不释放等于泄漏一个 Activity。 */
  private static final long IDLE_SHUTDOWN_MS = 15000;

  private static final Runnable idleShutdown =
      () -> {
        if (running != null || !queue.isEmpty()) return;
        destroy();
      };

  private static final Runnable timeout =
      () -> {
        if (running == null) return;
        Request request = running;
        String partial = console.toString().trim();
        destroy();
        finish(
            false,
            "脚本超时（"
                + TIMEOUT_MS / 1000
                + " 秒），可能是死循环。"
                + (partial.isEmpty() ? "" : "\n\nconsole:\n" + partial));
      };

  private ModuleScript() {}

  /** 在主页上下文里跑一段脚本。可从任意线程调用，内部转主线程。 */
  public static void run(
      Context context,
      ModuleCard card,
      String code,
      Map<String, String> values,
      Callback callback) {
    run(context, card, code, values, null, callback);
  }

  /** 带槽位回写能力的版本：脚本可以用 dsha.slot('line1', '...') 改自己卡片上的字。 */
  public static void run(
      Context context,
      ModuleCard card,
      String code,
      Map<String, String> values,
      Slots slots,
      Callback callback) {
    if (context == null || callback == null) return;
    Handler main = handler();
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post(() -> run(context, card, code, values, slots, callback));
      return;
    }
    if (code == null || code.trim().isEmpty()) {
      callback.onResult(false, "脚本为空");
      return;
    }
    if (code.length() > ModuleCard.MAX_SCRIPT) {
      callback.onResult(false, "脚本太长（上限 " + ModuleCard.MAX_SCRIPT + " 字符）");
      return;
    }
    if (queue.size() >= MAX_QUEUE) {
      callback.onResult(false, "脚本排满了，等一下再试");
      return;
    }
    handler().removeCallbacks(idleShutdown);
    queue.add(
        new Request(
            context,
            context.getApplicationContext(),
            card,
            card == null ? "" : card.id,
            card == null ? "" : card.name,
            code,
            values,
            slots,
            callback));
    pump();
  }

  /** 释放隐藏的 WebView。宿主退出主页时不用调，进程结束时系统会回收。 */
  public static void shutdown() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      handler().post(ModuleScript::shutdown);
      return;
    }
    queue.clear();
    running = null;
    destroy();
  }

  // ------------------------------------------------------------ 调度

  private static void pump() {
    if (running != null || queue.isEmpty()) return;
    if (engine == null) {
      create();
      return;
    }
    if (!ready || loading) return;
    running = queue.poll();
    if (running == null) return;
    console.setLength(0);
    runningId = "s" + (++counter);
    String payload =
        "__dsha_run("
            + JSONObject.quote(runningId)
            + ","
            + JSONObject.quote(running.code)
            + ","
            + valuesJson(running.values)
            + ")";
    handler().removeCallbacks(timeout);
    handler().postDelayed(timeout, TIMEOUT_MS);
    try {
      engine.evaluateJavascript(payload, null);
    } catch (RuntimeException error) {
      destroy();
      finish(false, "脚本引擎出错：" + error.getMessage());
    }
  }

  private static void finish(boolean ok, String output) {
    handler().removeCallbacks(timeout);
    Request request = running;
    running = null;
    runningId = null;
    if (request != null) request.callback.onResult(ok, output == null ? "" : output);
    pump();
    if (running == null && queue.isEmpty()) {
      handler().removeCallbacks(idleShutdown);
      handler().postDelayed(idleShutdown, IDLE_SHUTDOWN_MS);
    }
  }

  private static void create() {
    Context context = null;
    // 队列里第一个请求带上宿主上下文，WebView 用它取主题（应用上下文建 WebView 在部分机型会炸）。
    Iterator<Request> iterator = queue.iterator();
    while (iterator.hasNext()) {
      Request request = iterator.next();
      context = request.host;
      break;
    }
    if (context == null) return;
    WebView view = new WebView(context);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setDomStorageEnabled(false);
    settings.setDatabaseEnabled(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setGeolocationEnabled(false);
    settings.setSaveFormData(false);
    settings.setBlockNetworkLoads(true);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    view.setBackgroundColor(0x00000000);
    view.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            if (message != null && console.length() < 2000)
              console.append(message.message()).append('\n');
            return true;
          }
        });
    view.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView webView, String url) {
            loading = false;
            ready = true;
            pump();
          }
        });
    view.addJavascriptInterface(new Bridge(), "DshaBridge");
    engine = view;
    ready = false;
    loading = true;
    view.loadDataWithBaseURL("https://module.dsha.local/", HARNESS, "text/html", "utf-8", null);
    handler()
        .postDelayed(
            () -> {
              // 兜底：某些机型 onPageFinished 不回调，2 秒后直接当就绪，脚本自己会报错。
              if (loading && engine == view) {
                loading = false;
                ready = true;
                pump();
              }
            },
            2000);
  }

  private static void destroy() {
    if (engine != null) {
      try {
        engine.removeJavascriptInterface("DshaBridge");
        engine.loadUrl("about:blank");
        engine.destroy();
      } catch (RuntimeException ignored) {
        // 已经销毁
      }
    }
    engine = null;
    ready = false;
    loading = false;
  }

  private static Handler handler() {
    if (handler == null) handler = new Handler(Looper.getMainLooper());
    return handler;
  }

  private static String valuesJson(Map<String, String> values) {
    JSONObject json = new JSONObject();
    if (values != null) {
      for (Map.Entry<String, String> entry : values.entrySet()) {
        try {
          json.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        } catch (org.json.JSONException ignored) {
          // key 不合法时跳过
        }
      }
    }
    return json.toString();
  }

  // ------------------------------------------------------------ 桥

  /** 脚本能碰到的全部能力都在这一个类里，没有别的入口。 */
  private static final class Bridge {

    @JavascriptInterface
    public String value(String source) {
      Request request = running;
      if (request == null) return "";
      try {
        return ModuleData.value(request.context, String.valueOf(source));
      } catch (RuntimeException error) {
        return "";
      }
    }

    @JavascriptInterface
    public String exec(String command) {
      Request request = running;
      if (request == null) return "脚本已结束";
      String cmd = String.valueOf(command == null ? "" : command);
      // 授权绑当前内容摘要：卡片被更新过，旧批准不再生效。
      String digest =
          ModuleCardStore.contentDigest(ModuleCardStore.directoryOf(request.context, request.card));
      if (!ModuleExec.isAllowed(request.context, request.cardId, digest))
        return "[未授权] 这张卡片还没有运行命令的权限，先在卡片上点一次执行类按钮并确认。";
      ModuleExec.Result result = ModuleExec.run(request.context, cmd);
      return (result.ok ? "" : "[失败] ") + result.output;
    }

    @JavascriptInterface
    public void toast(String text) {
      Request request = running;
      if (request == null) return;
      final String message = String.valueOf(text == null ? "" : text);
      handler().post(() -> Toast.makeText(request.context, message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void copy(String text) {
      Request request = running;
      if (request == null) return;
      final String value = String.valueOf(text == null ? "" : text);
      handler()
          .post(
              () -> {
                ClipboardManager clipboard =
                    (ClipboardManager) request.context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null)
                  clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", value));
                Toast.makeText(request.context, UiText.choose("已复制", "Copied"), Toast.LENGTH_SHORT)
                    .show();
              });
    }

    @JavascriptInterface
    public void open(String url) {
      Request request = running;
      if (request == null) return;
      final String target = String.valueOf(url == null ? "" : url).trim();
      // 只放 http/https，挡掉 intent:// 之类的隐式跳转。
      if (!target.startsWith("https://") && !target.startsWith("http://")) return;
      handler()
          .post(
              () -> {
                try {
                  request.context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (RuntimeException ignored) {
                  // 没有应用能打开
                }
              });
    }

    /**
     * Host capability bus. Deliberately not wired in this change: every capability has to pass an
     * explicit per-card grant first, so the entry point keeps answering "unavailable" until that
     * model lands (see the module platform PR series).
     */
    @JavascriptInterface
    public String call(String name, String argsJson) {
      return "能力总线尚未开放";
    }

    /** Capabilities the host exposes right now; empty until the grant model lands. */
    @JavascriptInterface
    public String services() {
      return "";
    }

    @JavascriptInterface
    public void slot(String name, String text) {
      final Request request = running;
      if (request == null || request.slots == null) return;
      final String slot = String.valueOf(name).trim();
      if (!ModuleCard.SLOTS.contains(slot)) return;
      final String value = text == null ? "" : String.valueOf(text);
      handler().post(() -> request.slots.set(slot, value));
    }

    @JavascriptInterface
    public void log(String text) {
      if (console.length() < 2000) console.append(String.valueOf(text)).append('\n');
    }

    @JavascriptInterface
    public void done(String id, boolean ok, String output) {
      final String expected = runningId;
      final String value = output == null ? "" : String.valueOf(output);
      handler()
          .post(
              () -> {
                if (expected == null || !expected.equals(String.valueOf(id))) return;
                if (running == null) return;
                String extra = console.toString().trim();
                String body = value;
                if (ok && !extra.isEmpty()) body = (body.isEmpty() ? "" : body + "\n\n") + extra;
                finish(ok, body);
              });
    }
  }

  // ------------------------------------------------------------ 引导页

  private static final String HARNESS =
      "<!doctype html><meta charset=\"utf-8\"><title>module</title><script>\n"
          + "(function(){\n"
          + "  // 断网：即使 WebView 的 blockNetworkLoads 被机型忽略，这里也拦一道。\n"
          + "  function blocked(){ throw new Error('模块脚本不能联网'); }\n"
          + "  window.fetch = blocked; window.XMLHttpRequest = blocked; window.WebSocket = blocked;\n"
          + "  window.EventSource = blocked; window.Worker = blocked;\n"
          + "  window.importScripts = blocked;\n"
          + "  window.__dsha_run = function(id, code, values){\n"
          + "    var api = {\n"
          + "      get: function(name){ return String(DshaBridge.value(String(name))); },\n"
          + "      exec: function(cmd){ return String(DshaBridge.exec(String(cmd))); },\n"
          + "      toast: function(t){ DshaBridge.toast(String(t)); },\n"
          + "      copy: function(t){ DshaBridge.copy(String(t)); },\n"
          + "      open: function(u){ DshaBridge.open(String(u)); },\n"
          + "      log: function(t){ DshaBridge.log(String(t)); },\n"
          + "      call: function(name, args){ "
          + "return String(DshaBridge.call(String(name), JSON.stringify(args || {}))); },\n"
          + "      services: function(){ return String(DshaBridge.services()); },\n"
          + "      slot: function(name, t){ DshaBridge.slot(String(name), String(t)); },\n"
          + "      field: function(k){ return values[k] === undefined ? '' : String(values[k]); },\n"
          + "      fields: values || {}\n"
          + "    };\n"
          + "    var settled = false;\n"
          + "    function settle(ok, out){\n"
          + "      if (settled) return; settled = true;\n"
          + "      DshaBridge.done(id, !!ok, out === undefined || out === null ? '' : String(out));\n"
          + "    }\n"
          + "    try {\n"
          + "      var fn = new Function('dsha', code);\n"
          + "      var r = fn(api);\n"
          + "      if (r && typeof r.then === 'function') {\n"
          + "        r.then(function(v){ settle(true, v); }, function(e){ settle(false, '脚本出错：' + e); });\n"
          + "      } else { settle(true, r); }\n"
          + "    } catch (e) { settle(false, '脚本出错：' + (e && e.message ? e.message : e)); }\n"
          + "  };\n"
          + "})();\n"
          + "</script>";
}
