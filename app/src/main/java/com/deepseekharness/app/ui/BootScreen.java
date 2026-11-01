package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.BootPalette;
import com.deepseekharness.app.util.UiText;

/**
 * 应用开机加载页：复刻 DSH 网页启动画面（字标 HARNESS + 圆环 + 一行状态）。
 *
 * <ul>
 *   <li>主界面路径使用宿主 shell 主题色，随应用的日间 / 夜间 / 跟随系统切换；
 *   <li>直启 Web 路径使用最近一次 DSH 网页上报的背景色推导前景，衔接 DSH 主题与外部主题壳；
 *   <li>手机与平板（最短边 ≥ 600dp）分别取尺寸，平板放大字标与圆环，底部操作区限宽居中。
 * </ul>
 *
 * <p>只表达真实阶段文字，不推算百分比；操作按钮延迟出现，卡住时仍能看诊断或回到主界面。
 */
final class BootScreen {
  /** 诊断入口延迟出现：正常冷启动不打扰，真正卡住时仍可达。 */
  static final long DIAGNOSTICS_DELAY_MS = 8_000L;

  private final Activity activity;
  private final FrameLayout root;
  private final TextView hint;
  private final LinearLayout actions;
  private final TextView diagnostics;
  private final TextView openMain;
  private final BootSpinnerView spinner;
  private final Integer savedStatusBar, savedNavigationBar;
  private final boolean savedLightStatus, savedLightNavigation;
  private final Runnable revealDiagnostics;

  BootScreen(Activity activity, BootPalette palette) {
    this.activity = activity;
    float density = activity.getResources().getDisplayMetrics().density;
    Configuration configuration = activity.getResources().getConfiguration();
    boolean tablet = configuration.smallestScreenWidthDp >= 600;

    root = new FrameLayout(activity);
    root.setBackgroundColor(palette.background);
    root.setClickable(true);
    root.setFocusable(true);

    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);

    TextView wordmark = new TextView(activity);
    // DSH 字标是品牌名，不随语言翻译。
    wordmark.setText(UiText.raw("HARNESS"));
    wordmark.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 20 : 16);
    wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    wordmark.setLetterSpacing(0.08f);
    wordmark.setTextColor(palette.primary);
    wordmark.setIncludeFontPadding(false);
    wordmark.setGravity(Gravity.CENTER);
    wordmark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    card.addView(wordmark, new LinearLayout.LayoutParams(-2, -2));

    spinner = new BootSpinnerView(activity);
    spinner.setColors(palette.border, palette.brand);
    spinner.setStrokeWidth((tablet ? 2.5f : 2f) * density);
    int spinnerSize = Math.round((tablet ? 26 : 20) * density);
    var spinnerParams = new LinearLayout.LayoutParams(spinnerSize, spinnerSize);
    int gap = Math.round((tablet ? 20 : 16) * density);
    spinnerParams.topMargin = gap;
    card.addView(spinner, spinnerParams);

    hint = new TextView(activity);
    hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 14 : 12);
    hint.setTextColor(palette.tertiary);
    hint.setGravity(Gravity.CENTER);
    hint.setLineSpacing(0, 1.2f);
    hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    int hintWidth = Math.round((tablet ? 420 : 280) * density);
    var hintParams = new LinearLayout.LayoutParams(-2, -2);
    hintParams.topMargin = gap;
    hint.setMaxWidth(hintWidth);
    card.addView(hint, hintParams);

    root.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

    actions = new LinearLayout(activity);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    actions.setGravity(Gravity.CENTER);
    actions.setVisibility(View.GONE);
    diagnostics = action(palette, tablet, UiText.choose("查看诊断", "View diagnostics"));
    openMain = action(palette, tablet, UiText.choose("进入主界面", "Open main screen"));
    openMain.setVisibility(View.GONE);
    actions.addView(diagnostics);
    actions.addView(openMain);
    var actionParams =
        new FrameLayout.LayoutParams(
            tablet ? Math.round(480 * density) : -1,
            -2,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    actionParams.bottomMargin = Math.round(16 * density);
    root.addView(actions, actionParams);

    // 遮罩盖住整窗：按系统栏与刘海避让内容，背景仍铺满。
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });

    // 系统栏随加载页底色；收起时还原 Activity 原值。
    var window = activity.getWindow();
    var controller = WindowCompat.getInsetsController(window, window.getDecorView());
    savedStatusBar = window.getStatusBarColor();
    savedNavigationBar = window.getNavigationBarColor();
    savedLightStatus = controller.isAppearanceLightStatusBars();
    savedLightNavigation = controller.isAppearanceLightNavigationBars();
    boolean light = !BootPalette.dark(palette.background);
    window.setStatusBarColor(palette.background);
    window.setNavigationBarColor(palette.background);
    controller.setAppearanceLightStatusBars(light);
    controller.setAppearanceLightNavigationBars(light);

    revealDiagnostics =
        () -> {
          if (root.getParent() == null) return;
          diagnostics.setVisibility(View.VISIBLE);
          actions.setVisibility(View.VISIBLE);
        };
    diagnostics.setOnClickListener(
        v -> activity.startActivity(DiagnosticActivity.downloadLogs(activity)));
    diagnostics.setVisibility(View.GONE);
  }

  /** 宿主 shell 主题色：主界面路径与“无网页色记录”的直启都用它。 */
  static BootPalette shellPalette(Activity activity) {
    return BootPalette.of(
        activity.getColor(R.color.shell_bg),
        activity.getColor(R.color.shell_text),
        activity.getColor(R.color.shell_text_muted),
        activity.getColor(R.color.shell_line_strong),
        activity.getColor(R.color.shell_text));
  }

  private TextView action(BootPalette palette, boolean tablet, String label) {
    float density = activity.getResources().getDisplayMetrics().density;
    var button = new androidx.appcompat.widget.AppCompatButton(activity, null, 0);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, tablet ? 14 : 13);
    button.setTextColor(palette.primary);
    button.setIncludeFontPadding(false);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(Math.round(48 * density));
    button.setMinimumHeight(Math.round(48 * density));
    int pad = Math.round(16 * density);
    button.setPadding(pad, 0, pad, 0);
    TypedValue ripple = new TypedValue();
    activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    button.setBackgroundResource(ripple.resourceId);
    var params = new LinearLayout.LayoutParams(0, -2, 1);
    button.setLayoutParams(params);
    return button;
  }

  void attach() {
    activity.addContentView(root, new ViewGroup.LayoutParams(-1, -1));
    ViewCompat.requestApplyInsets(root);
    root.postDelayed(revealDiagnostics, DIAGNOSTICS_DELAY_MS);
  }

  void setStatus(String text) {
    CharSequence current = hint.getText();
    if (text == null || current != null && text.contentEquals(current)) return;
    hint.setText(text);
  }

  /** 直启路径的退出口：始终可见，回到原生主界面。 */
  void offerMain(Runnable open) {
    openMain.setOnClickListener(v -> open.run());
    openMain.setVisibility(View.VISIBLE);
    actions.setVisibility(View.VISIBLE);
  }

  boolean attached() {
    return root.getParent() != null;
  }

  /** 淡出并移除；系统关闭动画时直接移除。系统栏还原到 Activity 原值。 */
  void dismiss() {
    root.removeCallbacks(revealDiagnostics);
    var window = activity.getWindow();
    var controller = WindowCompat.getInsetsController(window, window.getDecorView());
    window.setStatusBarColor(savedStatusBar);
    window.setNavigationBarColor(savedNavigationBar);
    controller.setAppearanceLightStatusBars(savedLightStatus);
    controller.setAppearanceLightNavigationBars(savedLightNavigation);
    ViewGroup parent = (ViewGroup) root.getParent();
    if (parent == null) return;
    if (!root.isAttachedToWindow() || !UiMotion.animationsEnabled(activity)) {
      parent.removeView(root);
      return;
    }
    root.animate()
        .alpha(0f)
        .setDuration(160L)
        .setInterpolator(UiMotion.standard())
        .withEndAction(
            () -> {
              if (root.getParent() == parent) parent.removeView(root);
            })
        .start();
  }
}
