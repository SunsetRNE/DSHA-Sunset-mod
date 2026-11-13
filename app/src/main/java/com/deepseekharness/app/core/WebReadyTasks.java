package com.deepseekharness.app.core;

import com.deepseekharness.app.util.Rc1MigrationResult;
import java.util.Map;

/** Bounded post-authentication work. The caller remains the generation and state authority. */
final class WebReadyTasks {
  interface Ports {
    boolean current();

    void asynchronous(String name, Runnable work);

    void delay(long millis) throws InterruptedException;

    String finalizeMigration() throws Exception;

    void migrationLog(String value);

    void migrationNeedsAttention();

    boolean exchangeCookie();

    boolean lanBound();

    void lanReady();
  }

  static void run(boolean safeMode, boolean lanMode, Ports ports) {
    if (!ports.current()) return;
    if (!safeMode) ports.asynchronous("dsha-rc1-verification", () -> finalizeMigration(ports));
    if (!lanMode) return;
    for (int attempt = 0; attempt < 3 && ports.current(); attempt++) {
      try {
        if (ports.exchangeCookie()) break;
      } catch (Throwable ignored) {
      }
      try {
        ports.delay(1200);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    for (int attempt = 0; attempt < 12 && ports.current() && !ports.lanBound(); attempt++) {
      try {
        ports.delay(200);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (ports.current() && ports.lanBound()) ports.lanReady();
  }

  private static void finalizeMigration(Ports ports) {
    try {
      ports.delay(3000);
      if (!ports.current()) return;
      String result = ports.finalizeMigration();
      if (!ports.current()) return;
      Map<String, Object> receipt = Rc1MigrationResult.parse(result);
      // 历史完成只是无迁移写入的普通启动；pending/unknown 不得静默，也不能被当成
      // 本次核验或自动重试许可。
      if (!Rc1MigrationResult.isRoutineReceipt(receipt)) {
        ports.migrationLog(result);
        if (ports.current()) ports.migrationNeedsAttention();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      // 解析失败、重复回执或进程结果不完整也是未知终态：记录并让用户可见，
      // 不当作“没有迁移”或“下次启动会再试”。
      if (ports.current()) {
        ports.migrationLog("RC1_MIGRATION_VERIFICATION_UNAVAILABLE");
        ports.migrationNeedsAttention();
      }
    }
  }
}
