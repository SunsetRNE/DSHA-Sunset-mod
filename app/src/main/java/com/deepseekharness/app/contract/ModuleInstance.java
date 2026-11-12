package com.deepseekharness.app.contract;

import java.util.List;

/**
 * 已安装模块实例；贡献句柄与回调都受宿主 generation 租约约束。
 *
 * <p>DEFERRED(A1)：ModuleHost 资源 scope 不在本轮；模块自行申请的资源仍需在 {@link #close} 释放。
 */
public interface ModuleInstance {
  enum CloseReason {
    USER_DISABLED,
    HOST_CLOSE,
    SAFE_MODE
  }

  List<Contribution> contributions();

  /** 幂等清理；宿主仍会强制撤销贡献，异常由宿主捕获并诊断。 */
  default void close(CloseReason reason) {}
}
