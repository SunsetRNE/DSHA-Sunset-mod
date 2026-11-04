package com.deepseekharness.app.ui;

import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.R;

/**
 * 横屏移植试验探针（2026-11-03）。
 *
 * <p>目的：在 build294 上先用最小代码验证三件机制，再决定要不要搬 2200 行的
 * DockDisplayActivity：
 *
 * <ol>
 *   <li>16:9 桌面视口：逻辑 1280x720 的舞台，按 min 缩放并居中；
 *   <li>系统栏全隐：setDecorFitsSystemWindows(false) + InsetsController.hide(status|nav)
 *       + 下滑临时唤出；
 *   <li>安全区可视化：把 displayCutout / systemBars / ime insets 画成色块，
 *       并判定 16:9 舞台有没有侵入这四边。
 * </ol>
 *
 * <p>不依赖 DockDisplayActivity 的实现，只在 {@link ModernAndroidUi} 的免 padding 名单里
 * 登记本页（否则会被自动加 systemBars/cutout padding，和 16:9 舞台打架）；退出用右下角按钮。
 */
public class LandscapeProbeActivity extends AppCompatActivity {
  private static final String TAG = "LandscapeProbe";
  private static final float RATIO = 16f / 9f;
  private static final int LOGIC_W = 1280;
  private static final int LOGIC_H = 720;

  private FrameLayout root;
  private FrameLayout stage;
  private ProbeView probe;
  private TextView hud;

  private Insets cut = Insets.NONE;
  private Insets bars = Insets.NONE;
  private Insets ime = Insets.NONE;
  private int stageW;
  private int stageH;
  private int stageLeft;
  private int stageTop;
  private float scale = 1f;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // 机制探针只在可调试构建可用：release 包即使用户侥幸拿到入口也不进入。
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
      finish();
      return;
    }
    // 与旧版 DockDisplayActivity 对齐：AppCompatActivity + 项目主题 + 主动请求横屏。
    // 所有初始化都兜住，真崩了也只弹 Toast（不依赖写文件）。
    try {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    } catch (Throwable ignored) {
    }
    try {
      setupWindow();
      buildUi();
    } catch (Throwable t) {
      reportCrash(t);
    }
  }

  /** 崩溃兜底：Toast 给「异常类型: 消息 + 第一帧（类.方法:行号）」，日志带完整堆栈。 */
  private void reportCrash(Throwable t) {
    String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
    StackTraceElement e = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
    if (e != null)
      msg += "\n@" + e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber();
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    Log.e(TAG, "probe crash", t);
    finish();
  }

  /** 与 DockDisplayActivity.setupWindow 同款：整个方法包 try-catch，失败不外抛。 */
  private void setupWindow() {
    try {
      WindowManager.LayoutParams wlp = getWindow().getAttributes();
      if (Build.VERSION.SDK_INT >= 28) {
        wlp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wlp);
      }
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
      getWindow().setStatusBarColor(Color.TRANSPARENT);
      getWindow().setNavigationBarColor(Color.TRANSPARENT);
      if (Build.VERSION.SDK_INT >= 30) {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
          ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
          ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
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

  private void buildUi() {
    root = new FrameLayout(this);
    // 留边（含左侧挖孔安全区）铺 289 那张雾白渐变，别让安全区露出一条黑边。
    root.setBackgroundResource(R.drawable.bg_dsh_stage);

    stage = new FrameLayout(this);
    stage.setClipChildren(false);
    stage.addView(new StageContent(this), new FrameLayout.LayoutParams(-1, -1));
    root.addView(
        stage, new FrameLayout.LayoutParams(LOGIC_W, LOGIC_H, Gravity.TOP | Gravity.START));

    probe = new ProbeView(this);
    root.addView(probe, new FrameLayout.LayoutParams(-1, -1));

    hud = new TextView(this);
    hud.setTextColor(Color.WHITE);
    hud.setTextSize(11);
    hud.setLineSpacing(4, 1f);
    hud.setBackgroundColor(0xB3000000);
    hud.setPadding(dp(12), dp(8), dp(12), dp(8));
    root.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

    TextView exit = new TextView(this);
    exit.setText(com.deepseekharness.app.util.UiText.text("退出"));
    exit.setTextColor(Color.BLACK);
    exit.setTextSize(14);
    exit.setBackgroundColor(0xFFFFFFFF);
    exit.setPadding(dp(18), dp(8), dp(18), dp(8));
    exit.setOnClickListener(v -> finish());
    root.addView(exit, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));

    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            cut = insets.getInsets(WindowInsets.Type.displayCutout());
            bars = insets.getInsets(WindowInsets.Type.systemBars());
            ime = insets.getInsets(WindowInsets.Type.ime());
            Log.i(TAG, "cutout=" + cut + " bars=" + bars + " ime=" + ime);
          }
          updateHud();
          probe.invalidate();
          return insets;
        });

    setContentView(root);
    root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyStage());
  }

  /** 16:9 舞台：逻辑 1280x720，按 min 缩放、居中；四周是舞台底色。 */
  private void applyStage() {
    int w = root.getWidth();
    int h = root.getHeight();
    if (w <= 0 || h <= 0) return;
    int nw;
    int nh;
    if ((float) w / h > RATIO) {
      nh = h;
      nw = Math.round(h * RATIO);
    } else {
      nw = w;
      nh = Math.round(w / RATIO);
    }
    scale = Math.min(nw / (float) LOGIC_W, nh / (float) LOGIC_H);
    stage.setPivotX(0f);
    stage.setPivotY(0f);
    stage.setScaleX(scale);
    stage.setScaleY(scale);
    stageW = Math.round(LOGIC_W * scale);
    stageH = Math.round(LOGIC_H * scale);
    stageLeft = Math.round((w - stageW) / 2f);
    stageTop = Math.round((h - stageH) / 2f);
    stage.setX(stageLeft);
    stage.setY(stageTop);
    updateHud();
    probe.invalidate();
    Log.i(
        TAG,
        "stage=" + stageW + "x" + stageH + "@" + stageLeft + "," + stageTop + " scale=" + scale);
  }

  private boolean stageHitsCutout() {
    int w = root.getWidth();
    int h = root.getHeight();
    if (cut.left > 0 && stageLeft < cut.left) return true;
    if (cut.right > 0 && stageLeft + stageW > w - cut.right) return true;
    if (cut.top > 0 && stageTop < cut.top) return true;
    if (cut.bottom > 0 && stageTop + stageH > h - cut.bottom) return true;
    return false;
  }

  private void updateHud() {
    if (hud == null) return;
    boolean hit = stageHitsCutout();
    String cutoutValues = cut.left + " / " + cut.top + " / " + cut.right + " / " + cut.bottom;
    String barsValues = bars.left + " / " + bars.top + " / " + bars.right + " / " + bars.bottom;
    String text =
        com.deepseekharness.app.util.UiText.format(
            "cutout L/T/R/B = %s\nbars L/T/R/B = %s\nime bottom = %s\nstage %s @(%s,%s) scale=%s\n16:9 居中：%s\n与 cutout 相交：%s\n下滑可临时唤出系统栏",
            cutoutValues,
            barsValues,
            ime.bottom,
            stageW + "x" + stageH,
            stageLeft,
            stageTop,
            String.format(java.util.Locale.ROOT, "%.3f", scale),
            stageLeft > 0 || stageTop > 0
                ? com.deepseekharness.app.util.UiText.text("有留边")
                : com.deepseekharness.app.util.UiText.text("贴边"),
            hit
                ? com.deepseekharness.app.util.UiText.text("是 !")
                : com.deepseekharness.app.util.UiText.text("否 OK"));
    hud.setText(text);
  }

  @Override
  protected void onResume() {
    super.onResume();
    setupWindow();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) setupWindow();
  }

  private int dp(int v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  /** 舞台里的"网页"替身：网格 + 中心十字，方便肉眼确认缩放与居中。 */
  private static final class StageContent extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    StageContent(android.content.Context c) {
      super(c);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      canvas.drawColor(0xFFF4F6FA);
      paint.setColor(0xFFD7DEE8);
      paint.setStrokeWidth(1f);
      int step = 80;
      for (int x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), paint);
      for (int y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, paint);
      paint.setColor(0xFF1F6FEB);
      paint.setStrokeWidth(3f);
      canvas.drawLine(
          getWidth() / 2f - 40, getHeight() / 2f, getWidth() / 2f + 40, getHeight() / 2f, paint);
      canvas.drawLine(
          getWidth() / 2f, getHeight() / 2f - 40, getWidth() / 2f, getHeight() / 2f + 40, paint);
      paint.setColor(0xFF94A3B8);
      paint.setTextSize(28f);
      canvas.drawText("16:9 logical 1280x720", 32, 56, paint);
      canvas.drawText("DPI " + getResources().getDisplayMetrics().density, 32, 96, paint);
    }
  }

  /** 安全区可视化：cutout 红、systemBars 蓝、16:9 舞台黄框。 */
  private final class ProbeView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    ProbeView(android.content.Context c) {
      super(c);
      fill.setStyle(Paint.Style.FILL);
      stroke.setStyle(Paint.Style.STROKE);
      stroke.setStrokeWidth(dp(2));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      int w = getWidth();
      int h = getHeight();

      if (cut.left > 0) canvas.drawRect(0, 0, cut.left, h, fillWith(0x66FF3B30));
      if (cut.right > 0) canvas.drawRect(w - cut.right, 0, w, h, fillWith(0x66FF3B30));
      if (cut.top > 0) canvas.drawRect(0, 0, w, cut.top, fillWith(0x66FF3B30));
      if (cut.bottom > 0) canvas.drawRect(0, h - cut.bottom, w, h, fillWith(0x66FF3B30));

      stroke.setColor(0x990A84FF);
      if (bars.left > 0) canvas.drawRect(1, 0, bars.left, h, stroke);
      if (bars.right > 0) canvas.drawRect(w - bars.right, 0, w - 1, h, stroke);
      if (bars.top > 0) canvas.drawRect(0, 1, w, bars.top, stroke);
      if (bars.bottom > 0) canvas.drawRect(0, h - bars.bottom, w, h - 1, stroke);

      stroke.setColor(0xFFFFD60A);
      canvas.drawRect(stageLeft, stageTop, stageLeft + stageW, stageTop + stageH, stroke);

      Rect stageRect = new Rect(stageLeft, stageTop, stageLeft + stageW, stageTop + stageH);
      Rect cutLeftRect = new Rect(0, 0, cut.left, h);
      Rect cutRightRect = new Rect(w - cut.right, 0, w, h);
      Rect cutTopRect = new Rect(0, 0, w, cut.top);
      Rect cutBottomRect = new Rect(0, h - cut.bottom, w, h);
      if (Rect.intersects(stageRect, cutLeftRect)
          || Rect.intersects(stageRect, cutRightRect)
          || Rect.intersects(stageRect, cutTopRect)
          || Rect.intersects(stageRect, cutBottomRect)) {
        stroke.setColor(0xFFFF3B30);
        stroke.setStrokeWidth(dp(4));
        canvas.drawRect(stageLeft, stageTop, stageLeft + stageW, stageTop + stageH, stroke);
      }
    }

    private Paint fillWith(int color) {
      fill.setColor(color);
      return fill;
    }
  }
}
