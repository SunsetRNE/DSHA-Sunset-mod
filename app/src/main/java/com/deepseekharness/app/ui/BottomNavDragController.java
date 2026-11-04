package com.deepseekharness.app.ui;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.HomeLayoutStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 底栏编辑：单指轻点交给宿主、双指捏合缩放；底栏顺序不再支持拖动换位。 */
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

  private ViewGroup menuView() {
    if (nav.getChildCount() == 0) return null;
    View first = nav.getChildAt(0);
    return first instanceof ViewGroup ? (ViewGroup) first : null;
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
      // 底栏不再支持按顺序拖动；单指只用于「轻点退出编辑」，双指用于捏合缩放。
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

  private int targetIndex(ViewGroup menu, float x) {
    int best = 0;
    float distance = Float.MAX_VALUE;
    for (int i = 0; i < menu.getChildCount(); i++) {
      View child = menu.getChildAt(i);
      float delta = Math.abs(x - menu.getLeft() - child.getLeft() - child.getWidth() / 2f);
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
