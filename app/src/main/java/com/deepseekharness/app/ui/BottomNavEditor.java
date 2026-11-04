package com.deepseekharness.app.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.HomeLayoutStore;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 底栏模块条：编辑主页时出现，底栏五项在这里同样是可拖动的模块。
 *
 * <p>拖动只改 {@link HomeLayoutStore} 的底栏顺序，落点立刻重建真实菜单，
 * 所以松手那一刻底栏本身就换了顺序，不是「保存后再去看效果」。
 */
final class BottomNavEditor {

  private static final List<String> KEYS =
      Collections.unmodifiableList(
          Arrays.asList("module", "plugins", "launch", "settings", "terminal"));

  private final android.app.Activity activity;
  private final View panel;
  private final RecyclerView strip;
  private final Runnable onChanged;
  private final Strip adapter;
  private int dragPosition = -1;

  /** 通用构造：主界面用底部浮条，模块中心用内嵌卡片，两边共用同一套拖动逻辑。 */
  BottomNavEditor(
      android.app.Activity activity, View panel, RecyclerView strip, Runnable onChanged) {
    this.activity = activity;
    this.panel = panel;
    this.strip = strip;
    this.onChanged = onChanged;
    strip.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
    strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
    adapter = new Strip(new ArrayList<>(HomeLayoutStore.navOrder(activity)));
    strip.setAdapter(adapter);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  void setVisible(boolean visible) {
    if (panel == null) return;
    if (visible) adapter.reload(new ArrayList<>(HomeLayoutStore.navOrder(activity)));
    panel.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private static String label(String key) {
    switch (key) {
      case "module":
        return UiText.choose("模块中心", "Modules");
      case "plugins":
        return UiText.choose("插件", "Plugins");
      case "launch":
        return UiText.choose("启动", "Start");
      case "settings":
        return UiText.choose("设置", "Settings");
      default:
        return UiText.choose("终端", "Terminal");
    }
  }

  private static int icon(String key) {
    switch (key) {
      case "module":
        return R.drawable.ic_module_center;
      case "plugins":
        return R.drawable.ic_plugins;
      case "launch":
        return R.drawable.ic_launch;
      case "settings":
        return R.drawable.ic_settings;
      default:
        return R.drawable.ic_terminal;
    }
  }

  private final class Strip extends RecyclerView.Adapter<Strip.Chip> {
    private final List<String> order;

    Strip(List<String> order) {
      this.order = order;
    }

    void reload(List<String> values) {
      order.clear();
      order.addAll(values);
      notifyDataSetChanged();
    }

    void swapInternal(int from, int to) {
      if (from < 0 || to < 0 || from >= order.size() || to >= order.size()) return;
      Collections.swap(order, from, to);
      notifyItemMoved(from, to);
      HomeLayoutStore.setNavOrder(activity, order);
      if (onChanged != null) onChanged.run();
    }

    /** 手指落在第几个 chip 的中心之前，就换到那个位置。 */
    int indexAt(float rawX) {
      int count = strip.getChildCount();
      if (count == 0) return -1;
      for (int i = 0; i < count; i++) {
        View child = strip.getChildAt(i);
        int[] pos = new int[2];
        child.getLocationOnScreen(pos);
        if (rawX < pos[0] + child.getWidth() / 2f) return strip.getChildAdapterPosition(child);
      }
      return strip.getChildAdapterPosition(strip.getChildAt(count - 1));
    }

    void swap(int from, int to) {
      swapInternal(from, to);
    }

    @NonNull
    @Override
    public Chip onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      LinearLayout row = new LinearLayout(activity);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setBackgroundResource(R.drawable.bg_shell_note);
      row.setPadding(dp(8), dp(6), dp(8), dp(6));
      row.setLayoutParams(
          new RecyclerView.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

      ImageView handle = new ImageView(activity);
      handle.setImageResource(R.drawable.ic_ui2_drag);
      handle.setContentDescription(UiText.choose("按住拖动", "Press and drag"));
      row.addView(handle, new LinearLayout.LayoutParams(dp(12), dp(12)));

      ImageView mark = new ImageView(activity);
      mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(13), dp(13));
      markParams.leftMargin = dp(6);
      row.addView(mark, markParams);

      TextView title = new TextView(activity);
      title.setTextSize(11);
      title.setIncludeFontPadding(false);
      title.setTextColor(activity.getColor(R.color.shell_text));
      LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
      titleParams.leftMargin = dp(6);
      row.addView(title, titleParams);

      return new Chip(row, handle, mark, title);
    }

    @Override
    public void onBindViewHolder(@NonNull Chip holder, int position) {
      String key = order.get(position);
      holder.mark.setImageResource(icon(key));
      holder.mark.setImageTintList(
          android.content.res.ColorStateList.valueOf(activity.getColor(R.color.shell_accent)));
      holder.title.setText(label(key));
      holder.row.setContentDescription(
          UiText.choose(
              "底栏第 " + (position + 1) + " 位：" + label(key),
              "Bottom bar position " + (position + 1) + ": " + label(key)));
      // 整块 chip 都可拖：这条只在编辑态出现，五项一屏放得下，不需要横向滚动。
      holder.row.setOnTouchListener(
          (v, event) -> {
            switch (event.getActionMasked()) {
              case android.view.MotionEvent.ACTION_DOWN:
                // 不让 RecyclerView 自己横向滚动，否则 MOVE 会被它拦成滚动手势，
                // 子 view 收到 CANCEL，拖动永远换不了位。
                strip.requestDisallowInterceptTouchEvent(true);
                dragPosition = holder.getAdapterPosition();
                holder.row.setElevation(dp(10));
                holder.row.setAlpha(0.95f);
                holder.row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
              case android.view.MotionEvent.ACTION_MOVE:
                if (dragPosition < 0) return true;
                int target = indexAt(event.getRawX());
                if (target >= 0 && target != dragPosition) {
                  swap(dragPosition, target);
                  dragPosition = target;
                }
                return true;
              case android.view.MotionEvent.ACTION_UP:
              case android.view.MotionEvent.ACTION_CANCEL:
                dragPosition = -1;
                holder.row.setElevation(0f);
                holder.row.setAlpha(1f);
                strip.requestDisallowInterceptTouchEvent(false);
                return true;
              default:
                return false;
            }
          });
      RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.row.getLayoutParams();
      params.rightMargin = position == order.size() - 1 ? 0 : dp(6);
      holder.row.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
      return order.size();
    }

    final class Chip extends RecyclerView.ViewHolder {
      final LinearLayout row;
      final ImageView handle;
      final ImageView mark;
      final TextView title;

      Chip(LinearLayout row, ImageView handle, ImageView mark, TextView title) {
        super(row);
        this.row = row;
        this.handle = handle;
        this.mark = mark;
        this.title = title;
      }
    }
  }

  /** 供自动化核对当前拖动条顺序。 */
  List<String> order() {
    List<String> values = new ArrayList<>(KEYS);
    values.retainAll(HomeLayoutStore.navOrder(activity));
    return HomeLayoutStore.navOrder(activity);
  }
}
