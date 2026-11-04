package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 横屏 Dock 两个自定义 Drawable 的绘制冒烟测试。
 *
 * <p>P0-1（上一轮编号 P0-2）的教训：没有 draw(Canvas) 测试时，{@code color()} 自递归（首次绘制 StackOverflowError）
 * 不会被任何单测发现。这里直接调用 draw：
 * <ul>
 *   <li>选中/未选中、alpha 0/64/128/255 各画一次，不得抛错（覆盖递归与空画笔路径）；
 *   <li>draw 之后画笔上的实际 alpha 必须等于 setAlpha 的值（回归「setColor 覆盖 alpha」的修复）；
 *   <li>setColorFilter 后仍能绘制。
 * </ul>
 *
 * <p>注意：Robolectric 的 Canvas 在默认（legacy）图形模式下不进行真实光栅化，
 * 像素级 alpha 断言不可移植，因此这里验证画笔状态而不是位图像素；真机像素效果由验收覆盖。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DockDrawableTest {
  private static final int W = 44;
  private static final int H = 26;

  private static void draw(android.graphics.drawable.Drawable drawable) {
    Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    drawable.setBounds(0, 0, W, H);
    drawable.draw(canvas);
  }

  @Test
  public void bracketChipDrawsWithoutRecursionAndKeepsAlpha() {
    BracketChipDrawable chip = new BracketChipDrawable(true);
    chip.setDensity(2f);
    for (int alpha : new int[] {255, 128, 64, 0}) {
      chip.setAlpha(alpha);
      draw(chip);
      assertEquals(alpha, chip.paintAlphaForTest());
    }
    // 未选中态走另一组颜色，同样要能画。
    BracketChipDrawable plain = new BracketChipDrawable(false);
    plain.setAlpha(255);
    draw(plain);
    assertEquals(255, plain.paintAlphaForTest());
  }

  @Test
  public void sciFiPanelDrawsWithoutRecursionAndKeepsAlpha() {
    SciFiPanelDrawable panel = new SciFiPanelDrawable(2f);
    for (int alpha : new int[] {255, 128, 64, 0}) {
      panel.setAlpha(alpha);
      draw(panel);
      assertEquals(alpha, panel.paintAlphaForTest());
    }
  }

  @Test
  public void colorFilterIsAppliedWithoutThrowing() {
    BracketChipDrawable chip = new BracketChipDrawable(false);
    SciFiPanelDrawable panel = new SciFiPanelDrawable(1.5f);
    chip.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));
    panel.setColorFilter(new PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.SRC_IN));
    draw(chip);
    draw(panel);
    assertTrue(true);
  }
}
