package com.deepseekharness.app.device;

import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.RootModuleInventory;
import com.deepseekharness.app.util.RootModulePolicy;
import com.deepseekharness.app.util.SelfPackageIdentity;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** F07 专用 UID0 只读协调；只调用固定版本 probe 与固定模块根 reader，没有任意命令入口。 */
public final class RootModuleReadCore {
  private RootModuleReadCore() {}

  interface ReadPort {
    long now();

    RootModulePolicy.ProbeResult probe(
        SelfPackageIdentity identity,
        RootModulePolicy.Manager manager,
        RootModulePolicy.Probe probe,
        long timeoutMs)
        throws java.io.IOException, InterruptedException;

    RootModuleFiles.Scan scan(long deadline) throws java.io.IOException;
  }

  public static String read(SelfPackageIdentity identity) {
    if (android.os.Process.myUid() != 0 || identity == null)
      return unavailable("ROOT_IDENTITY_UNVERIFIED");
    return read(
        identity,
        new ReadPort() {
          public long now() {
            return android.os.SystemClock.elapsedRealtime();
          }

          public RootModulePolicy.ProbeResult probe(
              SelfPackageIdentity owner,
              RootModulePolicy.Manager manager,
              RootModulePolicy.Probe probe,
              long timeoutMs)
              throws java.io.IOException, InterruptedException {
            BoundedProcessRunner.Result result =
                RootModuleReadCore.probe(owner, manager, probe, timeoutMs);
            if (result.timedOut) throw new java.io.IOException("ROOT_MODULE_PROBE_UNKNOWN");
            return new RootModulePolicy.ProbeResult(
                result.exitCode, !result.truncated, result.output);
          }

          public RootModuleFiles.Scan scan(long deadline) throws java.io.IOException {
            return new RootModuleFiles().scan(deadline);
          }
        });
  }

  /** 只供同包夹具注入 OS 事实；公开生产入口必须先核验真实 UID0。 */
  static String read(SelfPackageIdentity identity, ReadPort port) {
    try {
      if (identity == null) return unavailable("ROOT_IDENTITY_UNVERIFIED");
      long deadline = port.now() + RootModulePolicy.DEADLINE_MS;
      RootModulePolicy.Manager selected = null;
      for (RootModulePolicy.Manager manager : RootModulePolicy.Manager.values()) {
        Map<RootModulePolicy.Probe, RootModulePolicy.ProbeResult> results =
            new EnumMap<>(RootModulePolicy.Probe.class);
        for (RootModulePolicy.Probe probe : RootModulePolicy.probes(manager)) {
          long left = deadline - port.now();
          if (left <= 0 || Thread.currentThread().isInterrupted()) return unavailable("UNKNOWN");
          results.put(probe, port.probe(identity, manager, probe, left));
        }
        if (RootModulePolicy.probeMatches(manager, results)) {
          if (selected != null) return unavailable("MANAGER_AMBIGUOUS");
          selected = manager;
        }
      }
      if (selected == null) return unavailable("UNSUPPORTED_MANAGER_VERSION");
      if (port.now() >= deadline || Thread.currentThread().isInterrupted())
        return unavailable("UNKNOWN");
      RootModuleFiles.Scan scan;
      try {
        scan = port.scan(deadline);
      } catch (java.io.InterruptedIOException cancelled) {
        Thread.currentThread().interrupt();
        return unavailable("UNKNOWN");
      } catch (java.io.IOException unreadable) {
        return unavailable(scanFailure(unreadable));
      }
      RootModuleInventory.Builder inventory = new RootModuleInventory.Builder(selected);
      boolean complete = scan.complete();
      RootModulePolicy.Code reason = complete ? null : scan.reason();
      if (!complete && !RootModulePolicy.failureReason(reason))
        reason = RootModulePolicy.Code.UNKNOWN;
      for (RootModuleFiles.Entry source : scan.entries()) {
        if (port.now() >= deadline || Thread.currentThread().isInterrupted()) {
          complete = false;
          reason = RootModulePolicy.Code.MODULE_DEADLINE_EXCEEDED;
          break;
        }
        byte[] property = source.properties();
        try {
          RootModuleInventory.Entry entry =
              RootModuleInventory.parse(
                  source.id(),
                  property,
                  source.disabled(),
                  source.removePending(),
                  source.updatePending(),
                  source.modifiedMillis());
          if (!inventory.add(entry, property.length)) {
            complete = false;
            RootModulePolicy.Code failure = inventory.failure();
            reason = failure != null ? failure : RootModulePolicy.Code.UNKNOWN;
            break;
          }
        } catch (RootModuleInventory.Rejected rejected) {
          complete = false;
          reason = rejected.code();
        }
      }
      RootModuleInventory.Result result = inventory.finish(complete, reason);
      JSONArray modules = new JSONArray();
      for (RootModuleInventory.Entry entry : result.modules()) {
        modules.put(
            new JSONObject()
                .put("id", entry.id())
                .put("name", entry.name())
                .put("version", entry.version())
                .put("versionCode", entry.versionCode())
                .put("disabled", entry.disabled())
                .put("removing", entry.removing())
                .put("updatePending", entry.updatePending())
                .put("modifiedMillis", entry.modifiedMillis()));
      }
      return new JSONObject()
          .put("ok", true)
          .put("readOnly", true)
          .put("code", result.code().name())
          .put("reason", result.reason() == null ? "" : result.reason().name())
          .put("manager", selected.name())
          .put("executionUid", 0)
          .put("complete", result.complete())
          .put("modules", modules)
          .toString();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return unavailable("UNKNOWN");
    } catch (java.io.IOException probeUnknown) {
      return unavailable("UNKNOWN");
    } catch (Exception unavailable) {
      return unavailable("UNKNOWN");
    }
  }

  private static BoundedProcessRunner.Result probe(
      SelfPackageIdentity identity,
      RootModulePolicy.Manager manager,
      RootModulePolicy.Probe probe,
      long timeoutMs)
      throws java.io.IOException, InterruptedException {
    ProcessBuilder builder =
        new ProcessBuilder(
                "/system/bin/app_process",
                "/system/bin",
                RootModuleProbeMain.class.getName(),
                identity.name,
                Integer.toString(identity.uid),
                manager.name(),
                probe.name())
            .redirectErrorStream(true);
    builder.environment().put("CLASSPATH", identity.source);
    Process child = builder.start();
    try {
      try (var stdin = child.getOutputStream()) {
        // 固定 argv，不向上游程序提供可解释的 stdin。
      }
      return BoundedProcessRunner.collect(
          child, timeoutMs, RootModulePolicy.MAX_PROBE_BYTES, RootModuleReadCore::closeChild);
    } finally {
      closeChild(child);
      if (child.isAlive()) throw new java.io.IOException("ROOT_MODULE_PROBE_EXIT_UNKNOWN");
    }
  }

  private static void closeChild(Process child) {
    if (!child.isAlive()) return;
    try {
      child.destroy();
      if (child.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
      child.destroyForcibly();
      child.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException denied) {
      // 父调用仍会检查 isAlive；无法关闭不能用输出或超时伪造退出。
    }
  }

  /** 只透传 reader 已判定的截止/换链；其余读取错误不能回显原始路径或 errno。 */
  private static String scanFailure(java.io.IOException error) {
    String message = error.getMessage();
    return "MODULE_DEADLINE_EXCEEDED".equals(message) || "MODULE_SOURCE_CHANGED".equals(message)
        ? message
        : "MODULE_ROOT_UNREADABLE";
  }

  public static String unavailable(String code) {
    try {
      return new JSONObject()
          .put("ok", false)
          .put("readOnly", true)
          .put("code", code)
          .put("complete", false)
          .put("modules", new JSONArray())
          .toString();
    } catch (Exception impossible) {
      return "{\"ok\":false,\"readOnly\":true,\"code\":\"UNKNOWN\",\"complete\":false}";
    }
  }
}
