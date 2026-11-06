package com.deepseekharness.app.module;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 受管程序的独立授权位：与每卡命令授权分开，默认关闭、逐程序授予、可单独撤销。
 *
 * <p>命令授权允许模块跑容器命令，但不应顺带允许执行导入的原生程序。程序授权记录
 * {@code <cardId>.<programId>} → <b>程序文件的内容摘要</b>，模块移除或程序更新时清理，
 * 避免旧授权继续放行新文件（只按 id 记授权时，替换二进制仍会沿用旧批准）。
 */
public final class ModuleProgramAuth {

  private static final String PREFS = "dsha_module_program_auth";

  private ModuleProgramAuth() {}

  public static boolean isAllowed(Context context, String cardId, String programId, String digest) {
    if (digest == null || digest.isEmpty()) return false;
    Object stored =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getAll()
            .get(key(cardId, programId));
    if (!(stored instanceof String)) {
      if (stored != null) revoke(context, cardId, programId);
      return false;
    }
    return digest.equals(stored);
  }

  public static void setAllowed(Context context, String cardId, String programId, String digest) {
    if (digest == null || digest.isEmpty()) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(key(cardId, programId), digest)
        .apply();
  }

  public static void revoke(Context context, String cardId, String programId) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(key(cardId, programId))
        .apply();
  }

  /** 移除模块时清掉它名下全部程序授权。 */
  public static void revokeAll(Context context, String cardId) {
    SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String prefix = cardId + ".";
    SharedPreferences.Editor editor = preferences.edit();
    for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply();
  }

  private static String key(String cardId, String programId) {
    return (cardId == null ? "" : cardId) + "." + (programId == null ? "" : programId);
  }
}
