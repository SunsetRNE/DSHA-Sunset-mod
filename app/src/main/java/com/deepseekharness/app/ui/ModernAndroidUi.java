package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** 统一处理 Android 15+ 强制铺满窗口后的状态栏、挖孔和输入法区域。 */
public final class ModernAndroidUi implements Application.ActivityLifecycleCallbacks {
  @Override
  public void onActivityPostCreated(Activity activity, Bundle saved) {
    applyInsets(activity);
  }

  private void applyInsets(Activity activity) {
    if (activity instanceof WebFullscreenUi.Host || activity instanceof TerminalActivity) return;
    View content = activity.findViewById(android.R.id.content);
    if (content == null) return;
    if (android.os.Build.VERSION.SDK_INT >= 29) content.setForceDarkAllowed(false);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    // 壁纸铺在 content 背景上：页面根布局保持透明就能透出同一张底图，
    // 系统栏也跟着一起透，不再各自铺一块不透明底色。
    content.setBackground(WindowBackdrop.create(activity));
    boolean light = !isNight(activity);
    androidx.core.view.WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(activity.getWindow(), content);
    controller.setAppearanceLightStatusBars(light);
    controller.setAppearanceLightNavigationBars(light);
    final int left = content.getPaddingLeft(), top = content.getPaddingTop();
    final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(
        content,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
          view.setPadding(
              left + bars.left,
              top + bars.top,
              right + bars.right,
              bottom + Math.max(bars.bottom, keyboard));
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(content);
  }

  /** 系统栏图标颜色跟着当前日夜走：深色壁纸配浅色图标。 */
  private static boolean isNight(Activity activity) {
    return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  @Override
  public void onActivityCreated(Activity activity, Bundle saved) {
    if (android.os.Build.VERSION.SDK_INT < 29)
      activity
          .getWindow()
          .getDecorView()
          .post(
              () -> {
                if (!activity.isFinishing()) applyInsets(activity);
              });
  }

  @Override
  public void onActivityStarted(Activity activity) {}

  @Override
  public void onActivityResumed(Activity activity) {}

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

  @Override
  public void onActivityDestroyed(Activity activity) {}
}
