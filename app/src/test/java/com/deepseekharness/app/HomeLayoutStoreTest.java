package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 主页便签编辑写回偏好的约定：只接受完整排列，坏数据不许悄悄退回默认把用户的拖动吞掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HomeLayoutStoreTest {
  private Context context() {
    return RuntimeEnvironment.getApplication();
  }

  private static final java.util.List<String> FULL =
      Arrays.asList(
          "actions",
          "metric-environment",
          "metric-workbench",
          "metric-port",
          "shortcuts-title",
          "shortcuts",
          "logs");

  @Test
  public void setOrderPersistsCompletePermutation() {
    Context context = context();
    java.util.List<String> order =
        Arrays.asList(
            "logs",
            "actions",
            "metric-environment",
            "metric-workbench",
            "metric-port",
            "shortcuts",
            "shortcuts-title");
    HomeLayoutStore.setOrder(context, order);
    assertEquals(order, HomeLayoutStore.order(context));
  }

  /** 用户模块是追加的 id，order 里允许比内置多；缺项和重复仍然拒绝。 */
  @Test
  public void setOrderAcceptsExtraIdsButNotMissingOnes() {
    Context context = context();
    java.util.List<String> order = new java.util.ArrayList<>(FULL);
    order.add("user:my.pet");
    HomeLayoutStore.setOrder(context, order);
    assertEquals(order, HomeLayoutStore.order(context));
  }

  @Test
  public void setOrderRejectsIncompleteOrDuplicated() {
    Context context = context();
    assertRejected(context, FULL.subList(0, FULL.size() - 1));
    java.util.List<String> duplicated = new java.util.ArrayList<>(FULL);
    duplicated.set(1, "actions");
    assertRejected(context, duplicated);
    assertRejected(context, Collections.emptyList());
    assertRejected(context, null);
  }

  @Test
  public void rejectedOrderKeepsPreviousValue() {
    Context context = context();
    java.util.List<String> previous =
        Arrays.asList(
            "metric-port",
            "logs",
            "actions",
            "metric-environment",
            "metric-workbench",
            "shortcuts",
            "shortcuts-title");
    HomeLayoutStore.setOrder(context, previous);
    assertRejected(context, Arrays.asList("logs", "metric-environment"));
    assertEquals(previous, HomeLayoutStore.order(context));
  }

  @Test
  public void navOrderRoundTripAndDefaults() {
    Context context = context();
    assertEquals(HomeLayoutStore.DEFAULT_NAV_ORDER, HomeLayoutStore.navOrder(context));
    HomeLayoutStore.setNavOrder(
        context, Arrays.asList("terminal", "settings", "launch", "plugins", "module"));
    assertEquals(
        Arrays.asList("terminal", "settings", "launch", "plugins", "module"),
        HomeLayoutStore.navOrder(context));
    HomeLayoutStore.resetNav(context);
    assertEquals(HomeLayoutStore.DEFAULT_NAV_ORDER, HomeLayoutStore.navOrder(context));
  }

  @Test
  public void hiddenFlagAndReset() {
    Context context = context();
    assertFalse(HomeLayoutStore.isHidden(context, "logs"));
    HomeLayoutStore.setHidden(context, "logs", true);
    assertTrue(HomeLayoutStore.isHidden(context, "logs"));
    HomeLayoutStore.setHidden(context, "logs", false);
    assertFalse(HomeLayoutStore.isHidden(context, "logs"));
    try {
      HomeLayoutStore.setHidden(context, "unknown", true);
      fail("HOME_LAYOUT_ID");
    } catch (IllegalArgumentException expected) {
      assertEquals("HOME_LAYOUT_ID", expected.getMessage());
    }
  }

  @Test
  public void resetRestoresDefaultOrderAndVisibility() {
    Context context = context();
    HomeLayoutStore.setOrder(
        context,
        Arrays.asList(
            "logs",
            "actions",
            "metric-environment",
            "metric-workbench",
            "metric-port",
            "shortcuts",
            "shortcuts-title"));
    HomeLayoutStore.setHidden(context, "actions", true);
    HomeLayoutStore.reset(context);
    assertEquals(HomeLayoutStore.DEFAULT_ORDER, HomeLayoutStore.order(context));
    assertFalse(HomeLayoutStore.isHidden(context, "actions"));
  }

  /** 旧版本只写 size_=half/full；升级后要正确映射，metric 的 full 不能退回半宽。 */
  @Test
  public void legacySizePrefsMapToWidthFraction() {
    Context context = context();
    context
        .getSharedPreferences("dsha_home_layout", 0)
        .edit()
        .putString("size_metric-port", "full")
        .putString("size_actions", "half")
        .commit();
    assertEquals(1f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    assertEquals(0.5f, HomeLayoutStore.widthFraction(context, "actions"), 0.001f);
    HomeLayoutStore.clearSize(context, "metric-port");
    assertEquals(1f / 3f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
  }

  @Test
  public void continuousWidthAndZoomArePersistedPerTile() {
    Context context = context();
    assertEquals(1f / 3f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    assertEquals(1f, HomeLayoutStore.widthFraction(context, "logs"), 0.001f);
    assertEquals(1f, HomeLayoutStore.zoom(context, "metric-port"), 0.001f);
    assertEquals(1, HomeLayoutStore.span(context, "metric-port"));

    HomeLayoutStore.setWidthFraction(context, "metric-port", 0.72f);
    assertEquals(0.72f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    assertEquals(1, HomeLayoutStore.span(context, "metric-port"));
    HomeLayoutStore.setWidthFraction(context, "metric-port", 0.9f);
    assertEquals(0.9f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    assertEquals(2, HomeLayoutStore.span(context, "metric-port"));
    HomeLayoutStore.setWidthFraction(context, "metric-port", 0.01f);
    assertEquals(
        HomeLayoutStore.WIDTH_MIN, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    HomeLayoutStore.setWidthFraction(context, "metric-port", 9f);
    assertEquals(
        HomeLayoutStore.WIDTH_MAX, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);

    HomeLayoutStore.setZoom(context, "metric-port", 1.35f);
    assertEquals(1.35f, HomeLayoutStore.zoom(context, "metric-port"), 0.001f);
    HomeLayoutStore.setZoom(context, "metric-port", 9f);
    assertEquals(HomeLayoutStore.ZOOM_MAX, HomeLayoutStore.zoom(context, "metric-port"), 0.001f);

    // 单卡恢复默认只清大小，不影响别的卡。
    HomeLayoutStore.setWidthFraction(context, "logs", 0.8f);
    HomeLayoutStore.resetCard(context, "metric-port");
    assertEquals(1f / 3f, HomeLayoutStore.widthFraction(context, "metric-port"), 0.001f);
    assertEquals(1f, HomeLayoutStore.zoom(context, "metric-port"), 0.001f);
    assertEquals(0.8f, HomeLayoutStore.widthFraction(context, "logs"), 0.001f);

    // 全部恢复也要清掉用户模块的自定义大小。
    HomeLayoutStore.setWidthFraction(context, "user:test", 0.6f);
    HomeLayoutStore.setZoom(context, "user:test", 1.7f);
    HomeLayoutStore.reset(context);
    assertEquals(1f, HomeLayoutStore.widthFraction(context, "user:test"), 0.001f);
    assertEquals(1f, HomeLayoutStore.zoom(context, "user:test"), 0.001f);
  }

  @Test
  public void freePositionRoundTripAndReset() {
    Context context = context();
    assertFalse(HomeLayoutStore.hasPosition(context, "logs"));
    HomeLayoutStore.setPosition(context, "logs", 0.3f, 120f);
    assertTrue(HomeLayoutStore.hasPosition(context, "logs"));
    assertEquals(0.3f, HomeLayoutStore.xFraction(context, "logs"), 0.001f);
    assertEquals(120f, HomeLayoutStore.yDp(context, "logs"), 0.001f);
    HomeLayoutStore.setPosition(context, "logs", -1f, -5f);
    assertEquals(0f, HomeLayoutStore.xFraction(context, "logs"), 0.001f);
    assertEquals(0f, HomeLayoutStore.yDp(context, "logs"), 0.001f);

    HomeLayoutStore.setPosition(context, "actions", 0.2f, 80f);
    HomeLayoutStore.clearPosition(context, "logs");
    assertFalse(HomeLayoutStore.hasPosition(context, "logs"));
    HomeLayoutStore.reset(context);
    assertFalse(HomeLayoutStore.hasPosition(context, "actions"));
  }

  /** 底栏每个按钮的大小独立可调，且被夹在合法区间内。 */
  @Test
  public void navItemScaleIsPerKeyAndClamped() {
    Context context = context();
    assertEquals(1f, HomeLayoutStore.navItemScale(context, "launch"), 0.001f);
    HomeLayoutStore.setNavItemScale(context, "launch", 1.3f);
    assertEquals(1.3f, HomeLayoutStore.navItemScale(context, "launch"), 0.001f);
    // 不影响别的按钮
    assertEquals(1f, HomeLayoutStore.navItemScale(context, "settings"), 0.001f);
    // 越界被夹住
    HomeLayoutStore.setNavItemScale(context, "settings", 9f);
    assertEquals(
        HomeLayoutStore.NAV_SCALE_MAX, HomeLayoutStore.navItemScale(context, "settings"), 0.001f);
    HomeLayoutStore.setNavItemScale(context, "settings", 0.01f);
    assertEquals(
        HomeLayoutStore.NAV_SCALE_MIN, HomeLayoutStore.navItemScale(context, "settings"), 0.001f);
    HomeLayoutStore.resetNavItemScale(context);
    assertEquals(1f, HomeLayoutStore.navItemScale(context, "launch"), 0.001f);
  }

  @Test
  public void defaultNavOrderStartsWithLaunch() {
    assertEquals("launch", HomeLayoutStore.DEFAULT_NAV_ORDER.get(0));
    assertEquals(5, HomeLayoutStore.DEFAULT_NAV_ORDER.size());
    assertEquals(HomeLayoutStore.DEFAULT_NAV_ORDER, HomeLayoutStore.navOrder(context()));
  }

  private void assertRejected(Context context, java.util.List<String> order) {
    try {
      HomeLayoutStore.setOrder(context, order);
      fail("HOME_LAYOUT_ORDER should reject " + order);
    } catch (IllegalArgumentException expected) {
      assertEquals("HOME_LAYOUT_ORDER", expected.getMessage());
    }
  }
}
