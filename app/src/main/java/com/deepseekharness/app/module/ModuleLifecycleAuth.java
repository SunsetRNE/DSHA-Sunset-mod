package com.deepseekharness.app.module;

import android.content.Context;

/**
 * 生命周期钩子（{@code lifecycle.onShow} / {@code lifecycle.tick}）的独立授权位。
 *
 * <p>它和命令授权分开：允许模块「显示时跑一小段脚本」不等于允许它跑任意容器命令，
 * 但两者都是「会自动执行代码」，所以同样默认关闭、逐卡授予、并在内容变化后失效
 * （与 {@link ModuleExec} 一致地绑定内容 SHA-256，而不是绑定 id）。
 *
 * <p>本版没有 UI 接线，这里先把门立起来：将来主页/悬浮层要触发 {@code onShow} 时，
 * 必须先用 {@link #isAllowed} 判断，未授权就不执行、只在卡片上提示。
 */
public final class ModuleLifecycleAuth {

  private static final String PREFS = "dsha_module_lifecycle_auth";

  private ModuleLifecycleAuth() {}

  public static boolean isAllowed(Context context, String cardId, String digest) {
    if (context == null || cardId == null || digest == null || digest.isEmpty()) return false;
    Object stored =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().get("allow_" + cardId);
    if (!(stored instanceof String)) {
      if (stored != null) revoke(context, cardId);
      return false;
    }
    return digest.equals(stored);
  }

  public static void setAllowed(Context context, String cardId, String digest) {
    if (context == null || cardId == null || digest == null || digest.isEmpty()) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("allow_" + cardId, digest)
        .apply();
  }

  public static void revoke(Context context, String cardId) {
    if (context == null || cardId == null) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove("allow_" + cardId)
        .apply();
  }
}
