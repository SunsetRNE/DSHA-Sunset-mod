package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupErrorCode;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings review outcomes and the independently proven lifetime of their isolated guest. */
final class SettingsTrialCleanup {
  static final String PROCESS_UNCONFIRMED = "SETTINGS_TRIAL_PROCESS_UNCONFIRMED";

  private SettingsTrialCleanup() {}

  static void finish(
      BackupFileSystem fs,
      File operation,
      WebProcessManager manager,
      Process launcher,
      RuntimeWorkPort.Work work,
      Throwable primary,
      Integer exitCode)
      throws IOException {
    finish(
        fs,
        operation,
        launcher,
        work,
        primary,
        exitCode,
        guestCleanup(manager, launcher),
        tracked -> {
          if (!manager.observeStopped(false)) return false;
          RuntimeTrial.confirmSettingsContextGone(manager, fs.child(operation, "payload"));
          return true;
        });
  }

  static void finish(
      BackupFileSystem fs,
      File operation,
      Process launcher,
      RuntimeWorkPort.Work work,
      Throwable primary,
      Integer exitCode,
      TrialGuestCleanup.Io guest,
      TrialSupport.ExitCheck confirmed)
      throws IOException {
    String cleanupCode = "";
    try {
      if (launcher == null) {
        work.close();
        if (!fs.stat(fs.child(operation, "launched")).type.equals("MISSING"))
          throw new IOException(PROCESS_UNCONFIRMED);
        TrialSupport.closeOwnedPayload(fs, operation);
        return;
      }
      boolean closed =
          TrialSupport.finish(
              launcher,
              work,
              new TrialSupport.Cleanup() {
                public void stop() throws IOException {
                  if (!TrialGuestCleanup.stop(guest).isEmpty())
                    throw new IOException(PROCESS_UNCONFIRMED);
                }

                public boolean confirmed(Process tracked) throws IOException {
                  return ProcessTermination.exited(tracked) && confirmed.confirmed(tracked);
                }

                public void closeRecord() throws IOException {
                  TrialSupport.closeOwnedPayload(fs, operation);
                }
              },
              primary,
              PROCESS_UNCONFIRMED);
      if (!closed) {
        Throwable[] secondary = primary.getSuppressed();
        cleanupCode = BackupErrorCode.from(secondary[secondary.length - 1]);
      }
    } catch (IOException | RuntimeException failure) {
      cleanupCode = BackupErrorCode.from(failure);
      if (primary == null) {
        primary = failure;
        throw failure;
      }
      primary.addSuppressed(failure);
    } finally {
      if (primary != null) recordFailure(fs, operation, primary, exitCode, cleanupCode);
    }
  }

  static TrialGuestCleanup.Io guestCleanup(WebProcessManager manager, Process launcher) {
    return new TrialGuestCleanup.Io() {
      public String stop() {
        return manager.stopSettingsTrialGuest(false);
      }

      public String force() {
        return manager.stopSettingsTrialGuest(true);
      }

      public boolean launcherExited() {
        return launcher == null || ProcessTermination.exited(launcher);
      }

      public void awaitLauncher(long millis) {
        if (launcher != null) ProcessTermination.awaitExit(launcher, millis);
      }

      public void destroyLauncher() {
        if (launcher != null) Compat.destroy(launcher);
      }

      public void diagnostic(String detail) {}
    };
  }

  static void recordFailure(
      BackupFileSystem fs,
      File operation,
      Throwable primary,
      Integer exitCode,
      String cleanupCode) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("runtimeMode", "proot");
    value.put("error", BackupErrorCode.from(primary));
    value.put("failedAt", System.currentTimeMillis());
    if (exitCode != null) value.put("exitCode", exitCode);
    if (!cleanupCode.isEmpty()) value.put("cleanupCode", cleanupCode);
    try {
      fs.atomic(operation, "failure.json", BackupJson.write(value, 16384));
    } catch (IOException | RuntimeException failure) {
      primary.addSuppressed(failure);
    }
  }
}
