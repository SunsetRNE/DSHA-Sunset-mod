package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DshAuthUrl;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用内 Web 预览。
 *
 * <p>系统 WebView：优先用 Java 侧交换到的 dsh-auth Cookie 注入后直接加载 base URL
 * （确定性鉴权）；没拿到 Cookie 时退回直接加载鉴权链接。
 * <p>GeckoView：直接加载鉴权链接，让内核自己跟重定向 + 存 Cookie。
 */
public class WebPreviewActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "url";
    private static final String EXTRA_COOKIE = "cookie";
    /** dsh 前端需要 AbortSignal.any/timeout，低于该 Chrome 内核版本会白屏。 */
    private static final int MIN_WEBVIEW_CHROME = 118;

    private GeckoSession geckoSession;

    public static Intent intent(Context ctx, String url, String cookie) {
        return new Intent(ctx, WebPreviewActivity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_COOKIE, cookie);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_preview);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String cookie = getIntent().getStringExtra(EXTRA_COOKIE);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        FrameLayout container = findViewById(R.id.web_container);
        if (shouldUseGecko()) {
            try {
                setupGecko(container, url);
                return;
            } catch (Throwable e) {
                Log.w("DSHA", "GeckoView 启动失败，回退系统 WebView: " + e.getMessage());
            }
        }
        setupWebView(container, url, cookie);
    }

    private boolean shouldUseGecko() {
        boolean pref = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_GECKO_CORE, false);
        if (pref) return true;
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua);
            if (m.find() && Integer.parseInt(m.group(1)) < MIN_WEBVIEW_CHROME) {
                Log.w("DSHA", "系统 WebView 过旧 (Chrome/" + m.group(1)
                        + " < " + MIN_WEBVIEW_CHROME + ")，自动切换 GeckoView");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void setupWebView(FrameLayout container, String url, String cookie) {
        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setDatabaseEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        if (getSharedPreferences(Constants.PREFS, MODE_PRIVATE)
                .getBoolean(Constants.KEY_DESKTOP_MODE, false)) {
            ws.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        }
        wv.setWebViewClient(new WebViewClient());
        container.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (cookie != null && !cookie.isEmpty()) {
            // 确定性鉴权：注入 dsh-auth Cookie 后直接加载 base URL（端口跟随 dsh 实际打印的）
            String base = url.contains("?token=")
                    ? url.substring(0, url.indexOf("?token="))
                    : DshAuthUrl.LOOPBACK_BASE_URL;
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(base, cookie + "; Path=/");
            cm.flush();
            wv.loadUrl(base);
        } else {
            wv.loadUrl(url);
        }
    }

    private void setupGecko(FrameLayout container, String url) {
        GeckoRuntime runtime = GeckoRuntime.getDefault(this);
        GeckoView gv = new GeckoView(this);
        GeckoSession gs = new GeckoSession();
        gs.open(runtime);
        gv.setSession(gs);
        container.addView(gv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        geckoSession = gs;
        gs.loadUri(url);
    }

    @Override
    protected void onDestroy() {
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }
        super.onDestroy();
    }
}
