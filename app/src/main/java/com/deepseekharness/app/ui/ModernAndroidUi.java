package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.deepseekharness.app.R;

/** 统一处理 Android 15+ 强制铺满窗口后的状态栏、挖孔和输入法区域。 */
public final class ModernAndroidUi implements Application.ActivityLifecycleCallbacks {
  @Override
  public void onActivityPostCreated(Activity activity, Bundle saved) {
    applyKeepScreenOn(activity);
    applyInsets(activity);
  }

  /**
   * 屏幕常亮：按设置给当前窗口挂/摘 FLAG_KEEP_SCREEN_ON。
   *
   * <p>放在 ActivityLifecycleCallbacks 里而不是各页面自己写：网页在 WebPreviewActivity、
   * 终端在 TerminalActivity，各自都是独立窗口，逐页补一定会漏。
   */
  private void applyKeepScreenOn(Activity activity) {
    try {
      boolean on = new com.deepseekharness.app.core.ConfigStore(activity).isKeepScreenOn();
      activity
          .getWindow()
          .setFlags(
              on ? android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0,
              android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } catch (Throwable ignored) {
      // 设置读不到不影响页面
    }
  }

  private void applyInsets(Activity activity) {
    if (activity instanceof WebFullscreenUi.Host || activity instanceof TerminalActivity) return;
    View content = activity.findViewById(android.R.id.content);
    if (content == null) return;
    if (android.os.Build.VERSION.SDK_INT >= 29) content.setForceDarkAllowed(false);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    int color = activity.getColor(R.color.surface);
    activity
        .getWindow()
        .setNavigationBarColor(
            android.os.Build.VERSION.SDK_INT >= 26
                ? color
                : activity.getColor(R.color.terminal_surface));
    content.setBackgroundColor(color);
    boolean light = ColorUtils.calculateLuminance(color) > 0.5;
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
  public void onActivityResumed(Activity activity) {
    applyKeepScreenOn(activity);
  }

  @Override
  public void onActivityPaused(Activity activity) {}

  @Override
  public void onActivityStopped(Activity activity) {}

  @Override
  public void onActivitySaveInstanceState(Activity activity, Bundle out) {}

  @Override
  public void onActivityDestroyed(Activity activity) {}
}
