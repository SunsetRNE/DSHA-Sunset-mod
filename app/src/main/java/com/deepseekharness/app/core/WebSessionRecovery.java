package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.runtime.WebProcessManager.SessionObservation;

/** 单线程 IO 上同步识别原会话；只复核身份与鉴权，不启动、停止或重放业务。 */
final class WebSessionRecovery {
  static final int MAX_AUTH_ATTEMPTS = 3;

  enum Result {
    NONE,
    RESTORED,
    UNKNOWN,
    STALE,
    EXITED
  }

  /** reason 只含稳定非敏感码；attempts 是实际调用 authenticate 的次数。 */
  record Outcome(Result result, String reason, int attempts) {}

  interface Ports {
    StoredWebSession.Record read() throws Exception;

    boolean current();

    boolean compatible(StoredWebSession.Record record) throws Exception;

    /** 仅为旧调用及测试兼容；生产实现必须覆盖 observe，false 不能作为退出证明。 */
    default boolean verify(StoredWebSession.Record record) throws Exception {
      return false;
    }

    default SessionObservation observe(StoredWebSession.Record record) throws Exception {
      return verify(record) ? SessionObservation.LIVE : SessionObservation.UNKNOWN;
    }

    default boolean launcherExited(StoredWebSession.Record record) throws Exception {
      return false;
    }

    /** 调用方须在主线程按原记录身份再次绑定核验；仅真实退休成功才返回 true。 */
    default boolean retire(StoredWebSession.Record record) throws Exception {
      return false;
    }

    boolean authenticate(StoredWebSession.Record record) throws Exception;

    boolean adopt(StoredWebSession.Record record) throws Exception;

    /** attempt 是已完成的鉴权次数（1 或 2）；恢复算法在等待前后检查 current。 */
    default void pauseBeforeRetry(int attempt) throws InterruptedException {
      Thread.sleep(retryDelayMillis(attempt));
    }

    default void progress(String reason, int attempt) {}

    default void unavailable(String stage, Exception failure) {}
  }

  static Result recover(Ports ports) {
    return recoverDetailed(ports).result();
  }

  static Outcome recoverDetailed(Ports ports) {
    String stage = "READ";
    String reason = "RECORD_UNAVAILABLE";
    int attempts = 0;
    try {
      // 保留只读现场装载；即使此前停止，也不让旧记录丢失停止证据。
      var saved = ports.read();
      if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
      if (saved == null) return outcome(ports, Result.NONE, "NO_RECORD", attempts);
      for (int attempt = 1; attempt <= MAX_AUTH_ATTEMPTS; attempt++) {
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (Thread.currentThread().isInterrupted())
          return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);
        stage = "KERNEL_VERIFY";
        reason = "IDENTITY_UNKNOWN";
        var observed = ports.observe(saved);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (observed != SessionObservation.LIVE) return notLive(ports, saved, observed, attempts);

        // 已退出记录无需通过新包兼容性检查；仍存活的记录才允许继续鉴权。
        stage = "COMPATIBILITY";
        reason = "INCOMPATIBLE";
        boolean compatible = ports.compatible(saved);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (!compatible) return outcome(ports, Result.UNKNOWN, reason, attempts);
        notifyProgress(ports, "AUTHENTICATING", attempt);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (Thread.currentThread().isInterrupted())
          return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);

        stage = "AUTHENTICATE";
        reason = "AUTH_UNAVAILABLE";
        attempts = attempt;
        boolean authenticated;
        try {
          authenticated = ports.authenticate(saved);
        } catch (InterruptedException interrupted) {
          throw interrupted;
        } catch (Exception unavailable) {
          reportUnavailable(ports, stage, unavailable);
          authenticated = false;
        }
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (Thread.currentThread().isInterrupted())
          return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);

        // 鉴权成功仍须二次身份核验；失败也只在身份仍 LIVE 时允许有限复核。
        stage = "KERNEL_REVERIFY";
        reason = "IDENTITY_UNKNOWN";
        observed = ports.observe(saved);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (observed != SessionObservation.LIVE) return notLive(ports, saved, observed, attempts);
        if (authenticated) {
          stage = "ADOPT";
          reason = "ADOPT_UNAVAILABLE";
          if (Thread.currentThread().isInterrupted())
            return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);
          // adopt 的成功提交本身会改变代次；不可用提交后的 current 否定成功。
          return ports.adopt(saved)
              ? outcome(ports, Result.RESTORED, "RESTORED", attempts)
              : outcome(ports, Result.STALE, "STALE", attempts);
        }
        if (attempts == MAX_AUTH_ATTEMPTS)
          return outcome(ports, Result.UNKNOWN, "AUTH_UNAVAILABLE", attempts);
        notifyProgress(ports, "AUTH_UNAVAILABLE", attempts);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
        if (Thread.currentThread().isInterrupted())
          return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);
        stage = "RETRY_WAIT";
        reason = "RETRY_UNAVAILABLE";
        ports.pauseBeforeRetry(attempts);
        if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
      }
      throw new IllegalStateException("UNREACHABLE_RECOVERY_ATTEMPT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      reportUnavailable(ports, stage, interrupted);
      return failure(ports, "INTERRUPTED", attempts);
    } catch (Exception unavailable) {
      reportUnavailable(ports, stage, unavailable);
      return failure(ports, reason, attempts);
    }
  }

  private static Outcome notLive(
      Ports ports, StoredWebSession.Record saved, SessionObservation observed, int attempts) {
    if (observed != SessionObservation.EXITED)
      return outcome(ports, Result.UNKNOWN, "IDENTITY_UNKNOWN", attempts);
    String stage = "LAUNCHER_EXIT";
    String reason = "LAUNCHER_PENDING";
    try {
      if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
      if (Thread.currentThread().isInterrupted())
        return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);
      boolean exited = ports.launcherExited(saved);
      if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
      if (!exited) return outcome(ports, Result.UNKNOWN, reason, attempts);
      stage = "RETIRE";
      reason = "RETIRE_UNAVAILABLE";
      if (Thread.currentThread().isInterrupted())
        return outcome(ports, Result.UNKNOWN, "INTERRUPTED", attempts);
      if (ports.retire(saved)) return outcome(ports, Result.EXITED, "EXIT_CONFIRMED", attempts);
      return failure(ports, reason, attempts);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      reportUnavailable(ports, stage, interrupted);
      return failure(ports, "INTERRUPTED", attempts);
    } catch (Exception unavailable) {
      reportUnavailable(ports, stage, unavailable);
      return failure(ports, reason, attempts);
    }
  }

  static long retryDelayMillis(int attempt) {
    if (attempt == 1) return 500;
    if (attempt == 2) return 1500;
    throw new IllegalArgumentException("INVALID_RETRY_ATTEMPT");
  }

  private static Outcome failure(Ports ports, String reason, int attempts) {
    try {
      if (!ports.current()) return outcome(ports, Result.STALE, "STALE", attempts);
    } catch (RuntimeException unavailable) {
      reason = "CURRENT_UNAVAILABLE";
    }
    return outcome(ports, Result.UNKNOWN, reason, attempts);
  }

  private static Outcome outcome(Ports ports, Result result, String reason, int attempts) {
    notifyProgress(ports, reason, attempts);
    return new Outcome(result, reason, attempts);
  }

  private static void notifyProgress(Ports ports, String reason, int attempt) {
    try {
      ports.progress(reason, attempt);
    } catch (RuntimeException ignored) {
      // 状态或诊断通知失败不能改变结果，也不能授予接纳或退休权限。
    }
  }

  private static void reportUnavailable(Ports ports, String stage, Exception failure) {
    try {
      ports.unavailable(stage, failure);
    } catch (RuntimeException ignored) {
      // 诊断只报告阶段；异常文案及凭据不得流入 Outcome.reason。
    }
  }
}
