package com.deepseekharness.app.contract;

import java.util.Objects;

/** 一次安装/关闭对应的回调租约；代次变化后旧租约立即失效，不补发、不重放。 */
public final class GenerationLease {
  private final ModuleId owner;
  private final long generation;
  private final DiagnosticsSink diagnostics;
  private volatile boolean active = true;

  GenerationLease(ModuleId owner, long generation, DiagnosticsSink diagnostics) {
    if (owner == null || generation < 1) throw new IllegalArgumentException("LEASE_INVALID");
    this.owner = owner;
    this.generation = generation;
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public ModuleId owner() {
    return owner;
  }

  public long generation() {
    return generation;
  }

  public boolean active() {
    return active;
  }

  /**
   * 同步完成 active 检查与回调执行：并发 invalidate 必须等本次回调结束后才能生效。
   *
   * <p>回调的 RuntimeException 被捕获并写入诊断，不向宿主逃逸；Error 保持原语义向上抛。
   */
  public synchronized boolean runIfActive(Runnable callback) {
    if (!active || callback == null) return false;
    try {
      callback.run();
      return true;
    } catch (RuntimeException error) {
      diagnostics.report(
          ContractCodes.MODULE_CALLBACK_FAILED,
          "module callback failed: " + owner + " (" + error.getClass().getSimpleName() + ")");
      return false;
    }
  }

  synchronized boolean invalidate() {
    boolean wasActive = active;
    active = false;
    return wasActive;
  }
}
