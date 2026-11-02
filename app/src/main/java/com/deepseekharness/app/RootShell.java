package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.RootShellCandidates;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Root 直接通道；仅在用户开启开关并执行设备操作时请求 su，不需要 ADB。 */
public final class RootShell {
  private enum Status {
    WAITING,
    RESPONDED,
    TIMED_OUT,
    FAILED
  }

  private static final class LastResult {
    final Status status;
    final String executable, detail;

    LastResult(Status status, String executable, String detail) {
      this.status = status;
      this.executable = executable;
      this.detail = detail;
    }

    String render(String current) {
      if (!executable.equals(current) || status == Status.WAITING)
        return com.deepseekharness.app.util.UiText.format("su：%s\n尚未验证 root 授权", current);
      return switch (status) {
        case RESPONDED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 通道已响应 · 设备命令仍受策略保护", current);
        case TIMED_OUT ->
            com.deepseekharness.app.util.UiText.format(
                "su：%s\nroot 授权或执行超时，请在 root 管理器查看 DSHA 授权", current);
        case FAILED ->
            com.deepseekharness.app.util.UiText.format("su：%s\nroot 授权或执行失败：%s", current, detail);
        case WAITING -> throw new IllegalStateException("ROOT_STATUS_WAITING");
      };
    }
  }

  private static volatile LastResult lastResult = new LastResult(Status.WAITING, "", "");

  private RootShell() {}

  public static boolean enabled(Context ctx) {
    return ctx.getSharedPreferences(Constants.PREFS, 0)
        .getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
  }

  private static String executable() {
    // Only inspect known system/root-manager locations. Discovery never invokes
    // su or trusts an app-controlled PATH; authorization happens on execution.
    return RootShellCandidates.firstVerified(
        path -> {
          try {
            File file = new File(path);
            android.system.StructStat stat = android.system.Os.stat(path);
            int writable = android.system.OsConstants.S_IWGRP | android.system.OsConstants.S_IWOTH;
            return stat.st_uid == 0
                && android.system.OsConstants.S_ISREG(stat.st_mode)
                && (stat.st_mode & writable) == 0
                && file.canExecute();
          } catch (android.system.ErrnoException | SecurityException ignored) {
            return false;
          }
        });
  }

  public static boolean present() {
    return executable() != null;
  }

  public static String status(Context ctx) {
    if (!enabled(ctx)) return com.deepseekharness.app.util.UiText.text("未启用 · 开启后可直接申请 root 授权");
    String su = executable();
    String base =
        su == null
            ? com.deepseekharness.app.util.UiText.text("未找到 su，可使用 Shizuku 或 ADB 通道")
            : lastResult.render(su);
    return base + "\n" + com.deepseekharness.app.util.UiText.format("命令拦截档位：%s", policyLabel(ctx));
  }

  /** 当前档位的中文展示名；三个档位只影响 root 原始命令，不改变 SMS 等原生闸门。 */
  public static String policyLabel(Context ctx) {
    return switch (new com.deepseekharness.app.core.ConfigStore(ctx).rootPolicyMode()) {
      case STRICT -> com.deepseekharness.app.util.UiText.text("严格（默认）");
      case RELAXED -> com.deepseekharness.app.util.UiText.text("放宽（保留不可逆操作拦截）");
      case FULL -> com.deepseekharness.app.util.UiText.text("完全放宽（不按命令内容拦截）");
    };
  }

  public static String exec(Context ctx, String command, int authorizedSmsUser) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    String hostBlocked = com.deepseekharness.app.util.HostDeviceCommandGuard.rejection(command);
    if (hostBlocked != null) return hostBlocked + "\n[EXIT=126]";
    DeviceShellPolicy.Mode mode =
        new com.deepseekharness.app.core.ConfigStore(ctx).rootPolicyMode();
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command, mode);
    if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
    if (plan.kind == DeviceShellPolicy.Kind.SENSITIVE_READ
        && (authorizedSmsUser < 0
            || !new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed()))
      return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 短信读取尚未经过原生授权\n[EXIT=126]");
    if (plan.kind == DeviceShellPolicy.Kind.SHELL) {
      // 原始 shell 只能经 root 通道执行；Shizuku/ADB 的通用执行器仍走严格档，不识别此计划。
      if (mode == DeviceShellPolicy.Mode.STRICT || plan.shell == null)
        return com.deepseekharness.app.util.UiText.text(
            "[POLICY_BLOCKED] ROOT_SHELL_PLAN_UNVERIFIED\n[EXIT=126]");
      return runPrivileged(ctx, plan.shell, -1, "root-unrestricted");
    }
    return runPrivileged(ctx, command, authorizedSmsUser, "");
  }

  /** Native-only launcher path. Generic RootShell.exec rejects app_process unconditionally. */
  public static String execVirtualScreen(Context ctx, String command) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    try {
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new File(source).getCanonicalPath();
      if (!source.equals(canonical)
          || !DeviceShellPolicy.inspectManagedVirtualScreen(
                  command, canonical, ctx.getPackageName())
              .allowed())
        return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 虚拟屏启动参数无法核验\n[EXIT=126]");
    } catch (Exception invalid) {
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 无法核验 DSHA 安装包路径\n[EXIT=126]");
    }
    return runPrivileged(ctx, command, -1, "virtual-screen-start");
  }

  /** Preserve pre-send refusal separately from a possibly executed native launch/probe. */
  public static com.deepseekharness.app.device.DeviceTransportResult execVirtualScreenTyped(
      Context ctx, String command) {
    if (!enabled(ctx))
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root", "ROOT_NOT_ENABLED", "[POLICY_BLOCKED] ROOT_NOT_ENABLED\n[EXIT=126]");
    try {
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new File(source).getCanonicalPath();
      if (!source.equals(canonical)
          || !DeviceShellPolicy.inspectManagedVirtualScreen(
                  command, canonical, ctx.getPackageName())
              .allowed())
        return com.deepseekharness.app.device.DeviceTransportResult.notSent(
            "root",
            "DEVICE_POLICY_BLOCKED",
            "[POLICY_BLOCKED] VSCREEN_ARGUMENTS_UNVERIFIED\n[EXIT=126]");
    } catch (Exception invalid) {
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root",
          "SELF_PACKAGE_UNVERIFIED",
          "[POLICY_BLOCKED] SELF_PACKAGE_UNVERIFIED\n[EXIT=126]");
    }
    if (executable() == null)
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "root", "ROOT_UNAVAILABLE", "[ROOT_UNAVAILABLE] ROOT_UNAVAILABLE\n[EXIT=124]");
    return com.deepseekharness.app.device.DeviceTransportResult.fromLegacy(
        "root",
        runPrivileged(ctx, command, -1, "virtual-screen-start"),
        com.deepseekharness.app.device.DeviceTransportIdentity.unknown());
  }

  /**
   * Typed external-device execution. This is deliberately separate from exec(): it accepts an
   * argv array only, never a shell string, and is callable only through the dedicated root helper.
   */
  public static String execExternal(
      Context ctx,
      java.util.List<String> argv,
      java.util.Map<String, String> environment,
      long timeoutMs) {
    return execExternal(ctx, argv, environment, timeoutMs, null);
  }

  public static String execExternal(
      Context ctx,
      java.util.List<String> argv,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (argv == null || argv.isEmpty() || argv.size() > 32 || timeoutMs <= 0 || timeoutMs > 900_000)
      return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接命令参数无效\n[EXIT=126]");
    String externalPolicy =
        expectedRoot == null
            ? "EXTERNAL_ROOT_REQUIRED"
            : com.deepseekharness.app.util.ExternalToolPolicy.rejection(argv, expectedRoot);
    if (externalPolicy != null) return "[POLICY_BLOCKED] " + externalPolicy + "\n[EXIT=126]";
    try {
      org.json.JSONArray values = new org.json.JSONArray();
      for (String value : argv) {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
          return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接命令参数无效\n[EXIT=126]");
        values.put(value);
      }
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment != null) {
        if (environment.size() > 32) return "[POLICY_BLOCKED] 外接环境变量过多\n[EXIT=126]";
        for (var entry : environment.entrySet()) {
          String key = entry.getKey(), value = entry.getValue();
          if (key == null
              || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
              || value == null
              || value.length() > 4096
              || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
          env.put(key, value);
        }
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-argv")
              .put("argv", values)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      return runPrivilegedExternal(ctx, request, timeoutMs);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  /** Runs one owned local-only ADB server and client inside the same root helper process. */
  public static String execExternalAdbSession(
      Context ctx,
      java.util.List<String> serverArgv,
      java.util.List<String> clientArgv,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (timeoutMs <= 0 || timeoutMs > 900_000 || expectedRoot == null)
      return "[POLICY_BLOCKED] 外接 ADB 会话参数无效\n[EXIT=126]";
    String serverPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
            serverArgv, expectedRoot);
    String clientPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejection(clientArgv, expectedRoot);
    if (serverPolicy != null || clientPolicy != null)
      return "[POLICY_BLOCKED] "
          + (serverPolicy != null ? serverPolicy : clientPolicy)
          + "\n[EXIT=126]";
    try {
      org.json.JSONArray serverValues = new org.json.JSONArray();
      org.json.JSONArray clientValues = new org.json.JSONArray();
      for (String value : serverArgv) serverValues.put(value);
      for (String value : clientArgv) clientValues.put(value);
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment == null || environment.size() > 32)
        return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
      for (var entry : environment.entrySet()) {
        String key = entry.getKey(), value = entry.getValue();
        if (key == null
            || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
            || value == null
            || value.length() > 4096
            || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
        env.put(key, value);
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-adb-session")
              .put("serverArgv", serverValues)
              .put("argv", clientValues)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      // server 就绪等待最多 15 秒，再加客户端期限与 su 启动余量。
      return runPrivilegedExternal(ctx, request, timeoutMs + 25_000);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  /**
   * 同一专属 server 内顺序执行多步客户端命令；每步都在 App 侧和 root helper 内各按 {@link
   * com.deepseekharness.app.util.ExternalToolPolicy} 核验一次。输出格式见 {@link
   * com.deepseekharness.app.util.ExternalStepOutput}。
   */
  public static String execExternalAdbSteps(
      Context ctx,
      java.util.List<String> serverArgv,
      java.util.List<java.util.List<String>> steps,
      java.util.Map<String, String> environment,
      long timeoutMs,
      String expectedRoot,
      String importRoot,
      String nonce,
      boolean continueOnFailure) {
    if (!enabled(ctx))
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 未允许 root shell\n[EXIT=126]");
    if (executable() == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    if (timeoutMs <= 0
        || timeoutMs > 900_000
        || expectedRoot == null
        || steps == null
        || steps.isEmpty()
        || steps.size() > 64
        || !com.deepseekharness.app.util.ExternalStepOutput.validNonce(nonce))
      return "[POLICY_BLOCKED] 外接 ADB 会话参数无效\n[EXIT=126]";
    String serverPolicy =
        com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
            serverArgv, expectedRoot);
    if (serverPolicy != null) return "[POLICY_BLOCKED] " + serverPolicy + "\n[EXIT=126]";
    try {
      org.json.JSONArray stepValues = new org.json.JSONArray();
      for (int i = 0; i < steps.size(); i++) {
        String policy =
            com.deepseekharness.app.util.ExternalToolPolicy.rejection(
                steps.get(i), expectedRoot, importRoot);
        if (policy != null) return "[POLICY_BLOCKED] " + policy + " @step " + i + "\n[EXIT=126]";
        stepValues.put(new org.json.JSONArray(steps.get(i)));
      }
      org.json.JSONObject env = new org.json.JSONObject();
      if (environment == null || environment.size() > 32)
        return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
      for (var entry : environment.entrySet()) {
        String key = entry.getKey(), value = entry.getValue();
        if (key == null
            || !key.matches("[A-Z_][A-Z0-9_]{0,63}")
            || value == null
            || value.length() > 4096
            || value.indexOf('\0') >= 0) return "[POLICY_BLOCKED] 外接环境变量无效\n[EXIT=126]";
        env.put(key, value);
      }
      org.json.JSONObject request =
          new org.json.JSONObject()
              .put("operation", "external-adb-steps")
              .put("serverArgv", new org.json.JSONArray(serverArgv))
              .put("steps", stepValues)
              .put("environment", env)
              .put("timeoutMs", timeoutMs)
              .put("expectedRoot", expectedRoot)
              .put("importRoot", importRoot == null ? "" : importRoot)
              .put("nonce", nonce)
              .put("continueOnFailure", continueOnFailure)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid);
      return runPrivilegedExternal(ctx, request, timeoutMs + 25_000);
    } catch (Exception error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    }
  }

  private static String runPrivilegedExternal(
      Context ctx, org.json.JSONObject request, long timeoutMs) {
    String su = executable();
    if (su == null) return "[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]";
    Process process = null;
    try {
      String entry =
          "CLASSPATH="
              + ShellQuote.arg(ctx.getApplicationInfo().sourceDir)
              + " /system/bin/app_process /system/bin "
              + RootShellMain.class.getName();
      process = new ProcessBuilder(su, "-c", entry).redirectErrorStream(true).start();
      try (java.io.OutputStream input = process.getOutputStream()) {
        input.write(request.toString().getBytes(StandardCharsets.UTF_8));
      }
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, timeoutMs, 768 * 1024, Compat::destroy);
      int marker = result.output.lastIndexOf(RootShellMain.RESULT);
      if (!result.timedOut && !result.truncated && marker >= 0) {
        String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
        return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
      }
      return "[EXECUTION_UNKNOWN] 外接 root 进程未返回完整结果，不会自动重试\n[EXIT=125]";
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "[EXECUTION_UNKNOWN] 外接 root 请求已中断，不会自动重试\n[EXIT=125]";
    } catch (Throwable error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    } finally {
      if (process != null) {
        try {
          process.exitValue();
        } catch (IllegalThreadStateException running) {
          Compat.destroy(process);
        }
        try {
          process.getOutputStream().close();
        } catch (java.io.IOException ignored) {
        }
        try {
          process.getInputStream().close();
        } catch (java.io.IOException ignored) {
        }
      }
    }
  }

  private static String runPrivileged(
      Context ctx, String command, int authorizedSmsUser, String operation) {
    String su = executable();
    if (su == null)
      return com.deepseekharness.app.util.UiText.text("[ROOT_UNAVAILABLE] 未找到 su\n[EXIT=124]");
    Process process = null;
    try {
      // 命令通过标准输入传递；su 的 shell 参数只含固定入口与已安装 APK 路径。
      String entry =
          "CLASSPATH="
              + ShellQuote.arg(ctx.getApplicationInfo().sourceDir)
              + " /system/bin/app_process /system/bin "
              + RootShellMain.class.getName();
      process = new ProcessBuilder(su, "-c", entry).redirectErrorStream(true).start();
      try (java.io.OutputStream input = process.getOutputStream()) {
        org.json.JSONObject request =
            new org.json.JSONObject()
                .put("command", command)
                .put("smsUser", authorizedSmsUser)
                .put("selfPackage", ctx.getPackageName())
                .put("selfUid", ctx.getApplicationInfo().uid);
        if (!operation.isEmpty()) request.put("operation", operation);
        input.write(request.toString().getBytes(StandardCharsets.UTF_8));
      }
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, 65_000, 400_000, Compat::destroy);
      int marker = result.output.lastIndexOf(RootShellMain.RESULT);
      if (!result.timedOut && !result.truncated && marker >= 0) {
        String encoded = result.output.substring(marker + RootShellMain.RESULT.length()).trim();
        String output = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        lastResult = new LastResult(Status.RESPONDED, su, "");
        return output;
      }
      lastResult =
          new LastResult(
              result.timedOut ? Status.TIMED_OUT : Status.FAILED,
              su,
              "exit="
                  + result.exitCode
                  + " bytes="
                  + result.totalBytes
                  + " truncated="
                  + result.truncated);
      return "[EXECUTION_UNKNOWN] "
          + lastResult.render(su)
          + com.deepseekharness.app.util.UiText.text("。未取得完整结果，不会自动重试\n[EXIT=125]");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return com.deepseekharness.app.util.UiText.text(
          "[EXECUTION_UNKNOWN] root 请求已中断，不会自动重试\n[EXIT=125]");
    } catch (Throwable e) {
      lastResult = new LastResult(Status.FAILED, su, SensitiveData.redact(String.valueOf(e)));
      return "[EXECUTION_UNKNOWN] " + lastResult.render(su) + "\n[EXIT=125]";
    } finally {
      if (process != null) {
        try {
          process.exitValue();
        } catch (IllegalThreadStateException running) {
          Compat.destroy(process);
        }
        try {
          process.getOutputStream().close();
        } catch (java.io.IOException ignored) {
        }
        try {
          process.getInputStream().close();
        } catch (java.io.IOException ignored) {
        }
        try {
          process.getErrorStream().close();
        } catch (java.io.IOException ignored) {
        }
      }
    }
  }
}
