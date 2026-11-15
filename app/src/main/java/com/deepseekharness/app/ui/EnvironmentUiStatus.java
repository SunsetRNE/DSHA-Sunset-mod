package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.HarnessController;

/** 仅用于显示的后台快照。真正执行入口仍重新校验磁盘和维护门禁。 */
final class EnvironmentUiStatus {
  static final class Snapshot {
    final boolean known, ready, recovery, repairs;

    Snapshot(boolean known, boolean ready, boolean recovery, boolean repairs) {
      this.known = known;
      this.ready = ready;
      this.recovery = recovery;
      this.repairs = repairs;
    }
  }

  private static volatile Snapshot value = new Snapshot(false, false, false, false);
  private static final java.util.concurrent.ExecutorService IO =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private static long requested;
  private static boolean running;

  static synchronized Snapshot get(Context context) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (!running && (requested == 0 || now - requested >= 2500)) {
      running = true;
      requested = now;
      Context app = context.getApplicationContext();
      IO.execute(
          () -> {
            try {
              HarnessController controller = HarnessController.get(app);
              var facts =
                  com.deepseekharness.app.util.EnvironmentUiFacts.banner(
                      new EnvironmentUiReader(app, controller));
              value = new Snapshot(true, facts.runnable(), facts.recovery(), facts.repairs());
            } finally {
              synchronized (EnvironmentUiStatus.class) {
                running = false;
              }
            }
          });
    }
    return value;
  }
}
