package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.deepseekharness.app.R;

/** 内容短时收紧，长时滚动正文并保留底部操作。宽 ≥ 600dp 居中限宽，更窄贴底滑入。 */
final class CardSheet {
  static androidx.appcompat.app.AlertDialog create(Context context, CardPage page) {
    boolean wide = wide(context);
    page.root.setBackgroundResource(wide ? R.drawable.bg_shell_card : R.drawable.bg_shell_sheet);
    page.scroll.setFillViewport(false);
    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, -2);
    page.scroll.setLayoutParams(scrollParams);
    final int maxWidth = dp(context, 540);
    FrameLayout container =
        new FrameLayout(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit =
                Math.round(getResources().getDisplayMetrics().heightPixels * (wide ? .84f : .86f));
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            if (wide && View.MeasureSpec.getSize(widthSpec) > maxWidth)
              widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY);
            // 短列表按内容收紧；只有超过上限才把滚动区改成权重，避免「取消」被顶到屏幕底。
            scrollParams.height = -2;
            scrollParams.weight = 0;
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            if (getMeasuredHeight() >= limit) {
              scrollParams.height = 0;
              scrollParams.weight = 1;
              super.onMeasure(
                  widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.EXACTLY));
            }
          }
        };
    container.addView(page.root, new FrameLayout.LayoutParams(-1, -2));
    View content = container;
    if (wide) {
      FrameLayout host = new FrameLayout(context);
      host.addView(container, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
      content = host;
    }
    var dialog = new DshaDialogBuilder(context).setView(content).create();
    // 贴底与半屏动效必须在第一次 show() 前设置，否则首帧会先出现在屏幕中央再跳到底部。
    if (dialog.getWindow() != null) {
      if (wide) {
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setGravity(Gravity.CENTER);
      } else {
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(-1, -2);
        dialog.getWindow().setWindowAnimations(R.style.Animation_DSHA_Sheet);
        // 手机底栏是叠在内容上的悬浮胶囊；贴底弹层抬到胶囊上方。宽屏居中卡片不设这个偏移。
        android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
        lp.y =
            context.getResources().getDimensionPixelSize(R.dimen.shell_nav_height)
                + context.getResources().getDimensionPixelSize(R.dimen.shell_nav_margin_bottom);
        dialog.getWindow().setAttributes(lp);
      }
    }
    return dialog;
  }

  static void show(androidx.appcompat.app.AlertDialog dialog, Context context) {
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog
          .getWindow()
          .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (wide(context)) {
        int width =
            Math.min(
                dp(context, 540),
                context.getResources().getDisplayMetrics().widthPixels - dp(context, 32));
        dialog.getWindow().setLayout(width, -2);
      }
    }
  }

  private static boolean wide(Context context) {
    return context.getResources().getConfiguration().screenWidthDp >= 600;
  }

  private static int dp(Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static void choices(
      Context context,
      String title,
      java.util.function.Supplier<java.util.List<String>> source,
      java.util.function.IntConsumer selected) {
    CardPage page = new CardPage(context, title, "", true);
    var dialog = create(context, page);
    java.util.List<String> values = source.get();
    for (int i = 0; i < values.size(); i++) {
      final int position = i;
      page.entry(
          page.content,
          values.get(i),
          "",
          R.drawable.ic_ui2_box,
          () -> {
            dialog.dismiss();
            selected.accept(position);
          });
    }
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }

  static void show(Context context, String title, String body) {
    CardPage page = new CardPage(context, title, "", true);
    TextView text = page.text(body, 13, R.color.text_secondary);
    text.setPadding(0, page.dp(14), 0, 0);
    text.setTextIsSelectable(true);
    text.setLineSpacing(page.dp(5), 1);
    page.content.addView(text);
    var dialog = create(context, page);
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("关闭", "Close"),
        false,
        dialog::dismiss);
    show(dialog, context);
  }
}
