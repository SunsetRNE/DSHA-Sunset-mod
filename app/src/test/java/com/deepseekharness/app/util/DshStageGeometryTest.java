package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 横屏 DSH 显示的纯几何单测：真机 2675×1197 @3.5 下三档的缩放与居中。 */
public class DshStageGeometryTest {
  private static final float DENSITY = 3.5f;
  private static final int AREA_W = 2675;
  private static final int AREA_H = 1197;

  @Test
  public void modeOneFillsSixteenByNineArea() {
    assertEquals(4480, DshStageGeometry.layoutWidth(0, DENSITY));
    assertEquals(2520, DshStageGeometry.layoutHeight(0, DENSITY));
    assertEquals(0.475f, DshStageGeometry.scale(0, DENSITY, AREA_W, AREA_H), 0.0005f);
    int[] offset = DshStageGeometry.centerOffset(0, DENSITY, AREA_W, AREA_H);
    // 2128 宽居中 → 左右各 273.5，取整 273 / 274
    assertEquals(273, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.0f, DshStageGeometry.textScale(0), 0.0001f);
  }

  @Test
  public void modeTwoZoomsByOneQuarter() {
    assertEquals(3584, DshStageGeometry.layoutWidth(1, DENSITY));
    assertEquals(2016, DshStageGeometry.layoutHeight(1, DENSITY));
    assertEquals(0.5938f, DshStageGeometry.scale(1, DENSITY, AREA_W, AREA_H), 0.001f);
    int[] offset = DshStageGeometry.centerOffset(1, DENSITY, AREA_W, AREA_H);
    // 同样铺满 16:9 区域，二档只是 CSS 视口窄了 → 字大 25%
    assertEquals(273, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.25f, DshStageGeometry.textScale(1), 0.0001f);
  }

  @Test
  public void modeThreeZoomsByOneHalf() {
    assertEquals(2989, DshStageGeometry.layoutWidth(2, DENSITY));
    assertEquals(1680, DshStageGeometry.layoutHeight(2, DENSITY));
    assertEquals(0.7125f, DshStageGeometry.scale(2, DENSITY, AREA_W, AREA_H), 0.0005f);
    int[] offset = DshStageGeometry.centerOffset(2, DENSITY, AREA_W, AREA_H);
    // 16:9 依旧铺满，只是两侧从 273 收到 272（854 宽取整的余量）
    assertEquals(272, offset[0]);
    assertEquals(0, offset[1]);
    assertEquals(1.5f, DshStageGeometry.textScale(2), 0.0001f);
  }

  @Test
  public void invalidInputsDoNotDivideByZero() {
    assertEquals(0f, DshStageGeometry.scale(0, 0f, AREA_W, AREA_H), 0f);
    assertEquals(0f, DshStageGeometry.scale(0, DENSITY, 0, AREA_H), 0f);
    assertEquals(0f, DshStageGeometry.scale(0, DENSITY, AREA_W, 0), 0f);
    assertEquals(0, DshStageGeometry.clampMode(-5));
    assertEquals(2, DshStageGeometry.clampMode(99));
  }

  @Test
  public void productionStageMatchesReferenceDevice() {
    DshStageGeometry.Stage stage =
        DshStageGeometry.compute(0, DENSITY, AREA_W, AREA_H, 0, AREA_H, 0f);
    assertEquals(4480, stage.layoutW);
    assertEquals(2520, stage.stageH);
    assertEquals(0.475f, stage.scale, 0.0005f);
    assertEquals(2128, stage.viewW);
    assertEquals(1197, stage.viewH);
    assertEquals(274, stage.left);
    assertEquals(0, stage.top);
    assertEquals(false, stage.keyboard);
  }

  @Test
  public void everyDensityKeepsTheSameVisibleViewport() {
    for (float density : new float[] {1f, 2f, 4f}) {
      DshStageGeometry.Stage stage =
          DshStageGeometry.compute(0, density, AREA_W, AREA_H, 0, AREA_H, 0f);
      assertEquals(Math.round(1280 * density), stage.layoutW);
      assertEquals(Math.round(720 * density), stage.stageH);
      assertEquals(2128, stage.viewW);
      assertEquals(1197, stage.viewH);
      assertEquals(274, stage.left);
      assertEquals(0, stage.top);
    }
  }

  @Test
  public void windowSmallerThanTheLayoutStillFitsAndCenters() {
    DshStageGeometry.Stage stage = DshStageGeometry.compute(0, 2f, 800, 300, 0, 300, 0f);
    assertEquals(300, stage.viewH);
    assertEquals(533, stage.viewW);
    assertEquals(134, stage.left);
    assertEquals(0, stage.top);
    assertEquals(true, stage.scale <= 1f);
  }

  @Test
  public void keyboardKeepsScaleAndSquashesTheStage() {
    DshStageGeometry.Stage stage =
        DshStageGeometry.compute(0, DENSITY, AREA_W, AREA_H, 500, AREA_H, 0.475f);
    assertEquals(true, stage.keyboard);
    assertEquals(0.475f, stage.scale, 0.0005f);
    assertEquals(1467, stage.stageH);
    assertEquals(697, stage.viewH);
    assertEquals(0, stage.top);
  }

  @Test
  public void romResizedWindowUsesItsOwnHeightWhenKeyboardIsUp() {
    DshStageGeometry.Stage stage =
        DshStageGeometry.compute(0, DENSITY, AREA_W, 700, 500, AREA_H, 0.475f);
    assertEquals(true, stage.keyboard);
    assertEquals(700, stage.viewH);
    assertEquals(0, stage.top);
  }

  @Test
  public void narrowWindowStillKeepsSixteenByNine() {
    DshStageGeometry.Stage stage = DshStageGeometry.compute(0, 2f, 800, 1200, 0, 1200, 0f);
    assertEquals(800, stage.viewW);
    assertEquals(450, stage.viewH);
    assertEquals(0, stage.left);
    assertEquals(375, stage.top);
    assertEquals(0.3125f, stage.scale, 0.0005f);
  }

  @Test
  public void tinyAvailableAreaBottomAlignsWithNegativeTop() {
    DshStageGeometry.Stage stage =
        DshStageGeometry.compute(0, DENSITY, AREA_W, 100, 50, AREA_H, 0.475f);
    assertEquals(true, stage.keyboard);
    assertEquals(200, stage.viewH);
    // 视口比可用区高：底对齐（宁可裁顶部，也让输入区露出来），top 允许为负。
    assertEquals(-100, stage.top);
  }

  @Test
  public void invalidWindowOrDensityReturnsNull() {
    assertEquals(null, DshStageGeometry.compute(0, 0f, AREA_W, AREA_H, 0, AREA_H, 0f));
    assertEquals(null, DshStageGeometry.compute(0, DENSITY, 0, AREA_H, 0, AREA_H, 0f));
    assertEquals(null, DshStageGeometry.compute(0, DENSITY, AREA_W, 0, 0, AREA_H, 0f));
  }
}
