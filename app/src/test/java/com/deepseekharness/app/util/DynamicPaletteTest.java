package com.deepseekharness.app.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 运行时推导必须与构建期生成器逐字一致：同一 hue / 饱和度在两处得到不同 hex，
 * 运行期配色就会偏离五套既有调色板的风格，对比度门禁也失去意义。
 *
 * <p>期望值由 {@code /root/DSHA开发/monet-probe/gen_ref.py} 从生成器导出后自动写入，
 * 不要手改；生成器算法改动时重跑该脚本。
 */
public class DynamicPaletteTest {
  /** 188_080：由 tools/generate-theme-palettes.py 的 derive() 导出。 */
  private static final String[][] AURORA = {
    {"#0D6573", "#16B0C8"}, // primary
    {"#0E4E58", "#206771"}, // primary_dark
    {"#FFFFFF", "#081D21"}, // accent_on
    {"#140D6573", "#1416B0C8"}, // ripple
    {"#C7F7FF", "#0D262A"}, // logo_top
    {"#0D6573", "#1CCBE6"}, // terminal_accent
    {"#C7F7FF", "#0D262A"}, // terminal_accent_bg
    {"#A6F3FF", "#13343A"}, // terminal_accent_soft_2
    {"#77DAEA", "#20535A"}, // terminal_accent_line
    {"#FFFFFF", "#081D21"}, // terminal_on_accent
    {"#1F0D6573", "#331CCBE6"}, // terminal_ripple
    {"#0D6573", "#1CCBE6"}, // terminal_state_idle
    {"#0D6573", "#16B0C8"}, // shell_accent
    {"#140D6573", "#1416B0C8"}, // shell_accent_soft
    {"#1F0D6573", "#2416B0C8"}, // shell_accent_tonal
    {"#FFFFFF", "#081D21"}, // shell_on_accent
    {"#140D6573", "#1416B0C8"}, // shell_nav_ripple
    {"#470D6573", "#4D16B0C8"}, // onb_accent_line
    {"#EDF8F9", "#091011"}, // surface
    {"#F9FEFF", "#0F1B1C"}, // card
    {"#DEF0F2", "#162629"}, // raised
    {"#DEF0F2", "#162629"}, // disabled
    {"#DEF0F2", "#040A0A"}, // preview
    {"#EDF8F9", "#091011"}, // shell_bg
    {"#F9FEFF", "#0F1B1C"}, // shell_surface
    {"#DEF0F2", "#162629"}, // shell_raised
    {"#F9FEFF", "#0F1B1C"}, // shell_nav_bg
    {"#E0F4FEFF", "#DB142427"}, // shell_glass
    {"#FFFFFF", "#091011"}, // terminal_panel
    {"#F2FBFC", "#0D1618"}, // terminal_panel_card
    {"#E2F2F4", "#111E1F"}, // terminal_raised
    {"#ECF6F7", "#101D1E"}, // terminal_tile
    {"#DFEDF0", "#162629"}, // terminal_tile_pressed
  };

  /** 30_085：由 tools/generate-theme-palettes.py 的 derive() 导出。 */
  private static final String[][] AMBER = {
    {"#894A0B", "#ED861F"}, // primary
    {"#6C3D0E", "#996027"}, // primary_dark
    {"#FFFFFF", "#211408"}, // accent_on
    {"#14894A0B", "#14ED861F"}, // ripple
    {"#FFECD8", "#301F0F"}, // logo_top
    {"#894A0B", "#F09840"}, // terminal_accent
    {"#FFECD8", "#301F0F"}, // terminal_accent_bg
    {"#FFE2C4", "#412B16"}, // terminal_accent_soft_2
    {"#EFC498", "#654524"}, // terminal_accent_line
    {"#FFFFFF", "#211408"}, // terminal_on_accent
    {"#1F894A0B", "#33F09840"}, // terminal_ripple
    {"#894A0B", "#F09840"}, // terminal_state_idle
    {"#894A0B", "#ED861F"}, // shell_accent
    {"#14894A0B", "#14ED861F"}, // shell_accent_soft
    {"#1F894A0B", "#24ED861F"}, // shell_accent_tonal
    {"#FFFFFF", "#211408"}, // shell_on_accent
    {"#14894A0B", "#14ED861F"}, // shell_nav_ripple
    {"#47894A0B", "#4DED861F"}, // onb_accent_line
    {"#FAF5F0", "#130E09"}, // surface
    {"#FFFDFA", "#1E1811"}, // card
    {"#F4EBE3", "#2C2217"}, // raised
    {"#F4EBE3", "#2C2217"}, // disabled
    {"#F4EBE3", "#0C0804"}, // preview
    {"#FAF5F0", "#130E09"}, // shell_bg
    {"#FFFDFA", "#1E1811"}, // shell_surface
    {"#F4EBE3", "#2C2217"}, // shell_raised
    {"#FFFDFA", "#1E1811"}, // shell_nav_bg
    {"#E0FFFBF8", "#DB292016"}, // shell_glass
    {"#FFFFFF", "#130E09"}, // terminal_panel
    {"#FCF8F5", "#19140E"}, // terminal_panel_card
    {"#F5EDE6", "#221A13"}, // terminal_raised
    {"#F8F3EE", "#211912"}, // terminal_tile
    {"#F1E9E2", "#2B2218"}, // terminal_tile_pressed
  };

  @Test
  public void matchesGeneratorForAurora() {
    assertPalette(188, 0.80, AURORA);
  }

  @Test
  public void matchesGeneratorForAmber() {
    assertPalette(30, 0.85, AMBER);
  }

  @Test
  public void namesMatchGeneratorOrder() {
    assertTrue(DynamicPalette.NAMES.length == 33);
    assertTrue("primary".equals(DynamicPalette.NAMES[0]));
    assertTrue("primary_dark".equals(DynamicPalette.NAMES[1]));
    assertTrue("terminal_tile_pressed".equals(DynamicPalette.NAMES[32]));
  }

  @Test
  public void everyColorIsArgbHex() {
    for (int hue : new int[] {0, 45, 120, 188, 240, 335, 359}) {
      String[][] values = DynamicPalette.derive(hue, 0.7);
      assertNotNull("hue " + hue, values);
      for (int index = 0; index < DynamicPalette.NAMES.length; index++) {
        for (int mode = 0; mode < 2; mode++) {
          String value = values[index][mode];
          assertTrue(
              DynamicPalette.NAMES[index] + " " + value,
              value.matches("#[0-9A-F]{6}") || value.matches("#[0-9A-F]{8}"));
        }
      }
    }
  }

  /** 设计门禁是 4.5；推导按 4.6 搜索，因此每一档都必须有余量。 */
  @Test
  public void accentsKeepContrastOnEveryBackground() {
    for (int hue = 0; hue < 360; hue += 15) {
      String[][] values = DynamicPalette.derive(hue, 0.7);
      assertNotNull("hue " + hue, values);
      for (int mode = 0; mode < 2; mode++) {
        for (int background :
            new int[] {
              DynamicPalette.SURFACE, DynamicPalette.CARD,
              DynamicPalette.RAISED, DynamicPalette.DISABLED
            }) {
          assertTrue(
              "hue " + hue + " mode " + mode,
              DynamicPalette.contrast(values[0][mode], values[background][mode]) >= 4.5);
        }
        assertTrue(
            "onAccent hue " + hue,
            DynamicPalette.contrast(values[2][mode], values[0][mode]) >= 4.5);
      }
    }
  }

  /** 极端饱和度不能把界面卡死：运行期只能降档退让，不能返回 null 让 Activity 崩。 */
  @Test
  public void extremeSaturationFallsBackInsteadOfFailing() {
    for (int hue = 0; hue < 360; hue += 30) {
      assertNotNull("hue " + hue, DynamicPalette.deriveWithinReach(hue, 1.0));
      assertNotNull("hue " + hue, DynamicPalette.deriveWithinReach(hue, 0.0));
    }
  }

  private static void assertPalette(int hue, double saturation, String[][] expected) {
    String[][] actual = DynamicPalette.derive(hue, saturation);
    assertNotNull(actual);
    for (int index = 0; index < DynamicPalette.NAMES.length; index++) {
      for (int mode = 0; mode < 2; mode++) {
        String name = DynamicPalette.NAMES[index] + (mode == 0 ? "/day" : "/night");
        assertTrue(
            "hue "
                + hue
                + " "
                + name
                + " expected "
                + expected[index][mode]
                + " actual "
                + actual[index][mode],
            expected[index][mode].equals(actual[index][mode]));
      }
    }
  }
}
