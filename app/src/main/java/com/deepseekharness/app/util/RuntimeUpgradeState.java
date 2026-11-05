package com.deepseekharness.app.util;

/**
 * 覆盖升级的纯逻辑判定：「环境可继续运行」与「已装运行时就是当前候选」是两件事。
 *
 * <p>{@code isEnvironmentReady()} 按兼容性与健康回执判断环境能否使用（健康兼容前代仍可进入受限主界面），
 * 但同一 dsh 版本、只有受管补丁与描述符输入变化的覆盖安装（build323 → build324）在兼容性上恒为 true。
 * 如果维护页把「环境就绪」直接当成「无事可做」，就会把“进入主界面”摆到主操作位、把更新降为次要按钮，
 * 而受限入口又因为没有记录受限身份，让主界面门禁立刻把用户送回维护页。
 *
 * <p>因此这里把“已装运行时是否就是当前候选”（{@code RuntimeDescriptor.latest}）作为与就绪并列的一维：
 * 只有两者同时成立才算真正无事可做。
 */
public final class RuntimeUpgradeState {
  private RuntimeUpgradeState() {}

  /** 无事可做：环境可继续运行，且已装运行时就是当前候选（补丁级更新也算未完成）。 */
  public static boolean upToDate(boolean environmentReady, boolean installedLatest) {
    return environmentReady && installedLatest;
  }

  /** 仍需整屏维护：环境不可继续运行，或已装运行时不是当前候选（补丁级更新也算）。 */
  public static boolean maintenanceRequired(boolean environmentReady, boolean installedLatest) {
    return !upToDate(environmentReady, installedLatest);
  }

  /**
   * 入口门禁是否打开整屏维护页。
   *
   * <p>未确认的原实例（unresolved）不再阻止打开维护页：它只拦自动开始
   * （{@link EnvironmentIdentity#shouldAutoStart} 的 unresolved 条件）。否则覆盖安装后旧 Web 仍存活时，
   * 环境需要维护的用户会被留在主界面，看不到唯一的修复入口。
   */
  public static boolean openMaintenancePage(
      boolean gateFailed, boolean maintenance, boolean restoring) {
    return !gateFailed && maintenance && !restoring;
  }

  /** 进入主界面时必须按受限入口记录：环境未就绪、仍有未完成的维护，或维护任务占着锁。 */
  public static boolean limitedEntry(
      boolean environmentReady, boolean installedLatest, boolean busy, boolean pending) {
    return busy || pending || !upToDate(environmentReady, installedLatest);
  }
}
