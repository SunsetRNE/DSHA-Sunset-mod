package com.deepseekharness.app.ui;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.deepseekharness.app.R;
import com.deepseekharness.app.HomeLayoutStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 底栏编辑：单指拖动换位、单指轻点交给宿主、双指捏合缩放。 */
final class BottomNavDragController {
  interface Host {
    void onOrderCommitted();

    /** 编辑态轻点底栏按钮：没有拖动也没有捏合时回调，宿主借此退出编辑并处理导航。 */
    void onNavTapped(int itemId);
  }

  private final MainActivity activity;
  private final EditableBottomNavigationView nav;
  private final Host host;
  private final List<Integer> ids = new ArrayList<>();
  private final List<View> items = new ArrayList<>();
  private final Map<String, Float> pendingScales = new LinkedHashMap<>();
  private boolean enabled;
  private int dragIndex = -1;
  private int firstPointer = -1;
  private int secondPointer = -1;
  private float downX;
  private float downY;
  private boolean moved;
  private boolean scaling;
  private float lastSpan;
  private String scalingKey;

  /** 拖动过程中累积的目标顺序（null = 本次没有换位）。 */
  private java.util.List<String> dragOrder;

  BottomNavDragController(MainActivity activity, EditableBottomNavigationView nav, Host host) {
    this.activity = activity;
    this.nav = nav;
    this.host = host;
  }

  void setEnabled(boolean on) {
    finishGesture(true);
    enabled = on;
    nav.setBackgroundResource(on ? R.drawable.bg_shell_nav_edit : R.drawable.bg_shell_nav);
    nav.setEditTouchListener(on ? this::onTouch : null);
  }

  /**
   * 找到真正「一个子视图对应一个菜单项」的那层容器。
   *
   * <p>Material3 的 BottomNavigationView 内部层级随版本变化（外面还有 NavigationBarView
   * 包一层），直接取 getChildAt(0) 很可能拿到只含一个孩子的外层，childCount 与菜单项数量
   * 对不上，于是整个拖动逻辑在 ACTION_DOWN 就提前返回——表现就是「编辑态底栏拖不动」。
   * 这里按「孩子数量 == 菜单项数量」递归找，找不到就退回原来的第一层。
   */
  private ViewGroup menuView() {
    int expected = nav.getMenu().size();
    ViewGroup found = findMenuContainer(nav, expected, 0);
    if (found != null) return found;
    if (nav.getChildCount() == 0) return null;
    View first = nav.getChildAt(0);
    return first instanceof ViewGroup ? (ViewGroup) first : null;
  }

  private static ViewGroup findMenuContainer(ViewGroup parent, int expected, int depth) {
    if (depth > 4) return null;
    for (int i = 0; i < parent.getChildCount(); i++) {
      View child = parent.getChildAt(i);
      if (!(child instanceof ViewGroup)) continue;
      ViewGroup group = (ViewGroup) child;
      if (group.getChildCount() == expected) return group;
      ViewGroup nested = findMenuContainer(group, expected, depth + 1);
      if (nested != null) return nested;
    }
    return null;
  }

  private boolean onTouch(View view, MotionEvent event) {
    if (!enabled) return false;
    ViewGroup menu = menuView();
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      finishGesture(false);
      if (menu == null || menu.getChildCount() != nav.getMenu().size()) return true;
      for (int i = 0; i < menu.getChildCount(); i++) {
        items.add(menu.getChildAt(i));
        ids.add(nav.getMenu().getItem(i).getItemId());
      }
      firstPointer = event.getPointerId(0);
      downX = event.getX();
      downY = event.getY();
      dragIndex = targetIndex(menu, downX);
      dragOrder = null;
      // 单指拖动换位；没有拖动时松手仍算「轻点」交给宿主退出编辑。
      // 双指捏合缩放在 ACTION_POINTER_DOWN 里接管，不会再走这条分支。
      return true;
    }
    if (!sameMenu(menu)) {
      finishGesture(true);
      return true;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_POINTER_DOWN:
        if (secondPointer < 0) {
          int index = event.getActionIndex();
          if (firstPointer < 0) firstPointer = event.getPointerId(index == 0 ? 1 : 0);
          secondPointer = event.getPointerId(index);
          dragIndex = -1;
          scaling = true;
          scalingKey = null;
          lastSpan = 0;
          scale(event);
        }
        return true;
      case MotionEvent.ACTION_MOVE:
        if (scaling) {
          scale(event);
        } else if (dragIndex >= 0) {
          int pointer = event.findPointerIndex(firstPointer);
          if (pointer < 0) return true;
          float x = event.getX(pointer);
          if (Math.abs(x - downX) > dp(6) || Math.abs(event.getY(pointer) - downY) > dp(6))
            moved = true;
          if (moved) reorderTo(targetIndex(menu, x));
        }
        return true;
      case MotionEvent.ACTION_POINTER_UP:
        int lifted = event.getPointerId(event.getActionIndex());
        if (lifted == firstPointer || lifted == secondPointer) {
          firstPointer = secondPointer = -1;
          for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == event.getActionIndex()) continue;
            if (firstPointer < 0) firstPointer = event.getPointerId(i);
            else if (secondPointer < 0) secondPointer = event.getPointerId(i);
          }
          lastSpan = 0;
          scalingKey = null;
        }
        return true;
      case MotionEvent.ACTION_UP:
        if (scaling) {
          for (Map.Entry<String, Float> entry : pendingScales.entrySet())
            HomeLayoutStore.setNavItemScale(activity, entry.getKey(), entry.getValue());
          finishGesture(true);
        } else if (moved && dragOrder != null) {
          // 拖动换位：写回偏好后重建菜单，松手即生效。
          HomeLayoutStore.setNavOrder(activity, dragOrder);
          finishGesture(true);
          host.onOrderCommitted();
        } else {
          int from = dragIndex;
          int tapped = from >= 0 && !moved ? ids.get(from) : 0;
          finishGesture(true);
          if (tapped != 0) host.onNavTapped(tapped);
        }
        return true;
      case MotionEvent.ACTION_CANCEL:
        finishGesture(true);
        return true;
      default:
        return true;
    }
  }

  /**
   * 单指拖动换位：把当前按住的项移动到 target 位置，并把新顺序记进 {@link #dragOrder}。
   *
   * <p>只重排内存中的 ids，不重建菜单——拖动过程中重建菜单会让按住的 view 被移除、
   * 触摸流中断。菜单在松手提交时统一重建。
   */
  private void reorderTo(int target) {
    if (target < 0 || target >= ids.size() || dragIndex < 0 || target == dragIndex) return;
    int movingId = ids.remove(dragIndex);
    ids.add(target, movingId);
    dragIndex = target;
    dragOrder = new ArrayList<>();
    for (int id : ids) dragOrder.add(MainActivity.navKeyOf(id));
    // 拖动过程中就把顺序写回并重建菜单：手势末尾的 UP 在部分 ROM/输入法手势下会被
    // 系统截走，只靠 UP 提交会出现「拖了但没生效」。这里用 setNavOrder 的幂等写入，
    // 每跨过一格提交一次，松手时视觉与偏好已经一致。
    HomeLayoutStore.setNavOrder(activity, dragOrder);
    host.onOrderCommitted();
    // 菜单已重建：旧的 item view 全部失效，按新顺序重新抓一遍，否则后续 MOVE
    // 会对着被移除的 view 算中心点，dragIndex 从此错位。
    items.clear();
    ids.clear();
    int keptIndex = dragIndex;
    ViewGroup rebuilt = menuView();
    if (rebuilt != null && rebuilt.getChildCount() == nav.getMenu().size()) {
      for (int i = 0; i < rebuilt.getChildCount(); i++) {
        items.add(rebuilt.getChildAt(i));
        ids.add(nav.getMenu().getItem(i).getItemId());
        // 记住被拖那一项重建后的真实索引（顺序已变，但语义仍是同一个目的地）。
      }
      for (int i = 0; i < ids.size(); i++) if (ids.get(i) == movingId) keptIndex = i;
    }
    dragIndex = Math.max(0, Math.min(keptIndex, Math.max(0, ids.size() - 1)));
  }

  /** 坐标统一在底栏内计算，不受按钮自身的视觉缩放影响。 */
  private void scale(MotionEvent event) {
    int first = event.findPointerIndex(firstPointer);
    int second = event.findPointerIndex(secondPointer);
    if (first < 0 || second < 0) return;
    float dx = event.getX(first) - event.getX(second);
    float dy = event.getY(first) - event.getY(second);
    float span = (float) Math.hypot(dx, dy);
    ViewGroup menu = menuView();
    int target = targetIndex(menu, (event.getX(first) + event.getX(second)) / 2f);
    String key = MainActivity.navKeyOf(ids.get(target));
    if (key == null) return;
    if (!key.equals(scalingKey)) {
      scalingKey = key;
      lastSpan = span;
      items.get(target).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      return;
    }
    if (lastSpan > 0 && span > 0) {
      float value =
          pendingScales.containsKey(key)
              ? pendingScales.get(key)
              : HomeLayoutStore.navItemScale(activity, key);
      value =
          Math.max(
              HomeLayoutStore.NAV_SCALE_MIN,
              Math.min(HomeLayoutStore.NAV_SCALE_MAX, value * span / lastSpan));
      pendingScales.put(key, value);
      applyScale(items.get(target), value);
    }
    lastSpan = span;
  }

  private boolean sameMenu(ViewGroup menu) {
    if (menu == null
        || items.isEmpty()
        || menu.getChildCount() != items.size()
        || nav.getMenu().size() != ids.size()) return false;
    for (int i = 0; i < items.size(); i++)
      if (menu.getChildAt(i) != items.get(i) || nav.getMenu().getItem(i).getItemId() != ids.get(i))
        return false;
    return true;
  }

  private void finishGesture(boolean releaseParent) {
    restoreAlpha();
    for (int i = 0; i < items.size(); i++) {
      String key = MainActivity.navKeyOf(ids.get(i));
      if (key != null) applyScale(items.get(i), HomeLayoutStore.navItemScale(activity, key));
    }
    pendingScales.clear();
    items.clear();
    ids.clear();
    dragIndex = firstPointer = secondPointer = -1;
    moved = scaling = false;
    dragOrder = null;
    scalingKey = null;
    lastSpan = 0;
    if (releaseParent && nav.getParent() != null)
      nav.getParent().requestDisallowInterceptTouchEvent(false);
  }

  private static void applyScale(View view, float value) {
    view.setPivotX(view.getWidth() / 2f);
    view.setPivotY(view.getHeight() / 2f);
    view.setScaleX(value);
    view.setScaleY(value);
  }

  private void restoreAlpha() {
    for (View item : items) item.setAlpha(1f);
  }

  /**
   * 把「相对底栏」的 x 换成最近的第几项。
   *
   * <p>menu 容器在底栏里可能有自己的 left 偏移，也可能只是若干层里的一层，
   * 所以先把 x 换算成 menu 的本地坐标（逐级减去 left），再和每个孩子的中心比。
   */
  private int targetIndex(ViewGroup menu, float x) {
    float local = x;
    View cursor = menu;
    while (cursor != null && cursor != nav) {
      local -= cursor.getLeft();
      Object parent = cursor.getParent();
      cursor = parent instanceof View ? (View) parent : null;
    }
    int best = 0;
    float distance = Float.MAX_VALUE;
    for (int i = 0; i < menu.getChildCount(); i++) {
      View child = menu.getChildAt(i);
      float delta = Math.abs(local - (child.getLeft() + child.getWidth() / 2f));
      if (delta < distance) {
        distance = delta;
        best = i;
      }
    }
    return best;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
