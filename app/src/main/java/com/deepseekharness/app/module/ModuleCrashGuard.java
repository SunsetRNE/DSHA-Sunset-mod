package com.deepseekharness.app.module;

/**
 * 模块崩溃看门狗：模块把进程弄崩时，记住是哪个模块，下次启动直接停用它。
 *
 * <p>只做三件事：进入模块时记下 id；未捕获异常时把 id 写进偏好；下次启动把该模块
 * 的开关关掉并返回 id，让界面提示「已自动停用」。正文不抛出任何异常，避免看门狗
 * 自己阻断启动。
 */
public final class ModuleCrashGuard {

  private static final String PREFS = "dsha_module_guard";
  private static final String CRASHED = "crashed";
  private static final String BOOT_PENDING = "boot_pending";

  private static volatile String current;
  private static volatile boolean installed;

  private ModuleCrashGuard() {}

  public static void enter(String id) {
    if (id != null && !id.isEmpty()) current = id;
  }

  public static void leave(String id) {
    if (id != null && id.equals(current)) current = null;
  }

  /** 在 UI 进程尽早安装；重复调用无副作用。 */
  @android.annotation.SuppressLint("ApplySharedPref") // 崩溃现场必须同步落盘，apply() 可能在进程退出前来不及写完
  public static synchronized void install(android.content.Context context) {
    if (installed) return;
    installed = true;
    android.content.Context app = context.getApplicationContext();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          String id = current;
          if (id != null) {
            try {
              app.getSharedPreferences(PREFS, 0)
                  .edit()
                  .putString(CRASHED, id)
                  .putBoolean(BOOT_PENDING, true)
                  .commit();
            } catch (RuntimeException ignored) {
              // 记录失败不影响系统崩溃处理
            }
          }
          if (previous != null) previous.uncaughtException(thread, error);
          else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
          }
        });
  }

  /**
   * 启动时调用：上一次进程没走完、且记录了崩溃模块时，直接停用它。
   *
   * @return 被自动停用的模块 id；没有则空串
   */
  public static String recover(android.content.Context context) {
    android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
    String crashed = prefs.getString(CRASHED, "");
    boolean pending = prefs.getBoolean(BOOT_PENDING, false);
    prefs.edit().putBoolean(BOOT_PENDING, true).remove(CRASHED).apply();
    if (!pending || crashed.isEmpty()) return "";
    try {
      ModuleState.setEnabled(context, crashed, false);
      ModuleCardStore.bump();
      com.deepseekharness.app.DshaApp.from(context).invalidateModuleSnapshot();
    } catch (RuntimeException ignored) {
      // 停用失败也不能阻断启动
    }
    return crashed;
  }

  /** 崩溃模块的人话名字；卡片已被删时回退成 id，提示里始终能看出是谁。 */
  public static String labelOf(android.content.Context context, String id) {
    try {
      // 用 loadAll：这时模块刚被停用，只读启用清单会查不到它。
      for (ModuleCard card : ModuleCardStore.loadAll(context))
        if (id.equals(card.id) && !card.name.isEmpty()) return card.name + "（" + id + "）";
    } catch (RuntimeException ignored) {
      // 读卡片失败不影响提示本身
    }
    return id;
  }

  /** 界面已经正常显示后再清掉「启动中」标记，避免正常退出被当成崩溃。 */
  public static void bootCompleted(android.content.Context context) {
    context.getSharedPreferences(PREFS, 0).edit().putBoolean(BOOT_PENDING, false).apply();
  }
}
