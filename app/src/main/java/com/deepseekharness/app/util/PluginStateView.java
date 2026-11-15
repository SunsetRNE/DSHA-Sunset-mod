package com.deepseekharness.app.util;

/** 插件状态的兼容投影；只解释调用方提供的证据，不读取目录或决定重试。 */
public final class PluginStateView {
  private PluginStateView() {}

  public enum RuntimePhase {
    PENDING,
    LOADING,
    UNLOADING,
    ACTIVE,
    FAILED,
    ABSENT,
    UNKNOWN
  }

  public enum Evidence {
    LOADER,
    HEARTBEAT,
    LEDGER,
    NONE
  }

  public enum OperationState {
    NONE,
    QUEUED,
    RUNNING,
    COMMITTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    INVALIDATED,
    UNCONFIRMED,
    REJECTED,
    TIMEOUT
  }

  /** null 仅表示生产者明确观察到的无活根；未观察/过期必须使用 unknown 或 stale。 */
  public static RuntimePhase runtimePhase(String raw, boolean stale) {
    if (stale) return RuntimePhase.UNKNOWN;
    if (raw == null) return RuntimePhase.ABSENT;
    return switch (raw) {
      case "pending", "waiting" -> RuntimePhase.PENDING;
      case "loading" -> RuntimePhase.LOADING;
      case "unloading" -> RuntimePhase.UNLOADING;
      case "active", "loaded", "running" -> RuntimePhase.ACTIVE;
      case "failed" -> RuntimePhase.FAILED;
      case "stopped", "unloaded", "disabled", "inactive" -> RuntimePhase.ABSENT;
      default -> RuntimePhase.UNKNOWN;
    };
  }

  /** 历史激活账本和无证据不能为当前 Web 领取加载或失败结论。 */
  public static RuntimePhase runtimePhase(String raw, boolean stale, Evidence evidence) {
    if (evidence != Evidence.LOADER && evidence != Evidence.HEARTBEAT) return RuntimePhase.UNKNOWN;
    return runtimePhase(raw, stale);
  }

  /** 未知非空操作阶段保守显示待核验，不按文案猜测结果。 */
  public static OperationState operationState(String phase) {
    if (phase == null || phase.isEmpty()) return OperationState.NONE;
    return switch (phase) {
      case "QUEUED" -> OperationState.QUEUED;
      case "RUNNING" -> OperationState.RUNNING;
      case "COMMITTING" -> OperationState.COMMITTING;
      case "SUCCEEDED" -> OperationState.SUCCEEDED;
      case "FAILED" -> OperationState.FAILED;
      case "CANCELLED" -> OperationState.CANCELLED;
      case "SUPERSEDED" -> OperationState.SUPERSEDED;
      case "INVALIDATED" -> OperationState.INVALIDATED;
      case "REJECTED" -> OperationState.REJECTED;
      case "TIMEOUT", "TIMED_OUT" -> OperationState.TIMEOUT;
      default -> OperationState.UNCONFIRMED;
    };
  }

  public static boolean prominent(OperationState state) {
    return state != null && state != OperationState.NONE && state != OperationState.SUCCEEDED;
  }

  public static boolean failed(OperationState state) {
    return state == OperationState.FAILED || state == OperationState.REJECTED;
  }
}
