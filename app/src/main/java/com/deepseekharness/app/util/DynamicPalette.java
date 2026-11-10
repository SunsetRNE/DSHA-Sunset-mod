package com.deepseekharness.app.util;

/**
 * 运行时调色板推导：把壁纸主色推导成与构建期生成器一致的一整套调色板色。
 *
 * <p>色名、标准色基准与对比度门槛全部对齐 {@code tools/generate-theme-palettes.py}：同一套 hue /
 * 饱和度输入必须得到逐字相同的 hex，否则运行期配色会与构建期五套调色板风格不一致，对比度门禁也失去意义。
 * 移植的是该脚本的 {@code derive()}：底色按色相重映射并保持原亮度，强调色逐档搜索 HSL 亮度直到所有
 * 强调色对比度达标（留 4.6 余量），最后按主色亮度回填 {@code primary_dark}。
 *
 * <p>纯逻辑：不 import 任何 Android API，可脱离设备用 {@code tools/run-unit-tests.py} 验证。
 *
 * <p>舍入必须用 {@link Math#rint}（half-to-even）：Python 的 {@code round()} 是银行家舍入，
 * 用 {@code Math.round} 会在 .5 处差 1 个量化单位，导致与生成器不一致。
 */
public final class DynamicPalette {
  /** 调色板色名，顺序与生成器 PALETTE_NAMES 一致；索引即 {@link #derive} 返回结果的第一个下标。 */
  public static final String[] NAMES = {
    "primary",
    "primary_dark",
    "accent_on",
    "ripple",
    "logo_top",
    "terminal_accent",
    "terminal_accent_bg",
    "terminal_accent_soft_2",
    "terminal_accent_line",
    "terminal_on_accent",
    "terminal_ripple",
    "terminal_state_idle",
    "shell_accent",
    "shell_accent_soft",
    "shell_accent_tonal",
    "shell_on_accent",
    "shell_nav_ripple",
    "onb_accent_line",
    "surface",
    "card",
    "raised",
    "disabled",
    "preview",
    "shell_bg",
    "shell_surface",
    "shell_raised",
    "shell_nav_bg",
    "shell_glass",
    "terminal_panel",
    "terminal_panel_card",
    "terminal_raised",
    "terminal_tile",
    "terminal_tile_pressed"
  };

  static final int PRIMARY = 0;
  static final int PRIMARY_DARK = 1;
  static final int ACCENT_ON = 2;
  static final int RIPPLE = 3;
  static final int LOGO_TOP = 4;
  static final int TERMINAL_ACCENT = 5;
  static final int TERMINAL_ACCENT_BG = 6;
  static final int TERMINAL_ACCENT_SOFT_2 = 7;
  static final int TERMINAL_ACCENT_LINE = 8;
  static final int TERMINAL_ON_ACCENT = 9;
  static final int TERMINAL_RIPPLE = 10;
  static final int TERMINAL_STATE_IDLE = 11;
  static final int SHELL_ACCENT = 12;
  static final int SHELL_ACCENT_SOFT = 13;
  static final int SHELL_ACCENT_TONAL = 14;
  static final int SHELL_ON_ACCENT = 15;
  static final int SHELL_NAV_RIPPLE = 16;
  static final int ONB_ACCENT_LINE = 17;
  static final int SURFACE = 18;
  static final int CARD = 19;
  static final int RAISED = 20;
  static final int DISABLED = 21;
  static final int PREVIEW = 22;
  static final int SHELL_BG = 23;
  static final int SHELL_SURFACE = 24;
  static final int SHELL_RAISED = 25;
  static final int SHELL_NAV_BG = 26;
  static final int SHELL_GLASS = 27;
  static final int TERMINAL_PANEL = 28;
  static final int TERMINAL_PANEL_CARD = 29;
  static final int TERMINAL_RAISED = 30;
  static final int TERMINAL_TILE = 31;
  static final int TERMINAL_TILE_PRESSED = 32;

  /** 强调色对比度门槛：与生成器 MARGIN 一致（实际门禁 4.5，搜索留 4.6 余量）。 */
  private static final double MARGIN = 4.6;

  /** 标准色基准（日, 夜），与生成器 STANDARD 逐字一致；推导只用到它的色相与亮度。 */
  private static final String[][] STANDARD = {
    {"#1E50D0", "#6AA4FF"}, // primary
    {"#1A46B8", "#3A75DA"}, // primary_dark
    {"#FFFFFF", "#081428"}, // accent_on
    {"#141E50D0", "#146AA4FF"}, // ripple
    {"#E8EFFF", "#13233C"}, // logo_top
    {"#1E50D0", "#8AB4FF"}, // terminal_accent
    {"#E8EFFF", "#13233C"}, // terminal_accent_bg
    {"#DBE7FF", "#1B3050"}, // terminal_accent_soft_2
    {"#B9CCF4", "#2C4C7C"}, // terminal_accent_line
    {"#FFFFFF", "#081428"}, // terminal_on_accent
    {"#1F1E50D0", "#338AB4FF"}, // terminal_ripple
    {"#1E50D0", "#8AB4FF"}, // terminal_state_idle
    {"#1E50D0", "#6AA4FF"}, // shell_accent
    {"#141E50D0", "#146AA4FF"}, // shell_accent_soft
    {"#1F1E50D0", "#246AA4FF"}, // shell_accent_tonal
    {"#FFFFFF", "#091729"}, // shell_on_accent
    {"#141E50D0", "#146AA4FF"}, // shell_nav_ripple
    {"#471E50D0", "#4D6AA4FF"}, // onb_accent_line
    {"#F3F6FB", "#0B0F16"}, // surface
    {"#FCFDFF", "#131923"}, // card
    {"#E7EDF6", "#1B2432"}, // raised
    {"#E7EDF6", "#1B2432"}, // disabled
    {"#E7EDF6", "#05090E"}, // preview
    {"#F3F6FB", "#0B0F16"}, // shell_bg
    {"#FCFDFF", "#131923"}, // shell_surface
    {"#E7EDF6", "#1B2432"}, // shell_raised
    {"#FCFDFF", "#131923"}, // shell_nav_bg
    {"#E0FAFCFF", "#DB192230"}, // shell_glass
    {"#FFFFFF", "#0B0F16"}, // terminal_panel
    {"#F7F9FD", "#10151E"}, // terminal_panel_card
    {"#EAEFF7", "#151C27"}, // terminal_raised
    {"#F1F4F9", "#141B25"}, // terminal_tile
    {"#E6EBF3", "#1B2431"} // terminal_tile_pressed
  };

  /** 半透明叠色基准：{@code primary_ripple} 不随调色板变化，但参与对比度校验。 */
  private static final String[] PRIMARY_RIPPLE = {"#1FFFFFFF", "#20FFFFFF"};

  private DynamicPalette() {}

  /**
   * 推导不可达（该色相/饱和度下找不到满足对比度的亮度档）时返回 null，绝不放宽门槛。
   *
   * <p>搜索范围与生成器逐字一致：日间 {@code range(600,199,-5)} 即 600→200、夜间 400→900。
   * 终止用比较而不是 {@code !=} 哨兵 —— 5 的步长永远撞不上 199/901，写成哨兵会让循环越过
   * 声明边界继续跑（历史上真的越到 tone=185），靠端点 clamp 出图才碰巧终止；那时「不可达
   * 返回 null」的契约整条失效，门禁一收紧就变主线程死循环。
   */
  public static String[][] derive(double hue, double saturation) {
    // NaN 会污染 hsl 的每个分量（出 #000000），对比度恒不达标；Infinity 同理。直接按不可达处理，
    // 不给调用方一个看起来合法实则全黑的表。
    if (!Double.isFinite(hue) || !Double.isFinite(saturation)) return null;
    String[][] values = new String[NAMES.length][2];
    for (int index = 0; index < NAMES.length; index++) {
      values[index][0] = rehue(STANDARD[index][0], hue);
      values[index][1] = rehue(STANDARD[index][1], hue);
    }
    for (int mode = 0; mode < 2; mode++) {
      boolean dark = mode == 1;
      String on = dark ? hsl(hue, 0.6, 0.08) : "#FFFFFF";
      String[][] chosen = null;
      for (int tone = dark ? 400 : 600; dark ? tone <= 900 : tone >= 200; tone += dark ? 5 : -5) {
        double light = tone / 1000.0;
        String accent = hsl(hue, saturation, light);
        String terminal = hsl(hue, saturation, Math.min(light + (dark ? 0.07 : 0), 0.9));
        String[][] trial = copy(values);
        trial[PRIMARY][mode] = accent;
        trial[SHELL_ACCENT][mode] = accent;
        trial[TERMINAL_ACCENT][mode] = terminal;
        trial[TERMINAL_STATE_IDLE][mode] = terminal;
        trial[ACCENT_ON][mode] = on;
        trial[SHELL_ON_ACCENT][mode] = on;
        trial[TERMINAL_ON_ACCENT][mode] = on;
        for (int index :
            new int[] {
              RIPPLE, SHELL_ACCENT_SOFT, SHELL_ACCENT_TONAL, SHELL_NAV_RIPPLE, ONB_ACCENT_LINE
            }) {
          trial[index][mode] = withAlpha(trial[PRIMARY][mode], alphaOf(STANDARD[index][mode]));
        }
        trial[TERMINAL_RIPPLE][mode] =
            withAlpha(trial[TERMINAL_ACCENT][mode], alphaOf(STANDARD[TERMINAL_RIPPLE][mode]));
        if (passesAccentContrast(trial, mode)) {
          chosen = trial;
          break;
        }
      }
      if (chosen == null) return null;
      double accentLightness = rgbToHls(rgba(chosen[PRIMARY][mode]))[1];
      // 用 clamp 后的饱和度：裸调 derive(hue, 2) 时 2×0.9 在 hsl 里才被夹回 1，
      // 算出的 primary_dark 与 derive(hue, 1) 不同；先夹让入参等价性成立。
      double clamped = Math.max(0, Math.min(1, saturation));
      chosen[PRIMARY_DARK][mode] =
          hsl(hue, clamped * (dark ? 0.7 : 0.9), accentLightness - (dark ? 0.15 : 0.05));
      values = chosen;
    }
    return values;
  }

  /**
   * 运行时入口：推导失败时向邻近饱和度逐档退让，仍失败则返回 null。
   *
   * <p>退让顺序是「离请求值最近优先」，交替向上、向下各试一档：请求 0.95 不可达时先试 1.0 再试
   * 0.9，而不是跳到 0.2 —— 后者会让主题突然变淡到几乎认不出，用户以为功能坏了。每档 0.05、
   * 最多各 16 步覆盖 [0.05, 1]，与生成器 MARGIN 下的实际可达域比足够宽。
   */
  public static String[][] deriveWithinReach(double hue, double saturation) {
    if (!Double.isFinite(hue) || !Double.isFinite(saturation)) return null;
    double start = Math.max(0, Math.min(1, saturation));
    String[][] values = derive(hue, start);
    if (values != null) return values;
    for (int step = 1; step <= 16; step++) {
      double up = start + step * 0.05;
      double down = start - step * 0.05;
      if (up <= 1 + 1e-9) {
        values = derive(hue, Math.min(1, up));
        if (values != null) return values;
      }
      if (down >= -1e-9) {
        values = derive(hue, Math.max(0, down));
        if (values != null) return values;
      }
    }
    return null;
  }

  /**
   * 取某套推导结果里的主色（界面色块与强调色预览用）。
   *
   * <p>索引与 {@link #NAMES} 绑定，调用方不需要知道 primary 排在第几位。
   */
  public static String accent(String[][] values, boolean dark) {
    return values[PRIMARY][dark ? 1 : 0];
  }

  private static boolean passesAccentContrast(String[][] values, int mode) {
    for (String[] pair : accentPairs(values, mode)) {
      if (contrast(pair[0], pair[1]) < MARGIN) return false;
    }
    return true;
  }

  /** 与生成器 {@code accent_pairs} 逐项对应：{fg, bg} 表示文字色压在底色上的对比度校验对。 */
  private static java.util.List<String[]> accentPairs(String[][] v, int mode) {
    java.util.List<String[]> pairs = new java.util.ArrayList<>();
    for (int background : new int[] {SURFACE, CARD, RAISED, DISABLED}) {
      pairs.add(pair(v[PRIMARY][mode], v[background][mode]));
    }
    for (int background : new int[] {SURFACE, CARD, RAISED}) {
      String selected = over(v[RIPPLE][mode], v[background][mode]);
      pairs.add(pair(v[PRIMARY][mode], selected));
      pairs.add(pair(v[PRIMARY][mode], over(v[RIPPLE][mode], selected)));
    }
    pairs.add(pair(v[ACCENT_ON][mode], v[PRIMARY][mode]));
    pairs.add(pair(v[ACCENT_ON][mode], over(PRIMARY_RIPPLE[mode], v[PRIMARY][mode])));
    for (int background : new int[] {SHELL_BG, SHELL_SURFACE, SHELL_RAISED}) {
      pairs.add(pair(v[SHELL_ACCENT][mode], v[background][mode]));
    }
    pairs.add(pair(v[SHELL_ON_ACCENT][mode], v[SHELL_ACCENT][mode]));
    for (int background : new int[] {TERMINAL_PANEL, TERMINAL_PANEL_CARD}) {
      pairs.add(pair(v[TERMINAL_ACCENT][mode], v[background][mode]));
    }
    pairs.add(pair(v[TERMINAL_ON_ACCENT][mode], v[TERMINAL_ACCENT][mode]));
    return pairs;
  }

  private static String[] pair(String foreground, String background) {
    return new String[] {foreground, background};
  }

  /** 换色相并保持相对亮度：同样的 HSL 亮度在紫、蓝上更暗，在黄、青上更亮，所以按亮度二分回原值。 */
  static String rehue(String value, double hue) {
    int[] source = rgba(value);
    double[] hls = rgbToHls(new double[] {source[1] / 255.0, source[2] / 255.0, source[3] / 255.0});
    double saturation = hls[2];
    double target = luminance(hexa(255, source[1], source[2], source[3]));
    double low = 0;
    double high = 1;
    for (int step = 0; step < 40; step++) {
      double middle = (low + high) / 2;
      if (luminance(hsl(hue, saturation, middle)) < target) low = middle;
      else high = middle;
    }
    String lowColor = hsl(hue, saturation, low);
    String highColor = hsl(hue, saturation, high);
    String best =
        Math.abs(luminance(highColor) - target) < Math.abs(luminance(lowColor) - target)
            ? highColor
            : lowColor;
    return withAlpha(best, source[0]);
  }

  static String hsl(double hue, double saturation, double lightness) {
    return hsl(hue, saturation, lightness, 255);
  }

  static String hsl(double hue, double saturation, double lightness, int alpha) {
    double[] rgb =
        hlsToRgb(
            (((hue % 360) + 360) % 360) / 360.0,
            Math.max(0, Math.min(1, lightness)),
            Math.max(0, Math.min(1, saturation)));
    return hexa(alpha, pyRound(rgb[0] * 255), pyRound(rgb[1] * 255), pyRound(rgb[2] * 255));
  }

  /** CPython colorsys.hls_to_rgb 的逐行移植。 */
  private static double[] hlsToRgb(double hue, double lightness, double saturation) {
    if (saturation == 0) return new double[] {lightness, lightness, lightness};
    double m2 =
        lightness <= 0.5
            ? lightness * (1 + saturation)
            : lightness + saturation - lightness * saturation;
    double m1 = 2 * lightness - m2;
    return new double[] {
      value(m1, m2, hue + 1.0 / 3), value(m1, m2, hue), value(m1, m2, hue - 1.0 / 3)
    };
  }

  private static double value(double m1, double m2, double hue) {
    double normalized = hue % 1.0;
    if (normalized < 0) normalized += 1.0;
    if (normalized < 1.0 / 6) return m1 + (m2 - m1) * normalized * 6.0;
    if (normalized < 0.5) return m2;
    if (normalized < 2.0 / 3) return m1 + (m2 - m1) * (2.0 / 3 - normalized) * 6.0;
    return m1;
  }

  /** 从 ARGB 包装取值：生成器传的是 {@code rgba(...)[1:]} 三个 0-255 分量。 */
  static double[] rgbToHls(int[] argb) {
    return rgbToHls(new double[] {argb[1] / 255.0, argb[2] / 255.0, argb[3] / 255.0});
  }

  /** CPython colorsys.rgb_to_hls 的逐行移植；返回 {h, l, s}。 */
  static double[] rgbToHls(double[] rgb) {
    double r = rgb[0];
    double g = rgb[1];
    double b = rgb[2];
    double max = Math.max(r, Math.max(g, b));
    double min = Math.min(r, Math.min(g, b));
    double lightness = (min + max) / 2.0;
    if (min == max) return new double[] {0, lightness, 0};
    double saturation =
        lightness <= 0.5 ? (max - min) / (max + min) : (max - min) / (2.0 - max - min);
    double rc = (max - r) / (max - min);
    double gc = (max - g) / (max - min);
    double bc = (max - b) / (max - min);
    double hue;
    if (r == max) hue = bc - gc;
    else if (g == max) hue = 2.0 + rc - bc;
    else hue = 4.0 + gc - rc;
    hue = ((hue / 6.0) % 1.0 + 1.0) % 1.0;
    return new double[] {hue, lightness, saturation};
  }

  static int[] rgba(String value) {
    String raw = value.substring(1);
    if (raw.length() == 6) raw = "FF" + raw;
    int[] out = new int[4];
    for (int index = 0; index < 4; index++)
      out[index] = Integer.parseInt(raw.substring(index * 2, index * 2 + 2), 16);
    return out;
  }

  private static String hexa(int alpha, int r, int g, int b) {
    return alpha == 255
        ? String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b)
        : String.format(java.util.Locale.ROOT, "#%02X%02X%02X%02X", alpha, r, g, b);
  }

  static String withAlpha(String value, int alpha) {
    int[] source = rgba(value);
    return hexa(alpha, source[1], source[2], source[3]);
  }

  static int alphaOf(String value) {
    return rgba(value)[0];
  }

  static String over(String top, String base) {
    int[] a = rgba(top);
    int[] b = rgba(base);
    int[] out = new int[3];
    for (int index = 0; index < 3; index++) {
      out[index] = pyRound((a[index + 1] * a[0] + b[index + 1] * (255 - a[0])) / 255.0);
    }
    return hexa(255, out[0], out[1], out[2]);
  }

  static double luminance(String value) {
    int[] rgb = rgba(value);
    double[] linear = new double[3];
    for (int index = 0; index < 3; index++) {
      double channel = rgb[index + 1] / 255.0;
      linear[index] =
          channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
  }

  static double contrast(String foreground, String background) {
    double first = luminance(foreground);
    double second = luminance(background);
    double low = Math.min(first, second);
    double high = Math.max(first, second);
    return (high + 0.05) / (low + 0.05);
  }

  /** Python {@code round()} 的 half-to-even；负数不会出现在颜色分量上。 */
  static int pyRound(double value) {
    return (int) Math.rint(value);
  }

  private static String[][] copy(String[][] source) {
    String[][] out = new String[source.length][];
    for (int index = 0; index < source.length; index++) out[index] = source[index].clone();
    return out;
  }
}
