package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 卡片自己的小状态：输入框里填过什么、脚本往槽位里写过什么、模块开关与「退出主页接管」。
 *
 * <p>没有这个，卡片就是「一次性的」——切个页面回来输入框空了、脚本写的字也没了，
 * 用户会觉得这玩意没记忆。存在 SharedPreferences 里，进程重启也还在。
 *
 * <p>键格式统一 {@code kind|id|名字}，分隔符是 {@code |}。旧开发安装用的
 * {@code <id>.slot} / {@code card:<id>.enabled} 等旧键**作废不迁移**（功能尚未发布，
 * 重新设置一次即可）。模块 id 的规则
 * （{@code [a-z0-9][a-z0-9._-]{0,47}}）不允许它出现，所以按 id 精确清理不会误伤
 * {@code a.b} 这类只是共享前缀的兄弟模块 —— 旧版用 {@code .} 连接，{@code clear("a")}
 * 会把 {@code a.b} 的状态一起清掉。
 */
public final class ModuleState {

  private static final String PREFS = "dsha_module_state";

  /** 开关作用域：卡片与清单可能同 id，共用一个键会互相覆盖。 */
  public static final String SCOPE_CARD = "card";

  public static final String SCOPE_MANIFEST = "manifest";

  private ModuleState() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static String slot(Context context, String cardId, String slot) {
    return prefs(context).getString("slot|" + cardId + "|" + slot, null);
  }

  public static void setSlot(Context context, String cardId, String slot, String text) {
    prefs(context)
        .edit()
        .putString("slot|" + cardId + "|" + slot, text == null ? "" : text)
        .apply();
  }

  public static String field(Context context, String cardId, String key) {
    return prefs(context).getString("field|" + cardId + "|" + key, null);
  }

  public static void setField(Context context, String cardId, String key, String value) {
    prefs(context)
        .edit()
        .putString("field|" + cardId + "|" + key, value == null ? "" : value)
        .apply();
  }

  /**
   * 模块开关：关掉不删，主页和悬浮/主题层都不再加载它。
   *
   * <p><b>默认关闭</b>：卡片是能从容器投递进来的内容，一落地就生效等于容器可以改宿主界面；
   * 用户显式开启过（存在对应键）才加载。移除模块时同生共死一起清。
   */
  public static boolean isEnabled(Context context, String scope, String id) {
    return prefs(context).getBoolean(enabledKey(scope, id), false);
  }

  /** 未显式设置过返回 null；用于区分「没设置」和「显式开启/关闭」。 */
  public static Boolean enabledOrNull(Context context, String scope, String id) {
    SharedPreferences preferences = prefs(context);
    String key = enabledKey(scope, id);
    return preferences.contains(key) ? preferences.getBoolean(key, false) : null;
  }

  public static void setEnabled(Context context, String scope, String id, boolean enabled) {
    prefs(context).edit().putBoolean(enabledKey(scope, id), enabled).apply();
    ModuleCardStore.bump();
  }

  private static String enabledKey(String scope, String id) {
    return "switch|"
        + (scope == null ? SCOPE_CARD : scope)
        + "|"
        + (id == null ? "" : id)
        + "|enabled";
  }

  /**
   * 模块主页接管被用户主动退出：按<b>模块 id</b> 记住。
   *
   * <p>不按内容摘要：用户「关掉这个接管」是对模块本身的决定，模块更新不应该让同一个弹窗
   * 再来一次。移除模块时随 {@link #clear} 一起忘掉。
   */
  public static boolean appDismissed(Context context, String cardId) {
    return prefs(context).getBoolean("dismissed|" + cardId, false);
  }

  public static void dismissApp(Context context, String cardId) {
    prefs(context).edit().putBoolean("dismissed|" + cardId, true).apply();
  }

  /**
   * 只清某个作用域下的状态。
   *
   * <p>清单卸载必须走这里：{@link #clear} 会把卡片作用域的开关与页面里的槽位/输入一起删掉，
   * 而同 id 的卡片是可以并存的，用户数据不该被连带清空。
   */
  public static void clearScope(Context context, String scope, String id) {
    if (context == null || scope == null || id == null) return;
    SharedPreferences preferences = prefs(context);
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) {
      String[] parts = key.split("\\|", -1);
      if (parts.length == 4
          && "switch".equals(parts[0])
          && scope.equals(parts[1])
          && id.equals(parts[2])) editor.remove(key);
    }
    editor.apply();
  }

  /** 卡片被移除或重新导入时清掉它自己的状态（两种作用域一起清）。 */
  public static void clear(Context context, String cardId) {
    if (context == null || cardId == null) return;
    SharedPreferences preferences = prefs(context);
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) {
      if (ownedBy(key, cardId)) editor.remove(key);
    }
    editor.apply();
  }

  /**
   * 这个键是不是属于给定 id：第二段等于 id（slot/field/dismissed），或 switch 键的第三段
   * 等于 id。id 不含 {@code |}，所以按段比对是精确匹配，不会把 {@code a.b} 误当成 {@code a}。
   */
  private static boolean ownedBy(String key, String id) {
    int first = key.indexOf('|');
    if (first < 0) return false;
    String kind = key.substring(0, first);
    String[] parts = key.split("\\|", -1);
    if ("switch".equals(kind)) {
      return parts.length == 4 && id.equals(parts[2]);
    }
    // 非 switch 键的 id 一律在第二段：slot|<id>|<名>、field|<id>|<名>、dismissed|<id>
    // （dismissed 只有 2 段，旧实现要求第二个 | 存在，导致 clear 从不清掉 dismissed）。
    return parts.length >= 2 && id.equals(parts[1]);
  }
}
