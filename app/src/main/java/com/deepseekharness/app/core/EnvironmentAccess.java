package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.backup.RuntimeDescriptor;
import com.deepseekharness.app.runtime.BoundedGuestSessions;
import com.deepseekharness.app.runtime.RuntimeTrial;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.DshVersionPolicy;
import com.deepseekharness.app.util.EnvironmentIdentity;
import java.io.IOException;

/** 主界面与执行入口共用恢复状态；进入界面不会写入环境就绪标记。 */
public final class EnvironmentAccess {
  private static final String ACCEPTED_ROLLBACK = "accepted_rollback";
  private static volatile String unconfirmedRollbackKey = "";

  private enum RollbackSelection {
    UNSELECTED,
    UNCONFIRMED,
    VERIFIED
  }

  enum StartupMaintenance {
    NONE,
    RUNTIME,
    TRIAL
  }

  private EnvironmentAccess() {}

  public static boolean needsRecovery(HarnessController controller) {
    return !controller.isEnvironmentReady()
        || com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller);
  }

  public static boolean runtimeLatest(HarnessController controller) {
    try {
      var installed = controller.proot().installedRuntimeDescriptor();
      return installed != null && installed.latest(controller.proot().expectedRuntimeDescriptor());
    } catch (java.io.IOException error) {
      return false;
    }
  }

  public static com.deepseekharness.app.util.DshVersionPolicy.Status runtimeVersionStatus(
      HarnessController controller) {
    try {
      return controller.proot().installedVersionStatus();
    } catch (RuntimeException error) {
      return com.deepseekharness.app.util.DshVersionPolicy.Status.UNKNOWN;
    }
  }

  private static long apkUpdatedAt(HarnessController controller) throws java.io.IOException {
    try {
      var context = controller.context();
      long updated =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
      if (updated <= 0) throw new java.io.IOException("APK_UPDATE_TIME_UNAVAILABLE");
      return updated;
    } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
      throw new java.io.IOException("APK_UPDATE_TIME_UNAVAILABLE", error);
    }
  }

  /** Same identity is used by both the UI gate and the service startup gate. */
  public static String candidateAttemptKey(HarnessController controller)
      throws java.io.IOException {
    return candidateAttemptKey(controller, controller.proot().expectedRuntimeDescriptor());
  }

  private static String candidateAttemptKey(
      HarnessController controller, RuntimeDescriptor expected) throws IOException {
    return EnvironmentIdentity.attemptKey("runtime:" + expected.id(), apkUpdatedAt(controller));
  }

  private static SharedPreferences upgrades(HarnessController controller) {
    return controller
        .context()
        .getSharedPreferences("dsha_environment_upgrade", Context.MODE_PRIVATE);
  }

  private static String rollbackKey(HarnessController controller) throws IOException {
    var installed = controller.proot().installedRuntimeDescriptor();
    var expected = controller.proot().expectedRuntimeDescriptor();
    var version = runtimeVersionStatus(controller);
    if (installed == null
        || installed.latest(expected)
        || !installed.compatible(expected)
        || version != DshVersionPolicy.Status.CURRENT
            && version != DshVersionPolicy.Status.INSTALLED_OLDER) return "";
    var health = controller.proot().runtimeHealth();
    if (!RuntimeDescriptor.healthy(health, installed.id())) return "";
    return candidateAttemptKey(controller, expected)
        + "#"
        + installed.id()
        + "#"
        + health.get("nonce");
  }

  /** Called by the explicit rollback task only after its isolated trial and transaction commit. */
  static void rememberAcceptedRollback(HarnessController controller) {
    try {
      String key = rollbackKey(controller);
      if (!key.isEmpty()) {
        unconfirmedRollbackKey = key;
        var prefs = upgrades(controller);
        if (prefs.edit().putString(ACCEPTED_ROLLBACK, key).commit()) {
          unconfirmedRollbackKey = "";
          return;
        }
        // Android can update SharedPreferences in memory even when the disk write fails.
        prefs.edit().remove(ACCEPTED_ROLLBACK).apply();
      }
    } catch (IOException | RuntimeException unavailable) {
      // A committed recovery is still successful; the next entry must recheck its own proof.
    }
    DiagnosticLog.record(controller.context(), "ROLLBACK_SELECTION_UNCONFIRMED", "");
  }

  /** A verified previous runtime can continue on this APK installation after the user chose it. */
  public static boolean hasAcceptedRollback(HarnessController controller) {
    return rollbackSelection(controller, null) == RollbackSelection.VERIFIED;
  }

  private static RollbackSelection rollbackSelection(
      HarnessController controller, String candidate) {
    boolean selected = false;
    try {
      String accepted = upgrades(controller).getString(ACCEPTED_ROLLBACK, "");
      if (accepted.isEmpty() || accepted.equals(unconfirmedRollbackKey))
        return RollbackSelection.UNSELECTED;
      if (candidate == null) candidate = candidateAttemptKey(controller);
      if (!accepted.startsWith(candidate + "#")) return RollbackSelection.UNSELECTED;
      selected = true;
      var task = BackupTask.get(controller.context());
      var snapshot = task.snapshot();
      if (!controller.isEnvironmentReady()) return RollbackSelection.UNCONFIRMED;
      // Stale choices cannot grant admission or replace the earlier candidate-attempt policy.
      if (!accepted.equals(rollbackKey(controller))) return RollbackSelection.UNSELECTED;
      var context = controller.context();
      if (MaintenanceCoordinator.pending(controller)
          || RuntimeTrial.hasPending(context)
          || BoundedGuestSessions.firstPending(context.getFilesDir()) != null)
        return RollbackSelection.UNCONFIRMED;
      if (task.maintenanceBusy()
          || EnvironmentIdentity.unresolvedTask(snapshot.status)
          || snapshot.status == BackupTaskState.Status.FAILED
              && DeployStages.environmentKind(snapshot.kind)) return RollbackSelection.UNSELECTED;
      return RollbackSelection.VERIFIED;
    } catch (IOException | RuntimeException unavailable) {
      return selected ? RollbackSelection.UNCONFIRMED : RollbackSelection.UNSELECTED;
    }
  }

  /** A limited-entry choice belongs to the installed APK, not future same-code updates. */
  public static String limitedEntryKey(HarnessController controller) {
    try {
      return com.deepseekharness.app.util.EnvironmentIdentity.attemptKey(
          controller.proot().environmentIdentity(), apkUpdatedAt(controller));
    } catch (java.io.IOException error) {
      return "";
    }
  }

  /** 返回 true 时启动前必须进入维护；新候选只自动尝试一次，健康兼容前代仍可使用。 */
  public static boolean requiresMaintenanceBeforeStart(HarnessController controller) {
    return startupMaintenance(controller) != StartupMaintenance.NONE;
  }

  static StartupMaintenance startupMaintenance(HarnessController controller) {
    com.deepseekharness.app.util.DshVersionPolicy.Status version = runtimeVersionStatus(controller);
    if (version == com.deepseekharness.app.util.DshVersionPolicy.Status.INSTALLED_NEWER)
      return StartupMaintenance.RUNTIME;
    try {
      // Settings trials may leave an open record even when the installed runtime is current.
      if (RuntimeTrial.hasPending(controller.context())) return StartupMaintenance.TRIAL;
    } catch (IOException | RuntimeException error) {
      return StartupMaintenance.TRIAL;
    }
    if (runtimeLatest(controller)) return StartupMaintenance.NONE;
    try {
      String candidate = candidateAttemptKey(controller);
      if (com.deepseekharness.app.util.EnvironmentIdentity.unresolvedTask(
          BackupTask.get(controller.context()).snapshot().status))
        return StartupMaintenance.RUNTIME;
      RollbackSelection selection = rollbackSelection(controller, candidate);
      if (selection != RollbackSelection.UNSELECTED)
        return selection == RollbackSelection.VERIFIED
            ? StartupMaintenance.NONE
            : StartupMaintenance.RUNTIME;
      return com.deepseekharness.app.util.EnvironmentIdentity.shouldBlockRuntimeStart(
              false,
              true,
              com.deepseekharness.app.core.MaintenanceCoordinator.isEnvironmentTaskBusy(),
              com.deepseekharness.app.core.MaintenanceCoordinator.pending(controller),
              candidate,
              upgrades(controller).getString("attempted_identity", ""))
          ? StartupMaintenance.RUNTIME
          : StartupMaintenance.NONE;
    } catch (java.io.IOException error) {
      // 不能认证 APK 自带的运行时描述时，不得由 START_STICKY/看门狗启动旧 Web。
      return StartupMaintenance.RUNTIME;
    }
  }
}
