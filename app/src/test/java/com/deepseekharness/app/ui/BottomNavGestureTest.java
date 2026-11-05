package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.deepseekharness.app.R;
import com.deepseekharness.app.HomeLayoutStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** 从父容器分发多指事件，覆盖 Material 菜单的真实事件分流边界。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = MainEntryLayoutLifecycleTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BottomNavGestureTest {
  private MainActivity activity;
  private FrameLayout root;
  private EditableBottomNavigationView nav;
  private BottomNavDragController controller;
  private long time;
  private float center;
  private float halfSpan;
  private float y;
  private boolean parentInterceptDisallowed;
  private int tappedItemId;

  @Before
  public void setUp() {
    activity = Robolectric.buildActivity(MainActivity.class).get();
    activity.setTheme(R.style.Theme_DeepseekHarness);
    activity.getSharedPreferences("dsha_home_layout", 0).edit().clear().commit();
    root =
        new FrameLayout(activity) {
          @Override
          public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            parentInterceptDisallowed = disallowIntercept;
            super.requestDisallowInterceptTouchEvent(disallowIntercept);
          }
        };
    activity.setContentView(root);
    nav = new EditableBottomNavigationView(activity, null);
    nav.inflateMenu(R.menu.bottom_nav_menu);
    root.addView(nav, new FrameLayout.LayoutParams(1000, 160));
    controller =
        new BottomNavDragController(
            activity,
            nav,
            new BottomNavDragController.Host() {
              @Override
              public void onOrderCommitted() {
                rebuild();
              }

              @Override
              public void onNavTapped(int itemId) {
                tappedItemId = itemId;
              }
            });
    layout();
    View item = nav.findViewById(R.id.nav_settings);
    center = nav.getChildAt(0).getLeft() + item.getLeft() + item.getWidth() / 2f;
    halfSpan = item.getWidth() * 0.65f;
    y = nav.getChildAt(0).getTop() + item.getTop() + item.getHeight() / 2f;
    time = SystemClock.uptimeMillis();
    controller.setEnabled(true);
  }

  private void layout() {
    root.measure(
        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 1000, 180);
  }

  private void rebuild() {
    nav.getMenu().clear();
    int position = 0;
    for (String key : HomeLayoutStore.navOrder(activity)) {
      int id =
          key.equals("launch")
              ? R.id.nav_launch
              : key.equals("module")
                  ? R.id.nav_module
                  : key.equals("plugins")
                      ? R.id.nav_plugins
                      : key.equals("settings") ? R.id.nav_settings : R.id.nav_terminal;
      nav.getMenu().add(0, id, position++, key).setCheckable(true);
    }
    layout();
  }

  private void event(int action, float... xs) {
    eventWithIds(action, false, xs);
  }

  private void eventWithIds(int action, boolean remainingSecond, float... xs) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[xs.length];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[xs.length];
    for (int i = 0; i < xs.length; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = remainingSecond ? 12 : 7 + i * 5;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[i] = new MotionEvent.PointerCoords();
      coordinates[i].x = xs[i];
      coordinates[i].y = y;
      coordinates[i].pressure = 1;
      coordinates[i].size = 1;
    }
    MotionEvent event =
        MotionEvent.obtain(
            time,
            time += 16,
            action,
            xs.length,
            properties,
            coordinates,
            0,
            0,
            1,
            1,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0);
    try {
      assertTrue(root.dispatchTouchEvent(event));
    } finally {
      event.recycle();
    }
  }

  private void beginPinch() {
    event(MotionEvent.ACTION_DOWN, center - halfSpan);
    event(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        center - halfSpan,
        center + halfSpan);
  }

  private void movePinch(float factor) {
    event(MotionEvent.ACTION_MOVE, center - halfSpan * factor, center + halfSpan * factor);
  }

  private void finishPinch() {
    event(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        center - halfSpan,
        center + halfSpan);
    event(MotionEvent.ACTION_UP, center - halfSpan);
  }

  @Test
  public void parentCannotInterceptGestureAndIsReleasedAtEnd() {
    assertTrue(root.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN, center)));
    assertTrue(parentInterceptDisallowed);
    assertTrue(root.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, center + 20)));
    assertTrue(parentInterceptDisallowed);
    assertTrue(root.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP, center + 20)));
    assertFalse(parentInterceptDisallowed);
  }

  private MotionEvent motionEvent(int action, float... xs) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[xs.length];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[xs.length];
    for (int i = 0; i < xs.length; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = 7 + i * 5;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[i] = new MotionEvent.PointerCoords();
      coordinates[i].x = xs[i];
      coordinates[i].y = y;
      coordinates[i].pressure = 1;
      coordinates[i].size = 1;
    }
    return MotionEvent.obtain(
        time,
        time += 16,
        action,
        xs.length,
        properties,
        coordinates,
        0,
        0,
        1,
        1,
        0,
        0,
        android.view.InputDevice.SOURCE_TOUCHSCREEN,
        0);
  }

  @Test
  public void pinchAcrossItemsUsesMidpointAndOnlyPersistsOnRelease() {
    beginPinch();
    movePinch(1.4f);
    assertEquals(1.4f, nav.findViewById(R.id.nav_settings).getScaleX(), 0.01f);
    assertEquals(1f, HomeLayoutStore.navItemScale(activity, "settings"), 0f);
    finishPinch();
    assertEquals(1.4f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    assertEquals(1f, HomeLayoutStore.navItemScale(activity, "plugins"), 0f);
    assertEquals(HomeLayoutStore.DEFAULT_NAV_ORDER, HomeLayoutStore.navOrder(activity));
  }

  @Test
  public void remainingFingerCannotShrinkOrDragAfterPointerUp() {
    beginPinch();
    movePinch(1.3f);
    event(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        center - halfSpan,
        center + halfSpan);
    event(MotionEvent.ACTION_MOVE, 20f);
    event(MotionEvent.ACTION_UP, 20f);
    assertEquals(1.3f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    assertEquals(HomeLayoutStore.DEFAULT_NAV_ORDER, HomeLayoutStore.navOrder(activity));
  }

  @Test
  public void liftingFirstPointerAlsoPreservesScale() {
    beginPinch();
    movePinch(1.2f);
    event(MotionEvent.ACTION_POINTER_UP, center - halfSpan, center + halfSpan);
    eventWithIds(MotionEvent.ACTION_UP, true, center + halfSpan);
    assertEquals(1.2f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
  }

  @Test
  public void repeatedPinchesUseSavedScaleAndClampBothDirections() {
    beginPinch();
    movePinch(1.5f);
    finishPinch();
    beginPinch();
    movePinch(1.3f);
    finishPinch();
    assertEquals(1.6f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    beginPinch();
    movePinch(0.25f);
    finishPinch();
    assertEquals(0.7f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
  }

  @Test
  public void cancelOrLeavingEditModeRestoresPreviewAndDoesNotWrite() {
    beginPinch();
    movePinch(1.4f);
    event(MotionEvent.ACTION_CANCEL, center - halfSpan, center + halfSpan);
    assertEquals(1f, nav.findViewById(R.id.nav_settings).getScaleX(), 0f);
    beginPinch();
    movePinch(0.8f);
    controller.setEnabled(false);
    assertEquals(1f, HomeLayoutStore.navItemScale(activity, "settings"), 0f);
    assertEquals(1f, nav.findViewById(R.id.nav_settings).getScaleX(), 0f);
  }

  @Test
  public void retargetKeepsBothPreviewsUntilRelease() {
    beginPinch();
    movePinch(1.3f);
    View plugins = nav.findViewById(R.id.nav_plugins);
    center = nav.getChildAt(0).getLeft() + plugins.getLeft() + plugins.getWidth() / 2f;
    movePinch(1f);
    movePinch(0.8f);
    finishPinch();
    assertEquals(1.3f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    assertEquals(0.8f, HomeLayoutStore.navItemScale(activity, "plugins"), 0.01f);
  }

  @Test
  public void dragReordersThenPinchAndPlainTapStillWork() {
    View first = ((ViewGroup) nav.getChildAt(0)).getChildAt(0);
    float start = nav.getChildAt(0).getLeft() + first.getLeft() + first.getWidth() / 2f;
    event(MotionEvent.ACTION_DOWN, start);
    event(MotionEvent.ACTION_MOVE, 970f);
    event(MotionEvent.ACTION_UP, 970f);
    // 单指拖动换位：把第一项拖到末端，跨格即时持久化并重建菜单。
    assertEquals("底栏拖动应换位", "module,plugins,settings,terminal,launch", joinedOrder());
    View settings = nav.findViewById(R.id.nav_settings);
    center = nav.getChildAt(0).getLeft() + settings.getLeft() + settings.getWidth() / 2f;
    beginPinch();
    movePinch(1.2f);
    finishPinch();
    assertEquals(1.2f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    event(MotionEvent.ACTION_DOWN, center);
    event(MotionEvent.ACTION_UP, center);
    assertEquals(1.2f, HomeLayoutStore.navItemScale(activity, "settings"), 0.01f);
    controller.setEnabled(false);
    nav.setOnItemSelectedListener(item -> true);
    nav.setSelectedItemId(R.id.nav_settings);
    assertEquals(R.id.nav_settings, nav.getSelectedItemId());
  }

  /** 编辑态轻点底栏按钮（未拖未捏）必须回报被点项，让宿主退出编辑并导航。 */
  @Test
  public void tapOnItemWithoutMovingReportsTappedId() {
    event(MotionEvent.ACTION_DOWN, center);
    event(MotionEvent.ACTION_UP, center);
    assertEquals(R.id.nav_settings, tappedItemId);
    assertEquals("launch,module,plugins,settings,terminal", joinedOrder());
  }

  /** 拖动超过阈值算换位，不算轻点：松手后顺序已变且不触发导航。 */
  @Test
  public void dragBeyondSlopReordersAndDoesNotTap() {
    event(MotionEvent.ACTION_DOWN, center);
    event(MotionEvent.ACTION_MOVE, center + halfSpan * 2);
    event(MotionEvent.ACTION_UP, center + halfSpan * 2);
    assertEquals(0, tappedItemId);
    assertEquals("launch,module,plugins,terminal,settings", joinedOrder());
  }

  private String joinedOrder() {
    return String.join(",", HomeLayoutStore.navOrder(activity));
  }

  @Test
  public void menuRebuildDuringPinchCancelsPendingValues() {
    beginPinch();
    movePinch(1.4f);
    rebuild();
    movePinch(1.5f);
    finishPinch();
    assertEquals(1f, HomeLayoutStore.navItemScale(activity, "settings"), 0f);
  }
}
