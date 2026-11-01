package com.deepseekharness.app.util;

/**
 * 桌面图标启动后进入哪个页面。只有两个值：主界面（默认）与 Web 界面。
 *
 * <p>选择只决定“下一次从桌面图标启动”的去向，不启动、不停止、也不改变任何运行状态；
 * 环境门禁、维护页、启动恢复与原实例核验始终优先，任何一项未放行都回到主界面。
 */
public final class EntryTarget {
  public static final String MAIN = "main";
  public static final String WEB = "web";

  private EntryTarget() {}

  /** 未知、空或旧值一律回到主界面，绝不把无法识别的输入解释成直启。 */
  public static String normalize(String value) {
    return WEB.equals(value) ? WEB : MAIN;
  }

  /** 桥写入只接受精确的两个值；其它输入（含大小写变体、空白）拒绝而不是降级。 */
  public static boolean valid(String value) {
    return MAIN.equals(value) || WEB.equals(value);
  }

  /** Main 首次创建时的入口事实；全部来自宿主状态，不读取网页或插件的声明。 */
  public record Launch(
      String preference,
      boolean launcherIntent,
      boolean restoredState,
      boolean pendingInstallLink,
      boolean firstRunStart,
      boolean startupRecoveryRequested) {}

  /**
   * 是否在入口门禁放行后直接进入 Web。
   *
   * <ul>
   *   <li>只认桌面图标（ACTION_MAIN + CATEGORY_LAUNCHER）的新建实例；通知、任务卡、旋转恢复都不直启；
   *   <li>待安装插件链接、首次部署的启动请求与已登记的启动恢复都需要主界面处理，不能被直启吞掉。
   * </ul>
   */
  public static boolean directWeb(Launch launch) {
    return launch != null
        && WEB.equals(normalize(launch.preference()))
        && launch.launcherIntent()
        && !launch.restoredState()
        && !launch.pendingInstallLink()
        && !launch.firstRunStart()
        && !launch.startupRecoveryRequested();
  }

  /** 门禁回调里的二次判断：维护、未确认原实例或恢复中的会话一律交还主界面。 */
  public static boolean admitted(
      boolean armed,
      boolean gateFailed,
      boolean maintenance,
      boolean unresolved,
      boolean restoring) {
    return armed && !gateFailed && !maintenance && !unresolved && !restoring;
  }

  /** 桥接口的稳定回执：当前值与可选值，供插件按字段解析。 */
  public static String bridgeState(String current) {
    return "{\"target\":\""
        + normalize(current)
        + "\",\"options\":[\""
        + MAIN
        + "\",\""
        + WEB
        + "\"]}";
  }
}
