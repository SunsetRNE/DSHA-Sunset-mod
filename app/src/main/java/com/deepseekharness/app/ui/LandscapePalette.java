package com.deepseekharness.app.ui;

/**
 * 横屏 Dock 的配色单一来源：舞台、滚动条、胶囊与两个自定义 Drawable 都从这里取色。
 *
 * <p>全部是 ARGB 常量（Drawable 无 Context，不能直接引用颜色资源）。XML 侧
 * {@code res/values/landscape_colors.xml} 只保留探针/兼容页背景与矢量图标占位色，**不是**逐项镜像；
 * 两者各自的用途在文件头注释里写清，避免误以为漏了某个颜色。
 */
final class LandscapePalette {
  private LandscapePalette() {}

  /** 舞台留边底色（浅灰蓝）。 */
  static final int STAGE = 0xFFEEF1F6;

  /** 舞台渐变（深蓝黑，四段）。 */
  static final int STAGE_GRADIENT_0 = 0xFF070B10;

  static final int STAGE_GRADIENT_1 = 0xFF0E1822;
  static final int STAGE_GRADIENT_2 = 0xFF1A2835;
  static final int STAGE_GRADIENT_3 = 0xFF0B1219;

  /** 顶部冷光。 */
  static final int STAGE_GLOW = 0x334E7FA8;

  /** 胶囊图标与诊断文字。 */
  static final int DOCK_ICON = 0xFFB6CCDF;

  /** 滚动条：轨道与滑块。 */
  static final int SCROLL_TRACK = 0x33BFE6FF;

  static final int SCROLL_THUMB = 0x99BFE6FF;

  /** 加载遮罩与三档按钮文字。 */
  static final int LOADING_BG = 0xF00B1219;

  static final int CHIP_TEXT_ON = 0xFFEAF7FF;
  static final int CHIP_TEXT_OFF = 0xFFA9C0D4;

  /** 三档 chip（BracketChipDrawable）：选中/未选中的边框、括号、填充与辉光。 */
  static final int CHIP_BORDER_OFF = 0x4D8AA6C0;

  static final int CHIP_BORDER_ON = 0xB37FD8FF;
  static final int CHIP_BRACKET_OFF = 0x8C6FD3FF;
  static final int CHIP_BRACKET_ON = 0xFF9BEAFF;
  static final int CHIP_FILL_OFF = 0x12081018;
  static final int CHIP_FILL_ON = 0xB81A2A3A;
  static final int CHIP_GLOW = 0x596FD3FF;

  /** 胶囊底板（SciFiPanelDrawable）：填充、描边、四角括号。 */
  static final int PANEL_FILL = 0xE00B1219;

  static final int PANEL_BORDER = 0x547FD8FF;
  static final int PANEL_BRACKET = 0xD98BF0FF;
}
