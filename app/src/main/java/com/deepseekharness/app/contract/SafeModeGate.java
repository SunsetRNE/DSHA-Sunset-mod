package com.deepseekharness.app.contract;

/** 安全模式状态；恢复入口是宿主兜底，任何模块停用/注册失败/安全模式都不能移除。 */
public final class SafeModeGate {
  private volatile boolean forced;

  public SafeModeGate() {}

  public SafeModeGate(boolean forced) {
    this.forced = forced;
  }

  public boolean forcedSafeMode() {
    return forced;
  }

  public void enterSafeMode() {
    forced = true;
  }

  public void exitSafeMode() {
    forced = false;
  }

  /** 恒为 true：法定许可与恢复入口由宿主固定持有，不依赖模块贡献。 */
  public boolean recoveryEntryAvailable() {
    return true;
  }
}
