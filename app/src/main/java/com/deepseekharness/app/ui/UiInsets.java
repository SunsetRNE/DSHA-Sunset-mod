package com.deepseekharness.app.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * edge-to-edge 页面的系统栏留白工具（外观背景页、后台任务页共用）。
 *
 * <p>这类页面自己铺壁纸、自己排页头，不能靠 {@link ModernAndroidUi} 给
 * {@code android.R.id.content} 加 padding —— 那会把壁纸也缩进系统栏，顶部露出一条白边。
 * 只能页头与滚动层各管一段：
 *
 * <ul>
 *   <li>{@link #top(View)}：页头吃状态栏 / 挖孔。页头必须是 {@code page.root} 的第 0 个子 View
 *       （{@link UiNavigation#addHeader} 就是这么插的）；
 *   <li>{@link #scroll(View)}：滚动层只补左右安全区与底部，**顶部交给页头**。
 *       两边都补 top 会把副标题和第一张卡片整块压下整整一个状态栏 —— 237 修过的 bug。
 * </ul>
 *
 * <p>两个监听都 {@code return insets}（不消费）：页头与滚动层是兄弟节点，
 * 谁也消费不了谁的，只能各算各的。
 */
final class UiInsets {
  private UiInsets() {}

  /** 页头补顶部（状态栏 + 挖孔）。 */
  static void top(View view) {
    if (view == null) return;
    final int base = view.getPaddingTop();
    ViewCompat.setOnApplyWindowInsetsListener(
        view,
        (v, insets) -> {
          Insets bars = bars(insets);
          v.setPadding(v.getPaddingLeft(), base + bars.top, v.getPaddingRight(), v.getPaddingBottom());
          return insets;
        });
  }

  /** 滚动层补左右安全区 + 底部；顶部保持原样（交给页头）。 */
  static void scroll(View scroll) {
    if (scroll == null) return;
    final int baseLeft = scroll.getPaddingLeft();
    final int baseRight = scroll.getPaddingRight();
    final int baseBottom = scroll.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (v, insets) -> {
          Insets bars = bars(insets);
          v.setPadding(
              baseLeft + bars.left,
              v.getPaddingTop(),
              baseRight + bars.right,
              baseBottom + bars.bottom);
          return insets;
        });
  }

  private static Insets bars(WindowInsetsCompat insets) {
    return insets.getInsets(
        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
  }
}
