package com.deepseekharness.app.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.deepseekharness.app.module.ModuleCard;
import com.deepseekharness.app.module.ModuleCardStore;
import com.deepseekharness.app.module.ModuleData;
import com.deepseekharness.app.module.ModuleServices;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * 模块网页容器：模块自带 HTML/CSS/JS，宿主用沙箱 WebView 渲染。
 *
 * <p>资源只从模块私有目录经 {@link WebViewAssetLoader} 提供；网络请求与 file/content 访问
 * 全部拦截，页面只能通过 {@code dsha} 桥调用宿主能力（能力仍走逐卡权限）。
 * 这一层让模块不受节点表限制：Canvas、SVG、CSS 动画、任意布局都由模块自己写。
 */
final class ModuleWebView extends FrameLayout {

  private static final String HOST = "https://appassets.androidplatform.net";
  private static final int MAX_SCRIPT = 4000;

  private final WebView web;
  private final ModuleCard card;
  private final Handler main = new Handler(Looper.getMainLooper());

  @SuppressLint("SetJavaScriptEnabled")
  ModuleWebView(Context context, ModuleCard card, String entry) {
    super(context);
    this.card = card;
    com.deepseekharness.app.module.ModuleCrashGuard.enter(card.id);
    web = new WebView(context);
    setBackgroundColor(context.getColor(com.deepseekharness.app.R.color.shell_bg));
    addView(web, new LayoutParams(-1, -1));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setBlockNetworkLoads(true);
    WebView.setWebContentsDebuggingEnabled(false);

    File directory = ModuleCardStore.directoryOf(context, card);
    if (directory == null || !card.isWebAsset(entry)) {
      web.loadDataWithBaseURL(HOST, errorPage("模块网页资源缺失"), "text/html", "utf-8", null);
      return;
    }
    WebViewAssetLoader loader =
        new WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler(
                "/module/", new WebViewAssetLoader.InternalStoragePathHandler(context, directory))
            .build();
    web.setWebViewClient(
        new WebViewClientCompat() {
          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            WebResourceResponse local = loader.shouldInterceptRequest(request.getUrl());
            if (local != null) return local;
            // 断网：不是模块目录里的资源一律返回空，不发真实网络请求。
            return new WebResourceResponse(
                "text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return true;
          }
        });
    web.addJavascriptInterface(new Bridge(), "DshaBridge");
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      WebViewCompat.addDocumentStartJavaScript(web, bridgeScript(), java.util.Set.of("*"));
    }
    web.loadUrl(HOST + "/module/" + Uri.encode(entry));
    web.addOnAttachStateChangeListener(
        new OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View view) {}

          @Override
          public void onViewDetachedFromWindow(View view) {
            destroy();
          }
        });
  }

  private String errorPage(String message) {
    return "<!doctype html><html><body style='font-family:sans-serif;padding:24px;color:#AAB6C8;"
        + "background:#0E1116'><p>"
        + message
        + "</p></body></html>";
  }

  /** 页面脚本运行前注入 dsha 门面；没有网络、没有 Java 类，只有这几个方法。 */
  private static String bridgeScript() {
    return "(function(){"
        + "if(window.dsha)return;var b=window.DshaBridge;window.dsha={"
        + "call:function(n,a){return b.call(String(n),JSON.stringify(a||{}));},"
        + "value:function(s){return b.value(String(s));},"
        + "toast:function(t){b.toast(String(t));},"
        + "copy:function(t){b.copy(String(t));},"
        + "open:function(u){b.open(String(u));},"
        + "page:function(id){b.page(String(id));},"
        + "services:function(){return b.services().split(',');},"
        + "log:function(t){console.log(String(t));}"
        + "};})();";
  }

  private final class Bridge {
    @JavascriptInterface
    public String call(String name, String argsJson) {
      return ModuleServices.call(
          getContext(), card, String.valueOf(name), String.valueOf(argsJson));
    }

    @JavascriptInterface
    public String value(String source) {
      try {
        return ModuleData.value(getContext(), String.valueOf(source));
      } catch (RuntimeException error) {
        return "";
      }
    }

    @JavascriptInterface
    public String services() {
      return String.join(",", ModuleServices.names());
    }

    @JavascriptInterface
    public void toast(String text) {
      final String message = String.valueOf(text == null ? "" : text);
      main.post(() -> Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void copy(String text) {
      final String value = String.valueOf(text == null ? "" : text);
      main.post(
          () -> {
            ClipboardManager clipboard =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("DSHA", value));
            Toast.makeText(getContext(), UiText.choose("已复制", "Copied"), Toast.LENGTH_SHORT).show();
          });
    }

    @JavascriptInterface
    public void open(String url) {
      final String target = String.valueOf(url == null ? "" : url).trim();
      if (!target.startsWith("https://") && !target.startsWith("http://")) return;
      main.post(
          () -> {
            try {
              getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            } catch (RuntimeException ignored) {
              // 没有应用能打开
            }
          });
    }

    @JavascriptInterface
    public void page(String id) {
      final String pageId = String.valueOf(id == null ? "" : id).trim();
      if (pageId.isEmpty() || !card.pages.containsKey(pageId)) return;
      main.post(() -> ModulePageActivity.open(getContext(), card, pageId));
    }

    @JavascriptInterface
    public void log(String text) {
      // 控制台由 WebView 自己保留；不把网页日志写进宿主文件。
    }
  }

  void destroy() {
    com.deepseekharness.app.module.ModuleCrashGuard.leave(card.id);
    try {
      web.removeJavascriptInterface("DshaBridge");
      web.stopLoading();
      web.loadUrl("about:blank");
      web.destroy();
    } catch (RuntimeException ignored) {
      // WebView 已随窗口销毁
    }
  }
}
