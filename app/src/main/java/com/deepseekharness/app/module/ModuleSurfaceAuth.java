package com.deepseekharness.app.module;

import android.content.Context;

/**
 * 「接管宿主界面」类能力的独立授权位：{@code ui.app}（替换主页/设置/插件页内容区）
 * 与 {@code module.web}（模块自带网页）。
 *
 * <p>为什么单列：这两件事的风险不是「显示得好看不好看」，而是<b>替用户决定他看到什么</b>。
 * 只靠一张通用的「启用」开关不够 —— 用户为了试一个皮肤点了启用，不该顺带允许模块接管主页。
 * 与 {@link ModuleExec} 一致，授权绑当前内容摘要：内容一变，旧批准失效。
 *
 * <p>{@code ui.nav} / {@code ui.skin} / 主题属于外观层，仍跟随通用开关：它们不能执行代码，
 * 也不能改变页面语义，单独再要一次授权只会让用户困惑。
 */
public final class ModuleSurfaceAuth {

  /** 宿主页面接管：{@code contributes.ui.app}。 */
  public static final String APP = "ui.app";

  /** 模块自带网页：{@code contributes.module.web}。 */
  public static final String WEB = "module.web";

  private static final String PREFS = "dsha_module_surface_auth";

  private ModuleSurfaceAuth() {}

  public static boolean isAllowed(Context context, String cardId, String surface, String digest) {
    if (context == null || cardId == null || surface == null || digest == null || digest.isEmpty())
      return false;
    Object stored =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getAll()
            .get(key(cardId, surface));
    if (!(stored instanceof String)) {
      if (stored != null) revoke(context, cardId, surface);
      return false;
    }
    return digest.equals(stored);
  }

  public static void setAllowed(Context context, String cardId, String surface, String digest) {
    if (context == null || cardId == null || surface == null || digest == null || digest.isEmpty())
      return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(key(cardId, surface), digest)
        .apply();
  }

  public static void revoke(Context context, String cardId, String surface) {
    if (context == null || cardId == null || surface == null) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(key(cardId, surface))
        .apply();
  }

  /** 模块移除时清掉它名下全部界面授权。 */
  public static void revokeAll(Context context, String cardId) {
    if (context == null || cardId == null) return;
    android.content.SharedPreferences preferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    android.content.SharedPreferences.Editor editor = preferences.edit();
    // 键是 <cardId>|<surface>，| 是 id 不允许的字符：按前缀清不会命中 a.b 之类的兄弟模块。
    String prefix = cardId + "|";
    for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply();
  }

  private static String key(String cardId, String surface) {
    return (cardId == null ? "" : cardId) + "|" + (surface == null ? "" : surface);
  }
}
