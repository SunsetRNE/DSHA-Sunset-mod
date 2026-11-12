package com.deepseekharness.app.contract;

/** 一次安装/关闭对应的回调租约；代次变化后旧租约立即失效，不补发、不重放。 */
public final class GenerationLease {
  private final ModuleId owner;
  private final long generation;
  private volatile boolean active = true;

  GenerationLease(ModuleId owner, long generation) {
    if (owner == null || generation < 1) throw new IllegalArgumentException("LEASE_INVALID");
    this.owner = owner;
    this.generation = generation;
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

  public boolean runIfActive(Runnable callback) {
    if (!active || callback == null) return false;
    callback.run();
    return true;
  }

  boolean invalidate() {
    boolean wasActive = active;
    active = false;
    return wasActive;
  }
}
