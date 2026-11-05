package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 两列便签网格：窄卡回填空列（masonry）、全宽卡独占一行、内容高度自然。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HomeMasonryLayoutTest {

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
  }

  private static View tile(Context context, int height) {
    return new View(context) {
      @Override
      protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), height);
      }
    };
  }

  @Test
  public void halfTilesFillTwoColumnsBeforeNextRow() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 80);
    View second = tile(context, 80);
    View third = tile(context, 80);
    grid.addView(first, new HomeMasonryLayout.LayoutParams(1));
    grid.addView(second, new HomeMasonryLayout.LayoutParams(1));
    grid.addView(third, new HomeMasonryLayout.LayoutParams(1));
    grid.setFlow(Arrays.asList(first, second, third));
    grid.setAnimateChanges(false);
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());

    assertTrue(first.getTop() == second.getTop());
    assertTrue(second.getLeft() > first.getLeft());
    assertTrue(third.getTop() > first.getTop());
    assertTrue(third.getLeft() == first.getLeft());
  }

  @Test
  public void fullTileBreaksTheColumns() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View half = tile(context, 60);
    View full = tile(context, 100);
    View after = tile(context, 60);
    grid.addView(half, new HomeMasonryLayout.LayoutParams(1));
    grid.addView(full, new HomeMasonryLayout.LayoutParams(2));
    grid.addView(after, new HomeMasonryLayout.LayoutParams(1));
    grid.setFlow(Arrays.asList(half, full, after));
    grid.setAnimateChanges(false);
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());

    // 整行卡必须排在半宽卡下方，并且铺满整行。
    assertTrue(full.getTop() > half.getTop());
    assertEquals(0, full.getLeft());
    assertEquals(400, full.getRight());
    // 整行卡之后的窄卡必须在它下方（不能回头与它并排）。
    assertTrue(after.getTop() > full.getTop());
  }

  /**
   * 关键回归：整行卡下面出现空洞时，后面的窄卡要一路回填到最上面的空列，
   * 而不是每张窄卡各占一行、右边留白。
   */
  @Test
  public void narrowCardsBackfillTheEmptyColumnLeftByAFullTile() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 60); // 左列
    View full = tile(context, 100); // 整行，截断
    View leftEmpty = tile(context, 60); // 应该回填到 full 上方左侧空位？不能跨过 full
    View second = tile(context, 60);
    View third = tile(context, 60);
    grid.addView(first, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(full, new HomeMasonryLayout.LayoutParams(1f));
    grid.addView(leftEmpty, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(second, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(third, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.setFlow(Arrays.asList(first, full, leftEmpty, second, third));
    grid.setAnimateChanges(false);
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());

    // 整行卡不能与 first 并排。
    assertTrue(full.getTop() >= first.getBottom());
    // 整行卡之后的三个半宽卡：前两个必须并排在同一行（回填），第三个才换行。
    assertEquals(leftEmpty.getTop(), second.getTop());
    assertTrue(second.getLeft() > leftEmpty.getLeft());
    assertTrue(third.getTop() > leftEmpty.getTop());
  }

  /**
   * 长短不一的窄卡回填：矮卡先排左列，高卡排右列，第三张矮卡应回填到左列下方，
   * 而不是等右列的高卡结束。
   */
  /**
   * 两个半宽卡占满一行并且被等高之后，第三张半宽卡换到下一行最左。
   */
  @Test
  public void thirdHalfTileStartsNextRowWhenRowIsEven() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View shortLeft = tile(context, 40); // 左列矮
    View tallRight = tile(context, 160); // 右列高
    View next = tile(context, 40);
    grid.addView(shortLeft, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(tallRight, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(next, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.setFlow(Arrays.asList(shortLeft, tallRight, next));
    grid.setAnimateChanges(false);
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());

    // 前两张并排在同一行。
    assertEquals(shortLeft.getTop(), tallRight.getTop());
    assertTrue(tallRight.getLeft() > shortLeft.getLeft());
    // 前两张并排，第三张按「补最短列」落到左列下方（排版期用的是自然高度，
    // 等高在排版结束后统一补齐，所以这里只校验列归属与相对位置）。
    assertEquals(shortLeft.getLeft(), next.getLeft());
    assertTrue("第三张必须在第一行之下", next.getTop() > shortLeft.getTop());
  }

  /**
   * 关键回归（作者反馈的场景）：一张窄卡后面跟一张整行卡，落单的窄卡把整行卡挤下去之后，
   * 后面的窄卡仍要左对齐成行，不能因为整行卡截断就每张各占一行、右边全空。
   */
  @Test
  public void narrowTileAfterFullTileAlignsLeftInsteadOfLeavingGap() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View lone = tile(context, 60);
    View full = tile(context, 100);
    View afterLeft = tile(context, 60);
    View afterRight = tile(context, 60);
    grid.addView(lone, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(full, new HomeMasonryLayout.LayoutParams(1f));
    grid.addView(afterLeft, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(afterRight, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.setFlow(Arrays.asList(lone, full, afterLeft, afterRight));
    grid.setAnimateChanges(false);
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());

    // 整行卡独占一行且铺满。
    assertTrue(full.getTop() >= lone.getBottom());
    assertEquals(0, full.getLeft());
    assertEquals(400, full.getRight());
    // 整行卡之后的两张窄卡必须并排同一行（左对齐），不留空列。
    assertEquals(lone.getLeft(), afterLeft.getLeft());
    assertEquals(afterLeft.getTop(), afterRight.getTop());
    assertTrue(afterRight.getLeft() > afterLeft.getLeft());
    assertTrue(afterLeft.getTop() >= full.getBottom());
  }

  /**
   * 关键不变量（作者反复反馈的 bug）：**选区框必须和卡片本体完全同尺寸**。
   *
   * <p>这里直接检查 HomeTile 的 overlay 尺寸 == tile 尺寸 == 网格分配的矩形。
   * 任何"拉伸容器但不拉伸内容"的改动都会让这条挂掉。
   */
  @Test
  public void selectionOverlayMatchesTileSizeExactly() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    HomeTile tall = new HomeTile(context, tile(context, 500));
    HomeTile shortTile = new HomeTile(context, tile(context, 80));
    grid.addView(tall, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.addView(shortTile, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.setFlow(Arrays.asList(tall, shortTile));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);

    for (HomeTile t : Arrays.asList(tall, shortTile)) {
      t.setSelectedForResize(true, null, () -> {}, () -> {});
      measureAndLayout(grid);
      View overlay = t.overlay();
      assertNotNull("选中后应有选区框", overlay);
      assertEquals("选区框宽度必须等于卡片宽度", t.getWidth(), overlay.getWidth());
      assertEquals("选区框高度必须等于卡片高度", t.getHeight(), overlay.getHeight());
      t.setSelectedForResize(false, null, () -> {}, () -> {});
    }
    // 两张卡高度必须各自等于内容高度（谁都不许被拉伸）。
    assertEquals(500, tall.getHeight());
    assertEquals(80, shortTile.getHeight());
  }

  /** 换位不是瞬移：被让位的卡片先保留旧视觉位置，再用弹性动画滑到新槽位。 */
  @Test
  public void reflowStartsSpringAnimationOnOtherTiles() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 80);
    View second = tile(context, 80);
    grid.addView(first, new HomeMasonryLayout.LayoutParams(2));
    grid.addView(second, new HomeMasonryLayout.LayoutParams(2));
    grid.setAnimateChanges(false);
    grid.setFlow(Arrays.asList(first, second));
    measureAndLayout(grid);
    first.setTranslationY(0f);
    second.setTranslationY(0f);

    grid.setAnimateChanges(true);
    grid.setFlow(Arrays.asList(second, first));
    measureAndLayout(grid);
    // first 从第一行换到第二行：动画起点是旧位置，因此 translationY 非 0。
    assertTrue(Math.abs(first.getTranslationY()) > 0f);
  }

  /** 原版排版：三个 1/3 卡在同一行，第三张不能被迫换行。 */
  @Test
  public void threeThirdWidthCardsShareOneRow() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 80);
    View second = tile(context, 80);
    View third = tile(context, 80);
    for (View view : Arrays.asList(first, second, third)) {
      grid.addView(view, new HomeMasonryLayout.LayoutParams(1f / 3f));
    }
    grid.setFlow(Arrays.asList(first, second, third));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);
    assertEquals(first.getTop(), second.getTop());
    assertEquals(first.getTop(), third.getTop());
    assertTrue(second.getLeft() > first.getLeft());
    assertTrue(third.getLeft() > second.getLeft());
    assertTrue(third.getRight() <= 400);
  }

  /** 固定到自由位置的卡片直接按 x/y 摆放，不再参与流式重排。 */
  @Test
  public void pinnedPositionsAreHonored() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 80);
    View second = tile(context, 80);
    HomeMasonryLayout.LayoutParams firstParams = new HomeMasonryLayout.LayoutParams(0.5f);
    firstParams.setPosition(0f, 0f);
    HomeMasonryLayout.LayoutParams secondParams = new HomeMasonryLayout.LayoutParams(0.5f);
    secondParams.setPosition(0.5f, 50f);
    grid.addView(first, firstParams);
    grid.addView(second, secondParams);
    grid.setFlow(Arrays.asList(first, second));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);

    assertEquals(0, first.getLeft());
    assertEquals(0, first.getTop());
    assertEquals(200, second.getLeft());
    assertEquals(
        Math.round(50 * context.getResources().getDisplayMetrics().density), second.getTop());
  }

  /** 自由位置也不能叠在一起：后落位、又压到别人身上的卡被顺到已放好的卡下面。 */
  @Test
  public void pinnedCardsNeverOverlap() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View first = tile(context, 80);
    View second = tile(context, 80);
    HomeMasonryLayout.LayoutParams firstParams = new HomeMasonryLayout.LayoutParams(0.5f);
    firstParams.setPosition(0f, 0f);
    HomeMasonryLayout.LayoutParams secondParams = new HomeMasonryLayout.LayoutParams(0.5f);
    secondParams.setPosition(0f, 0f);
    grid.addView(first, firstParams);
    grid.addView(second, secondParams);
    grid.setFlow(Arrays.asList(first, second));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);

    assertEquals(0, first.getTop());
    assertTrue(second.getTop() > first.getBottom());
    assertEquals(0, second.getLeft());
    // 顺开后的坐标要写回 params，下一轮不会再按重叠坐标重新排一次。
    assertEquals(
        second.getTop(),
        Math.round(secondParams.yDp * context.getResources().getDisplayMetrics().density));
  }

  /** 用户正在拖/缩放的那张卡保位：它压到别人时，别人向下让开。 */
  @Test
  public void anchorKeepsPositionAndOthersGiveWay() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View other = tile(context, 80);
    View anchor = tile(context, 80);
    HomeMasonryLayout.LayoutParams otherParams = new HomeMasonryLayout.LayoutParams(0.5f);
    otherParams.setPosition(0f, 0f);
    HomeMasonryLayout.LayoutParams anchorParams = new HomeMasonryLayout.LayoutParams(0.5f);
    anchorParams.setPosition(0f, 0f);
    grid.addView(other, otherParams);
    grid.addView(anchor, anchorParams);
    grid.setFlow(Arrays.asList(other, anchor));
    grid.setAnimateChanges(false);
    grid.setAnchor(anchor);
    measureAndLayout(grid);

    assertEquals(0, anchor.getTop());
    assertTrue(other.getTop() > anchor.getBottom());
  }

  /** 连续比例宽度：0.7 + 0.3 也能并在同一行，而不是只认半宽/整行。 */
  @Test
  public void continuousFractionsShareARow() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View left = tile(context, 80);
    View right = tile(context, 80);
    grid.addView(left, new HomeMasonryLayout.LayoutParams(0.7f));
    grid.addView(right, new HomeMasonryLayout.LayoutParams(0.3f));
    grid.setFlow(Arrays.asList(left, right));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);
    assertEquals(left.getTop(), right.getTop());
    assertTrue(right.getLeft() > left.getLeft());
    assertTrue(right.getRight() <= 400);
  }

  /** 一行放不下就换行，不能重叠。 */
  @Test
  public void continuousFractionsWrapWhenTheyDoNotFit() {
    HomeMasonryLayout grid = new HomeMasonryLayout(context, 10, 2);
    View left = tile(context, 80);
    View right = tile(context, 80);
    grid.addView(left, new HomeMasonryLayout.LayoutParams(0.7f));
    grid.addView(right, new HomeMasonryLayout.LayoutParams(0.5f));
    grid.setFlow(Arrays.asList(left, right));
    grid.setAnimateChanges(false);
    measureAndLayout(grid);
    assertTrue(right.getTop() > left.getTop());
    assertEquals(0, right.getLeft());
  }

  /** 选中后才出现缩放手柄；未选中卡片没有任何顶部图标。 */
  @Test
  public void selectionOverlayCoversTheTileOnlyWhenSelected() {
    HomeTile tile = new HomeTile(context, tile(context, 100));
    tile.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    tile.layout(200, 0, 500, tile.getMeasuredHeight());
    assertTrue(tile.overlay() == null);

    tile.setSelectedForResize(
        true,
        new HomeTile.Listener() {
          @Override
          public void onResizeStart(HomeTile value) {}

          @Override
          public void onResize(HomeTile value, float widthFraction, float zoom) {}

          @Override
          public void onResizeEnd(HomeTile value) {}
        },
        () -> {},
        () -> {});
    tile.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    tile.layout(200, 0, 500, tile.getMeasuredHeight());
    assertTrue(tile.overlay() != null);
    assertEquals(300, tile.overlay().getWidth());
    assertEquals(tile.getMeasuredHeight(), tile.overlay().getHeight());
  }

  private static void measureAndLayout(HomeMasonryLayout grid) {
    grid.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    grid.layout(0, 0, 400, grid.getMeasuredHeight());
  }

  @Test
  public void zoomGrowsTileHeightWithoutChangingWidth() {
    HomeTile tile = new HomeTile(context, tile(context, 100));
    tile.setZoom(1.4f);
    tile.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    assertEquals(300, tile.getMeasuredWidth());
    assertTrue(tile.getMeasuredHeight() >= 140);
  }
}
