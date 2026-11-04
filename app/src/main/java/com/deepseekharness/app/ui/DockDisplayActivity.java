package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;
import com.deepseekharness.app.util.DshStageGeometry;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

/**
 * 横屏 DSH 显示（2026-10-26 重写版，直接照 b-3 Dock 的 {@code PortalActivity}）。
 *
 * <p>为什么推倒重写：旧 {@code DshDisplayActivity} 继承 {@link WebPreviewActivity}，
 * 那套横屏是在竖屏 Web 外壳上叠 {@code setupLandscapeStage} + 三档 + 胶囊 +
 * {@code landscapeResetViewport}，越打补丁越乱，键盘/滚动/视口互相打架。
 * 这里换成一个独立 Activity，只做一件事：用 16:9 桌面视口把 DSH 桌面版装进去。
 *
 * <p>从 Dock 原样带过来的成熟部分：
 * <ul>
 *   <li>{@code setupWindow()}：{@code decorFitsSystemWindows(false)} + 框架
 *       {@code InsetsController.hide(status|nav)}，{@code onResume} / 窗口焦点回来再 hide；
 *   <li>{@code VirtualScreen}：WebView 布局 = 模式分辨率 × density，再整体 setScale 压进 16:9；
 *   <li>{@code PortalWebView}：整页拖不动的三层兜底（scroll-lock.js + 停手 300ms 复位 +
 *       键盘态({@code lastIme})不复位，收起 150ms 后复位一次）；
 *   <li>左侧滚动条 / 三档按钮 / 右侧胶囊的几何与样式；
 *   <li>{@code PixelCopy} 截图（{@code web.draw()} 会得到白图）。
 * </ul>
 *
 * <p>按 DSHA-Mod 的约定改了这些：
 * <ul>
 *   <li>胶囊四键：{@code ←} 网页后退 / {@code ⌂} 回启动页 / {@code ↻} 刷新 / {@code ⌸} 截图；
 *   <li>后退到网页根只 Toast「已在页面首页」，不退出；退出走 {@code ⌂}；
 *   <li>截图只截 16:9 视口，存 {@code Pictures/DSHA-Mod/}；
 *   <li>401：DSH 登录链接失效 → Toast + 关闭，不崩；
 *   <li>留边维持 Dock 原样的雾白 {@code #EEF1F6}，不叠自定义背景（用户明确要求横屏不动）。
 * </ul>
 */
public class DockDisplayActivity extends AppCompatActivity {

  private static final String TAG = "DockDisplay";
  public static final String EXTRA_URL = "url";

  /** 标记这是 DSH 页面：401 时提示重新进入，而不是静默白屏。 */
  public static final String EXTRA_DSH = "dsh";

  private static final int REQ_FILE = 2001;

  /**
   * 三档纯放大（2026-11-03 重设计，按分辨率而不是按"缩放阈值"）：
   *
   * <p>1280×720（×1.00）/ 1024×576（×1.25）/ 854×480（×1.50），三档都保持 16:9，
   * 所以每一档都铺满舞台，差别只有"CSS 视口多窄 → 字多大"。
   * 倍率 = 1280 / 宽度，与屏幕密度无关（密度在缩放里约掉了）。
   *
   * <p>档位定义在 {@link com.deepseekharness.app.util.DshStageGeometry}，这里只留按钮文案。
   */
  private static final String[] MODE_LABELS = {"模式1", "模式2", "模式3"};

  /** 210：三档 chip 的最小左边距（原来 10dp，去掉安全区 padding 后显得贴边）。 */
  private static final int MODE_MIN_LEFT_DP = 24;

  private static final int DOCK_ITEM_BIG = 48;

  private WebView web;

  /** 横屏外观运行时口子：默认档 / 胶囊位置 / 控件显隐 / UA / 手势锁（只读私有目录的数据）。 */
  private LandscapeConfig cfg;

  /** 与竖屏一致的 document-start 兼容脚本句柄（语言/主题/旧内核兼容/OAuth 标记）。 */
  private androidx.webkit.ScriptHandler compatibilityScript;

  /** WebMessageListener 是否可用（且 origin 可校验）：不可用时滚动条与 window.open 走降级路径。 */
  private boolean bridgeAvailable;

  /** 上一次应用到三档 chip 的字号（sp），避免每帧都 setTextSize。 */
  private float lastChipTextSp = -1f;

  /** 上一次读到的 defaultMode：只有它变化时才重置用户手动选的档位。 */
  private int lastConfiguredDefaultMode = -1;

  /** 下载与竖屏同一套引擎：cookie、进度、SAF 目标目录都复用。 */
  private WebDownloads downloads;

  private WebBlobDownload blobDownload;

  /** WebView 默认（手机）UA：配置里 desktopUa=false 时退回它。 */
  private String defaultUa = "";

  private String authUrl = "";
  private String baseUrl = "";
  private ValueCallback<Uri[]> fileCb;
  private TextView loadingView;
  private View scrollThumb;
  private View scrollTrack;
  private View scrollHot;
  private boolean draggingBar;
  private int dragPad, dragAvail, dragThumbH;
  private boolean dshPage;
  private boolean progressInjected;

  /** window.open 拦截脚本：旧 WebView 不支持文档起始注入时，在页面里再补一次（幂等）。 */
  private String openInterceptorJs = "";

  /** 是否已经成功装载过 DSH 自己的页面：用来判断 about:blank 是「初始帧」还是「白屏」。 */
  private boolean dshLoaded;

  private int mode = 0;
  private volatile boolean docNotScrollable;

  /** 连续弹回保护：10 秒窗口内最多自动回跳 3 次，超过就停手。 */
  private static final long BOUNCE_WINDOW_MS = 10_000L;

  private static final int MAX_BOUNCES = 3;

  private long lastBounceAt = 0L;
  private int bounceCount = 0;
  private int lastIme;

  /**
   * 真实满屏高度（显示器短边，px）：用来判断「键盘把窗口压矮」还是「ROM 根本不压窗口」。
   *
   * <p>以前用「上一次无键盘时的窗口高度 baseH」判断，隐患是有的 ROM 先 resize 窗口、
   * ime insets 后到 —— 那一帧 ime 还是 0，baseH 就被写成了压矮后的高度。
   * 直接读显示器尺寸没有这个问题，旋转时刷新一次即可。
   */
  private int fullScreenH;

  /** 键盘 insets 稳定后统一重算一次：弹出和收起都要，不能只做收起。 */
  private final Runnable imeSettle = this::settleIme;

  private void settleIme() {
    if (screen != null) screen.reapply();
    // 键盘态再等等布局落定，然后看一眼页面是不是被浏览器自动滚了。
    // 226：页面已经 reflow 到和视口等高（docH <= innerH）时，任何 scrollY>0 都只是
    // 「聚焦把内容顶上去」，直接归零，避免用户看到「整页向上」。
    ui.postDelayed(this::tidyPageScroll, 260);
  }

  /**
   * 只读一次页面滚动状态：够用就归零，并写进 kbd-debug 供下次验证。
   *
   * <p><b>不能只查键盘态</b>：236/234 回滚到「228 原版」时把这里改回 {@code lastIme <= 0}
   * 直接 return，于是「键盘收起后」不再复查 —— 收起时那一下焦点回弹留下的滚动就留在页面上，
   * 下次进入/再弹键盘才被清掉（用户看到的是「收起后页面停在半截」）。230 已经验证过：
   * 收起也复查一次，代价只是一次 evaluateJavascript。
   */
  private void tidyPageScroll() {
    if (web == null) return;
    try {
      web.evaluateJavascript(
          "(function(){try{return [window.scrollY|0,"
              + "document.documentElement.scrollHeight|0,window.innerHeight|0].join(',');}"
              + "catch(e){return '0,0,0';}})()",
          value -> {
            try {
              String raw = value == null ? "" : value.replace("\"", "").trim();
              String[] part = raw.split(",");
              int y = Integer.parseInt(part[0].trim());
              int docH = Integer.parseInt(part[1].trim());
              int ih = Integer.parseInt(part[2].trim());
              kbdLog("page y=" + y + " docH=" + docH + " ih=" + ih);
              // 页面本身不需要滚动 → 标记为「不可滚」，让 onScrollChanged 的实时归零生效
              // （键盘态聚焦时 WebView 会自己带一下焦点，那一下就是「界面被抬升」）。
              if (docH <= ih + 2) docNotScrollable = true;
              if (y > 0 && docH <= ih + 2) {
                try {
                  web.scrollTo(0, 0);
                  web.setScrollY(0);
                } catch (Throwable ignored) {
                }
                kbdLog("page scroll reset -> 0");
              }
            } catch (Throwable ignored) {
            }
          });
    } catch (Throwable ignored) {
    }
  }

  /**
   * 键盘态几何诊断：仅在 debug 构建写应用私有目录 files/ui-probe/kbd-debug.txt。
   *
   * <p>268 之后的键盘问题全靠这几行定位（`ime/fullH/sc/webH/top/visTop/shellH/page`），
   * 所以**保留**，别当临时代码删掉。文件每次键盘事件追加一行，量很小；release 包不写。
   */
  private void kbdLog(String line) {
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0)
      return;
    try {
      java.io.File dir = new java.io.File(getFilesDir(), "ui-probe");
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      java.io.File log = new java.io.File(dir, "kbd-debug.txt");
      // 别让它无限长：超过 64KB 就丢掉旧的重新写（诊断只关心最近几次）。
      if (log.isFile() && log.length() > 64L * 1024L) log.delete();
      String stamp =
          new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
              .format(new java.util.Date());
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
        out.write((stamp + " " + line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    } catch (Throwable ignored) {
    }
  }

  /** 键盘态保持的缩放值：窗口变矮时不重缩，页面才不会「跳」。 */
  private float lastScale;

  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  private final TextView[] modeBtns = new TextView[MODE_LABELS.length];
  private VirtualScreen screen;

  /** WebView 的舞台壳：位置和高度都在它的 LayoutParams 里，避免「平移先到、布局后到」的错位。 */
  private FrameLayout stageShell;

  private LinearLayout dock;
  private long lastModeSwitchAt = 0L;

  public static Intent intent(Context context, String url) {
    return new Intent(context, DockDisplayActivity.class)
        .putExtra(EXTRA_URL, url)
        .putExtra(EXTRA_DSH, true)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Intent it = getIntent();
    String url = it == null ? null : it.getStringExtra(EXTRA_URL);
    dshPage = it == null || it.getBooleanExtra(EXTRA_DSH, true);
    authUrl = url == null ? "" : url;
    baseUrl =
        authUrl.isEmpty()
            ? ""
            : com.deepseekharness.app.util.WebPreviewPolicy.loopbackBaseUrl(authUrl);
    // 横屏外观运行时口子：只读应用私有目录里的数据开关，不执行磁盘上的代码。
    cfg = LandscapeConfig.load(this);
    downloads = new WebDownloads(this, savedInstanceState);
    mode = DshStageGeometry.clampMode(cfg.defaultMode);
    lastConfiguredDefaultMode = cfg.defaultMode;

    try {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    } catch (Throwable ignored) {
    }
    setupWindow();

    web = new PortalWebView(this);
    WebSettings s = web.getSettings();
    defaultUa = s.getUserAgentString();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(true);
    // 与竖屏 WebPreviewActivity 对齐：不允许脚本自动开窗、媒体播放需手势。
    s.setJavaScriptCanOpenWindowsAutomatically(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setMinimumFontSize(1);
    s.setTextZoom(100);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
    if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

    // 电脑内核：1280 视口 + 桌面 UA。
    // useWideViewPort 必须关：开着时 WebView 会按「移动视口」规则处理 —— 页面若没写
    // viewport meta 就用 980px 兜底布局，和「电脑 UA + 1280 桌面视口」直接错位，
    // 表现就是整页比视口宽、只能整体滚。DSH 自己写了 width=device-width，
    // 关掉后布局宽度 = WebView 的 CSS 宽度 = 1280，和桌面一致。
    // 桌面 UA 默认开；配置里 desktopUa=false 时退回 WebView 默认（手机）UA。
    s.setUserAgentString(userAgentFor(cfg.desktopUa, defaultUa));
    // 与竖屏 WebPreviewActivity 一致：**不开多窗口**。
    // 开了之后 target=_blank / window.open 会走 WebChromeClient.onCreateWindow，
    // 谁没接住，页面就停在一个「新窗口已请求但没人创建」的状态 —— 真机上表现为
    // 点登录之后整个视口变白（登录页根本没打开）。这里显式关掉，再由下面
    // 的 JS 兜底把 window.open 交系统浏览器，双保险。
    s.setSupportMultipleWindows(false);
    s.setUseWideViewPort(false);
    s.setLoadWithOverviewMode(false);
    s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
    s.setSupportZoom(false);
    s.setBuiltInZoomControls(false);
    s.setDisplayZoomControls(false);
    try {
      if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);
      if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
    } catch (Throwable ignored) {
    }

    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, false);

    attachWebMessageBridge();
    installCompatibilityScripts();
    web.setBackgroundColor(Color.WHITE);
    // 244：舞台壳也要有底色（和页面一色，见 syncPageBackground）。
    // 键盘收起/弹起时页面高度变化，中间那一帧若 WebView 还没重绘，露出来的就是父容器底色；
    // 舞台壳原本没有背景 → 露出深色舞台壁纸 → 「一帧黑色」。
    if (stageShell != null) stageShell.setBackgroundColor(Color.WHITE);
    // 下载复用竖屏的引擎：带 cookie 与进度，blob:/data: 走 WebBlobDownload。
    web.setDownloadListener(
        (downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
          if (web == null || !onDshOrigin()) return;
          String name =
              android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType);
          if (downloadUrl.startsWith("blob:") || downloadUrl.startsWith("data:")) {
            if (blobDownload == null) blobDownload = new WebBlobDownload(web, downloads.model);
            blobDownload.start(baseUrl, downloadUrl, name);
            return;
          }
          downloads.start(
              baseUrl,
              downloadUrl,
              CookieManager.getInstance().getCookie(downloadUrl),
              name,
              contentLength,
              null);
        });

    installOpenInterceptor();
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setVerticalScrollBarEnabled(false);
    web.setHorizontalScrollBarEnabled(false);

    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl() == null ? "" : request.getUrl().toString();
            // 子框架只能加载网页资源；intent/深链/其它外部 scheme 一律拒绝（防止 iframe 拉起组件）。
            if (!request.isForMainFrame()) {
              return isIntentScheme(url) || !isWebViewScheme(schemeOf(url));
            }
            return handleMainFrameUrl(url, request.hasGesture(), view.getUrl());
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String target) {
            // 旧回调拿不到 hasGesture：竖屏在这一条路上也是一律交给系统，保持一致。
            return handleMainFrameUrl(target, true, view.getUrl());
          }

          @Override
          public void onPageStarted(WebView view, String pageUrl, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, pageUrl, favicon);
            // 兜底：这个 WebView 只允许装 DSH 自己的页面。任何非本机地址真的进来了
            // （登录页/外链绕过了 shouldOverrideUrlLoading 的某条路径），立刻退回 DSH，
            // 别把 16:9 视口停在一片白屏上。
            if (bounceForeignPage(view, pageUrl)) return;
            progressInjected = false;
            draggingBar = false;
            docNotScrollable = false;
            if (scrollThumb != null) scrollThumb.setVisibility(View.GONE);
          }

          @Override
          public void onPageFinished(WebView view, String pageUrl) {
            if (bounceForeignPage(view, pageUrl)) return;
            // document-start 不支持/注册失败时，至少在页面完成时补一次兼容脚本（照竖屏回退）。
            if (compatibilityScript == null) {
              try {
                view.evaluateJavascript(compatibilityScript(DockDisplayActivity.this), null);
              } catch (Throwable ignored) {
              }
            }
            onDshPageFinished(view, pageUrl);
          }

          @Override
          public void onPageCommitVisible(WebView view, String pageUrl) {
            super.onPageCommitVisible(view, pageUrl);
            injectScripts();
          }

          @Override
          public void onReceivedHttpError(
              WebView view,
              WebResourceRequest request,
              android.webkit.WebResourceResponse response) {
            if (dshPage
                && request != null
                && request.isForMainFrame()
                && response != null
                && response.getStatusCode() == 401) {
              toast("DSH 登录链接已失效，回启动页重新进入");
              finish();
            }
          }

          @Override
          public void onReceivedError(
              WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
              hideLoading();
              injectScripts();
              toast("页面加载失败，检查一下网络");
            }
          }

          @androidx.annotation.RequiresApi(26)
          @Override
          public boolean onRenderProcessGone(
              WebView view, android.webkit.RenderProcessGoneDetail detail) {
            Log.w(TAG, "onRenderProcessGone(didCrash=" + detail.didCrash() + ")");
            releaseWebSurface();
            cleanupDownloadsAndPickers();
            toast("网页渲染进程已退出");
            finish();
            return true;
          }
        });

    web.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onProgressChanged(WebView view, int newProgress) {
            if (loadingView != null && loadingView.getVisibility() == View.VISIBLE) {
              if (newProgress >= 100) hideLoading();
              else
                loadingView.setText(
                    com.deepseekharness.app.util.UiText.format("正在打开 %d%%", newProgress));
            }
            // 进度 8% 时 head/body 已经有了，这时就装脚本，别等加载尾巴上被滑走。
            if (newProgress >= 8 && !progressInjected) {
              progressInjected = true;
              injectScripts();
            }
          }

          @Override
          public void onPermissionRequest(final android.webkit.PermissionRequest request) {
            // 语音输入这类音频请求：只有 DSH 本机页面 + 已授予 RECORD_AUDIO 才放行。
            // 先校验请求来源：WebView 的麦克风授权对所有 frame 生效，跨源 iframe 也必须拒绝。
            android.net.Uri source = null;
            try {
              source = request.getOrigin();
            } catch (Throwable ignored) {
            }
            if (!allowDshOrigin(baseUrl, source, web == null ? null : web.getUrl())) {
              try {
                request.deny();
              } catch (Throwable ignored) {
              }
              return;
            }
            runOnUiThread(
                () -> {
                  try {
                    boolean audio = false;
                    for (String resource : request.getResources()) {
                      if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(
                          resource)) {
                        audio = true;
                      }
                    }
                    boolean mic =
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                    if (audio && mic) {
                      request.grant(
                          new String[] {android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                      request.deny();
                      toast("横屏暂不能申请麦克风，可回竖屏页面使用语音");
                    }
                  } catch (Throwable error) {
                    try {
                      request.deny();
                    } catch (Throwable ignored) {
                    }
                  }
                });
          }

          @Override
          public boolean onCreateWindow(
              WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            // 兜底：某些 WebView 实现即使关了多窗口也会把 window.open 送过来。
            // 用一个一次性的隐形 WebView 接住首个 URL，立刻交系统浏览器并销毁。
            if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
              return false;
            }
            final WebView probe = new WebView(DockDisplayActivity.this);
            probe.getSettings().setJavaScriptEnabled(false);
            final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            probe.setWebViewClient(
                new WebViewClient() {
                  private void destroyQuietly() {
                    if (done.getAndSet(true)) return;
                    try {
                      probe.destroy();
                    } catch (Throwable ignored) {
                    }
                  }

                  private void forward(String url) {
                    if (done.get()) return;
                    openExternal(url, false);
                    // 不在 WebViewClient 回调里直接 destroy 自己：推到下一帧。
                    ui.post(this::destroyQuietly);
                  }

                  @Override
                  public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    forward(request.getUrl() == null ? "" : request.getUrl().toString());
                    return true;
                  }

                  @Override
                  public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    forward(url);
                    return true;
                  }

                  @Override
                  public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    if (url != null && !url.isEmpty() && !"about:blank".equals(url)) forward(url);
                  }
                });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            // 兜底：个别页面只建窗口、从不导航 —— 假窗口永远拿不到 URL，8 秒后强制回收。
            ui.postDelayed(
                () -> {
                  if (done.getAndSet(true)) return;
                  try {
                    probe.destroy();
                  } catch (Throwable ignored) {
                  }
                },
                8000);
            return true;
          }

          @Override
          public void onShowCustomView(View view, CustomViewCallback callback) {
            // 网页要求全屏播放：桌面视口里没有承载层，直接收回去并说明，避免黑屏卡住。
            try {
              callback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            toast("横屏里暂不支持网页全屏播放，可回竖屏再看");
          }

          @Override
          public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            if (m != null
                && (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                    || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)) {
              Log.w(
                  TAG,
                  "[js:"
                      + m.messageLevel()
                      + "] "
                      + m.message()
                      + " @"
                      + m.sourceId()
                      + ":"
                      + m.lineNumber());
            }
            return true;
          }

          @Override
          public boolean onJsAlert(
              WebView view, String url, String message, final android.webkit.JsResult result) {
            new DshaDialogBuilder(DockDisplayActivity.this)
                .setTitle(com.deepseekharness.app.util.UiText.text("页面提示"))
                .setMessage(message)
                .setPositiveButton(
                    com.deepseekharness.app.util.UiText.text("知道了"), (d, w) -> result.confirm())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          @Override
          public boolean onJsConfirm(
              WebView view, String url, String message, final android.webkit.JsResult result) {
            new DshaDialogBuilder(DockDisplayActivity.this)
                .setTitle(com.deepseekharness.app.util.UiText.text("页面询问"))
                .setMessage(message)
                .setPositiveButton(
                    com.deepseekharness.app.util.UiText.text("确定"), (d, w) -> result.confirm())
                .setNegativeButton(
                    com.deepseekharness.app.util.UiText.text("取消"), (d, w) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          @Override
          public boolean onJsPrompt(
              WebView view,
              String url,
              String message,
              String defaultValue,
              final android.webkit.JsPromptResult result) {
            final android.widget.EditText input =
                new android.widget.EditText(DockDisplayActivity.this);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSelection(input.getText().length());
            new DshaDialogBuilder(DockDisplayActivity.this)
                .setTitle(com.deepseekharness.app.util.UiText.text("页面输入"))
                .setMessage(message)
                .setView(input)
                .setPositiveButton(
                    com.deepseekharness.app.util.UiText.text("确定"),
                    (d, w) -> result.confirm(input.getText().toString()))
                .setNegativeButton(
                    com.deepseekharness.app.util.UiText.text("取消"), (d, w) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          @Override
          public boolean onShowFileChooser(
              WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCb != null) {
              try {
                fileCb.onReceiveValue(null);
              } catch (Exception ignored) {
              }
            }
            fileCb = cb;
            try {
              startActivityForResult(params.createIntent(), REQ_FILE);
              return true;
            } catch (Exception error) {
              fileCb = null;
              return false;
            }
          }
        });

    buildImmersivePanel();
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                goBack();
              }
            });

    try {
      View deco = getWindow().getDecorView();
      deco.setAlpha(0f);
      deco.animate().alpha(1f).setDuration(160).start();
    } catch (Throwable ignored) {
    }

    if (!authUrl.isEmpty()) loadAuthenticated();
    else {
      toast("链接为空");
      finish();
    }
  }

  /**
   * 鉴权照 DSHA 自己的做法：先用 token URL 换一次 dsh-auth-* cookie，设到 loopback 上，
   * 再加载不带 token 的 baseUrl。直接把带 token 的地址塞给 WebView 也能进去，但
   * token 会留在历史里，且部分 ROM 的 CookieManager 时序会让首屏偶发 401。
   */
  private void loadAuthenticated() {
    if (baseUrl == null || baseUrl.isEmpty() || authUrl == null || authUrl.isEmpty()) {
      Log.e(TAG, "缺少本机服务地址，无法进入 Dock");
      toast("本机 DSH 服务未就绪，请回启动页重试");
      finish();
      return;
    }
    showLoading();
    final com.deepseekharness.app.core.HarnessController controller =
        com.deepseekharness.app.core.HarnessController.get(this);
    new Thread(
            () -> {
              String cookie = null;
              String failure = null;
              try {
                cookie = controller.exchangeDshAuthCookie();
                if (cookie == null) failure = controller.getWebAuthFailure();
              } catch (RuntimeException error) {
                failure = "无法验证 Web 访问权限，请稍后重试";
              }
              final String readyCookie = cookie;
              final String readyFailure = failure;
              runOnUiThread(
                  () -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (web == null) return;
                    if (readyCookie == null) {
                      hideLoading();
                      toast(readyFailure == null ? "Web 尚未就绪，请回启动页看看服务状态" : readyFailure);
                      finish();
                      return;
                    }
                    CookieManager cookies = CookieManager.getInstance();
                    cookies.setAcceptCookie(true);
                    cookies.setCookie(
                        baseUrl,
                        readyCookie + "; Path=/; HttpOnly; SameSite=Strict",
                        ok -> {
                          if (web == null) return;
                          web.loadUrl(Boolean.TRUE.equals(ok) ? baseUrl : authUrl);
                        });
                  });
            },
            "dock-auth")
        .start();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
    if (url == null || url.isEmpty() || web == null) return;
    authUrl = url;
    baseUrl = com.deepseekharness.app.util.WebPreviewPolicy.loopbackBaseUrl(url);
    // 重新进入也按约定重读配置，并重新应用 UA 等 WebSettings（与胶囊 ↻ 同一条路径）。
    applyLandscapeConfig();
    loadAuthenticated();
  }

  /** 沉浸式：全屏里画一块 16:9 的「电脑屏」，视口外铺自绘的深色渐变。 */
  private void buildImmersivePanel() {
    screen = new VirtualScreen(this);
    final VirtualScreen root = screen;
    root.setBackgroundColor(LandscapePalette.STAGE);
    ImageView stageBg = new ImageView(this);
    stageBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
    stageBg.setBackgroundColor(LandscapePalette.STAGE);
    // 212：深色舞台底（代码生成渐变，不依赖任何第三方图片资产）。
    stageBg.setImageDrawable(stageBackdrop());
    stageBg.setScaleType(ImageView.ScaleType.FIT_XY);
    root.addView(
        stageBg,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.target = web;
    // 227：以前 WebView 直接挂在 VirtualScreen 上，高度靠 LayoutParams、位置靠 translation，
    // 两者生效时机不同 —— 真机上就出现过「高度还是 2520、平移已经 -386」，页面整体被推上去。
    // 现在用一个舞台壳：宽高和位置**同一次 layout** 落定，缩放以顶部为轴，视觉位置就等于 topMargin。
    stageShell = new FrameLayout(this);
    stageShell.setClipChildren(false);
    // 244：先给个「和 WebView 同色」的底色，页面一加载完就会被 syncPageBackground 换成页面真实底色。
    stageShell.setBackgroundColor(Color.WHITE);
    stageShell.addView(
        web,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    root.addView(
        stageShell,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.TOP));

    loadingView = new TextView(this);
    loadingView.setText(com.deepseekharness.app.util.UiText.text("正在打开…"));
    loadingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    loadingView.setTextColor(LandscapePalette.DOCK_ICON);
    loadingView.setGravity(Gravity.CENTER);
    loadingView.setBackgroundColor(LandscapePalette.LOADING_BG);
    loadingView.setClickable(true);
    root.addView(
        loadingView,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    dock = new LinearLayout(this);
    dock.setOrientation(LinearLayout.VERTICAL);
    dock.setGravity(Gravity.CENTER);
    dock.setPadding(dp(4), dp(4), dp(4), dp(4));
    // 212：深色科幻底板（四角括号 + 冷色描边），和默认舞台壁纸、三档 C 款按钮同一套语言。
    dock.setBackground(new SciFiPanelDrawable(getResources().getDisplayMetrics().density));
    dock.setElevation(dp(0));

    ImageView back = dockIcon(R.drawable.ic_ui_back, "网页后退");
    back.setOnClickListener(v -> goBack());
    dock.addView(back, new LinearLayout.LayoutParams(dp(DOCK_ITEM_BIG), dp(DOCK_ITEM_BIG)));

    ImageView home = dockIcon(R.drawable.ic_ui_home, "回启动页");
    home.setOnClickListener(v -> goHome());
    LinearLayout.LayoutParams hlp =
        new LinearLayout.LayoutParams(dp(DOCK_ITEM_BIG), dp(DOCK_ITEM_BIG));
    hlp.topMargin = dp(2);
    dock.addView(home, hlp);

    ImageView refresh = dockIcon(R.drawable.ic_ui_refresh, "刷新");
    refresh.setOnClickListener(v -> reloadWeb());
    LinearLayout.LayoutParams rlp =
        new LinearLayout.LayoutParams(dp(DOCK_ITEM_BIG), dp(DOCK_ITEM_BIG));
    rlp.topMargin = dp(2);
    dock.addView(refresh, rlp);

    ImageView shot = dockIcon(R.drawable.ic_ui_camera, "截取 16:9 视口（长按复制视口诊断）");
    shot.setOnClickListener(v -> saveScreenshot());
    shot.setOnLongClickListener(
        v -> {
          copyDiagnostics();
          return true;
        });
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(dp(DOCK_ITEM_BIG), dp(DOCK_ITEM_BIG));
    slp.topMargin = dp(2);
    dock.addView(shot, slp);

    FrameLayout.LayoutParams dlp =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END | Gravity.CENTER_VERTICAL);
    dlp.rightMargin = dp(18);
    root.addView(dock, dlp);

    scrollTrack = new View(this);
    try {
      android.graphics.drawable.GradientDrawable gdT =
          new android.graphics.drawable.GradientDrawable();
      gdT.setColor(LandscapePalette.SCROLL_TRACK);
      gdT.setCornerRadius(dp(4));
      scrollTrack.setBackground(gdT);
    } catch (Throwable ignored) {
    }
    FrameLayout.LayoutParams trackLp =
        new FrameLayout.LayoutParams(
            dp(6), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP);
    trackLp.leftMargin = dp(9);
    trackLp.topMargin = dp(14);
    trackLp.bottomMargin = dp(14);
    root.addView(scrollTrack, trackLp);

    scrollThumb = new View(this);
    try {
      android.graphics.drawable.GradientDrawable gd =
          new android.graphics.drawable.GradientDrawable();
      gd.setColor(LandscapePalette.SCROLL_THUMB);
      gd.setCornerRadius(dp(3));
      scrollThumb.setBackground(gd);
    } catch (Throwable ignored) {
    }
    scrollThumb.setVisibility(View.GONE);
    FrameLayout.LayoutParams thumbLp =
        new FrameLayout.LayoutParams(dp(6), dp(80), Gravity.START | Gravity.TOP);
    thumbLp.leftMargin = dp(9);
    thumbLp.topMargin = dp(14);
    root.addView(scrollThumb, thumbLp);

    scrollHot = new View(this);
    scrollHot.setBackgroundColor(Color.TRANSPARENT);
    FrameLayout.LayoutParams hotLp =
        new FrameLayout.LayoutParams(
            dp(48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP);
    hotLp.leftMargin = dp(9) - dp(22);
    hotLp.topMargin = dp(14);
    hotLp.bottomMargin = dp(14);
    root.addView(scrollHot, hotLp);
    scrollHot.setOnTouchListener(
        (v, ev) -> {
          switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              if (scrollThumb == null || scrollThumb.getVisibility() != View.VISIBLE) {
                return false;
              }
              draggingBar = true;
              measureBar();
              seekTo(fracForY(ev.getY()));
              return true;
            case MotionEvent.ACTION_MOVE:
              if (draggingBar) {
                measureBar();
                seekTo(fracForY(ev.getY()));
              }
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              if (draggingBar) {
                measureBar();
                seekTo(fracForY(ev.getY()));
              }
              draggingBar = false;
              return true;
            default:
              return false;
          }
        });

    buildModeButtons(root);

    try {
      android.util.DisplayMetrics dm = readRealMetrics();
      root.apply(
          Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));
    } catch (Throwable ignored) {
    }

    setContentView(root);
    if (Build.VERSION.SDK_INT >= 30) {
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            if (ime != lastIme) {
              boolean closing = lastIme > 0 && ime == 0;
              lastIme = ime;
              // 221/223：**弹出和收起都要重算**。以前只在收起时 reapply，
              // 弹出时全靠窗口 resize 顺手触发；ROM 不 resize 就永远不 reflow，
              // 视口不变矮 → 输入框不跟键盘 → 浏览器只好把整页滚上去。
              ui.removeCallbacks(imeSettle);
              // 244：收起时**立刻**还原几何（延迟 0）。原来延迟 150ms 的那段里，
              // 键盘已经滑走、舞台还停在「压扁」的高度 —— 屏幕底部露出来的就是舞台底色
              // （深色壁纸），真机上看到的就是「收起键盘有一帧黑色」。
              // 现在即时长回满高：新露出的那截还在键盘后面，等键盘走完，页面已经填好了。
              ui.postDelayed(imeSettle, closing ? 0 : 120);
            }
            return insets;
          });
    }
  }

  /**
   * 桌面视口：WebView 内部按模式分辨率铺开，再整体缩放进 16:9 区域。
   * 比例不同时取 min 缩放并居中（模式2 拉长、模式3 缩短都靠这里）。
   */
  private class VirtualScreen extends FrameLayout {
    WebView target;
    private int lastW, lastH;
    int viewTop, viewH, viewLeft, viewW;

    VirtualScreen(Context c) {
      super(c);
      setClipChildren(false);
    }

    private int pendingW, pendingH;
    private final Runnable pendingApply =
        () -> {
          if (target != null && pendingW > 0 && pendingH > 0) apply(pendingW, pendingH);
        };

    @Override
    protected void onSizeChanged(final int w, final int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);
      if (target == null || w <= 0 || h <= 0) return;
      pendingW = w;
      pendingH = h;
      // 键盘动画期间窗口会连续 resize；合并到一帧再 apply，避免每帧都改 WebView 高度
      // 导致网页反复重排（那是「反复输入框之后卡/错位」的来源）。
      removeCallbacks(pendingApply);
      postDelayed(pendingApply, 16);
    }

    /** displayCutout 安全区（px）：{left, top, right, bottom}；低版本或无挖孔返回 0。 */
    private int[] cutoutSafeInsets() {
      try {
        if (Build.VERSION.SDK_INT >= 28) {
          android.view.WindowInsets insets = getRootWindowInsets();
          android.view.DisplayCutout cut = insets == null ? null : insets.getDisplayCutout();
          if (cut != null) {
            return new int[] {
              cut.getSafeInsetLeft(),
              cut.getSafeInsetTop(),
              cut.getSafeInsetRight(),
              cut.getSafeInsetBottom()
            };
          }
        }
      } catch (Throwable ignored) {
      }
      return new int[] {0, 0, 0, 0};
    }

    void reapply() {
      if (lastW > 0 && lastH > 0) apply(lastW, lastH);
    }

    void apply(int w, int h) {
      if (target == null) return;
      lastW = w;
      lastH = h;
      float d = getResources().getDisplayMetrics().density;
      // 键盘态判断用「实时 ime insets」而不是只看 lastIme：键盘把窗口变矮时
      // onSizeChanged 可能先到，此时上一帧记录的 lastIme 还是 0，只看它就会在那一帧
      // 重算 scale，整屏缩一下（真机「键盘一弹网页就收缩」的根因之一）。
      int ime = Math.max(lastIme, imeBottom());
      // 消费 displayCutout 安全区：舞台与控件都排进安全区内，不压挖孔/瀑布边。
      int[] safe = cutoutSafeInsets();
      int[] area = insetArea(w, h, safe[0], safe[1], safe[2], safe[3]);
      // 布局算法只有一个实现：DshStageGeometry.compute（纯函数、有边界单测）。
      DshStageGeometry.Stage stage =
          DshStageGeometry.compute(
              mode,
              d,
              area[0],
              area[1],
              ime,
              Math.max(0, fullScreenH - safe[1] - safe[3]),
              lastScale);
      if (stage == null) return;
      int ww = stage.layoutW;
      int webH = stage.stageH;
      float sc = stage.scale;
      lastScale = sc;
      viewTop = stage.top + area[3];
      viewH = stage.viewH;
      viewW = stage.viewW;
      viewLeft = stage.left + area[2];

      // 舞台壳：宽高 + 位置写在同一个 LayoutParams 里（Gravity.TOP + topMargin），
      // 缩放以顶部为轴（pivotY=0）→ 视觉顶部 === topMargin，不再依赖 translation 的生效时机。
      if (stageShell != null) {
        FrameLayout.LayoutParams lp =
            stageShell.getLayoutParams() instanceof FrameLayout.LayoutParams current
                ? current
                : new FrameLayout.LayoutParams(ww, webH, Gravity.TOP);
        int left = Math.round((area[0] - ww) / 2f) + area[2];
        // 值没变就别 setLayoutParams：键盘动画期间 apply 会跑十几次，
        // 每次都 set 等于每次都 requestLayout（和 208 修过的「逐帧 setPadding」同一类病）。
        // 230 修过；234 回滚到 228 时丢了，237 补回来。
        if (lp.width != ww
            || lp.height != webH
            || lp.gravity != Gravity.TOP
            || lp.leftMargin != left
            || lp.topMargin != viewTop) {
          lp.width = ww;
          lp.height = webH;
          lp.gravity = Gravity.TOP;
          lp.leftMargin = left;
          lp.topMargin = viewTop;
          stageShell.setLayoutParams(lp);
        }
        // 与上面的 setLayoutParams 同一原则：值没变就不碰属性，键盘动画期间
        // 每帧 set 一次 scale 也会反复重排合成层。
        if (stageShell.getPivotX() != ww / 2f) stageShell.setPivotX(ww / 2f);
        if (stageShell.getPivotY() != 0f) stageShell.setPivotY(0f);
        if (Math.abs(stageShell.getScaleX() - sc) > 0.0001f
            || Math.abs(stageShell.getScaleY() - sc) > 0.0001f) {
          stageShell.setScaleX(sc);
          stageShell.setScaleY(sc);
        }
        if (stageShell.getTranslationY() != 0f) stageShell.setTranslationY(0f);
      }
      if (ime > 0) {
        // 自证：下一帧量一次真实屏幕位置（含缩放/平移），写进 kbd-debug.txt。
        // visTop 应该等于 top（0 = 页面顶到键盘上沿）。
        final float expectTop = viewTop;
        final View probeView = (stageShell != null) ? stageShell : target;
        probeView.postOnAnimation(
            () ->
                probeView.postOnAnimation(
                    () -> {
                      try {
                        int[] loc = new int[2];
                        probeView.getLocationOnScreen(loc);
                        kbdLog(
                            "visTop="
                                + loc[1]
                                + " expect="
                                + Math.round(expectTop)
                                + " shellH="
                                + probeView.getHeight()
                                + " webH="
                                + webH
                                + " sc="
                                + sc);
                      } catch (Throwable ignored) {
                      }
                    }));
      }

      if (ime > 0) {
        kbdLog(
            "ime="
                + ime
                + " h="
                + h
                + " fullH="
                + fullScreenH
                + " sc="
                + sc
                + " webH="
                + webH
                + " viewH="
                + viewH
                + " top="
                + viewTop);
      }

      int pad = dp(14);
      int barX = Math.max(dp(4), viewLeft - dp(12));
      if (scrollTrack != null) {
        FrameLayout.LayoutParams lpTrack = (FrameLayout.LayoutParams) scrollTrack.getLayoutParams();
        int trackH = Math.max(0, viewH - pad * 2);
        // 高度跟着视口走，不是跟着窗口：键盘状态下窗口仍是满高，用 MATCH_PARENT
        // 会把轨道画到键盘底下去，滑块位置也就对不上了。
        if (lpTrack.leftMargin != barX
            || lpTrack.topMargin != viewTop + pad
            || lpTrack.height != trackH
            || lpTrack.bottomMargin != 0) {
          lpTrack.leftMargin = barX;
          lpTrack.topMargin = viewTop + pad;
          lpTrack.height = trackH;
          lpTrack.bottomMargin = 0;
          scrollTrack.setLayoutParams(lpTrack);
        }
        int trackVis = (cfg.chrome && bridgeAvailable) ? View.VISIBLE : View.GONE;
        if (scrollTrack.getVisibility() != trackVis) scrollTrack.setVisibility(trackVis);
      }
      if (scrollThumb != null) {
        FrameLayout.LayoutParams lpThumb = (FrameLayout.LayoutParams) scrollThumb.getLayoutParams();
        if (lpThumb.leftMargin != barX || lpThumb.topMargin != viewTop + pad) {
          lpThumb.leftMargin = barX;
          lpThumb.topMargin = viewTop + pad;
          scrollThumb.setLayoutParams(lpThumb);
        }
      }
      if (scrollHot != null) {
        FrameLayout.LayoutParams lpHot = (FrameLayout.LayoutParams) scrollHot.getLayoutParams();
        int hotH = Math.max(0, viewH - pad * 2);
        int hotLeft = Math.max(0, barX - dp(22));
        if (lpHot.leftMargin != hotLeft
            || lpHot.topMargin != viewTop + pad
            || lpHot.height != hotH
            || lpHot.bottomMargin != 0) {
          lpHot.leftMargin = hotLeft;
          lpHot.topMargin = viewTop + pad;
          lpHot.height = hotH;
          lpHot.bottomMargin = 0;
          scrollHot.setLayoutParams(lpHot);
        }
        int hotVis = (cfg.chrome && bridgeAvailable) ? View.VISIBLE : View.GONE;
        if (scrollHot.getVisibility() != hotVis) scrollHot.setVisibility(hotVis);
      }
      layoutModeButtons(barX);
      layoutDock();
      applyGestureExclusion();
    }
  }

  // ---------------------------------------------------------------------------
  // 241：缩放层（桌面视口缩放）
  // ---------------------------------------------------------------------------

  /**
   * 左右边缘各让出 24dp 给网页：Android 10+ 的系统「侧滑返回」会抢走屏幕边缘的横向手势，
   * 宽表格/代码块贴边时一滑就退回桌面。这里把 WebView 两侧边缘声明成手势排除区。
   * 横屏还有胶囊的返回按钮可用，不会失去「返回」入口。
   */
  private void applyGestureExclusion() {
    if (web == null || Build.VERSION.SDK_INT < 29) return;
    web.post(
        () -> {
          try {
            int w = web.getWidth(), h = web.getHeight();
            if (w <= 0 || h <= 0) return;
            int edge = dp(24);
            if (edge * 2 >= w) return;
            java.util.List<android.graphics.Rect> rects =
                java.util.Arrays.asList(
                    new android.graphics.Rect(0, 0, edge, h),
                    new android.graphics.Rect(w - edge, 0, w, h));
            web.setSystemGestureExclusionRects(rects);
          } catch (Throwable ignored) {
          }
        });
  }

  /** 胶囊按钮：线条图标 + 无边框涟漪；不再用 ⌂/⌸ 字体符号（部分 ROM 缺字会变方块）。 */
  private ImageView dockIcon(int iconRes, String description) {
    ImageView view = new ImageView(this);
    view.setImageResource(iconRes);
    view.setColorFilter(LandscapePalette.DOCK_ICON);
    view.setContentDescription(com.deepseekharness.app.util.UiText.text(description));
    view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    view.setPadding(dp(10), dp(10), dp(10), dp(10));
    view.setClickable(true);
    view.setFocusable(true);
    try {
      TypedValue tv = new TypedValue();
      getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
      view.setBackgroundResource(tv.resourceId);
    } catch (Throwable ignored) {
    }
    return view;
  }

  /** ← 网页后退：原生能退就退；SPA 用 history.back；真到根只提示，不退出。 */
  private void goBack() {
    if (web == null) {
      finish();
      return;
    }
    if (web.canGoBack()) {
      web.goBack();
      return;
    }
    web.evaluateJavascript(
        "(function(){try{return history.length;}catch(e){return 1;}})()",
        value -> {
          int length = 1;
          try {
            length = Integer.parseInt(value.replace("\"", "").trim());
          } catch (Throwable ignored) {
          }
          if (length > 1) {
            web.evaluateJavascript("(function(){try{history.back();}catch(e){}})()", null);
          } else if (dock == null || dock.getVisibility() != View.VISIBLE) {
            // 胶囊被配置隐藏/位置为 hide 时没有应用内退出按钮：根页返回即退出。
            finish();
          } else {
            toast("已在页面首页");
          }
        });
  }

  /** 一键回启动页：显式回 MainActivity，不依赖返回栈。 */
  private void goHome() {
    try {
      Intent it = new Intent(this, MainActivity.class);
      it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(it);
    } catch (Throwable error) {
      Log.w(TAG, "回启动页失败", error);
    }
    finish();
    try {
      overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    } catch (Throwable ignored) {
    }
  }

  /**
   * 视口自检：桌面 UA 下，页面拿到的 innerWidth 必须正好是当前档的 CSS 宽度
   * （1280 / 1024 / 854）。
   * 偏差超过 2px 就说明又被移动视口规则接管了（典型是 980 兜底），logcat 里留证据。
   */
  /** 量一次页面真实视口（203 起的对账手段，保持不变）。 */
  private void logViewport() {
    if (web == null) return;
    final int expect = DshStageGeometry.cssWidth(mode);
    web.evaluateJavascript(
        "(function(){try{var vv=window.visualViewport;return JSON.stringify({iw:innerWidth,ih:innerHeight,"
            + "dpr:devicePixelRatio,vv:vv?{w:Math.round(vv.width),h:Math.round(vv.height),scale:vv.scale,"
            + "oy:Math.round(vv.offsetTop)}:null,sh:document.scrollingElement.scrollHeight,"
            + "ch:document.scrollingElement.clientHeight,"
            + "bg:(function(){try{var c=getComputedStyle(document.body).backgroundColor;return c||''}catch(e){return ''}})()};}"
            + "catch(e){return 'ERR '+e;}})()",
        value -> {
          Log.i(
              TAG,
              "viewport mode="
                  + DshStageGeometry.cssWidth(mode)
                  + "x"
                  + DshStageGeometry.cssHeight(mode)
                  + " "
                  + value);
          try {
            org.json.JSONObject o = new org.json.JSONObject(unquote(value));
            int iw = o.optInt("iw", -1);
            if (iw > 0 && Math.abs(iw - expect) > 2) {
              Log.w(TAG, "VIEWPORT_MISMATCH expect=" + expect + " got=" + iw);
            }
            syncPageBackground(o.optString("bg", ""));
          } catch (Throwable ignored) {
          }
        });
  }

  private String unquote(String value) {
    if (value == null) return "null";
    if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) return value;
    try {
      return new org.json.JSONArray("[" + value + "]").optString(0);
    } catch (Throwable ignored) {
      return value;
    }
  }

  /** 长按截图键：把视口/文档尺寸复制到剪贴板，真机上直接粘出来反馈。 */
  private void copyDiagnostics() {
    if (web == null) {
      toast("还没有页面");
      return;
    }
    web.evaluateJavascript(
        "(function(){try{var vv=window.visualViewport;return JSON.stringify({mode:'"
            + DshStageGeometry.cssWidth(mode)
            + "x"
            + DshStageGeometry.cssHeight(mode)
            + "',iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,"
            + "vv:vv?{w:Math.round(vv.width),h:Math.round(vv.height),scale:vv.scale,oy:Math.round(vv.offsetTop)}:null,"
            + "sh:document.scrollingElement.scrollHeight,ch:document.scrollingElement.clientHeight,"
            + "sw:document.scrollingElement.scrollWidth,cw:document.scrollingElement.clientWidth});}catch(e){return 'ERR '+e;}})()",
        value -> {
          String text = "DockDisplay v" + appVersionName() + "\n" + unquote(value);
          try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dock-diag", text));
            toast("视口诊断已复制，可直接粘贴反馈");
          } catch (Throwable error) {
            toast(
                com.deepseekharness.app.util.UiText.format(
                    "复制失败：%s", error.getClass().getSimpleName()));
          }
        });
  }

  private String appVersionName() {
    try {
      android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
      return pi.versionName == null ? "?" : pi.versionName;
    } catch (Throwable error) {
      return "?";
    }
  }

  /** 读配置并把它应用到 WebSettings：胶囊 ↻、重开横屏共用同一条路径。 */
  private void applyLandscapeConfig() {
    refreshLandscapeConfig();
    if (web == null) return;
    web.getSettings().setUserAgentString(userAgentFor(cfg.desktopUa, defaultUa));
    installCompatibilityScripts();
    // 只有配置文件里的 defaultMode 变了才重置档位；用户手动选的档位在刷新后保留。
    mode = modeAfterReload(mode, lastConfiguredDefaultMode, cfg.defaultMode);
    lastConfiguredDefaultMode = cfg.defaultMode;
    paintModeButtons();
    applyDockStyle();
    layoutDock();
    if (screen != null) screen.reapply();
  }

  /**
   * UA 选择：desktopUa=true 时只替换平台段、保留真实 Chrome 版本（与竖屏同一约定）；
   * desktopUa=false 回退 WebView 默认 UA。抽成静态函数便于单测。
   */
  /** 重读配置后的档位：defaultMode 未变则保留当前手动档，变了才重置。 */
  static int modeAfterReload(int currentMode, int previousDefault, int newDefault) {
    return previousDefault == newDefault ? currentMode : DshStageGeometry.clampMode(newDefault);
  }

  /** 插件安装/管理导航是否允许：只认带手势的 http(s)，其余 scheme 交给 handleUrl 拒绝。 */
  static boolean pluginNavigationAllowed(String scheme, boolean gesture) {
    if (!gesture || scheme == null) return false;
    return "http".equals(scheme) || "https".equals(scheme);
  }

  /** 原生桥是否可用：特性支持且 origin 可校验；origin 为空一律视为不可用。 */
  static boolean bridgeEnabled(boolean supported, String origin) {
    return supported && origin != null && !origin.isEmpty();
  }

  static String userAgentFor(boolean desktopUa, String defaultUa) {
    return desktopUa
        ? com.deepseekharness.app.util.WebPreviewPolicy.desktopUserAgent(defaultUa)
        : defaultUa;
  }

  private void reloadWeb() {
    if (web == null) return;
    // 点胶囊 ↻ 即重新读取配置（数据开关）并重新应用 UA，不用重开横屏。
    applyLandscapeConfig();
    showLoading();
    web.reload();
  }

  private void showLoading() {
    if (loadingView == null) return;
    loadingView.animate().cancel();
    loadingView.setText(com.deepseekharness.app.util.UiText.text("正在打开…"));
    loadingView.setAlpha(1f);
    loadingView.setVisibility(View.VISIBLE);
  }

  private void hideLoading() {
    if (loadingView == null || loadingView.getVisibility() != View.VISIBLE) return;
    loadingView
        .animate()
        .alpha(0f)
        .setDuration(220)
        .withEndAction(
            () -> {
              if (loadingView != null && loadingView.getAlpha() <= 0.01f) {
                loadingView.setVisibility(View.GONE);
              }
            })
        .start();
  }

  /** 注入脚本：滚动链防护 / 滚动位置 / 整页拖不动。只在 DSH 本机服务页面上注入。 */
  private void injectScripts() {
    if (!onDshOrigin()) return;
    // 每页补一次（脚本自带幂等判断）：老内核没有文档起始注入时靠这条，
    // 也保证 __DSHA_NATIVE_OAUTH__ 在页面脚本跑之前/之后都成立。
    if (!openInterceptorJs.isEmpty()) {
      try {
        web.evaluateJavascript(openInterceptorJs, null);
      } catch (Throwable ignored) {
      }
    }
    injectAsset("web/scroll-guard.js");
    injectAsset("web/scroll-watcher.js");
    // 手势锁可被配置关掉（scrollLock=false 时页面里不做拦截）。
    if (cfg.scrollLock) injectAsset("web/scroll-lock.js");
  }

  /** 重读 dsh-a-landscape.json：胶囊 ↻ 与重新进入横屏时都会走一遍。 */
  private void refreshLandscapeConfig() {
    cfg = LandscapeConfig.load(this);
  }

  private void injectAsset(String name) {
    if (web == null) return;
    String js = readAsset(name);
    if (js == null || js.isEmpty()) {
      Log.w(TAG, "注入脚本缺失: " + name);
      return;
    }
    try {
      web.evaluateJavascript(js, null);
    } catch (Throwable ignored) {
    }
  }

  private String readAsset(String name) {
    InputStream in = null;
    try {
      in = getAssets().open(name);
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), "UTF-8");
    } catch (Throwable error) {
      return null;
    } finally {
      try {
        if (in != null) in.close();
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * 与竖屏一致的兼容脚本（document-start）：es-compat / compat / 语言 / 主题 / OAuth 标记。
   *
   * <p>刻意**不开** {@code __DSHA_NATIVE_PLUGINS__}：Dock 不承载内置插件桥，只复用语言、主题与
   * 旧内核兼容层；{@code __DSHA_NATIVE_OAUTH__=true} 让 DSH 的授权跳转走顶层导航而不是弹窗。
   * 语言/主题在胶囊 ↻ 或重开横屏时重新注册。
   */
  private void installCompatibilityScripts() {
    if (web == null) return;
    try {
      if (!androidx.webkit.WebViewFeature.isFeatureSupported(
          androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        Log.w(TAG, "DOCUMENT_START_SCRIPT 不可用，跳过兼容脚本");
        return;
      }
      String origin = serviceOrigin();
      if (origin.isEmpty()) return;
      if (compatibilityScript != null) {
        compatibilityScript.remove();
        compatibilityScript = null;
      }
      String script = compatibilityScript(this);
      compatibilityScript =
          androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
              web, script, java.util.Collections.singleton(origin));
    } catch (Throwable error) {
      Log.w(TAG, "兼容脚本注册失败", error);
    }
  }

  /**
   * 兼容脚本内容（纯函数，便于单测）：OAuth 标记 + 语言/主题 + es-compat/compat/startup。
   *
   * <p>刻意不含 {@code __DSHA_NATIVE_PLUGINS__}：Dock 不承载内置插件桥。
   */
  static String compatibilityScript(android.content.Context context) {
    return compatibilityScript(context, name -> assetText(context, name));
  }

  /** 资产读取可注入：单测用测试 fixture 验证 es-compat 内容，生产走 {@link #assetText}。 */
  static String compatibilityScript(
      android.content.Context context, java.util.function.Function<String, String> asset) {
    return "window.__DSHA_NATIVE_OAUTH__=true;\n"
        + WebPageScripts.language(context)
        + "\n"
        + WebPageScripts.theme(context)
        + "\n"
        + asset.apply("web-integration/es-compat.js")
        + "\n"
        + asset.apply("web-integration/compat.js")
        + "\n"
        + asset.apply("web-integration/startup.js");
  }

  private static String assetText(android.content.Context context, String name) {
    try (java.io.InputStream in = context.getAssets().open(name)) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
      return out.toString("UTF-8");
    } catch (Throwable error) {
      Log.w(TAG, "读取兼容脚本失败: " + name, error);
      return "";
    }
  }

  /**
   * 网页 → 原生的唯一桥：{@code WebMessageListener} + origin 校验，不再用
   * {@code addJavascriptInterface}（后者对所有 frame 生效，跨源 iframe 也能用）。
   *
   * <p>只接受 DSH 本机服务页面、主 frame 的 JSON 消息：
   * <ul>
   *   <li>{@code {"t":"open","u":...}} —— window.open 出去的外链交系统浏览器；
   *   <li>{@code {"t":"state","f":...,"v":...,"c":...}} —— 滚动位置与可滚状态；
   *   <li>{@code {"t":"doc","c":...}} —— 文档本身能否滚动（停手复位用）。
   * </ul>
   */
  private void attachWebMessageBridge() {
    try {
      boolean supported =
          androidx.webkit.WebViewFeature.isFeatureSupported(
              androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER);
      String origin = serviceOrigin();
      bridgeAvailable = bridgeEnabled(supported, origin);
      if (!supported) {
        Log.w(TAG, "WebMessageListener 不可用：滚动条隐藏，window.open 走顶层导航回退");
        return;
      }
      if (origin.isEmpty()) {
        Log.w(TAG, "没有可校验的 origin：不注册原生桥，window.open 走顶层导航回退");
        return;
      }
      androidx.webkit.WebViewCompat.addWebMessageListener(
          web,
          "WhaleBridge",
          java.util.Collections.singleton(origin),
          (view, message, sourceOrigin, isMainFrame, reply) -> {
            if (!isMainFrame || view == null) return;
            if (!isDshSource(sourceOrigin) || !onDshOrigin()) return;
            String data;
            try {
              data = message.getData();
            } catch (IllegalStateException error) {
              return;
            }
            handleBridgeMessage(data);
          });
    } catch (Throwable error) {
      bridgeAvailable = false;
      hideScrollBars();
      Log.w(TAG, "原生桥注册失败：滚动条隐藏，window.open 走顶层导航回退", error);
    }
  }

  private void hideScrollBars() {
    try {
      if (scrollTrack != null) scrollTrack.setVisibility(View.GONE);
      if (scrollThumb != null) scrollThumb.setVisibility(View.GONE);
      if (scrollHot != null) scrollHot.setVisibility(View.GONE);
    } catch (Throwable ignored) {
    }
  }

  private void handleBridgeMessage(String data) {
    if (data == null || data.isEmpty()) return;
    try {
      org.json.JSONObject value = new org.json.JSONObject(data);
      String type = value.optString("t", "");
      if ("open".equals(type)) {
        String url = value.optString("u", "");
        if (url.isEmpty()) return;
        ui.post(
            () -> {
              if (!isFinishing() && isWebViewScheme(schemeOf(url))) openExternal(url, false);
            });
      } else if ("state".equals(type)) {
        pendingFrac = (float) value.optDouble("f", 0);
        pendingView = (float) value.optDouble("v", 0);
        pendingCan = value.optBoolean("c", true);
        if (!pendingDirty) {
          pendingDirty = true;
          uiPost(applyTask);
        }
      } else if ("doc".equals(type)) {
        docNotScrollable = !value.optBoolean("c", true);
      }
    } catch (Exception ignored) {
    }
  }

  /** 当前页面是否仍是 DSH 本机服务（脚本注入与下载的前置条件）。 */
  private boolean onDshOrigin() {
    if (web == null || baseUrl == null || baseUrl.isEmpty()) return false;
    try {
      return com.deepseekharness.app.util.WebPreviewPolicy.sameService(baseUrl, web.getUrl());
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 麦克风等敏感权限请求的 origin 判定：请求 frame 与当前页面都必须属于 DSH 本机服务。
   *
   * <p>抽成静态纯函数是为了能在单测里覆盖负控（跨源 iframe、空 origin、非回环页面）。
   */
  static boolean allowDshOrigin(String baseUrl, android.net.Uri requestOrigin, String pageUrl) {
    if (baseUrl == null || baseUrl.isEmpty() || requestOrigin == null || pageUrl == null) {
      return false;
    }
    try {
      return com.deepseekharness.app.util.WebPreviewPolicy.sameService(
              baseUrl, requestOrigin.toString())
          && com.deepseekharness.app.util.WebPreviewPolicy.sameService(baseUrl, pageUrl);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private boolean isDshSource(android.net.Uri sourceOrigin) {
    if (sourceOrigin == null || baseUrl == null || baseUrl.isEmpty()) return false;
    try {
      return com.deepseekharness.app.util.WebPreviewPolicy.sameService(
          baseUrl, sourceOrigin.toString());
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 允许的消息 origin；baseUrl 以 "/" 结尾时去掉尾斜杠。 */
  private String serviceOrigin() {
    String origin = baseUrl == null ? "" : baseUrl;
    if (origin.isEmpty()) {
      if (authUrl == null || authUrl.isEmpty()) return "";
      try {
        Uri parsed = Uri.parse(authUrl);
        if (parsed.getScheme() == null || parsed.getHost() == null) return "";
        origin =
            parsed.getScheme()
                + "://"
                + parsed.getHost()
                + (parsed.getPort() > 0 ? ":" + parsed.getPort() : "");
      } catch (Throwable ignored) {
        return "";
      }
    }
    if (origin.endsWith("/")) origin = origin.substring(0, origin.length() - 1);
    return origin;
  }

  /**
   * 文档起始脚本：劫持 window.open 里的 http(s) 外链。
   *
   * <p>只劫持「不是 DSH 本机服务」的地址，本机地址仍交给页面自己处理（不改变同源行为）。
   * 必须在文档起始注入 —— 登录页可能在首帧就被脚本打开，等 onPageFinished 就晚了。
   */
  private void installOpenInterceptor() {
    if (web == null) return;
    // origin 用来放行本机页面自己的 window.open；取不到就按空处理（全部外开）。
    String origin = "";
    if (baseUrl != null && baseUrl.endsWith("/")) {
      origin = baseUrl.substring(0, baseUrl.length() - 1);
    } else if (authUrl != null && !authUrl.isEmpty()) {
      Uri parsed = Uri.parse(authUrl);
      if (parsed.getScheme() != null && parsed.getHost() != null) {
        origin =
            parsed.getScheme()
                + "://"
                + parsed.getHost()
                + (parsed.getPort() > 0 ? ":" + parsed.getPort() : "");
      }
    }

    // 说明：竖屏靠注入 __DSHA_NATIVE_PLUGINS__ 让上游走「当前页导航」→ 被 shouldOverride 接住。
    // 横屏这里**不**照抄那个标志，而是让上游照常 window.open("about:blank")，再由下面的
    // 假窗口接管：当前页面完全不动，登录页直接去系统浏览器。少一次导航 = 少一次白屏机会。

    // ② window.open 劫持：返回「假窗口」，绝不把 about:blank 交给 WebView。
    //    Android 文档写明 setSupportMultipleWindows(false) 时 window.open/target=_blank
    //    会被当成顶层导航——把当前页替换成空白，就是用户看到的「整屏白」。
    //    假窗口的 location.replace/assign 直接走原生桥（系统浏览器），页面逻辑不变。
    final String js =
        "(function(){try{"
            + "if(window.__whaleOpenPatched)return;window.__whaleOpenPatched=1;"
            + "var raw=window.open,self=String(location.origin||'');"
            + "function external(u){"
            + "if(!u)return true;u=String(u);"
            + "if(u==='about:blank'||u.indexOf('about:')===0)return true;"
            + "if(!/^https?:/i.test(u))return false;"
            + "return !self||u.indexOf(self)!==0;}"
            + "function fake(){var last='';var p={closed:false,opener:null,"
            + "close:function(){this.closed=true;},focus:function(){},blur:function(){},"
            + "postMessage:function(){},"
            + "location:{replace:function(u){go(u);},assign:function(u){go(u);}}};"
            + "function go(u){u=String(u);if(p.closed||u===last)return;last=u;"
            + "try{if(window.WhaleBridge&&window.WhaleBridge.postMessage){"
            + "window.WhaleBridge.postMessage(JSON.stringify({t:'open',u:u}));}"
            + "else{window.location.href=u;}}catch(e){}}"
            + "return p;}"
            + "window.open=function(url){"
            + "try{if(external(url))return fake();}catch(e){}"
            + "try{return raw?raw.apply(window,arguments):null;}catch(e2){return null;}};"
            + "}catch(e){}})()";

    openInterceptorJs = js;
    try {
      if (!origin.isEmpty()
          && androidx.webkit.WebViewFeature.isFeatureSupported(
              androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
            web, openInterceptorJs, java.util.Collections.singleton(origin));
      }
    } catch (Throwable ignored) {
    }
  }

  private volatile float pendingFrac;
  private volatile float pendingView;
  private volatile boolean pendingCan;
  private volatile boolean pendingDirty;

  private final Runnable applyTask =
      new Runnable() {
        @Override
        public void run() {
          pendingDirty = false;
          applyScroll(pendingFrac, pendingView, pendingCan);
        }
      };

  private void applyScroll(float frac, float viewRatio, boolean can) {
    if (scrollThumb == null) return;
    if (draggingBar) return;
    // chrome=false 时整组控件都隐藏：这里把滑块可见性也一并压住，别被 can 又点亮。
    if (!cfg.chrome) {
      if (scrollThumb.getVisibility() != View.GONE) scrollThumb.setVisibility(View.GONE);
      return;
    }
    View parent = (View) scrollThumb.getParent();
    int h = parent == null ? 0 : parent.getHeight();
    if (h <= 0) return;
    scrollThumb.setVisibility(can ? View.VISIBLE : View.GONE);
    if (!can) return;
    int pad = dp(14);
    int areaH = (screen != null && screen.viewH > 0) ? screen.viewH : h;
    int areaTop = (screen != null && screen.viewH > 0) ? screen.viewTop : 0;
    int avail = areaH - pad * 2;
    if (avail <= 0) return;

    float minThumb = dp(40);
    float maxThumb = Math.max(minThumb, avail * 0.30f);
    float thumbF = avail * Math.max(0f, Math.min(1f, viewRatio));
    if (thumbF < minThumb) thumbF = minThumb;
    if (thumbF > maxThumb) thumbF = maxThumb;
    int thumbH = Math.round(thumbF);

    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scrollThumb.getLayoutParams();
    if (lp.height <= 0 || Math.abs(lp.height - thumbH) > dp(2) || lp.topMargin != areaTop + pad) {
      lp.height = thumbH;
      lp.topMargin = areaTop + pad;
      scrollThumb.setLayoutParams(lp);
    }

    int thumbCur = lp.height > 0 ? lp.height : thumbH;
    float maxTop = Math.max(0f, avail - thumbCur);
    if (frac < 0f) frac = 0f;
    if (frac > 1f) frac = 1f;
    if (frac <= 0.0002f) frac = 0f;
    if (frac >= 0.9998f) frac = 1f;
    scrollThumb.setTranslationY(maxTop * frac);
  }

  private void measureBar() {
    dragPad = dp(14);
    View parent = scrollThumb == null ? null : (View) scrollThumb.getParent();
    int h = parent == null ? 0 : parent.getHeight();
    if (screen != null && screen.viewH > 0) h = screen.viewH;
    dragAvail = Math.max(1, h - dragPad * 2);
    FrameLayout.LayoutParams lp =
        scrollThumb == null ? null : (FrameLayout.LayoutParams) scrollThumb.getLayoutParams();
    dragThumbH = (lp != null && lp.height > 0) ? lp.height : dp(80);
  }

  private float fracForY(float y) {
    float maxTop = Math.max(1f, dragAvail - dragThumbH);
    float f = (y - dragThumbH / 2f) / maxTop;
    if (f < 0f) f = 0f;
    if (f > 1f) f = 1f;
    return f;
  }

  private void seekTo(float frac) {
    if (scrollThumb == null) return;
    float maxTop = Math.max(0f, dragAvail - dragThumbH);
    scrollThumb.setVisibility(View.VISIBLE);
    scrollThumb.setTranslationY(maxTop * frac);
    if (web == null) return;
    String js =
        "(function(){try{if(window.__whaleScroll&&window.__whaleScroll.seek)"
            + "window.__whaleScroll.seek("
            + frac
            + ");}catch(e){}})()";
    try {
      web.evaluateJavascript(js, null);
    } catch (Throwable ignored) {
    }
  }

  private void uiPost(Runnable r) {
    try {
      runOnUiThread(r);
    } catch (Throwable ignored) {
    }
  }

  private void setupWindow() {
    try {
      if (Build.VERSION.SDK_INT >= 28) {
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wlp);
      }
      getWindow()
          .setBackgroundDrawable(
              new android.graphics.drawable.ColorDrawable(LandscapePalette.STAGE));
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
      getWindow().setStatusBarColor(Color.TRANSPARENT);
      getWindow().setNavigationBarColor(Color.TRANSPARENT);
      if (Build.VERSION.SDK_INT >= 30) {
        getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
          ic.hide(
              android.view.WindowInsets.Type.statusBars()
                  | android.view.WindowInsets.Type.navigationBars());
          ic.setSystemBarsBehavior(
              android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
      } else {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow()
            .getDecorView()
            .setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * 实时读一次键盘高度（ime insets）。
   *
   * <p>窗口因为键盘变矮时，{@code onSizeChanged} 有可能比 insets 回调更早到；
   * 那一帧如果只看 {@code lastIme}，就会把页面当「没有键盘」重算缩放 —— 真机上
   * 表现就是键盘一弹整块网页缩一下。这里直接问根窗口要当前值，两路取大。
   */
  private int imeBottom() {
    if (Build.VERSION.SDK_INT < 30) return lastIme;
    try {
      android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
      if (wi != null) return wi.getInsets(android.view.WindowInsets.Type.ime()).bottom;
    } catch (Throwable ignored) {
    }
    return lastIme;
  }

  /** 横屏 WebView：文档不可滚时，把 WebView 自身的滚动钉在 0。 */
  private class PortalWebView extends WebView {
    PortalWebView(Context c) {
      super(c);
    }

    /**
     * 237：文档本身不可滚时，**在源头**拒绝「把焦点内容顶进可视区」的滚动请求。
     *
     * <p>为什么加：{@code files/ui-probe/kbd-debug.txt}（2026-10-27 10:18，228 那版）
     * 里，键盘弹起后 WebView 连续发了几十次滚动：
     * {@code scroll t=3,13,31,59,102,164,256,397,630,1003,1341,1547,1676,1763 → 0}。
     * 每一次都被下面的 {@link #onScrollChanged} 归零 —— 位置最终是对的，但「滚一下再弹回」
     * 的过程本身在真机上就是可见的抖动。228/230 都是「滚了再归零」，237 试着从源头拒掉。
     *
     * <p>为什么安全：只在 {@code docNotScrollable}（页面 reflow 之后
     * {@code docH <= ih + 2}，日志里是 {@code page y=0 docH=279 ih=279}）时拦 ——
     * 这时输入框本来就在视口里，不需要浏览器帮忙滚；页面真能滚的时候照常放行，
     * 不会出现「输入框被键盘挡住还滚不动」。
     *
     * <p><b>一行回退</b>：删掉这两个 {@code @Override} 即可，下面的 {@code onScrollChanged}
     * 归零逻辑原样保留。日志来自 228 时代，237 是否真的解决抖动需要真机弹收键盘验证
     * （每弹收一次，{@code kbd-debug.txt} 里若不再出现成串的 {@code scroll t=... -> 0} 即生效）。
     */
    @Override
    public boolean requestChildRectangleOnScreen(
        View child, android.graphics.Rect rectangle, boolean immediate) {
      if (contentFitsViewport()) return true;
      return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override
    public boolean requestRectangleOnScreen(android.graphics.Rect rectangle) {
      if (contentFitsViewport()) return true;
      return super.requestRectangleOnScreen(rectangle);
    }

    /**
     * 文档本身不可滚时，WebView 自身的任何滚动都只是「视觉视口被平移」（缩放怪癖）
     * 或者「聚焦把内容顶上去」，一律立刻归零。
     *
     * <p>228 起不再区分键盘态（以前带 `lastIme == 0`，正好放过了键盘弹出时那一下），
     * 手指拖动、键盘聚焦、收起回弹都由这一条兜住，不需要额外的延迟复位。
     */
    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
      super.onScrollChanged(l, t, oldl, oldt);
      if (t == 0 && l == 0) return;
      // 每次滚动都实测内容高度，而不是信 watcher 的一次性结论：
      // SPA 在键盘收起后变长/流式内容增长时，旧结论会把滚动永久归零。
      if (contentFitsViewport()) {
        if (lastIme > 0) kbdLog("scroll t=" + t + " -> 0 (ime on)");
        scrollTo(0, 0);
      }
    }

    /** 内容是否真的放得下视口（CSS px 对比），并同步 docNotScrollable。 */
    private boolean contentFitsViewport() {
      try {
        float density = getResources().getDisplayMetrics().density;
        int viewCss = Math.round(getHeight() / density);
        int content = getContentHeight();
        boolean fits = content > 0 && content <= viewCss + 2;
        docNotScrollable = fits;
        return fits;
      } catch (Throwable ignored) {
        return docNotScrollable;
      }
    }
  }

  /**
   * 舞台底：深蓝黑三段渐变 + 顶部一层很淡的冷光，全部由代码生成。
   *
   * <p>不用位图：一是避免引入来源/许可不明的第三方图片，二是 2560×1440 的 JPEG 解码约 14 MB，
   * 对低内存设备不友好。
   */
  private android.graphics.drawable.Drawable stageBackdrop() {
    android.graphics.drawable.GradientDrawable base =
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[] {
              LandscapePalette.STAGE_GRADIENT_0,
              LandscapePalette.STAGE_GRADIENT_1,
              LandscapePalette.STAGE_GRADIENT_2,
              LandscapePalette.STAGE_GRADIENT_3
            });
    android.graphics.drawable.GradientDrawable glow =
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {LandscapePalette.STAGE_GLOW, 0x00000000});
    android.graphics.drawable.LayerDrawable layers =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {base, glow});
    return layers;
  }

  /**
   * 扣掉 displayCutout 安全区后的可用区域：{width, height, offsetX, offsetY}。
   *
   * <p>抽成静态纯函数便于单测；宽高不足时归零，调用方按无效帧跳过。
   */
  static int[] insetArea(int w, int h, int safeLeft, int safeTop, int safeRight, int safeBottom) {
    int safeL = Math.max(0, safeLeft);
    int safeT = Math.max(0, safeTop);
    int safeR = Math.max(0, safeRight);
    int safeB = Math.max(0, safeBottom);
    return new int[] {Math.max(0, w - safeL - safeR), Math.max(0, h - safeT - safeB), safeL, safeT};
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }

  /**
   * 215：横屏顶层导航复用竖屏（{@code WebPreviewActivity.PreviewClient}）那套策略。
   *
   * <p>之前这里只按 scheme 判：http/https 全部留在 WebView 里。后果是 DSH 登录页
   * （platform.deepseek.com 的授权页）被塞进 16:9 视口 —— 既弹不出系统浏览器，
   * 也没有回跳，登录永远完不成（用户看到的「横屏登录点了没反应」）。
   *
   * <p>现在和竖屏一致：
   * <ol>
   *   <li>下载链接仍留在 WebView，交给 {@code DownloadListener}；
   *   <li>插件安装链接交 {@link PluginNavigation}（已经核验过包、路径与摘要）；
   *   <li>带手势的顶层导航、以及本机授权服务地址，
   *       交系统浏览器；登录完成后回调仍由 {@link #handleUrl} 接住 {@code dsh://}/{@code dsha://}；
   *   <li>其余（无手势的普通导航、子框架）照旧留在 WebView。
   * </ol>
   */
  /** DSH 页面加载完成后的收尾（原来直接写在 WebViewClient 里）。 */
  private void onDshPageFinished(WebView view, String pageUrl) {
    dshLoaded = true;
    hideLoading();
    injectScripts();
    logViewport();
    try {
      CookieManager.getInstance().flush();
    } catch (Throwable ignored) {
    }
  }

  /**
   * 把页面自己的背景色同步给 WebView 与舞台壳。
   *
   * <p>为什么要做：键盘弹收时页面高度会变（reflow），中间那一帧 WebView 可能还没重绘，
   * 露出来的就是父容器底色 —— 舞台壳没背景时是深色舞台壁纸，真机上就是「一帧黑色」。
   * 让这层底色 = 页面底色，过渡帧就看不见（深色页面变深色、浅色页面变浅色）。
   *
   * <p>只接受**不透明**的颜色（alpha ≥ 200），半透明或解析失败就保持原样。
   */
  private void syncPageBackground(String css) {
    if (css == null) return;
    String value = css.trim().toLowerCase(java.util.Locale.ROOT);
    if (value.isEmpty() || "transparent".equals(value)) return;
    int color;
    try {
      if (value.startsWith("#")) {
        color = Color.parseColor(value);
      } else if (value.startsWith("rgb")) {
        String[] parts = value.replace("rgba(", "").replace("rgb(", "").replace(")", "").split(",");
        if (parts.length < 3) return;
        int r = Integer.parseInt(parts[0].trim());
        int g = Integer.parseInt(parts[1].trim());
        int b = Integer.parseInt(parts[2].trim());
        int a = parts.length > 3 ? Math.round(Float.parseFloat(parts[3].trim()) * 255f) : 255;
        color = Color.argb(a, r, g, b);
      } else {
        return;
      }
    } catch (Throwable ignored) {
      return;
    }
    if (Color.alpha(color) < 200) return;
    try {
      if (web != null) web.setBackgroundColor(color);
      if (stageShell != null) stageShell.setBackgroundColor(color);
    } catch (Throwable ignored) {
    }
  }

  /**
   * 非本机页面被装进横屏视口时，立刻退回 DSH 首页。
   *
   * @return true 表示已经拦下并重载，调用方不要再做正常收尾。
   */
  private boolean bounceForeignPage(WebView view, String pageUrl) {
    if (view == null || pageUrl == null || authUrl.isEmpty()) return false;
    // about:blank 只在「已经进过 DSH 页面」之后才拦：首帧的初始 about:blank 是正常的，
    // 之后的 about:blank 只可能是 window.open 把页面换空了（真机白屏就是它）。
    if ("about:blank".equals(pageUrl)) {
      if (!dshLoaded) return false;
      return bounceBack(view, "登录页已转到浏览器");
    }
    // 只拦「外部网页」：about/blob/data 这些是页面自己生成的内容（预览、导出、下载），
    // 弹回首页反而会把功能弄坏。
    String scheme = Uri.parse(pageUrl).getScheme();
    if (scheme == null) return false;
    scheme = scheme.toLowerCase(java.util.Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
    // baseUrl 为空（鉴权地址不是本机回环）时不判断，避免把正常页面误弹。
    if (baseUrl == null) return false;
    if (com.deepseekharness.app.util.WebPreviewPolicy.sameService(baseUrl, pageUrl)) return false;
    return bounceBack(view, "已拦下外部页面，回到 DSH");
  }

  /**
   * 弹回已鉴权入口：优先 {@code baseUrl}（cookie 已就位），不再把带 token 的 {@code authUrl}
   * 写进 WebView 历史；连续弹回超过 3 次就停手，避免服务端重定向造成来回循环。
   */
  private boolean bounceBack(WebView view, String message) {
    long now = android.os.SystemClock.elapsedRealtime();
    int next = nextBounceCount(now, lastBounceAt, bounceCount, BOUNCE_WINDOW_MS, MAX_BOUNCES);
    lastBounceAt = now;
    try {
      view.stopLoading();
      if (next < 0) {
        bounceCount = MAX_BOUNCES;
        Log.w(TAG, "连续弹回达到上限，停止自动回跳");
        toast("连续跳转已停止，请点 ⌂ 回启动页");
        return true;
      }
      bounceCount = next;
      view.loadUrl(bounceEntry(baseUrl, authUrl));
      toast(message);
    } catch (Throwable ignored) {
    }
    return true;
  }

  /** 弹回目标：优先已鉴权的 baseUrl；只有非本机鉴权地址时才退回 authUrl。 */
  static String bounceEntry(String baseUrl, String authUrl) {
    return (baseUrl == null || baseUrl.isEmpty()) ? authUrl : baseUrl;
  }

  /**
   * 连续弹回计数：窗口外重置；超过上限返回 -1，调用方必须停手（避免服务端重定向造成死循环）。
   */
  static int nextBounceCount(long now, long lastBounceAt, int count, long windowMs, int max) {
    int base = (now - lastBounceAt > windowMs) ? 0 : count;
    int next = base + 1;
    return next > max ? -1 : next;
  }

  /** scheme 归一化后是否为 intent:（大小写变体一律拒绝）。 */
  static boolean isIntentScheme(String url) {
    if (url == null) return false;
    String scheme = Uri.parse(url).getScheme();
    return scheme != null && "intent".equals(scheme.toLowerCase(java.util.Locale.ROOT));
  }

  /** WebView 自己就能处理的网页 scheme（不触发任何组件启动）。 */
  static boolean isWebViewScheme(String scheme) {
    if (scheme == null) return false;
    switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
      case "http":
      case "https":
      case "file":
      case "about":
      case "data":
      case "blob":
      case "javascript":
      case "chrome":
        return true;
      default:
        return false;
    }
  }

  /** 取 URL 的小写 scheme；解析失败返回空串。 */
  static String schemeOf(String url) {
    if (url == null) return "";
    try {
      String scheme = Uri.parse(url).getScheme();
      return scheme == null ? "" : scheme.toLowerCase(java.util.Locale.ROOT);
    } catch (Throwable ignored) {
      return "";
    }
  }

  private boolean handleMainFrameUrl(String url, boolean gesture, String current) {
    if (url == null || url.isEmpty()) return false;
    // about:blank 是「window.open 被当顶层导航」留下的空页：DSH 页面永远不需要它。
    // 拦掉而不是加载，避免整屏白。
    if ("about:blank".equals(url)) return true;
    if (com.deepseekharness.app.util.WebPreviewPolicy.pageDownload(baseUrl, url)) return false;
    String scheme = Uri.parse(url).getScheme();
    if (scheme == null) return false;
    scheme = scheme.toLowerCase(java.util.Locale.ROOT);
    // 插件安装/管理只接受带手势的 dsha.cc https 精确白名单；dsha:// / dsh:// 一律走 handleUrl 拒绝。
    if (pluginNavigationAllowed(scheme, gesture) && PluginNavigation.open(this, url, gesture)) {
      return true;
    }
    boolean web = "http".equals(scheme) || "https".equals(scheme);
    if (web) {
      // 223：判据从「hasGesture || OAuth 白名单」换成「是不是 DSH 自己的服务」。
      // 前两版都栽在这里：登录按钮是 JS 异步跳转（没有手势），destination 也不一定
      // 落在 /dsh/authorize 白名单上 → 两个条件都不成立 → 要么白屏（215），要么拦掉
      // 之后点了没反应（221）。而「是不是本机服务」这个判断是确定的：
      //   · 127.0.0.1:<port> → DSH 自己的页面，交给 WebView 正常导航；
      //   · 其它任何 http/https（登录页、文档、外链） → 一律交系统浏览器。
      boolean internal = com.deepseekharness.app.util.WebPreviewPolicy.sameService(baseUrl, url);
      if (!internal) {
        openExternal(url, gesture);
        return true;
      }
      // 本机页面的顶层导航放行给 WebView：返回 true 却不 loadUrl 会把 <a href>
      // 这类正常导航吞掉（点链接没反应）。外部页面有 onPageStarted 的 bounceForeignPage 兜底。
      return false;
    }
    return handleUrl(url);
  }

  /**
   * 交系统处理：插件链接优先（同竖屏），其余 http/https 打开系统浏览器。
   * 与竖屏 {@code WebPreviewActivity.openExternal} 行为一致，便于两边一起排查。
   */
  private void openExternal(String url, boolean gesture) {
    if (url == null) return;
    String scheme = schemeOf(url);
    if (pluginNavigationAllowed(scheme, gesture) && PluginNavigation.open(this, url, gesture))
      return;
    Uri uri = Uri.parse(url);
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
      if (url.contains("platform.deepseek.com")) {
        toast("已用系统浏览器打开登录页，完成登录后回到这里即可");
      }
    } catch (RuntimeException error) {
      toast("未找到可用的系统浏览器");
    }
  }

  private boolean handleUrl(String url) {
    if (url == null) return false;
    try {
      Uri uri = Uri.parse(url);
      String scheme = uri.getScheme();
      if (scheme == null) return false;
      scheme = scheme.toLowerCase(java.util.Locale.ROOT);
      // intent: 会把 URL 里的组件/extras 变成任意组件启动（Intent 重定向面），大小写变体一并拒绝。
      if (isIntentScheme(url)) return true;
      if (scheme.equals("http")
          || scheme.equals("https")
          || scheme.equals("file")
          || scheme.equals("about")
          || scheme.equals("data")
          || scheme.equals("blob")
          || scheme.equals("javascript")
          || scheme.equals("chrome")) {
        return false;
      }
      // 其余 scheme（含 dsh:// / dsha:// 深链）一律不从网页内容里隐式启动组件：
      // dsha://install 会直接进插件安装页并自动 inspect/download，页面或跨源 iframe 不该能触发。
      // 需要安装/授权时回竖屏页面走显式原生入口。
      Log.i(TAG, "拒绝从网页启动的 scheme: " + scheme);
      return true;
    } catch (Throwable error) {
      Log.w(TAG, "外部链接处理失败: " + url, error);
      toast("没有找到能打开该链接的应用");
      return true;
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    if (req == REQ_FILE) {
      if (fileCb != null) {
        Uri[] out = null;
        try {
          out = WebChromeClient.FileChooserParams.parseResult(res, data);
        } catch (Exception ignored) {
        }
        try {
          fileCb.onReceiveValue(out);
        } catch (Exception ignored) {
        }
        fileCb = null;
      }
      return;
    }
    super.onActivityResult(req, res, data);
  }

  /**
   * 截取 16:9 视口：PixelCopy 抓窗口合成结果，再按 VirtualScreen 算出的可视矩形裁掉
   * 左侧三档/滚动条和右侧胶囊，存到 Pictures/DSHA-Mod/。
   */
  private void saveScreenshot() {
    if (screen == null || screen.viewW <= 0 || screen.viewH <= 0) {
      toast("页面还没排好，稍后再试");
      return;
    }
    View src = getWindow().getDecorView();
    int w = src.getWidth();
    int h = src.getHeight();
    if (w <= 0 || h <= 0) {
      toast("窗口还没画出来，稍后再试");
      return;
    }
    final Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    final java.util.List<View> hidden = hideChromeForScreenshot();
    try {
      // 等一帧让隐藏生效，再把「干净的视口」拷出来。
      ui.postDelayed(
          () ->
              android.view.PixelCopy.request(
                  getWindow(),
                  full,
                  result -> {
                    restoreChromeAfterScreenshot(hidden);
                    if (result != android.view.PixelCopy.SUCCESS) {
                      full.recycle();
                      toast("截图失败");
                      return;
                    }
                    Bitmap out = full;
                    try {
                      int x = Math.max(0, screen.viewLeft);
                      int y = Math.max(0, screen.viewTop);
                      int cw = Math.min(screen.viewW, w - x);
                      int ch = Math.min(screen.viewH, h - y);
                      if (cw > 16 && ch > 16) {
                        out = Bitmap.createBitmap(full, x, y, cw, ch);
                        if (out != full) full.recycle();
                      }
                    } catch (Throwable ignored) {
                    }
                    final Bitmap saved = out;
                    new Thread(
                            () -> {
                              String message = saveBitmap(saved);
                              runOnUiThread(() -> toast(message));
                              // 全屏位图一张约 12MB：保存完立刻回收，连续截图才不会被 OOM 打掉。
                              if (!saved.isRecycled()) saved.recycle();
                            })
                        .start();
                  },
                  ui),
          32);
    } catch (Throwable error) {
      restoreChromeAfterScreenshot(hidden);
      Log.w(TAG, "截图失败", error);
      toast("截图失败");
    }
  }

  /** 截图前隐藏胶囊/滚动条，避免把它们截进 16:9 视口。 */
  private java.util.List<View> hideChromeForScreenshot() {
    java.util.List<View> hidden = new java.util.ArrayList<>();
    for (View v : new View[] {dock, scrollTrack, scrollThumb, scrollHot}) {
      if (v != null && v.getVisibility() == View.VISIBLE) {
        v.setVisibility(View.INVISIBLE);
        hidden.add(v);
      }
    }
    return hidden;
  }

  private void restoreChromeAfterScreenshot(java.util.List<View> hidden) {
    for (View v : hidden) {
      try {
        v.setVisibility(View.VISIBLE);
      } catch (Throwable ignored) {
      }
    }
  }

  private String saveBitmap(Bitmap bm) {
    OutputStream os = null;
    try {
      if (bm == null) return "图片为空";
      ContentValues cv = new ContentValues();
      cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "dsha_" + System.currentTimeMillis() + ".png");
      cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
      if (Build.VERSION.SDK_INT >= 29) {
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DSHA");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
      }
      Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
      if (uri == null) return "插入相册失败";
      os = getContentResolver().openOutputStream(uri);
      if (os == null) return "写入相册失败";
      bm.compress(Bitmap.CompressFormat.PNG, 100, os);
      os.flush();
      os.close();
      os = null;
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
      }
      return com.deepseekharness.app.util.UiText.text("已截取 16:9 视口，存到相册 Pictures/DSHA");
    } catch (Throwable error) {
      return com.deepseekharness.app.util.UiText.format(
          "保存失败：%s",
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    } finally {
      try {
        if (os != null) os.close();
      } catch (Throwable ignored) {
      }
    }
  }

  /** 左上角三档按钮：只写模式1/2/3，分辨率放无障碍描述里。 */
  private void buildModeButtons(FrameLayout root) {
    // 与设置页 chip 同一套：radius_small 圆角、11sp、选中主色实心。
    int gap = dp(4);
    int bh = dp(48);
    int bw = dp(48);
    for (int i = 0; i < MODE_LABELS.length; i++) {
      final int m = i;
      TextView b = new TextView(this);
      b.setText(com.deepseekharness.app.util.UiText.text(MODE_LABELS[i]));
      b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
      b.setGravity(Gravity.CENTER);
      b.setIncludeFontPadding(false);
      b.setSingleLine(true);
      b.setClickable(true);
      b.setFocusable(true);
      b.setContentDescription(
          com.deepseekharness.app.util.UiText.text(MODE_LABELS[i])
              + " "
              + DshStageGeometry.cssWidth(i)
              + "×"
              + DshStageGeometry.cssHeight(i));
      b.setOnClickListener(v -> setMode(m));
      FrameLayout.LayoutParams lp =
          new FrameLayout.LayoutParams(bw, bh, Gravity.START | Gravity.TOP);
      lp.leftMargin = dp(MODE_MIN_LEFT_DP);
      lp.topMargin = dp(14) + i * (bh + gap);
      modeBtns[i] = b;
      root.addView(b, lp);
    }
    paintModeButtons();
  }

  /**
   * 三档 chip 的位置：**放在 16:9 视口左侧的空白区里**，不压网页。
   *
   * <p>210 早版本只在滚动条左边留了一条固定边距，视口一旦贴近屏幕左边（空白区变窄），
   * chip 就被推到视口里面压在网页上。现在改成按 {@code viewLeft}（视口左边界）算：
   *
   * <ul>
   *   <li>空白区够宽：44dp 的 chip 在空白区里水平居中（既不贴屏幕边，也不进视口）；
   *   <li>空白区偏窄：chip 宽度跟着收（最小 30dp），仍然只在空白区内；
   *   <li>还没量出视口（首帧）：按滚动条位置兜底，避免闪一下。
   * </ul>
   */
  private void layoutModeButtons(int barX) {
    int maxBtnW = dp(48);
    int minBtnW = dp(30);
    int stageLeft = screen == null ? 0 : screen.viewLeft;
    int btnW = maxBtnW;
    int left;
    if (stageLeft > dp(8)) {
      int free = stageLeft - dp(4);
      if (free >= maxBtnW + dp(4)) {
        left = Math.max(dp(4), (stageLeft - maxBtnW) / 2);
      } else {
        btnW = Math.max(minBtnW, Math.min(maxBtnW, free));
        left = Math.max(dp(2), (stageLeft - btnW) / 2);
      }
      // 再保险一次：右边界不许越过视口左边界。
      int maxLeft = Math.max(dp(2), stageLeft - btnW - dp(2));
      if (left > maxLeft) left = maxLeft;
    } else {
      left = Math.max(dp(10), barX - dp(16) - maxBtnW);
    }
    // 胶囊在左时 chip 换到右侧空白区，避免两者叠在同一块留边里。
    if (cfg.chrome && LandscapeConfig.CAPSULE_LEFT.equals(cfg.capsule)) {
      int rootW =
          screen == null ? getResources().getDisplayMetrics().widthPixels : screen.getWidth();
      int stageRight = screen == null ? 0 : screen.viewLeft + screen.viewW;
      int freeRight = Math.max(0, rootW - stageRight);
      if (freeRight >= maxBtnW + dp(4)) {
        left = stageRight + Math.max(dp(4), (freeRight - maxBtnW) / 2);
      } else {
        left = Math.max(dp(2), rootW - maxBtnW - dp(4));
      }
    }
    // 空白很窄时 chip 会被收窄，字号一起收一点，文字不至于被裁掉。
    float textSp = btnW < dp(40) ? 10f : 11f;
    for (TextView b : modeBtns) {
      if (b == null) continue;
      FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
      if (lp.width != btnW || lp.leftMargin != left) {
        lp.width = btnW;
        lp.leftMargin = left;
        b.setLayoutParams(lp);
      }
      if (lastChipTextSp != textSp) {
        lastChipTextSp = textSp;
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
      }
      if (b.getAlpha() != 1f) b.setAlpha(1f);
      b.setVisibility(cfg.chrome ? View.VISIBLE : View.GONE);
    }
  }

  private void applyDockStyle() {
    if (dock == null) return;
    // 212：胶囊不再随模式变大小 —— 三档下都保持右侧居中、同一尺寸。
    int item = dp(DOCK_ITEM_BIG);
    int pad = dp(4);
    dock.setPadding(pad, pad, pad, pad);
    for (int i = 0; i < dock.getChildCount(); i++) {
      View c = dock.getChildAt(i);
      try {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
        if (lp.width != item || lp.height != item) {
          lp.width = item;
          lp.height = item;
          c.setLayoutParams(lp);
        }
        if (c instanceof ImageView) {
          // 212：胶囊不再随模式变大小，内边距固定。
          int iconPad = dp(10);
          c.setPadding(iconPad, iconPad, iconPad, iconPad);
        }
      } catch (Throwable ignored) {
      }
    }
    layoutDock();
  }

  private void layoutDock() {
    if (dock == null) return;
    // 胶囊位置与显隐由 dsh-a-landscape.json 的 capsule 决定（right 默认 / left / hide）。
    boolean show = cfg.chrome && !LandscapeConfig.CAPSULE_HIDE.equals(cfg.capsule);
    if (!show) {
      if (dock.getVisibility() != View.GONE) dock.setVisibility(View.GONE);
      return;
    }
    boolean left = LandscapeConfig.CAPSULE_LEFT.equals(cfg.capsule);
    // 212：模式2 不再把胶囊甩到左下角；三档统一垂直居中，左右由配置决定。
    FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dock.getLayoutParams();
    int gravity = (left ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
    int leftMargin = left ? dp(18) : 0;
    int rightMargin = left ? 0 : dp(18);
    if (dl.gravity != gravity
        || dl.leftMargin != leftMargin
        || dl.rightMargin != rightMargin
        || dl.bottomMargin != 0) {
      dl.gravity = gravity;
      dl.leftMargin = leftMargin;
      dl.rightMargin = rightMargin;
      dl.bottomMargin = 0;
      dock.setLayoutParams(dl);
    }
    if (dock.getVisibility() != View.VISIBLE) dock.setVisibility(View.VISIBLE);
  }

  /**
   * 212：三档按钮换成「四角括号」样式（用户从四款里挑的 C）。
   *
   * <p>直角矩形 + 极淡边框 + 四个 L 形括号；选中时括号亮青、外圈多一层辉光、内部压深蓝。
   * 全部 Canvas 现画（{@link BracketChipDrawable}），不依赖图片资源。
   */
  private void paintModeButtons() {
    float density = getResources().getDisplayMetrics().density;
    for (int i = 0; i < modeBtns.length; i++) {
      TextView b = modeBtns[i];
      if (b == null) continue;
      boolean on = (i == mode);
      b.setSelected(on);
      if (Build.VERSION.SDK_INT >= 30) {
        b.setStateDescription(on ? com.deepseekharness.app.util.UiText.text("已选中") : null);
      }
      b.setTextColor(on ? LandscapePalette.CHIP_TEXT_ON : LandscapePalette.CHIP_TEXT_OFF);
      BracketChipDrawable chip = new BracketChipDrawable(on);
      chip.setDensity(density);
      b.setBackground(chip);
    }
  }

  private void setMode(int m) {
    if (m < 0 || m >= DshStageGeometry.MODE_COUNT) m = 0;
    // 人类操作：快速连点会让整块桌面视口连续重排；250ms 内只认第一次。
    long now = android.os.SystemClock.uptimeMillis();
    if (now - lastModeSwitchAt < 250) return;
    lastModeSwitchAt = now;
    if (mode == m) {
      if (m != 0) m = 0;
      else return;
    }
    mode = m;
    paintModeButtons();
    applyDockStyle();
    if (screen != null) screen.reapply();
    String js =
        "(function(){try{if(window.__whaleScroll&&window.__whaleScroll.kick)"
            + "window.__whaleScroll.kick();}catch(e){}})()";
    try {
      if (web != null) web.evaluateJavascript(js, null);
    } catch (Throwable ignored) {
    }
    toast(
        com.deepseekharness.app.util.UiText.text(MODE_LABELS[m])
            + " · "
            + DshStageGeometry.cssWidth(m)
            + "×"
            + DshStageGeometry.cssHeight(m));
  }

  private void toast(String msg) {
    try {
      Toast.makeText(this, com.deepseekharness.app.util.UiText.text(msg), Toast.LENGTH_SHORT)
          .show();
    } catch (Throwable ignored) {
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    setupWindow();
    if (screen != null) screen.reapply();
  }

  /**
   * 声明了 {@code configChanges}，旋转不会重建 Activity；但系统栏可能被系统重新显示，
   * 桌面视口也要按新窗口尺寸重算 —— 这两件都在这里补。
   */
  @Override
  public void onConfigurationChanged(android.content.res.Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    setupWindow();
    // 旋转后满屏高度变了：刷新一次，并让缩放按新尺寸重算（lastScale 是老朝向的，
    // 键盘态沿用它会把页面缩错）。
    try {
      android.util.DisplayMetrics dm = readRealMetrics();
      fullScreenH = Math.min(dm.widthPixels, dm.heightPixels);
    } catch (Throwable ignored) {
    }
    lastScale = 0f;
    ui.removeCallbacks(imeSettle);
    ui.postDelayed(imeSettle, 150);
    if (screen != null) screen.reapply();
  }

  /** 真实显示器尺寸（含系统栏）；横屏 Activity 里短边就是满屏高度。 */
  private android.util.DisplayMetrics readRealMetrics() {
    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
    try {
      getWindowManager().getDefaultDisplay().getRealMetrics(dm);
    } catch (Throwable ignored) {
      dm.widthPixels = getResources().getDisplayMetrics().widthPixels;
      dm.heightPixels = getResources().getDisplayMetrics().heightPixels;
    }
    fullScreenH = Math.min(dm.widthPixels, dm.heightPixels);
    return dm;
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) setupWindow();
  }

  @Override
  protected void onPause() {
    super.onPause();
    try {
      CookieManager.getInstance().flush();
    } catch (Throwable ignored) {
    }
  }

  /** 先摘掉客户端与 JS 桥再销毁 WebView，避免 renderer 已死时继续回调。 */
  private void releaseWebSurface() {
    if (web == null) return;
    try {
      web.setWebViewClient(null);
      web.setWebChromeClient(null);
      if (compatibilityScript != null) {
        try {
          compatibilityScript.remove();
        } catch (Throwable ignored) {
        }
        compatibilityScript = null;
      }
      ViewGroup parent = (ViewGroup) web.getParent();
      if (parent != null) parent.removeView(web);
      web.stopLoading();
      web.destroy();
    } catch (Throwable ignored) {
    }
    web = null;
  }

  /** 下载/文件选择等外部资源的统一清理（destroy 与 renderer 崩溃共用）。 */
  private void cleanupDownloadsAndPickers() {
    try {
      if (blobDownload != null) blobDownload.close();
    } catch (Throwable ignored) {
    }
    blobDownload = null;
    try {
      if (downloads != null) downloads.dismiss();
    } catch (Throwable ignored) {
    }
    downloads = null;
    if (fileCb != null) {
      try {
        fileCb.onReceiveValue(null);
      } catch (Throwable ignored) {
      }
      fileCb = null;
    }
  }

  @Override
  protected void onDestroy() {
    // 撤掉还没跑的键盘几何回调与假窗口回收，避免销毁后残留任务。
    ui.removeCallbacksAndMessages(null);
    cleanupDownloadsAndPickers();
    if (web != null) {
      releaseWebSurface();
    }
    try {
      CookieManager.getInstance().flush();
    } catch (Throwable ignored) {
    }
    super.onDestroy();
  }

  /** 保留给系统/外接键盘的返回键：走和手势返回同一条路径。 */
  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      goBack();
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }
}
