package com.deepseekharness.app.backup;

import android.app.job.*;
import android.content.*;
import android.util.Log;
import com.deepseekharness.app.BuildConfig;

/** 覆盖更新后的单次清理；运行中延后，绝不为清理终止用户任务。 */
public final class PostUpgradeCleanupService extends JobService {
  private static final int JOB = 9139, POLICY = 3;
  private static final WorkerQuiescence WORKERS = new WorkerQuiescence();
  private static final java.util.concurrent.atomic.AtomicBoolean FACTORY_RESET =
      new java.util.concurrent.atomic.AtomicBoolean();

  public static void schedule(Context context) {
    try {
      if (AutomaticBackups.factoryResetPending(context)) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB);
        return;
      }
      var prefs = context.getSharedPreferences("dsha_storage_cleanup", MODE_PRIVATE);
      if (prefs.getInt("completedBuild", 0) >= BuildConfig.VERSION_CODE
          && prefs.getInt("policy", 0) >= POLICY) return;
      JobScheduler scheduler = context.getSystemService(JobScheduler.class);
      if (scheduler == null || scheduler.getPendingJob(JOB) != null) return;
      int result =
          scheduler.schedule(
              new JobInfo.Builder(JOB, new ComponentName(context, PostUpgradeCleanupService.class))
                  .setMinimumLatency(30_000)
                  .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                  .setPersisted(true)
                  .build());
      if (result != JobScheduler.RESULT_SUCCESS) scheduleFailure("RESULT_FAILURE");
    } catch (RuntimeException unavailable) {
      scheduleFailure(unavailable.getClass().getSimpleName());
    }
  }

  private static void scheduleFailure(String reason) {
    Log.w("DshaBackup", "POST_UPGRADE_CLEANUP_SCHEDULER job=" + JOB + " reason=" + reason);
  }

  static void suspendForFactoryReset(BackupControl control) throws java.io.IOException {
    FACTORY_RESET.set(true);
    WORKERS.closeGate();
    try {
      WORKERS.awaitIdle(control, 15_000);
    } catch (java.io.IOException failure) {
      FACTORY_RESET.set(false);
      WORKERS.reopen();
      throw failure;
    }
  }

  static void resumeAfterFactoryReset() {
    FACTORY_RESET.set(false);
    WORKERS.reopen();
  }

  private void save(StorageMaintenance.CleanupResult result, String error) {
    synchronized (PostUpgradeCleanupService.class) {
      if (FACTORY_RESET.get() || AutomaticBackups.factoryResetPending(this)) return;
      var edit = getSharedPreferences("dsha_storage_cleanup", MODE_PRIVATE).edit();
      if (result != null) {
        edit.putLong("removedLogicalBytes", result.removedBytes())
            .putLong("availableBeforeBytes", result.availableBefore)
            .putLong("availableAfterBytes", result.availableAfter);
      }
      if (result != null && error.isEmpty())
        edit.putInt("completedBuild", BuildConfig.VERSION_CODE).putInt("policy", POLICY);
      edit.putString("error", error).commit();
    }
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    if (FACTORY_RESET.get() || AutomaticBackups.factoryResetPending(this)) return false;
    WorkerQuiescence.Lease lease = WORKERS.tryBegin();
    if (lease == null) return false;
    Thread worker =
        new Thread(
            () -> {
              try (WorkerQuiescence.Lease ignored = lease) {
                boolean retry = false;
                try {
                  var result = StorageMaintenance.cleanWhenIdle(this);
                  if (result != null) save(result, result.error);
                  else retry = !FACTORY_RESET.get() && !AutomaticBackups.factoryResetPending(this);
                } catch (Exception error) {
                  save(null, NativeBackupJobs.code(error));
                } finally {
                  jobFinished(params, retry);
                }
              }
            },
            "post-upgrade-cleanup");
    try {
      worker.start();
      return true;
    } catch (RuntimeException | Error unavailable) {
      lease.close();
      save(null, "WORKER_UNAVAILABLE");
      return false;
    }
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return !FACTORY_RESET.get() && !AutomaticBackups.factoryResetPending(this);
  }
}
