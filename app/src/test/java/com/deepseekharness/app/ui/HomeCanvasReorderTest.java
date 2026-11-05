package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.deepseekharness.app.R;
import com.deepseekharness.app.HomeLayoutStore;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/**
 * 主页编辑手势：点选卡片后出现缩放手柄，拖边角能连续改宽高；未选中卡片没有任何角标。
 *
 * <p>这组测试直接分发真实 MotionEvent，防止以后又把「像调窗口一样自由缩放」退回成
 * 两个固定档位的按钮。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = HomeCanvasReorderTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class HomeCanvasReorderTest {

  /** 不注册真实前台回调：本测试只验证主页视图手势，不需要拉起运行环境。 */
  public static final class App extends com.deepseekharness.app.DshaApp {
    @Override
    public void onCreate() {}
  }

  private ActivityController<Activity> controller;
  private Activity activity;
  private FrameLayout host;
  private View root;
  private HomeCanvas canvas;
  private long downTime;

  @Before
  public void setUp() {
    // 必须走真实的 attach/visible 流程：getLocationOnScreen 只在视图真正挂到窗口后成立。
    controller = Robolectric.buildActivity(Activity.class).setup();
    activity = controller.get();
    activity.setTheme(R.style.Theme_DeepseekHarness);
    activity.getSharedPreferences("dsha_home_layout", 0).edit().clear().commit();
    host = new FrameLayout(activity);
    activity.setContentView(host);
    root = activity.getLayoutInflater().inflate(R.layout.fragment_launch, host, false);
    host.addView(root);
    controller.visible();
    ShadowLooper.shadowMainLooper().idle();
    layout();
    canvas =
        new HomeCanvas(
            root,
            new HomeCanvas.Host() {
              @Override
              public void onEditModeChanged(boolean editing) {}

              @Override
              public void openBuiltin(String target) {}
            });
    canvas.setEditing(true);
    layout();
    ShadowLooper.shadowMainLooper().idle();
    layout();
    // 让进入编辑的弹起动画跑完，否则下面的“其它卡片不动”会读到半路位移。
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
    layout();
    downTime = SystemClock.uptimeMillis();
  }

  @After
  public void tearDown() {
    ShadowLooper.shadowMainLooper().idle();
    if (controller != null) controller.pause().stop().destroy();
  }

  private void layout() {
    host.measure(
        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1900, View.MeasureSpec.EXACTLY));
    host.layout(0, 0, 1000, 1900);
  }

  /** 从窗口根部分发，坐标会像真实触摸一样被逐层换算到卡片本地坐标。 */
  private void send(int action, float x, float y) {
    MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
    host.dispatchTouchEvent(event);
    event.recycle();
  }

  /** 编辑态轻点选中：不做任何移动，按下再抬起。 */
  private void tap(Rect bounds) {
    send(
        MotionEvent.ACTION_DOWN,
        bounds.left + bounds.width() / 2f,
        bounds.top + bounds.height() / 2f);
    send(
        MotionEvent.ACTION_UP,
        bounds.left + bounds.width() / 2f,
        bounds.top + bounds.height() / 2f);
    layout();
  }

  private void twoFingers(
      int action, long downTime, long eventTime, float left, float right, float y) {
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = i == 0 ? left : right;
      coords[i].y = y;
      coords[i].pressure = 1f;
      coords[i].size = 1f;
    }
    MotionEvent event =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            2,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0);
    host.dispatchTouchEvent(event);
    event.recycle();
  }

  /** 编辑态直接拖动换位：按住 0.4 秒拿起卡片后再移动。 */
  private void dragToTop(String id) {
    HomeTile tile = (HomeTile) canvas.tileView(id);
    assertNotNull("tile " + id, tile);
    Rect from = canvas.boundsOf(id);
    Rect target = canvas.boundsOf("actions");
    assertTrue(from.height() > 0);
    assertTrue(target.height() > 0);

    float x = from.left + from.width() / 2f;
    send(MotionEvent.ACTION_DOWN, x, from.top + from.height() / 2f);
    holdForDrag();
    assertTrue("长按应已拿起卡片", canvas.isDraggingForTest());
    send(MotionEvent.ACTION_MOVE, x, target.top + from.height() / 2f);
    send(MotionEvent.ACTION_MOVE, x, target.top);
    send(MotionEvent.ACTION_UP, x, target.top);
    layout();
  }

  /** 按住 0.4 秒拿起卡片；Robolectric 暂停时钟需要显式推进，并把布局恢复到测试尺寸。 */
  private void holdForDrag() {
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    layout();
  }

  /** 把一张卡拖到指定屏幕点（用卡片中心落点）。 */
  private void dragCardTo(String id, float x, float y) {
    Rect from = canvas.boundsOf(id);
    assertTrue(from.height() > 0);
    send(MotionEvent.ACTION_DOWN, from.left + from.width() / 2f, from.top + from.height() / 2f);
    holdForDrag();
    assertTrue("长按应已拿起卡片", canvas.isDraggingForTest());
    send(MotionEvent.ACTION_MOVE, x, y);
    send(MotionEvent.ACTION_MOVE, x, y);
    send(MotionEvent.ACTION_UP, x, y);
    layout();
  }

  /** 布局坐标（不含动画位移）：断言「有没有叠在一起」要看真实落点。 */
  private Rect layoutBounds(String id) {
    View tile = canvas.tileView(id);
    return new Rect(
        tile.getLeft(),
        tile.getTop(),
        tile.getLeft() + tile.getWidth(),
        tile.getTop() + tile.getHeight());
  }

  /** 编辑态里任何两张可见卡都不允许叠在一起。 */
  private void assertNoOverlap() {
    List<String> visible = canvas.visibleIds();
    for (int i = 0; i < visible.size(); i++) {
      for (int j = i + 1; j < visible.size(); j++) {
        Rect a = layoutBounds(visible.get(i));
        Rect b = layoutBounds(visible.get(j));
        assertFalse(visible.get(i) + " overlaps " + visible.get(j), Rect.intersects(a, b));
      }
    }
  }

  /** 主页必须从第一行开始紧凑排布：没有绝对坐标、没有重叠、没有顶部大空白。 */
  private void assertCompact() {
    assertNoOverlap();
    for (String id : canvas.visibleIds()) {
      assertFalse(id + " 仍保存着自由坐标", HomeLayoutStore.hasPosition(activity, id));
    }
    int minTop = Integer.MAX_VALUE;
    for (String id : canvas.visibleIds()) minTop = Math.min(minTop, layoutBounds(id).top);
    assertEquals("主页顶部不能被自由坐标顶出大空白", 0, minTop);
  }

  /** 拖到别人身上不是叠卡，而是重排：落点决定顺序，三格仍同一行且零重叠。 */
  @Test
  public void droppingOntoAnotherCardReordersWithoutOverlap() {
    List<String> before = new java.util.ArrayList<>(HomeLayoutStore.order(activity));
    assertTrue(before.indexOf("metric-environment") < before.indexOf("metric-workbench"));
    Rect workbench = canvas.boundsOf("metric-workbench");
    dragCardTo("metric-environment", workbench.centerX(), workbench.centerY());

    List<String> after = HomeLayoutStore.order(activity);
    assertEquals(before.size(), after.size());
    assertTrue(
        "order=" + after, after.indexOf("metric-environment") > after.indexOf("metric-workbench"));
    assertCompact();
    // 重排后三格仍同一行，不换行、不叠卡；画面顺序必须跟着偏好一起变。
    assertEquals(layoutBounds("metric-workbench").top, layoutBounds("metric-environment").top);
    assertEquals(layoutBounds("metric-workbench").top, layoutBounds("metric-port").top);
    assertTrue(layoutBounds("metric-environment").left > layoutBounds("metric-workbench").left);
  }

  /** 桌面手感：按住拿起后，手指还没松开，别的卡就已经让位。 */
  @Test
  public void otherCardsGiveWayBeforeRelease() {
    Rect environment = canvas.boundsOf("metric-environment");
    Rect workbench = canvas.boundsOf("metric-workbench");
    send(MotionEvent.ACTION_DOWN, environment.centerX(), environment.centerY());
    holdForDrag();
    send(MotionEvent.ACTION_MOVE, workbench.centerX(), workbench.centerY());
    layout();

    // 还按着手指：被拖卡已浮起，工作台已经让到左边。
    HomeTile tile = (HomeTile) canvas.tileView("metric-environment");
    assertTrue("dragged tile should float", tile.getTranslationX() > 100f);
    assertTrue(layoutBounds("metric-workbench").left < workbench.left);

    send(MotionEvent.ACTION_MOVE, workbench.centerX(), workbench.centerY());
    send(MotionEvent.ACTION_UP, workbench.centerX(), workbench.centerY());
    layout();

    assertCompact();
    assertTrue(
        HomeLayoutStore.order(activity).indexOf("metric-environment")
            > HomeLayoutStore.order(activity).indexOf("metric-workbench"));
  }

  /** 编辑态直接滑一下不能变成拖动：没有按住就只是普通翻页/滑动。 */
  @Test
  public void quickSwipeDoesNotStartDrag() {
    List<String> before = new java.util.ArrayList<>(HomeLayoutStore.order(activity));
    Rect environment = canvas.boundsOf("metric-environment");
    send(MotionEvent.ACTION_DOWN, environment.centerX(), environment.centerY());
    send(MotionEvent.ACTION_MOVE, environment.centerX(), environment.top - 200f);
    send(MotionEvent.ACTION_UP, environment.centerX(), environment.top - 200f);
    layout();

    assertEquals("快速滑动不能改顺序", before, HomeLayoutStore.order(activity));
    HomeTile tile = (HomeTile) canvas.tileView("metric-environment");
    assertEquals("快速滑动不能拿起卡片", 0f, tile.getTranslationY(), 0.01f);
    assertFalse(HomeLayoutStore.hasPosition(activity, "metric-environment"));
  }

  /** 三个状态格是三个独立便签，不是被一行绑死的视觉三连。 */
  @Test
  public void threeMetricCardsAreIndependentTiles() {
    List<String> visible = canvas.visibleIds();
    assertTrue(visible.contains("metric-environment"));
    assertTrue(visible.contains("metric-workbench"));
    assertTrue(visible.contains("metric-port"));
    Rect environment = canvas.boundsOf("metric-environment");
    Rect workbench = canvas.boundsOf("metric-workbench");
    Rect port = canvas.boundsOf("metric-port");
    assertNotEquals(environment, workbench);
    assertNotEquals(environment, port);
    assertNotEquals(workbench, port);
    // 原版排版：三格状态在同一行三等分，端口卡不换行。
    assertEquals(environment.top, workbench.top);
    assertEquals(environment.top, port.top);
    assertTrue(workbench.left > environment.left);
    assertTrue(port.left > workbench.left);
  }

  /** 未选中时卡片顶上没有任何图标；点一张才出现手柄，点另一张会转移选中。 */
  @Test
  public void unselectedCardsAreCleanAndTapSelectsExactlyOne() {
    HomeTile environment = (HomeTile) canvas.tileView("metric-environment");
    HomeTile workbench = (HomeTile) canvas.tileView("metric-workbench");
    assertNull(environment.overlay());
    assertNull(workbench.overlay());
    assertNull(canvas.selectedId());

    Rect bounds = canvas.boundsOf("metric-environment");
    tap(bounds);
    assertEquals("metric-environment", canvas.selectedId());
    assertNotNull(environment.overlay());
    assertNull(workbench.overlay());

    Rect other = canvas.boundsOf("metric-workbench");
    tap(other);
    assertEquals("metric-workbench", canvas.selectedId());
    assertNull(environment.overlay());
    assertNotNull(workbench.overlay());
  }

  /** 拖到顶部是换位：顺序里排第一，整页仍然紧凑、没有绝对坐标留下的空白。 */
  @Test
  public void draggingToTopReordersAndKeepsHomeCompact() {
    List<String> before = new java.util.ArrayList<>(HomeLayoutStore.order(activity));

    dragToTop("logs");

    List<String> after = HomeLayoutStore.order(activity);
    assertNotEquals(before, after);
    assertEquals(before.size(), after.size());
    assertEquals("logs", after.get(0));
    assertCompact();
  }

  /** 三格之间互换位置仍是同一行：不写绝对坐标、不叠卡、不留白。 */
  @Test
  public void draggingThirdMetricReordersTheRow() {
    List<String> before = new java.util.ArrayList<>(HomeLayoutStore.order(activity));
    Rect environmentBefore = canvas.boundsOf("metric-environment");

    dragCardTo("metric-port", environmentBefore.centerX(), environmentBefore.centerY());

    List<String> after = HomeLayoutStore.order(activity);
    assertNotEquals(before, after);
    assertEquals(before.size(), after.size());
    assertCompact();
    Rect environment = layoutBounds("metric-environment");
    Rect port = layoutBounds("metric-port");
    Rect workbench = layoutBounds("metric-workbench");
    assertEquals(environment.top, workbench.top);
    assertEquals(environment.top, port.top);
    // 从右往左拖到环境卡上：插到它前面（空槽落在手指下方），端口应在环境左边。
    assertTrue(port.left < environment.left);
  }

  /** 收起一张卡后，剩下的卡按流式紧凑排布，不会留下绝对坐标造成的大空白。 */
  @Test
  public void hidingACardKeepsRemainingCardsCompact() {
    HomeLayoutStore.setHidden(activity, "metric-port", true);
    canvas.render();
    layout();

    assertCompact();
    Rect environment = layoutBounds("metric-environment");
    Rect workbench = layoutBounds("metric-workbench");
    Rect shortcuts = layoutBounds("shortcuts-title");
    assertEquals(environment.top, workbench.top);
    // 下一行必须紧挨着这一行，中间不能出现几百像素的空洞。
    assertTrue(shortcuts.top - Math.max(environment.bottom, workbench.bottom) < 40);
  }

  /** 双击捏合只改高度；宽度由边角手柄调，二者互不抢。 */
  @Test
  public void pinchChangesHeightOnly() {
    Rect bounds = canvas.boundsOf("metric-port");
    float cx = bounds.left + bounds.width() / 2f;
    float cy = bounds.top + bounds.height() / 2f;
    downTime = SystemClock.uptimeMillis();
    send(MotionEvent.ACTION_DOWN, cx, cy);
    twoFingers(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        downTime,
        downTime + 10,
        cx - 80f,
        cx + 80f,
        cy);
    // 第一次 MOVE 只建立捏合基线（factor=1），第二次才是真正的放大。
    twoFingers(MotionEvent.ACTION_MOVE, downTime, downTime + 60, cx - 200f, cx + 200f, cy);
    twoFingers(MotionEvent.ACTION_MOVE, downTime, downTime + 110, cx - 320f, cx + 320f, cy);
    assertEquals(1.6f, ((HomeTile) canvas.tileView("metric-port")).zoom(), 0.01f);
    twoFingers(
        MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        downTime,
        downTime + 140,
        cx - 320f,
        cx + 320f,
        cy);
    send(MotionEvent.ACTION_UP, cx, cy);

    assertTrue(HomeLayoutStore.zoom(activity, "metric-port") > 1.2f);
    assertEquals(1f / 3f, HomeLayoutStore.widthFraction(activity, "metric-port"), 0.01f);
    // 变高也不能盖住下面的卡。
    layout();
    assertNoOverlap();
  }

  /** 点选后拖右下角手柄：宽、高同时连续变化，松手写回偏好。 */
  @Test
  public void handleDragResizesWidthAndHeight() {
    Rect bounds = canvas.boundsOf("metric-port");
    HomeTile tile = (HomeTile) canvas.tileView("metric-port");
    tap(bounds);
    layout();
    assertEquals("metric-port", canvas.selectedId());
    HomeTile.ResizeOverlay overlay = tile.overlay();
    assertNotNull(overlay);
    assertTrue(overlay.getWidth() > 0);
    assertTrue(overlay.getHeight() > 0);

    float inset = 10f * activity.getResources().getDisplayMetrics().density;
    float x = bounds.left + overlay.getLeft() + overlay.getWidth() - inset;
    float y = bounds.top + overlay.getTop() + overlay.getHeight() - inset;
    downTime = SystemClock.uptimeMillis();
    send(MotionEvent.ACTION_DOWN, x, y);
    send(MotionEvent.ACTION_MOVE, x + 120f, y + 80f);
    send(MotionEvent.ACTION_UP, x + 120f, y + 80f);
    layout();

    assertTrue(HomeLayoutStore.widthFraction(activity, "metric-port") > 1f / 3f);
    assertTrue(HomeLayoutStore.zoom(activity, "metric-port") > 1f);
    // 加宽后不能把同排/下一排的卡盖住：被压住的卡向下让位。
    assertNoOverlap();
  }

  /** 「恢复默认」在选中卡片上真实存在，点击后只重置这张卡的大小。 */
  @Test
  public void resetActionRestoresCardDefaultSize() {
    HomeLayoutStore.setWidthFraction(activity, "metric-port", 0.9f);
    HomeLayoutStore.setZoom(activity, "metric-port", 1.8f);
    canvas.render();
    layout();

    Rect bounds = canvas.boundsOf("metric-port");
    HomeTile tile = (HomeTile) canvas.tileView("metric-port");
    tap(bounds);
    layout();
    View reset = tile.findViewWithTag("home_reset");
    assertNotNull("reset action", reset);
    reset.performClick();
    layout();

    assertEquals(1f / 3f, HomeLayoutStore.widthFraction(activity, "metric-port"), 0.01f);
    assertEquals(1f, HomeLayoutStore.zoom(activity, "metric-port"), 0.01f);
    assertEquals("metric-port", canvas.selectedId());
  }

  /** 收起动作也只在选中后出现，未选中时没有打叉图标。 */
  @Test
  public void hideActionOnlyExistsOnSelectedCard() {
    assertFalse(HomeLayoutStore.isHidden(activity, "logs"));
    Rect bounds = canvas.boundsOf("logs");
    HomeTile tile = (HomeTile) canvas.tileView("logs");
    tap(bounds);
    layout();
    View hide = tile.findViewWithTag("home_hide");
    assertNotNull("hide action", hide);
    hide.performClick();
    layout();
    assertTrue(HomeLayoutStore.isHidden(activity, "logs"));
    assertNull(canvas.selectedId());
  }
}
