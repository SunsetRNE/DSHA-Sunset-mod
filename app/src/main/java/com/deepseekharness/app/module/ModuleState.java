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
   * <p>默认开启；存在 {@code <cardId>.enabled}，与其它状态同生共死（移除模块一起清）。
   */
  public static boolean isEnabled(Context context, String cardId) {
    return prefs(context).getBoolean(cardId + ".enabled", true);
  }

  /** 未显式设置过返回 null；用于从旧偏好迁移时区分「没设置」和「设置为开」。 */
  public static Boolean enabledOrNull(Context context, String cardId) {
    SharedPreferences preferences = prefs(context);
    String key = cardId + ".enabled";
    return preferences.contains(key) ? preferences.getBoolean(key, true) : null;
  }

  public static void setEnabled(Context context, String cardId, boolean enabled) {
    prefs(context).edit().putBoolean(cardId + ".enabled", enabled).apply();
    ModuleCardStore.bump();
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

  /** 卡片被移除或重新导入时清掉它自己的状态。 */
  public static void clear(Context context, String cardId) {
    SharedPreferences preferences = prefs(context);
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) {
      if (key.startsWith(cardId + ".")) editor.remove(key);
    }
    editor.apply();
  }
}
