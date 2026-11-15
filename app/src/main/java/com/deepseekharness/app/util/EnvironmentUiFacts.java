package com.deepseekharness.app.util;

/** 展示层的只读事实采样；不持有任务、租约、代次或执行许可，不统一各入口的业务门禁。 */
public final class EnvironmentUiFacts {
  private EnvironmentUiFacts() {}

  /** 平台只提供原有读口；每种采样保留原入口的读取次序和短路，不预先读取全部状态。 */
  public interface Reader {
    boolean runnable();

    boolean installed();

    boolean latest();

    boolean hostPending();

    boolean maintenanceBusy();

    boolean requiresMaintenance();

    boolean blocksEntry();

    boolean repairsPending();
  }

  public record Deployment(boolean runnable, boolean hostPending, boolean installed) {}

  public record Banner(boolean runnable, boolean recovery, boolean repairs) {}

  /** 部署胶囊原有降级：失败使 runnable=false，保留失败前已经读到的其他事实。 */
  public static Deployment deployment(Reader reader) {
    boolean runnable = false, pending = false, installed = false;
    try {
      runnable = reader.runnable();
      pending = reader.hostPending();
      installed = reader.installed();
    } catch (RuntimeException unavailable) {
      runnable = false;
    }
    return new Deployment(runnable, pending, installed);
  }

  /** 横幅继续复用纯投影；需要运行时维护时不再额外读取主入口的 live-owner 显示策略。 */
  public static Banner banner(Reader reader) {
    boolean runnable = reader.runnable();
    boolean recovery =
        RuntimeUpgradeState.maintenanceRequired(runnable, reader.latest()) || reader.blocksEntry();
    return new Banner(runnable, recovery, reader.repairsPending());
  }

  /** 引导的直入显示判定；短路次序不变，执行入口仍自行重新校验。 */
  public static boolean onboardingReady(Reader reader) {
    return !reader.maintenanceBusy()
        && !reader.hostPending()
        && reader.runnable()
        && !reader.requiresMaintenance();
  }
}
