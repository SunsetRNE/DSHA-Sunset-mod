package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.R;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 两种网页内核共用安全显示区；系统栏、挖孔和输入法都不能覆盖正文。 */
public final class WebFullscreenUi {
  /** 避免全局页面边距处理覆盖网页的全屏设置。 */
  public interface Host {}

  private WebFullscreenUi() {}

  public static void install(Activity activity) {
    // 旧 Android 的 FLAG_FULLSCREEN 会阻止 adjustResize，不能依赖输入法出现时再取消全屏。
    activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    View content = activity.findViewById(android.R.id.content);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    new ContentInsets(activity, content);
    applySystemBars(activity);
    ViewCompat.requestApplyInsets(content);
  }

  /** 容器统一处理尺寸，子浏览器不能再次扣除同一份安全区和输入法边距。 */
  static final class ContentInsets extends WindowInsetsAnimationCompat.Callback
      implements OnApplyWindowInsetsListener,
          ViewTreeObserver.OnWindowFocusChangeListener,
          View.OnAttachStateChangeListener {
    private final Activity activity;
    private final View content;
    private final Set<WindowInsetsAnimationCompat> imeAnimations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private ViewTreeObserver focusObserver;
    private Insets safe = Insets.NONE;
    private int keyboard;
    private boolean attached;

    ContentInsets(Activity activity, View content) {
      super(DISPATCH_MODE_STOP);
      this.activity = activity;
      this.content = content;
      // Core 1.9 先调用同一 View 的 API21 动画适配，再派发普通边距监听。
      // 此处消费仍保留兼容动画，并避免 WebView 再扣一次输入法边距。
      ViewCompat.setWindowInsetsAnimationCallback(content, this);
      ViewCompat.setOnApplyWindowInsetsListener(content, this);
      content.addOnAttachStateChangeListener(this);
      if (ViewCompat.isAttachedToWindow(content)) onViewAttachedToWindow(content);
    }

    private boolean ownsKeyboard() {
      return attached
          && content.hasWindowFocus()
          && !activity.isFinishing()
          && !activity.isDestroyed()
          && !PictureInPictureActivity.showing(activity);
    }

    private void forgetKeyboard() {
      imeAnimations.clear();
      keyboard = 0;
    }

    @Override
    public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
      safe = safeInsets(insets);
      if (!ownsKeyboard()) forgetKeyboard();
      // prepare 与 start 之间的 onApply 已是终态；先保留当前输入法高度，
      // 等 progress 提供实际起始或中间帧，不能提前跳到终点。
      else if (imeAnimations.isEmpty())
        keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
      render();
      return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onPrepare(WindowInsetsAnimationCompat animation) {
      if (!ownsKeyboard()) {
        forgetKeyboard();
        render();
      } else if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
        imeAnimations.add(animation);
      }
    }

    @Override
    public WindowInsetsCompat onProgress(
        WindowInsetsCompat insets, List<WindowInsetsAnimationCompat> runningAnimations) {
      if (attached) safe = safeInsets(insets);
      if (!ownsKeyboard()) forgetKeyboard();
      else if (runningAnimations.stream().anyMatch(imeAnimations::contains))
        keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
      render();
      return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onEnd(WindowInsetsAnimationCompat animation) {
      if (imeAnimations.remove(animation) && imeAnimations.isEmpty() && attached) {
        // 取消也会结束动画；请求窗口当前边距，不重放先前缓存的终态。
        // 新派发到达前保留最后一帧的实际高度。
        ViewCompat.requestApplyInsets(content);
      }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
      if (!hasFocus) {
        forgetKeyboard();
        render();
      } else if (attached) ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onViewAttachedToWindow(View view) {
      attached = true;
      focusObserver = content.getViewTreeObserver();
      focusObserver.addOnWindowFocusChangeListener(this);
      ViewCompat.requestApplyInsets(content);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
      attached = false;
      if (focusObserver != null && focusObserver.isAlive())
        focusObserver.removeOnWindowFocusChangeListener(this);
      focusObserver = null;
      forgetKeyboard();
      render();
    }

    private void render() {
      Insets padding =
          PictureInPictureActivity.showing(activity)
              ? Insets.NONE
              : Insets.of(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
      if (content.getPaddingLeft() != padding.left
          || content.getPaddingTop() != padding.top
          || content.getPaddingRight() != padding.right
          || content.getPaddingBottom() != padding.bottom)
        content.setPadding(padding.left, padding.top, padding.right, padding.bottom);
    }
  }

  private static Insets safeInsets(WindowInsetsCompat insets) {
    Insets bars =
        insets.getInsets(
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
    // 焦点或输入法切换时仍保留稳定状态栏、挖孔与小窗标题栏安全区。
    Insets stableTop =
        insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.displayCutout()
                | WindowInsetsCompat.Type.captionBar());
    return Insets.max(bars, stableTop);
  }

  public static void applySystemBars(Activity activity) {
    if (PictureInPictureActivity.showing(activity)) return;
    WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    int color =
        activity instanceof PictureInPictureActivity host
            ? host.browserThemeColor()
            : activity.getColor(R.color.surface);
    View content = activity.findViewById(android.R.id.content);
    if (content != null) {
      int left = content.getPaddingLeft(), top = content.getPaddingTop();
      int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
      content.setBackgroundColor(color);
      content.setPadding(left, top, right, bottom);
    }
    controller.setAppearanceLightStatusBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    controller.setAppearanceLightNavigationBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    activity
        .getWindow()
        .setNavigationBarColor(
            android.os.Build.VERSION.SDK_INT >= 26
                ? color
                : activity.getColor(R.color.terminal_surface));
    controller.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    // 显示状态栏，正文由 insets 避让；底部仍可通过手势唤出系统导航。
    controller.show(WindowInsetsCompat.Type.statusBars());
    controller.hide(WindowInsetsCompat.Type.navigationBars());
  }

  /** 当前实例核验后的主题色只改变绘制背景和图标，不重新加载页面。 */
  public static void applyThemeColor(Activity activity, int color) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    if (activity instanceof PictureInPictureActivity host) host.applyBrowserTheme(color);
    applySystemBars(activity);
  }
}
