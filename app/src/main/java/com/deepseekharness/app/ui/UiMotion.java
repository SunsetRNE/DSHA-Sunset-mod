package com.deepseekharness.app.ui;

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import androidx.fragment.app.FragmentTransaction;
import com.deepseekharness.app.R;

/**
 * v5 动效统一入口。
 *
 * <p>页面、弹窗、抽屉与列表共用同一条 cubic-bezier(.22, 1, .36, 1) 曲线；系统关闭动画时
 * 一律退回静态状态，不用“看起来像动效”的假进度或无限动画掩盖真实状态。
 */
final class UiMotion {
  private static final Interpolator STANDARD = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

  private UiMotion() {}

  static FragmentTransaction page(Context context, FragmentTransaction transaction) {
    transaction.setReorderingAllowed(true);
    try {
      if (animationsEnabled(context))
        transaction.setCustomAnimations(
            R.anim.page_enter, R.anim.page_exit, R.anim.page_enter, R.anim.page_exit);
    } catch (RuntimeException ignored) {
      /* 读取设置失败仍可正常导航。 */
    }
    return transaction;
  }

  /** 系统动画总开关；读设置失败按开启处理，不因此阻断页面操作。 */
  static boolean animationsEnabled(Context context) {
    try {
      return Settings.Global.getFloat(
                  context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
              > 0f
          && Settings.Global.getFloat(
                  context.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
              > 0f;
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  static Interpolator standard() {
    return STANDARD;
  }

  /** 列表首屏逐项淡入上移，最多 {@code maxItems} 项；重复调用不会重置已完成项。 */
  static void staggerIn(ViewGroup parent, int maxItems) {
    if (parent == null || maxItems <= 0 || !animationsEnabled(parent.getContext())) return;
    int count = Math.min(parent.getChildCount(), maxItems);
    float offset = 6f * parent.getResources().getDisplayMetrics().density;
    for (int index = 0; index < count; index++) {
      View child = parent.getChildAt(index);
      if (child == null || child.getVisibility() != View.VISIBLE) continue;
      child.setAlpha(0f);
      child.setTranslationY(offset);
      child
          .animate()
          .alpha(1f)
          .translationY(0f)
          .setStartDelay(index * 30L)
          .setDuration(200L)
          .setInterpolator(STANDARD)
          .start();
    }
  }

  /**
   * 按下轻微缩放；返回 false 不吞触摸，点击与无障碍仍由原控件处理。
   *
   * <p>只给没有自定义 OnTouchListener 的按钮使用，避免覆盖业务手势。
   */
  static void pressScale(View view) {
    if (view == null) return;
    view.setOnTouchListener(
        (target, event) -> {
          if (!animationsEnabled(target.getContext())) return false;
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN ->
                target
                    .animate()
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(90L)
                    .setInterpolator(STANDARD)
                    .start();
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(STANDARD)
                    .start();
            default -> {}
          }
          return false;
        });
  }
}
