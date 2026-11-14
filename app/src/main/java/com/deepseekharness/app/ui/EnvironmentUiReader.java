package com.deepseekharness.app.ui;

import android.content.Context;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.EnvironmentAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.core.StartupRepairs;
import com.deepseekharness.app.util.EnvironmentUiFacts;

/** 页面事实读口的无状态适配；不预取、不缓存，也不向执行入口提供许可。 */
final class EnvironmentUiReader implements EnvironmentUiFacts.Reader {
  private final Context context;
  private final HarnessController controller;

  EnvironmentUiReader(Context context, HarnessController controller) {
    this.context = context;
    this.controller = controller;
  }

  @Override
  public boolean runnable() {
    return controller.isEnvironmentReady();
  }

  @Override
  public boolean installed() {
    return controller.proot().isEnvironmentInstalled();
  }

  @Override
  public boolean latest() {
    return EnvironmentAccess.runtimeLatest(controller);
  }

  @Override
  public boolean hostPending() {
    return MaintenanceCoordinator.pending(controller);
  }

  @Override
  public boolean maintenanceBusy() {
    return BackupTask.get(context).maintenanceBusy();
  }

  @Override
  public boolean requiresMaintenance() {
    return EnvironmentAccess.requiresMaintenanceBeforeStart(controller);
  }

  @Override
  public boolean blocksEntry() {
    return MainEntryMaintenance.blocksEntry(controller);
  }

  @Override
  public boolean repairsPending() {
    return StartupRepairs.pending(context);
  }
}
