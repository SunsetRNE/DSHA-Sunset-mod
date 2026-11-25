package com.deepseekharness.app.ui;

import android.widget.TextView;
import com.deepseekharness.app.util.Fmt;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongFunction;

/**
 * 统计数字滚动（E5）：字节/计数从上一值滚到新值（200ms）。系统关闭动画、首次渲染或值未变时直接落值。
 */
final class RollingNumber {
  private RollingNumber() {}

  private static final Map<TextView, Long> LAST = new WeakHashMap<>();

  static void bytes(TextView view, long bytes) {
    value(view, bytes, Fmt::bytes);
  }

  static void value(TextView view, long target, LongFunction<String> render) {
    if (view == null || render == null) return;
    Long previous = LAST.put(view, target);
    UiMotion.animateValue(view, previous == null ? target : previous, target, render);
  }
}
