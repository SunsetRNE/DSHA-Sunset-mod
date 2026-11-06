package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 卡片自己的小状态：输入框里填过什么、脚本往槽位里写过什么。
 *
 * <p>没有这个，卡片就是「一次性的」——切个页面回来输入框空了、脚本写的字也没了，
 * 用户会觉得这玩意没记忆。存在 SharedPreferences 里，进程重启也还在。
 *
 * <p>键名统一 {@code <cardId>.<key>}，移除卡片时按前缀清空。
 */
public final class ModuleState {

  private static final String PREFS = "dsha_module_state";

  private ModuleState() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static String slot(Context context, String cardId, String slot) {
    return prefs(context).getString(cardId + ".slot." + slot, null);
  }

  public static void setSlot(Context context, String cardId, String slot, String text) {
    prefs(context).edit().putString(cardId + ".slot." + slot, text == null ? "" : text).apply();
  }

  public static String field(Context context, String cardId, String key) {
    return prefs(context).getString(cardId + ".field." + key, null);
  }

  public static void setField(Context context, String cardId, String key, String value) {
    prefs(context).edit().putString(cardId + ".field." + key, value == null ? "" : value).apply();
  }

  /**
   * 模块开关：关掉不删，主页和悬浮/主题层都不再加载它。
   *
   * <p><b>默认关闭</b>：卡片是能从容器投递进来的内容，一落地就生效等于容器可以改宿主界面；
   * 用户显式开启过（存在 {@code <cardId>.enabled}）才加载。移除模块时同生共死一起清。
   */
  /** 开关作用域：卡片与清单可能同 id，共用一个键会互相覆盖。 */
  public static final String SCOPE_CARD = "card";

  public static final String SCOPE_MANIFEST = "manifest";

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
    return (scope == null ? SCOPE_CARD : scope) + ":" + (id == null ? "" : id) + ".enabled";
  }

  /**
   * 模块主页接管被用户主动退出：按「模块 + 摘要」记住，换版本后重新出现。
   *
   * <p>它跟随 cardId 前缀清理，移除模块时一起忘掉。
   */
  public static boolean appDismissed(Context context, String key) {
    return prefs(context).getBoolean("app.dismissed." + key, false);
  }

  public static void dismissApp(Context context, String key) {
    prefs(context).edit().putBoolean("app.dismissed." + key, true).apply();
  }

  /**
   * 只清某个作用域下的状态。
   *
   * <p>清单卸载必须走这里：{@link #clear} 会把 {@code card:<id>.enabled} 和页面里的
   * {@code <id>.slot.* / <id>.field.*} 一起删掉，而同 id 的卡片是可以并存的，用户数据不该被连带清空。
   */
  public static void clearScope(Context context, String scope, String id) {
    if (context == null || scope == null || id == null) return;
    SharedPreferences preferences = prefs(context);
    SharedPreferences.Editor editor = preferences.edit();
    String prefix = scope + ":" + id + ".";
    for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply();
  }

  /** 卡片被移除或重新导入时清掉它自己的状态（两种作用域一起清）。 */
  public static void clear(Context context, String cardId) {
    SharedPreferences preferences = prefs(context);
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) {
      if (key.startsWith(cardId + ".")
          || key.startsWith(SCOPE_CARD + ":" + cardId + ".")
          || key.startsWith(SCOPE_MANIFEST + ":" + cardId + ".")) editor.remove(key);
    }
    editor.apply();
  }
}
