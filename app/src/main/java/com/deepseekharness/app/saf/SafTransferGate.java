package com.deepseekharness.app.saf;

import java.util.concurrent.Semaphore;

/** 桥与原生工作台共用传输槽位；同一进程总计最多两个，不按入口各算一份。 */
public final class SafTransferGate {
  public static final Semaphore TRANSFERS =
      new Semaphore(SafCapabilityLimits.MAX_CONCURRENT_TRANSFERS);

  private SafTransferGate() {}
}
