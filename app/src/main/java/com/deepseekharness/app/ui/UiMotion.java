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
import java.util.function.LongFunction;

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

  private static final Map<TextView, TextColorTransition> TEXT_COLORS = new WeakHashMap<>();

  private record TextColorTransition(int target, ValueAnimator animator) {}

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
      if (view.getAlpha() != 1f && !animationsEnabled(view.getContext())) {
        view.animate().cancel();
        view.setAlpha(1f);
      }
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
      cancelCounter(view);
      setTextIfChanged(view, String.valueOf(target));
      return;
    }
    animateCounter(view, from, target, value -> String.valueOf(Math.round(value)));
  }

  /** 小数计数（如秒表 1.5s）过渡；格式固定一位小数，调用方需自行保证一致。 */
  static void animateCounter(TextView view, float target) {
    if (view == null) return;
    Float from = parseDecimal(view.getText());
    if (!animationsEnabled(view.getContext()) || from == null || from == target) {
      cancelCounter(view);
      setTextIfChanged(view, String.format(java.util.Locale.ROOT, "%.1f", target));
      return;
    }
    animateCounter(
        view, from, target, value -> String.format(java.util.Locale.ROOT, "%.1f", value));
  }

  private static void animateCounter(
      TextView view, float from, float target, java.util.function.Function<Float, String> render) {
    cancelCounter(view);
    ValueAnimator animator = ValueAnimator.ofFloat(from, target);
    animator.setDuration(200L);
    animator.setInterpolator(STANDARD);
    animator.addUpdateListener(
        running -> {
          if (COUNTERS.get(view) == running)
            setTextIfChanged(view, render.apply((Float) running.getAnimatedValue()));
        });
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

  private static void cancelCounter(TextView view) {
    var previous = COUNTERS.remove(view);
    if (previous != null) previous.cancel();
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
   * 计时 / 计数文本：只有数字变化时直接落值（每秒淡入会变成呼吸闪烁），文案骨架变化时才淡入。
   */
  static void crossFadeTimerText(TextView view, CharSequence text) {
    if (view == null) return;
    CharSequence next = text == null ? "" : text;
    CharSequence previous = view.getText();
    if (!TextUtils.equals(previous, next) && sameTimerWording(previous, next)) {
      if (view.getAlpha() != 1f) {
        view.animate().cancel();
        view.setAlpha(1f);
      }
      view.setText(next);
      return;
    }
    crossFadeText(view, next);
  }

  private static boolean sameTimerWording(CharSequence previous, CharSequence next) {
    // Compare the words and separators, allowing digit runs to change width at 9 → 10.
    int from = 0;
    int to = 0;
    while (from < previous.length() && to < next.length()) {
      if (Character.isDigit(previous.charAt(from)) && Character.isDigit(next.charAt(to))) {
        do {
          from++;
        } while (from < previous.length() && Character.isDigit(previous.charAt(from)));
        do {
          to++;
        } while (to < next.length() && Character.isDigit(next.charAt(to)));
      } else if (previous.charAt(from++) != next.charAt(to++)) {
        return false;
      }
    }
    return from == previous.length() && to == next.length();
  }

  static void setTextIfChanged(TextView view, CharSequence text) {
    if (!TextUtils.equals(view.getText(), text)) view.setText(text);
  }

  /** 文字颜色 120ms 过渡（错误 / 正常状态切换）；系统关闭动画时直接落值。 */
  static void crossFadeTextColor(TextView view, int color) {
    if (view == null) return;
    int from = view.getCurrentTextColor();
    TextColorTransition previous = TEXT_COLORS.get(view);
    if (previous == null && from == color) return;
    boolean animate = animationsEnabled(view.getContext());
    if (animate && previous != null && previous.target() == color) return;
    if (previous != null) previous.animator().cancel();
    if (!animate || from == color) {
      if (from != color) view.setTextColor(color);
      return;
    }
    ValueAnimator animator = ValueAnimator.ofArgb(from, color);
    animator.setDuration(120L);
    animator.setInterpolator(STANDARD);
    animator.addUpdateListener(
        running -> {
          int next = (int) running.getAnimatedValue();
          if (view.getCurrentTextColor() != next) view.setTextColor(next);
        });
    animator.addListener(
        new android.animation.AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(android.animation.Animator animation) {
            TEXT_COLORS.remove(view);
          }
        });
    TEXT_COLORS.put(view, new TextColorTransition(color, animator));
    animator.start();
  }

  /**
   * 变化高亮：从 55% 透明度淡到全显（150ms），用于段位 / 小标记，不移动布局。
   */
  static void flash(View view) {
    if (view == null) return;
    view.animate().cancel();
    if (!animationsEnabled(view.getContext())) {
      view.setAlpha(1f);
      return;
    }
    view.setAlpha(0.55f);
    view.animate().alpha(1f).setDuration(150L).setInterpolator(STANDARD).start();
  }

  /**
   * 重建后的容器淡入（150ms）：整块列表/卡片替换时给出「内容已更新」的过渡，而不是硬跳。
   *
   * <p>只在调用方完成重建后调用；系统关闭动画时直接落为完全可见，不残留半透明。
   */
  static void reveal(View view) {
    if (view == null) return;
    view.animate().cancel();
    if (!animationsEnabled(view.getContext())) {
      view.setAlpha(1f);
      view.setTranslationY(0f);
      return;
    }
    float offset = 4f * view.getResources().getDisplayMetrics().density;
    view.setAlpha(0f);
    view.setTranslationY(offset);
    view.animate().alpha(1f).translationY(0f).setDuration(150L).setInterpolator(STANDARD).start();
  }

  /**
   * 收起折叠详情（120ms 淡出）后再交给调用方移除内容；系统关闭动画时直接执行。
   *
   * <p>先淡出、结束回写透明度再移除：阻断重新渲染时残留半透明或空容器。
   */
  static void conceal(View view, Runnable after) {
    if (view == null) {
      if (after != null) after.run();
      return;
    }
    view.animate().cancel();
    if (!animationsEnabled(view.getContext())) {
      view.setAlpha(1f);
      view.setTranslationY(0f);
      if (after != null) after.run();
      return;
    }
    view.animate()
        .alpha(0f)
        .setDuration(120L)
        .setInterpolator(STANDARD)
        .withEndAction(
            () -> {
              view.setAlpha(1f);
              view.setTranslationY(0f);
              if (after != null) after.run();
            })
        .start();
  }

  /** 折叠箭头 0↔90°（120ms）；系统关闭动画时直接落位，不残留中间角度。 */
  static void rotateChevron(
      Context context, android.graphics.drawable.RotateDrawable chevron, boolean expanded) {
    if (chevron == null) return;
    int target = expanded ? 10000 : 0;
    if (!animationsEnabled(context)) {
      chevron.setLevel(target);
      return;
    }
    ValueAnimator animator = ValueAnimator.ofInt(chevron.getLevel(), target);
    animator.setDuration(120L);
    animator.setInterpolator(STANDARD);
    animator.addUpdateListener(running -> chevron.setLevel((int) running.getAnimatedValue()));
    animator.start();
  }

  /** Animate the fraction while keeping byte and count endpoints as exact longs. */
  static void animateValue(TextView view, long from, long to, LongFunction<String> render) {
    if (view == null) return;
    if (from == to || !animationsEnabled(view.getContext())) {
      cancelCounter(view);
      setTextIfChanged(view, render.apply(to));
      return;
    }
    animateCounter(view, 0f, 1f, fraction -> render.apply(interpolateLong(from, to, fraction)));
  }

  private static long interpolateLong(long from, long to, float fraction) {
    if (fraction <= 0f) return from;
    if (fraction >= 1f) return to;
    long lower = Math.min(from, to);
    long upper = Math.max(from, to);
    long span = upper - lower;
    if (span >= 0) {
      // Round the change so close values still retain their byte precision at large magnitudes.
      long delta = to >= from ? span : -span;
      long change = Math.round(delta * (double) fraction);
      return from + (delta >= 0 ? Math.min(delta, change) : Math.max(delta, change));
    }
    // Opposite long extremes can exceed the range of a signed long delta.
    long value = Math.round(from + ((double) to - from) * fraction);
    return Math.max(lower, Math.min(upper, value));
  }

  /** 卡片圆角形变（120ms）：只改背景圆角，不动布局与文字；系统关闭动画时直接落位。 */
  static void cornerMorph(
      View host, android.graphics.drawable.GradientDrawable shape, float targetPx) {
    if (host == null || shape == null) return;
    float from = shape.getCornerRadius();
    if (!animationsEnabled(host.getContext()) || Math.abs(from - targetPx) < 0.5f) {
      shape.setCornerRadius(targetPx);
      return;
    }
    ValueAnimator animator = ValueAnimator.ofFloat(from, targetPx);
    animator.setDuration(120L);
    animator.setInterpolator(STANDARD);
    animator.addUpdateListener(
        running -> {
          shape.setCornerRadius((Float) running.getAnimatedValue());
          host.invalidate();
        });
    animator.start();
  }

  /**
   * 同一区域内容替换（G4）：旧内容淡出 90ms，重建后新内容淡入 150ms；系统关闭动画时直接替换。
   *
   * <p>重建失败也保证透明度回写，不把区域留在半透明状态。
   */
  static void swapContent(ViewGroup host, Runnable rebuild) {
    if (host == null || rebuild == null) {
      if (rebuild != null) rebuild.run();
      return;
    }
    host.animate().cancel();
    if (!animationsEnabled(host.getContext())) {
      rebuild.run();
      host.setAlpha(1f);
      return;
    }
    host.animate()
        .alpha(0f)
        .setDuration(90L)
        .setInterpolator(STANDARD)
        .withEndAction(
            () -> {
              try {
                rebuild.run();
              } finally {
                reveal(host);
              }
            })
        .start();
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
          int action = event.getActionMasked();
          if (action != MotionEvent.ACTION_DOWN
              && action != MotionEvent.ACTION_UP
              && action != MotionEvent.ACTION_CANCEL) return false;
          if (!target.isEnabled() || !animationsEnabled(target.getContext())) {
            if (action != MotionEvent.ACTION_DOWN
                || target.getScaleX() != 1f
                || target.getScaleY() != 1f) {
              target.animate().cancel();
              target.setScaleX(1f);
              target.setScaleY(1f);
            }
            return false;
          }
          boolean pressed = action == MotionEvent.ACTION_DOWN;
          float scale = pressed ? 0.985f : 1f;
          target
              .animate()
              .scaleX(scale)
              .scaleY(scale)
              .setStartDelay(0L)
              .setDuration(pressed ? 90L : 140L)
              .setInterpolator(STANDARD)
              .start();
          return false;
        });
  }
}
