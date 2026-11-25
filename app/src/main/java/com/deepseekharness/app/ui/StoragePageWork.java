package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.NativeBackupJobs;
import com.deepseekharness.app.backup.RollbackStorageInventory;
import com.deepseekharness.app.backup.StorageMaintenance;
import com.deepseekharness.app.core.DiagnosticLog;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Process-local work with one published frame per job. Workers hold only the application context. */
final class StoragePageWork {
  static final StoragePageWork PROCESS =
      new StoragePageWork(
          Executors.newSingleThreadExecutor(work -> new Thread(work, "storage-page")),
          (context, progress) -> StorageMaintenance.inspect(context, progress, null),
          (context, progress) -> StorageMaintenance.clean(context, progress, null),
          (context, target, progress) ->
              StorageMaintenance.expandRollback(
                  context, target.operationId(), target.archiveSha256(), progress, null));

  enum Kind {
    INITIAL_SCAN,
    REFRESH,
    CLEANUP,
    EXPAND
  }

  interface StorageTask<T> {
    T run(Context context, Consumer<StorageMaintenance.Progress> progress) throws Exception;
  }

  interface ExpansionTask {
    StorageMaintenance.ExpansionResult run(
        Context context,
        RollbackStorageInventory.ExpansionTarget target,
        Consumer<StorageMaintenance.Progress> progress)
        throws Exception;
  }

  record Expansion(RollbackStorageInventory.ExpansionTarget target, String path) {}

  record Result(
      StorageMaintenance.CleanupResult cleanup,
      StorageMaintenance.ExpansionResult expansion,
      String error,
      boolean refreshed,
      int errorHint) {}

  enum FailureStage {
    ADMISSION(R.string.storage_failure_maintenance_admission),
    TERMINAL_STOP(R.string.storage_failure_terminal_exit),
    WEB_STOP(R.string.storage_failure_web_exit),
    BACKGROUND_EXIT(R.string.storage_failure_background_exit),
    FILE_WORK(0),
    OTHER(0);

    final int hint;

    FailureStage(int hint) {
      this.hint = hint;
    }
  }

  record WorkFailure(
      String originalCode, String code, FailureStage stage, StackTraceElement site, String type) {
    int hint() {
      return "IO_FAILURE".equals(code) ? stage.hint : 0;
    }

    String diagnostic(Kind kind, StorageMaintenance.Progress progress, long atMs, long elapsedMs) {
      String detail =
          "operation="
              + kind.name()
              + " stage="
              + stage.name()
              + " atMs="
              + atMs
              + " elapsedMs="
              + elapsedMs
              + " originalCode="
              + logCode(originalCode)
              + " shownCode="
              + logCode(code)
              + " exception="
              + type;
      if (site != null) {
        detail +=
            " site="
                + site.getClassName()
                + "."
                + site.getMethodName()
                + ":"
                + site.getLineNumber();
      }
      if (progress == null || progress.phase() == null) return detail + " progress=NONE";
      return detail
          + " progress="
          + progress.phase().name()
          + " visited="
          + progress.visitedEntries()
          + " knownBytes="
          + progress.knownBytes()
          + " verified="
          + progress.verifiedEntries()
          + " verifiedBytes="
          + progress.verifiedBytes()
          + " removed="
          + progress.removedEntries()
          + " removedBytes="
          + progress.removedBytes()
          + " targets="
          + progress.completedTargets()
          + "/"
          + progress.totalTargets()
          + " archiveBytes="
          + progress.compressionBytes()
          + "/"
          + progress.compressionTotalBytes();
    }
  }

  record Frame(
      StorageMaintenance.Inventory inventory,
      boolean rescanning,
      StorageMaintenance.Progress progress,
      Result result) {
    boolean running() {
      return result == null;
    }
  }

  static final class Job {
    final long id;
    final Kind kind;
    final Expansion expansion;
    volatile Frame frame;
    private boolean displayed;

    Job(long id, Kind kind, StorageMaintenance.Inventory inventory, Expansion expansion) {
      this.id = id;
      this.kind = kind;
      this.expansion = expansion;
      frame = new Frame(inventory, false, null, null);
    }

    private void progress(StorageMaintenance.Progress progress) {
      if (progress.outcome() != StorageMaintenance.Outcome.RUNNING) return;
      Frame previous = frame;
      frame = new Frame(previous.inventory(), previous.rescanning(), progress, null);
    }
  }

  record RollbackResult(RollbackStorageInventory.Report report, String error) {}

  record CopyResult(RollbackStorageInventory.Copy copy, String error) {}

  static final class CopyLoad {
    volatile CopyResult result;
  }

  static final class RollbackLoad {
    volatile boolean cancelled;
    volatile RollbackStorageInventory.Report preview;
    volatile RollbackResult result;
    final Map<String, CopyLoad> measurements = new HashMap<>();
    CopyLoad activeMeasurement;

    boolean measuring() {
      return activeMeasurement != null && activeMeasurement.result == null;
    }
  }

  private final Executor executor;
  private final StorageTask<StorageMaintenance.Inventory> inspect;
  private final StorageTask<StorageMaintenance.CleanupResult> clean;
  private final ExpansionTask expand;
  private long nextId;
  private Job current;
  private RollbackLoad rollback;

  StoragePageWork(
      Executor executor,
      StorageTask<StorageMaintenance.Inventory> inspect,
      StorageTask<StorageMaintenance.CleanupResult> clean,
      ExpansionTask expand) {
    this.executor = executor;
    this.inspect = inspect;
    this.clean = clean;
    this.expand = expand;
  }

  synchronized Job open(Context context, long savedId) {
    if (current != null
        && (current.frame.running()
            || current.id == savedId
            || ((current.kind == Kind.CLEANUP || current.kind == Kind.EXPAND)
                && !current.displayed))) return current;
    return start(context, Kind.INITIAL_SCAN, current);
  }

  synchronized Job current() {
    return current;
  }

  synchronized Job start(Context context, Kind kind, Job expected) {
    return start(context, kind, expected, null);
  }

  synchronized Job startExpansion(Context context, Job expected, Expansion expansion) {
    return start(context, Kind.EXPAND, expected, expansion);
  }

  private Job start(Context context, Kind kind, Job expected, Expansion expansion) {
    if (expected != current || (current != null && current.frame.running())) return current;
    if (rollback != null) {
      rollback.cancelled = true;
      rollback = null;
    }
    var job =
        new Job(++nextId, kind, current == null ? null : current.frame.inventory(), expansion);
    current = job;
    Context app = context.getApplicationContext();
    executor.execute(() -> run(app, job));
    return job;
  }

  synchronized void markDisplayed(Job job) {
    job.displayed = true;
  }

  synchronized RollbackLoad openRollback(Context context, Job job) {
    if (job != current || job.frame.running()) return null;
    if (rollback != null && (rollback.result == null || rollback.result.report() != null))
      return rollback;
    var load = new RollbackLoad();
    rollback = load;
    Context app = context.getApplicationContext();
    executor.execute(() -> loadRollback(app, load));
    return load;
  }

  synchronized boolean measureCopy(Context context, Job job, String path) {
    if (job != current
        || job.frame.running()
        || rollback == null
        || rollback.result == null
        || rollback.result.report() == null
        || rollback.measuring()) return false;
    var load = rollback;
    var copy = new CopyLoad();
    load.measurements.put(path, copy);
    load.activeMeasurement = copy;
    Context app = context.getApplicationContext();
    executor.execute(() -> measureCopy(app, load, path, copy));
    return true;
  }

  private void run(Context app, Job job) {
    long started = System.nanoTime();
    StorageMaintenance.Inventory inventory = job.frame.inventory();
    StorageMaintenance.CleanupResult cleanup = null;
    StorageMaintenance.ExpansionResult expansion = null;
    String error = null;
    int errorHint = 0;
    boolean refreshed = false;
    try {
      if (job.kind == Kind.CLEANUP) {
        cleanup = clean.run(app, job::progress);
        job.frame = new Frame(inventory, true, null, null);
      } else if (job.kind == Kind.EXPAND) {
        expansion = expand.run(app, job.expansion.target(), job::progress);
        if (!expansionPublished(expansion)) {
          job.frame =
              new Frame(inventory, false, null, new Result(null, expansion, null, false, 0));
          return;
        }
        job.frame = new Frame(inventory, true, null, null);
      }
      inventory = inspect.run(app, job::progress);
      refreshed = true;
    } catch (Exception failure) {
      if ((job.kind == Kind.CLEANUP || job.kind == Kind.EXPAND)
          && cleanup == null
          && expansion == null) {
        WorkFailure classified = classifyFailure(failure);
        error = classified.code();
        errorHint = classified.hint();
        DiagnosticLog.record(
            app,
            "STORAGE_OPERATION_FAILURE",
            classified.diagnostic(
                job.kind,
                job.frame.progress(),
                System.currentTimeMillis(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
      } else error = NativeBackupJobs.code(failure);
    }
    job.frame =
        new Frame(
            inventory, false, null, new Result(cleanup, expansion, error, refreshed, errorHint));
  }

  static WorkFailure classifyFailure(Exception failure) {
    String originalCode = NativeBackupJobs.code(failure);
    FailureStage stage = FailureStage.OTHER;
    StackTraceElement site = null;
    Throwable matched = failure;
    boolean interrupted = false;
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable at = failure;
        at != null && visited.size() < 16 && visited.add(at);
        at = at.getCause()) {
      interrupted |= at instanceof InterruptedException;
      if (stage != FailureStage.OTHER) continue;
      StackTraceElement[] trace = at.getStackTrace();
      for (int index = 0; index < trace.length; index++) {
        StackTraceElement frame = trace[index];
        FailureStage found = stage(frame, index == 0);
        if (found != FailureStage.OTHER) {
          stage = found;
          site = frame;
          matched = at;
          break;
        }
        if (site == null && operationSite(frame)) site = frame;
      }
    }
    String code = interrupted && "IO_FAILURE".equals(originalCode) ? "CANCELLED" : originalCode;
    if (stage == FailureStage.OTHER && "STOP_DSH_AND_TERMINALS_FIRST".equals(code))
      stage = FailureStage.ADMISSION;
    String type = matched.getClass().getSimpleName();
    return new WorkFailure(
        originalCode,
        code,
        stage,
        site,
        type.matches("[A-Za-z][A-Za-z0-9_$]{0,79}") ? type : "Exception");
  }

  private static boolean operationSite(StackTraceElement frame) {
    return switch (frame.getClassName()) {
      case "com.deepseekharness.app.ui.StoragePageWork" -> frame.getMethodName().equals("run");
      case "com.deepseekharness.app.backup.StorageMaintenance" ->
          frame.getMethodName().equals("clean") || frame.getMethodName().equals("expandRollback");
      default -> false;
    };
  }

  private static FailureStage stage(StackTraceElement frame, boolean origin) {
    String method = frame.getMethodName();
    return switch (frame.getClassName()) {
        // Only a throw originating inside the gate is an admission failure. The same gate
        // is also on the stack for all failures later in the operation.
      case "com.deepseekharness.app.util.MaintenanceGate" ->
          origin && method.equals("exclusive") ? FailureStage.ADMISSION : FailureStage.OTHER;
      case "com.deepseekharness.app.util.EnvironmentTaskGate$Lease" ->
          origin && method.equals("run") ? FailureStage.ADMISSION : FailureStage.OTHER;
      case "com.deepseekharness.app.core.MaintenanceCoordinator$AndroidPorts" ->
          switch (method) {
            case "stopTerminals" -> FailureStage.TERMINAL_STOP;
            case "stopWeb" -> FailureStage.WEB_STOP;
            case "confirmOtherProcesses" -> FailureStage.BACKGROUND_EXIT;
            default -> FailureStage.OTHER;
          };
      case "com.deepseekharness.app.core.MaintenanceCoordinator" ->
          method.equals("stopWeb") ? FailureStage.WEB_STOP : FailureStage.OTHER;
      case "com.deepseekharness.app.runtime.BoundedGuestSessions" ->
          method.equals("reapExited") ? FailureStage.BACKGROUND_EXIT : FailureStage.OTHER;
      case "com.deepseekharness.app.backup.StorageMaintenance" ->
          switch (method) {
            case "files",
                    "volume",
                    "available",
                    "maintenanceFiles",
                    "cleanOwned",
                    "expandOwned",
                    "measured" ->
                FailureStage.FILE_WORK;
            default -> FailureStage.OTHER;
          };
      default -> FailureStage.OTHER;
    };
  }

  /** The general classifier can recognize a raw uppercase message. Persist only known codes. */
  private static String logCode(String value) {
    return switch (value) {
      case "IO_FAILURE",
              "CANCELLED",
              "PERMISSION_DENIED",
              "NO_SPACE",
              "ARCHIVE_TRUNCATED",
              "STOP_DSH_AND_TERMINALS_FIRST",
              "RECOVERY_PENDING",
              "FACTORY_RESET_QUIESCING",
              "STORAGE_VOLUME_UNAVAILABLE",
              "ROLLBACK_TARGET_INVALID",
              "ROLLBACK_EXPANSION_PATH",
              "ROLLBACK_ARCHIVE_CHANGED",
              "SOURCE_CHANGED",
              "BOUNDED_GUEST_RECORD_ROOT",
              "BOUNDED_GUEST_RECORD_LIMIT",
              "WEB_STOP_ROOT_UNKNOWN",
              "WEB_STOP_CANCELLED",
              "WEB_GUESTS_UNCONFIRMED",
              "RUNTIME_RECOVERY_REQUIRED",
              "ENVIRONMENT_RECOVERY_REQUIRED" ->
          value;
      default -> value.matches("FILESYSTEM_[0-9]{1,3}") ? value : "UNRECOGNIZED";
    };
  }

  static boolean expansionPublished(StorageMaintenance.ExpansionResult result) {
    return switch (result.outcome()) {
      case "RESTORED", "RESTORED_ARCHIVE_RETAINED", "ALREADY_EXPANDED" -> true;
      default -> false;
    };
  }

  private static void loadRollback(Context app, RollbackLoad load) {
    if (load.cancelled) return;
    try {
      load.result =
          new RollbackResult(
              RollbackStorageInventory.inspect(
                  app, () -> load.cancelled, report -> load.preview = report),
              null);
    } catch (Exception failure) {
      load.result = new RollbackResult(null, NativeBackupJobs.code(failure));
    }
  }

  private static void measureCopy(Context app, RollbackLoad load, String path, CopyLoad target) {
    if (load.cancelled) return;
    try {
      target.result =
          new CopyResult(
              RollbackStorageInventory.inspectCopy(app, path, () -> load.cancelled), null);
    } catch (Exception failure) {
      target.result = new CopyResult(null, NativeBackupJobs.code(failure));
    }
  }
}
