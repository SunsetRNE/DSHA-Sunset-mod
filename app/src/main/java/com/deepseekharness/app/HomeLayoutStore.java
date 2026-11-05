package com.deepseekharness.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 用户主页区块布局：只保存顺序和可见性，不改变区块业务或启动状态。 */
public final class HomeLayoutStore {
  public static final List<String> DEFAULT_ORDER =
      Collections.unmodifiableList(
          Arrays.asList(
              "actions",
              "metric-environment",
              "metric-workbench",
              "metric-port",
              "shortcuts-title",
              "shortcuts",
              "logs"));

  /**
   * 底栏默认顺序：启动在第一，其余沿用作者原始布局（插件、设置、终端），
   * 模块中心作为本分支新增的核心入口放在第二位。
   */
  public static final List<String> DEFAULT_NAV_ORDER =
      Collections.unmodifiableList(
          Arrays.asList("launch", "module", "plugins", "settings", "terminal"));

  private static final String PREFS = "dsha_home_layout";
  private static final String ORDER = "order";
  private static final String HIDDEN = "hidden";
  private static final String NAV_ORDER = "nav_order";
  private static final String NAV_SCALE_PREFIX = "nav_scale_";
  public static final float NAV_SCALE_MIN = 0.7f;
  public static final float NAV_SCALE_MAX = 1.6f;
  private static final String LAUNCH_ACTION = "launch_action";
  private static final String SIZE_PREFIX = "size_";
  private static final String WIDTH_PREFIX = "width_";
  private static final String ZOOM_PREFIX = "zoom_";
  private static final String POS_X_PREFIX = "px_";
  private static final String POS_Y_PREFIX = "py_";
  public static final float ZOOM_MIN = 0.5f;
  public static final float ZOOM_MAX = 2.5f;
  public static final float WIDTH_MIN = 0.25f;
  public static final float WIDTH_MAX = 1f;

  /**
   * 启动按钮的唯一「容器内」目标：容器里的 DSH 工作台。
   *
   * <p>启动按钮不负责壳内导航 —— 终端、设置、模块中心都在底栏，写进启动入口只会让
   * 主页主按钮变成第二个导航按钮。所以这里只剩两类：容器工作台，或容器里登记的程序。
   */
  public static final String ACTION_WEB = "web";

  /** 旧版本写入的壳内导航目标；读到时迁移回 {@link #ACTION_WEB}，不再作为可选值。 */
  private static final List<String> LEGACY_SHELL_ACTIONS =
      Collections.unmodifiableList(Arrays.asList("terminal", "settings", "modules"));

  /** 容器程序动作的固定前缀：启动容器里登记的程序（tool:&lt;id&gt;）。 */
  public static final String ACTION_TOOL_PREFIX = "tool:";

  /** 启动按钮可选的全部去向；模块通过 launch.set 改它时用这个做校验。 */
  public static final java.util.List<String> ACTIONS_ALL =
      Collections.unmodifiableList(Arrays.asList(ACTION_WEB));

  private HomeLayoutStore() {}

  public static List<String> order(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    List<String> result = validOrder(prefs.getString(ORDER, ""));
    return result.isEmpty() ? DEFAULT_ORDER : result;
  }

  /**
   * 底栏单个按钮的缩放（0.7-1.6）。
   *
   * <p>默认 1.0，在主页编辑态双指捏合调整。按 nav 的 key
   * （launch/module/plugins/settings/terminal）存。
   */
  public static float navItemScale(Context context, String key) {
    float value =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(NAV_SCALE_PREFIX + key, 1f);
    return Math.max(NAV_SCALE_MIN, Math.min(NAV_SCALE_MAX, value));
  }

  public static void setNavItemScale(Context context, String key, float scale) {
    float value = Math.max(NAV_SCALE_MIN, Math.min(NAV_SCALE_MAX, scale));
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(NAV_SCALE_PREFIX + key, value)
        .apply();
  }

  public static void resetNavItemScale(Context context) {
    android.content.SharedPreferences preferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    android.content.SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet())
      if (key.startsWith(NAV_SCALE_PREFIX)) editor.remove(key);
    editor.apply();
  }

  public static List<String> navOrder(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    List<String> values = validNavOrder(prefs.getString(NAV_ORDER, ""));
    return values.size() == DEFAULT_NAV_ORDER.size() ? values : DEFAULT_NAV_ORDER;
  }

  public static void moveNav(Context context, String id, int delta) {
    List<String> values = new ArrayList<>(navOrder(context));
    int index = values.indexOf(id), target = index + delta;
    if (index < 0 || target < 0 || target >= values.size()) return;
    Collections.swap(values, index, target);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(NAV_ORDER, String.join(",", values))
        .apply();
  }

  /**
   * 启动按钮的目标：容器工作台，或容器里登记的程序（{@code tool:<id>}）。
   *
   * <p>旧版本存过 terminal/settings/modules 这类壳内导航目标；启动按钮不该兼任第二个
   * 导航栏，读到就迁移回容器工作台，下一次写入即覆盖旧值。
   */
  public static String launchAction(Context context) {
    String value =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAUNCH_ACTION, ACTION_WEB);
    if (ACTIONS_ALL.contains(value)) return value;
    if (LEGACY_SHELL_ACTIONS.contains(value)) {
      // 旧壳内目标不再可用：静默回落到容器工作台，并在下次 set 时覆盖。
      context
          .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .edit()
          .putString(LAUNCH_ACTION, ACTION_WEB)
          .apply();
      return ACTION_WEB;
    }
    // tool:<id> 要求 id 合法：登记被删或偏好被写坏时退回默认工作台，不留死入口。
    if (isToolAction(value)
        && value.substring(ACTION_TOOL_PREFIX.length()).matches("[a-z0-9][a-z0-9._-]{0,47}"))
      return value;
    return ACTION_WEB;
  }

  /** 是否是「启动容器里登记的程序」动作（tool:<id>）。 */
  public static boolean isToolAction(String action) {
    return action != null
        && action.startsWith(ACTION_TOOL_PREFIX)
        && action.length() > ACTION_TOOL_PREFIX.length();
  }

  /** 从 tool:<id> 动作里取出程序 id；不是程序动作返回空串。 */
  public static String toolIdOf(String action) {
    return isToolAction(action) ? action.substring(ACTION_TOOL_PREFIX.length()) : "";
  }

  public static void setLaunchAction(Context context, String action) {
    boolean toolAction =
        isToolAction(action)
            && action.substring(ACTION_TOOL_PREFIX.length()).matches("[a-z0-9][a-z0-9._-]{0,47}");
    if (!ACTIONS_ALL.contains(action) && !toolAction)
      throw new IllegalArgumentException("HOME_LAUNCH_ACTION");
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(LAUNCH_ACTION, action)
        .apply();
  }

  public static void resetNav(Context context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(NAV_ORDER).apply();
  }

  public static boolean isHidden(Context context, String id) {
    return context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(HIDDEN, Collections.emptySet())
        .contains(id);
  }

  public static void setHidden(Context context, String id, boolean hidden) {
    if (!DEFAULT_ORDER.contains(id) && !com.deepseekharness.app.module.ModuleCard.isUserModule(id))
      throw new IllegalArgumentException("HOME_LAYOUT_ID");
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    java.util.Set<String> values =
        new java.util.LinkedHashSet<>(prefs.getStringSet(HIDDEN, Collections.emptySet()));
    if (hidden) values.add(id);
    else values.remove(id);
    prefs.edit().putStringSet(HIDDEN, values).apply();
  }

  /**
   * 拖拽落定后整体写回顺序。
   *
   * <p>必须是 {@link #DEFAULT_ORDER} 的一个完整排列：少一项、多一项、重复都会拒绝，
   * 否则 {@link #order} 会退回默认顺序，用户拖了半天看着像没生效。
   */
  public static void setOrder(Context context, List<String> values) {
    if (!isCompleteOrder(values, DEFAULT_ORDER))
      throw new IllegalArgumentException("HOME_LAYOUT_ORDER");
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(ORDER, String.join(",", values))
        .apply();
  }

  public static void setNavOrder(Context context, List<String> values) {
    if (!isCompleteOrder(values, DEFAULT_NAV_ORDER))
      throw new IllegalArgumentException("HOME_LAYOUT_NAV_ORDER");
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(NAV_ORDER, String.join(",", values))
        .apply();
  }

  /**
   * 内置区块必须齐全；允许额外 id —— 用户从外部导入的模块会以新增 id 留在 order 里。
   */
  private static boolean isCompleteOrder(List<String> values, List<String> expected) {
    if (values == null || values.size() < expected.size()) return false;
    java.util.Set<String> unique = new java.util.LinkedHashSet<>(values);
    if (unique.size() != values.size()) return false;
    return unique.containsAll(expected);
  }

  /**
   * 模块宽度占一行的比例（0.25–1.0）。
   *
   * <p>这是「像调窗口一样」的连续宽度：不再只有半宽/整行两档。默认三格状态三等分
   * 同一行，其它整行；旧版本写入的 size_ 偏好仍可读。
   */
  public static float widthFraction(Context context, String id) {
    float value =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(WIDTH_PREFIX + id, -1f);
    if (value < 0f) {
      String legacy =
          context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SIZE_PREFIX + id, "");
      value =
          legacy.isEmpty()
              ? defaultFraction(id)
              : com.deepseekharness.app.module.HomeModule.SIZE_HALF.equals(legacy) ? 0.5f : 1f;
    }
    return Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, value));
  }

  public static void setWidthFraction(Context context, String id, float value) {
    requireId(id);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(WIDTH_PREFIX + id, Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, value)))
        .apply();
  }

  /** 模块尺寸兼容视图：半宽/整行两档。新代码用 {@link #widthFraction}。 */
  public static String size(Context context, String id) {
    return widthFraction(context, id) < 0.75f
        ? com.deepseekharness.app.module.HomeModule.SIZE_HALF
        : com.deepseekharness.app.module.HomeModule.SIZE_FULL;
  }

  /** 三格状态默认三等分同一行（保持原版排版）；其它区块默认整行。 */
  private static float defaultFraction(String id) {
    return id.startsWith("metric-") ? 1f / 3f : 1f;
  }

  public static void setSize(Context context, String id, String size) {
    if (!com.deepseekharness.app.module.HomeModule.SIZE_HALF.equals(size)
        && !com.deepseekharness.app.module.HomeModule.SIZE_FULL.equals(size))
      throw new IllegalArgumentException("HOME_LAYOUT_SIZE");
    setWidthFraction(
        context, id, com.deepseekharness.app.module.HomeModule.SIZE_HALF.equals(size) ? 0.5f : 1f);
  }

  public static void clearSize(Context context, String id) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(SIZE_PREFIX + id)
        .remove(WIDTH_PREFIX + id)
        .apply();
  }

  public static boolean isHalf(Context context, String id) {
    return widthFraction(context, id) < 0.75f;
  }

  /** 网格列跨度：兼容旧调用（半宽 1 列，整行 2 列）。 */
  public static int span(Context context, String id) {
    return isHalf(context, id) ? 1 : 2;
  }

  /** 便签纵向缩放；选中卡片拖上下边或双指捏合调整，内容随高度一起缩放。 */
  public static float zoom(Context context, String id) {
    float value =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(ZOOM_PREFIX + id, 1f);
    return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, value));
  }

  public static void setZoom(Context context, String id, float value) {
    requireId(id);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(ZOOM_PREFIX + id, Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, value)))
        .apply();
  }

  /** 是否已经把这张卡固定到自由位置；没有固定时由默认流式排版接管。 */
  public static boolean hasPosition(Context context, String id) {
    android.content.SharedPreferences prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    return prefs.contains(POS_X_PREFIX + id) && prefs.contains(POS_Y_PREFIX + id);
  }

  /** 自由位置的横向比例（0–1），未固定返回 -1。 */
  public static float xFraction(Context context, String id) {
    return context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat(POS_X_PREFIX + id, -1f);
  }

  /** 自由位置的纵向 dp，未固定返回 -1。 */
  public static float yDp(Context context, String id) {
    return context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat(POS_Y_PREFIX + id, -1f);
  }

  public static void setPosition(Context context, String id, float xFraction, float yDp) {
    requireId(id);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putFloat(POS_X_PREFIX + id, Math.max(0f, Math.min(1f, xFraction)))
        .putFloat(POS_Y_PREFIX + id, Math.max(0f, yDp))
        .apply();
  }

  public static void clearPosition(Context context, String id) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(POS_X_PREFIX + id)
        .remove(POS_Y_PREFIX + id)
        .apply();
  }

  /** 单张卡恢复默认大小：只清宽度/高度，不动它的自由位置。 */
  public static void resetCard(Context context, String id) {
    requireId(id);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(WIDTH_PREFIX + id)
        .remove(ZOOM_PREFIX + id)
        .remove(SIZE_PREFIX + id)
        .apply();
  }

  private static void requireId(String id) {
    if (!DEFAULT_ORDER.contains(id) && !com.deepseekharness.app.module.ModuleCard.isUserModule(id))
      throw new IllegalArgumentException("HOME_LAYOUT_ID");
  }

  public static void move(Context context, String id, int delta) {
    List<String> values = new ArrayList<>(order(context));
    int index = values.indexOf(id), target = index + delta;
    if (index < 0 || target < 0 || target >= values.size()) return;
    Collections.swap(values, index, target);
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(ORDER, String.join(",", values))
        .apply();
  }

  public static void reset(Context context) {
    android.content.SharedPreferences.Editor editor =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(ORDER)
            .remove(NAV_ORDER)
            .remove(HIDDEN);
    for (String key : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().keySet()) {
      if (key.startsWith(SIZE_PREFIX)
          || key.startsWith(WIDTH_PREFIX)
          || key.startsWith(ZOOM_PREFIX)
          || key.startsWith(POS_X_PREFIX)
          || key.startsWith(POS_Y_PREFIX)
          || key.startsWith(NAV_SCALE_PREFIX)) {
        editor.remove(key);
      }
    }
    editor.apply();
  }

  public static void apply(Context context, android.view.ViewGroup container) {
    java.util.Map<String, android.view.View> views = new java.util.LinkedHashMap<>();
    views.put("actions", container.findViewById(com.deepseekharness.app.R.id.launch_actions));
    views.put(
        "metric-environment",
        container.findViewById(com.deepseekharness.app.R.id.launch_metric_environment));
    views.put(
        "metric-workbench",
        container.findViewById(com.deepseekharness.app.R.id.launch_metric_workbench));
    views.put(
        "metric-port", container.findViewById(com.deepseekharness.app.R.id.launch_metric_port));
    views.put(
        "shortcuts-title",
        container.findViewById(com.deepseekharness.app.R.id.launch_shortcuts_title));
    views.put("shortcuts", container.findViewById(com.deepseekharness.app.R.id.launch_shortcuts));
    views.put("logs", container.findViewById(com.deepseekharness.app.R.id.launch_log_panel));
    java.util.List<android.view.View> ordered = new ArrayList<>();
    for (String id : order(context)) {
      android.view.View view = views.get(id);
      if (view != null && !isHidden(context, id)) ordered.add(view);
    }
    for (android.view.View view : views.values()) container.removeView(view);
    for (android.view.View view : ordered) container.addView(view);
  }

  private static List<String> validNavOrder(String raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptyList();
    List<String> values = Arrays.asList(raw.split(","));
    java.util.Set<String> unique = new java.util.LinkedHashSet<>(values);
    if (unique.size() != DEFAULT_NAV_ORDER.size() || !unique.containsAll(DEFAULT_NAV_ORDER))
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(unique));
  }

  private static List<String> validOrder(String raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptyList();
    List<String> values = Arrays.asList(raw.split(","));
    java.util.Set<String> unique = new java.util.LinkedHashSet<>(values);
    // 允许比默认多：用户模块是追加进来的 id。
    if (unique.size() != values.size() || !unique.containsAll(DEFAULT_ORDER))
      return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(unique));
  }
}
