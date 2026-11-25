package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.ProfileConfigPath;
import com.deepseekharness.app.util.WebProcSel;
import com.deepseekharness.app.backup.RuntimeTrialRecords;
import com.deepseekharness.app.util.Ids;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The actual pending-trial recovery algorithm, with platform stop/confirmation supplied explicitly. */
final class TrialRecovery {
  interface Stopper {
    void stopAndConfirm(File payload) throws IOException;

    default void confirmSettings(File payload, boolean hasPidEvidence) throws IOException {
      throw new IOException(SettingsTrialCleanup.PROCESS_UNCONFIRMED);
    }

    /**
     * 无 PID / stale 证据时，由平台只读确认该记录对应的试运行进程已不存在。
     *
     * <p>默认 fail-closed：没有证据就保留记录，不能把“读不到”当成“已退出”。实现方只能按记录 id
     * 推导出的试运行 profile 与两次一致出生身份确认，不能按端口 / 名称 / 进程组。
     */
    default void confirmAbsent(String id) throws IOException {
      throw new IOException(
          "TRIAL_PROCESS_UNCONFIRMED",
          new IOException("TRIAL_NO_PID_OR_BOUND_SESSION_EXIT_EVIDENCE"));
    }
  }

  private TrialRecovery() {}

  static boolean recover(BackupFileSystem fs, File home, Stopper stopper) throws IOException {
    if (fs.stat(home).type.equals("MISSING")) return false;
    List<String> entries = fs.list(home);
    if (entries.size() > 64) throw new IOException("TRIAL_RETENTION_LIMIT");
    IOException retained = null;
    boolean closedSettings = false;
    for (String id : entries) {
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("CANCELLED");
      try {
        if (!id.matches(Ids.UUID_PATTERN)) throw new IOException("TRIAL_DIRECTORY");
        File entry = fs.child(home, id), closed = fs.child(entry, "closed");
        if (!fs.stat(closed).type.equals("MISSING")) {
          TrialSupport.closeOwnedPayload(fs, entry);
          continue;
        }
        File payload = fs.child(entry, "payload");
        File launched = fs.child(entry, "launched");
        String launchedType = fs.stat(launched).type;
        boolean settings = false;
        if (!launchedType.equals("MISSING")) {
          if (!launchedType.equals("FILE")
              || !id.equals(new String(fs.small(launched, 128), StandardCharsets.US_ASCII)))
            throw new IOException("TRIAL_MARKER");
          boolean hasPidEvidence =
              !fs.stat(fs.child(payload, ".dsha-web.pid")).type.equals("MISSING")
                  || !fs.stat(fs.child(payload, ".dsha-web.pid.stale")).type.equals("MISSING");
          settings = isSettingsRecord(fs, payload);
          if (settings) {
            stopper.confirmSettings(payload, hasPidEvidence);
          } else if (hasPidEvidence) {
            stopper.stopAndConfirm(payload);
          } else {
            // A launch can fail before writing the PID. Keep the record unless absence is proven.
            stopper.confirmAbsent(id);
          }
        }
        TrialSupport.closeOwnedPayload(fs, entry);
        closedSettings |= settings;
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException blocked) {
        if (Thread.currentThread().isInterrupted()) {
          java.io.InterruptedIOException cancelled =
              new java.io.InterruptedIOException("CANCELLED");
          cancelled.initCause(blocked);
          throw cancelled;
        }
        if (retained == null) retained = blocked;
        else retained.addSuppressed(blocked);
      }
    }
    if (retained != null) throw retained;
    RuntimeTrialRecords.pruneClosed(fs, home);
    return closedSettings;
  }

  /** Distinguish saved settings trials from runtime-install trials using the original host intent. */
  static boolean isSettingsRecord(BackupFileSystem fs, File payload) throws IOException {
    File operation = payload.getParentFile();
    String id = operation.getName(), nonce = id.replace("-", "");
    if (!id.matches(Ids.UUID_PATTERN)
        || !payload.equals(fs.child(operation, "payload"))
        || !payload.getCanonicalFile().equals(payload.getAbsoluteFile())
        || !id.equals(
            new String(fs.small(fs.child(operation, "launched"), 128), StandardCharsets.US_ASCII)))
      throw new IOException("TRIAL_RECORD_DIRECTORY");
    File intent = fs.child(operation, "intent.json");
    if (fs.stat(intent).type.equals("MISSING")) return false;
    if (!isSettingsIntent(id, fs.small(intent, 16384))) return false;
    File stale = fs.child(payload, ".dsha-web.pid.stale");
    if (!fs.stat(stale).type.equals("MISSING")
        && WebProcSel.parsePid(new String(fs.small(stale, 32), StandardCharsets.US_ASCII).trim())
            < 2) throw new IOException("TRIAL_PID_RECORD_INVALID");
    return true;
  }

  /** Identify the role from the same bounded bytes that a stop trace rechecks. */
  static boolean isSettingsIntent(String id, byte[] bytes) throws IOException {
    String nonce = id.replace("-", "");
    Map<String, Object> value = BackupJson.read(bytes, 16384);
    if (!value.containsKey("purpose") && !value.containsKey("sourceProfile")) return false;
    if (!value.keySet().equals(Set.of("id", "nonce", "purpose", "profile", "sourceProfile"))
        || !"profile-settings".equals(value.get("purpose"))
        || !id.equals(value.get("id"))
        || !nonce.equals(value.get("nonce"))
        || !("dsha-recovery-" + nonce.substring(0, 16)).equals(value.get("profile"))
        || !ProfileConfigPath.profile(BackupJson.string(value, "sourceProfile")))
      throw new IOException("TRIAL_INTENT_BINDING");
    return true;
  }

  /** Conservative absence proof: any one matching trial binding keeps the record open. */
  static boolean matchesContext(
      String nonce, String guest, String path, String argv, Map<String, String> env, String cwd) {
    if (("dsha-recovery-" + nonce.substring(0, 16)).equals(WebProcSel.trialProfile(argv))
        || nonce.equals(env.get("DSHA_RUNTIME_TRIAL_NONCE"))
        || (guest + "/home").equals(env.get("DSH_HOME"))
        || (guest + "/isolated-user-home").equals(env.get("HOME"))
        || (guest + "/isolated-home").equals(env.get("HOME"))
        || cwd.equals(path)
        || cwd.startsWith(path + "/")) return true;
    for (String argument : argv.split("\\u0000"))
      if (argument.equals(guest)
          || argument.startsWith(guest + "/")
          || argument.equals(path + ":" + guest)) return true;
    return false;
  }
}
