package com.deepseekharness.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;
import androidx.fragment.app.FragmentTransaction;
import com.deepseekharness.app.R;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v5 动效统一入口。
 *
 * <p>页面、弹窗、抽屉与列表共用同一条 cubic-bezier(.22, 1, .36, 1) 曲线；系统关闭动画时
 * 一律退回静态状态，不用“看起来像动效”的假进度或无限动画掩盖真实状态。
 *
 * <p>跨包只读入口是 {@link #animationsEnabled(Context)}（虚拟屏提示条据此决定是否呼吸），
 * 页面转场仍只给本包使用。
 */
public final class UiMotion {
  private static final Interpolator STANDARD = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

  /** 正在运行的数值过渡；同一 TextView 再次计数时先取消旧的，避免两段动画互相覆盖文本。 */
  private static final Map<TextView, ValueAnimator> COUNTERS = new WeakHashMap<>();

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

  /**
   * 主 tab 之间切换：按 tab 顺序横向滑入，{@code forward} 表示目标在右侧。
   *
   * <p>只用于根页互切；从子页返回、门禁页恢复等非横向关系仍走 {@link #page}。
   */
  static FragmentTransaction tab(
      Context context, FragmentTransaction transaction, boolean forward) {
    transaction.setReorderingAllowed(true);
    try {
      if (animationsEnabled(context)) {
        if (forward)
          transaction.setCustomAnimations(R.anim.tab_enter_forward, R.anim.tab_exit_forward);
        else transaction.setCustomAnimations(R.anim.tab_enter_backward, R.anim.tab_exit_backward);
      }
    } catch (RuntimeException ignored) {
      /* 读取设置失败仍可正常导航。 */
    }
    return transaction;
  }

  /** 系统动画总开关；读设置失败按开启处理，不因此阻断页面操作。 */
  public static boolean animationsEnabled(Context context) {
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

  /**
   * 状态文本切换：文字未变直接返回，变化时先写入新值再淡入（150ms）。
   *
   * <p>不做「先淡出旧文字、90ms 后再换」的两段式：状态文本可能每秒刷新，半程被下一次调用取消时
   * 旧文字会停在界面上。先写后淡入保证任何时刻显示的都是最新真实状态；系统关闭动画时直接落值。
   */
  static void crossFadeText(TextView view, CharSequence text) {
    if (view == null) return;
    CharSequence next = text == null ? "" : text;
    if (TextUtils.equals(view.getText(), next)) {
      if (!animationsEnabled(view.getContext())) view.setAlpha(1f);
      return;
    }
    view.animate().cancel();
    view.setText(next);
    if (!animationsEnabled(view.getContext())) {
      view.setAlpha(1f);
      return;
    }
    view.setAlpha(0f);
    view.animate().alpha(1f).setDuration(150L).setInterpolator(STANDARD).start();
  }

  /** 计数 / 计时数值过渡（200ms）；关闭动画、当前不是纯数字或目标等于当前值时直接落值。 */
  static void animateCounter(TextView view, int target) {
    if (view == null) return;
    Integer from = parseCount(view.getText());
    if (!animationsEnabled(view.getContext()) || from == null || from == target) {
      view.setText(String.valueOf(target));
      return;
    }
    animateCounter(view, from, target, value -> String.valueOf(Math.round(value)));
  }

  /** 小数计数（如秒表 1.5s）过渡；格式固定一位小数，调用方需自行保证一致。 */
  static void animateCounter(TextView view, float target) {
    if (view == null) return;
    Float from = parseDecimal(view.getText());
    if (!animationsEnabled(view.getContext()) || from == null || from == target) {
      view.setText(String.format(java.util.Locale.ROOT, "%.1f", target));
      return;
    }
    animateCounter(
        view, from, target, value -> String.format(java.util.Locale.ROOT, "%.1f", value));
  }

  private static void animateCounter(
      TextView view, float from, float target, java.util.function.Function<Float, String> render) {
    COUNTERS.computeIfPresent(
        view,
        (ignored, running) -> {
          running.cancel();
          return null;
        });
    ValueAnimator animator = ValueAnimator.ofFloat(from, target);
    animator.setDuration(200L);
    animator.setInterpolator(STANDARD);
    animator.addUpdateListener(
        running -> view.setText(render.apply((Float) running.getAnimatedValue())));
    animator.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator animation) {
            if (COUNTERS.get(view) == animation) COUNTERS.remove(view);
          }
        });
    COUNTERS.put(view, animator);
    animator.start();
  }

  private static Integer parseCount(CharSequence text) {
    try {
      return Integer.parseInt(text == null ? "" : text.toString().trim());
    } catch (RuntimeException notANumber) {
      return null;
    }
  }

  private static Float parseDecimal(CharSequence text) {
    try {
      return Float.parseFloat(text == null ? "" : text.toString().trim());
    } catch (RuntimeException notANumber) {
      return null;
    }
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
   * 按下轻微缩放与触觉反馈；返回 false 不吞触摸，点击与无障碍仍由原控件处理。
   *
   * <p>只给没有自定义 OnTouchListener 的按钮使用，避免覆盖业务手势。
   */
  static void pressScale(View view) {
    if (view == null) return;
    view.setOnTouchListener(
        (target, event) -> {
          int action = event.getActionMasked();
          if (action == MotionEvent.ACTION_DOWN && target.isEnabled()) {
            try {
              target.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } catch (RuntimeException ignored) {
              /* 单元测试或无震动器设备忽略。 */
            }
          }
          if (!animationsEnabled(target.getContext())) return false;
          switch (action) {
            case MotionEvent.ACTION_DOWN -> {
              if (target.isEnabled()) {
                target
                    .animate()
                    .scaleX(0.965f)
                    .scaleY(0.965f)
                    .alpha(0.92f)
                    .setDuration(80L)
                    .setInterpolator(STANDARD)
                    .start();
              }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                target
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150L)
                    .setInterpolator(STANDARD)
                    .start();
            default -> {}
          }
          return false;
        });
  }

  static void pressScaleAll(View... views) {
    if (views == null) return;
    for (View view : views) pressScale(view);
  }

  /**
   * 递归扫描视图树，为所有普通 Button（不含 CheckBox/Switch/RadioButton）和 ImageButton 挂载按压缩放与触觉反馈。
   */
  static void attachHierarchyPressScale(View root) {
    if (root == null) return;
    if (root instanceof android.widget.ImageButton) {
      pressScale(root);
    } else if (root instanceof android.widget.Button
        && !(root instanceof android.widget.CompoundButton)) {
      pressScale(root);
    }
    if (root instanceof ViewGroup group) {
      int count = group.getChildCount();
      for (int i = 0; i < count; i++) {
        attachHierarchyPressScale(group.getChildAt(i));
      }
    }
  }
}
