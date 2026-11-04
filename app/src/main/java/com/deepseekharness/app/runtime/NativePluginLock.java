package com.deepseekharness.app.runtime;

/** 与 guest 的 flock 使用同一 Linux 锁；FD 始终由 Java 所有者关闭。 */
public final class NativePluginLock {
  static {
    System.loadLibrary("termux");
  }

  private NativePluginLock() {}

  /** 仅对已打开的常规文件尝试 LOCK_EX|LOCK_NB；0 成功，否则负 errno。 */
  public static native int tryExclusive(int descriptor);
}
