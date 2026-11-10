package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 取色的可解释性：同一张图必须稳定给出同一个种子，无彩图必须如实报告“没取到”。 */
public class PaletteSeedExtractorTest {
  private static int argb(int red, int green, int blue) {
    return 0xFF000000 | (red << 16) | (green << 8) | blue;
  }

  private static int[] fill(int count, int color) {
    int[] pixels = new int[count];
    java.util.Arrays.fill(pixels, color);
    return pixels;
  }

  @Test
  public void pureRedReportsRedHue() {
    PaletteSeedExtractor.Seed seed = PaletteSeedExtractor.extract(fill(64, argb(255, 0, 0)));
    assertTrue(seed.found);
    assertTrue("hue " + seed.hue, Math.abs(seed.hue) < 1 || Math.abs(seed.hue - 360) < 1);
    assertEquals(0.95, seed.saturation, 0.001);
  }

  @Test
  public void pureBlueReportsBlueHue() {
    PaletteSeedExtractor.Seed seed = PaletteSeedExtractor.extract(fill(64, argb(0, 0, 255)));
    assertTrue(seed.found);
    assertEquals(240, seed.hue, 1);
  }

  /** 大面积底色不能盖过主色：像素多不等于颜色主，按饱和度投票才符合“取壁纸强调色”的直觉。 */
  @Test
  public void saturatedMinorityBeatsGrayMajority() {
    int[] pixels = new int[200];
    java.util.Arrays.fill(pixels, 0, 150, argb(128, 128, 128));
    java.util.Arrays.fill(pixels, 150, 200, argb(255, 0, 0));
    PaletteSeedExtractor.Seed seed = PaletteSeedExtractor.extract(pixels);
    assertTrue(seed.found);
    assertTrue("hue " + seed.hue, Math.abs(seed.hue) < 1 || Math.abs(seed.hue - 360) < 1);
  }

  @Test
  public void grayscaleWallpaperReportsNothing() {
    assertFalse(PaletteSeedExtractor.extract(fill(64, argb(128, 128, 128))).found);
    assertFalse(PaletteSeedExtractor.extract(fill(64, argb(255, 255, 255))).found);
  }

  @Test
  public void transparentAndTinyColorShareReportNothing() {
    assertFalse(PaletteSeedExtractor.extract(fill(64, 0x00000000)).found);
    int[] pixels = new int[10000];
    java.util.Arrays.fill(pixels, argb(255, 255, 255));
    // 一个红色像素：占比万分之一，低于千分之四门槛。
    pixels[0] = argb(255, 0, 0);
    assertFalse(PaletteSeedExtractor.extract(pixels).found);
  }

  /** 深色壁纸常见：近黑底的色相不可靠，必须被亮度门槛挡掉。 */
  @Test
  public void nearBlackPixelsDoNotVote() {
    assertFalse(PaletteSeedExtractor.extract(fill(64, argb(5, 5, 5))).found);
    assertFalse(PaletteSeedExtractor.extract(fill(64, argb(0, 0, 40))).found);
  }

  @Test
  public void saturationIsClampedIntoVisibleRange() {
    // 偏灰的蓝（饱和度约 0.1 上下摆动）仍要给出可见的强调色，不能算出几乎无色的种子。
    PaletteSeedExtractor.Seed seed = PaletteSeedExtractor.extract(fill(64, argb(120, 130, 150)));
    assertTrue(seed.found);
    assertTrue("saturation " + seed.saturation, seed.saturation >= 0.35);
    assertTrue("saturation " + seed.saturation, seed.saturation <= 0.95);
  }

  @Test
  public void rangeExtractionMatchesWholeImageOnSubset() {
    int[] pixels = new int[100];
    java.util.Arrays.fill(pixels, argb(0, 0, 255));
    PaletteSeedExtractor.Seed whole = PaletteSeedExtractor.extract(pixels);
    PaletteSeedExtractor.Seed part = PaletteSeedExtractor.extract(pixels, 0, 50);
    assertEquals(whole.hue, part.hue, 0.001);
    assertEquals(whole.found, part.found);
  }

  /** 空图必须安全返回，不能除零。 */
  @Test
  public void emptyInputIsSafe() {
    assertFalse(PaletteSeedExtractor.extract(new int[0]).found);
  }
}
