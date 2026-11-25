package com.deepseekharness.app;

import android.content.Context;
import android.os.Binder;
import androidx.annotation.Keep;
import com.deepseekharness.app.runtime.PrivilegedPackageContext;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SelfPackageIdentity;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.SmsQuery;
import com.deepseekharness.app.util.UiText;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shizuku UserService. RootShellMain uses the same command policy through local Java calls. */
public class ShellService extends IShellService.Stub {
  private static final String DEVICE_PATH = "/system/bin:/system/xbin:/sbin:/vendor/bin";
  private static final String CLOSED =
      "[SHIZUKU_SERVICE_NOT_READY] Device shell is closing\n[EXIT=124]";
  private static final int COMMAND_LIMIT = 256 * 1024;
  private final Context packageContext;
  private final SelfPackageIdentity localIdentity;
  private final long timeoutMillis;
  private final Set<Process> children = new HashSet<>();
  private boolean closed;
  private boolean exitRequested;

  public ShellService() {
    this(null, null, 30_000);
  }

  @Keep
  public ShellService(Context packageContext) {
    this(packageContext, null, 30_000);
  }

  ShellService(SelfPackageIdentity identity) {
    this(null, identity, 30_000);
  }

  ShellService(long timeoutMillis) {
    this(null, null, timeoutMillis);
  }

  private ShellService(Context context, SelfPackageIdentity identity, long timeoutMillis) {
    if (timeoutMillis < 1 || timeoutMillis > 30_000)
      throw new IllegalArgumentException("Invalid shell timeout");
    this.packageContext = context;
    this.localIdentity = identity;
    this.timeoutMillis = timeoutMillis;
  }

  private SelfPackageIdentity identity() throws IOException {
    if (localIdentity != null) return localIdentity;
    return PrivilegedPackageContext.callerIdentity(ShellService.class, packageContext);
  }

  private synchronized Process startOwned(ProcessBuilder builder) throws IOException {
    if (closed) return null;
    Process child = builder.start();
    children.add(child);
    return child;
  }

  private synchronized void release(Process child) {
    if (children.remove(child)) Compat.destroy(child);
  }

  private synchronized boolean isClosed() {
    return closed;
  }

  /** The local root watchdog never terminates the VM through this method. */
  synchronized void terminateOwned() {
    if (closed) return;
    closed = true;
    for (Process child : children) {
      try {
        Compat.destroy(child);
      } catch (RuntimeException error) {
        android.util.Log.w("ShellService", "Unable to reap owned child", error);
      }
    }
    children.clear();
  }

  private boolean calledRemotely() {
    return Binder.getCallingPid() != android.os.Process.myPid();
  }

  // The public Context constructor (Shizuku 13) identifies the Android user. Older servers
  // use the process suffix supplied in the bind request, chosen from the app's actual UID.
  private boolean owner(int caller) {
    if (caller < 10000) return false;
    if (packageContext == null)
      return processName().equals(BuildConfig.APPLICATION_ID + ":shizuku_" + caller);
    return BuildConfig.APPLICATION_ID.equals(packageContext.getPackageName())
        && packageContext.getClassLoader() == ShellService.class.getClassLoader()
        && packageContext.getApplicationInfo().uid == caller;
  }

  String processName() {
    try (FileInputStream file = new FileInputStream("/proc/self/cmdline")) {
      byte[] name = new byte[256];
      for (int i = 0; i < name.length; i++) {
        int value = file.read();
        if (value <= 0) return new String(name, 0, i, StandardCharsets.UTF_8);
        name[i] = (byte) value;
      }
    } catch (IOException ignored) {
    }
    return "";
  }

  private void checkCaller() {
    if (calledRemotely() && !owner(Binder.getCallingUid()))
      throw new SecurityException("SHELL_SERVICE_OWNER_MISMATCH");
  }

  @Override
  public String exec(String command) {
    checkCaller();
    if (isClosed()) return CLOSED;
    try {
      SelfPackageIdentity target =
          DeviceShellPolicy.inspect(command).kind == DeviceShellPolicy.Kind.STOP
              ? identity()
              : null;
      return DeviceShellExecutor.execute(command, this::runDeviceArgv, target);
    } catch (IOException failure) {
      return blocked(failure);
    }
  }

  @Override
  public String execVirtualScreen(String command) {
    checkCaller();
    if (isClosed()) return CLOSED;
    try {
      return DeviceShellExecutor.executeVirtualScreen(command, this::runDeviceArgv, identity());
    } catch (IOException failure) {
      return blocked(failure);
    }
  }

  private static String blocked(IOException failure) {
    return "[POLICY_BLOCKED] " + SensitiveData.redact(failure.getMessage()) + "\n[EXIT=126]";
  }

  @Override
  public void destroy() {
    closeService(false);
  }

  @Override
  public void destroyFromManager() {
    closeService(true);
  }

  private synchronized void closeService(boolean managerOnly) {
    if (!calledRemotely()) {
      terminateOwned();
      return;
    }
    int uid = android.os.Process.myUid();
    int caller = Binder.getCallingUid();
    if ((uid != 0 && uid != 2000) || (caller != uid && (managerOnly || !owner(caller))))
      throw new SecurityException("SHELL_SERVICE_DESTROY_CALLER");
    String name = processName();
    boolean dedicated =
        name.startsWith(BuildConfig.APPLICATION_ID + ":shizuku_")
            && name.substring((BuildConfig.APPLICATION_ID + ":shizuku_").length())
                .matches("[0-9]{5,10}");
    // A 13.x manager may load the new service on behalf of an older app using its old suffix.
    if (!dedicated
        && packageContext != null
        && name.equals(BuildConfig.APPLICATION_ID + ":shizuku"))
      dedicated = BuildConfig.APPLICATION_ID.equals(packageContext.getPackageName());
    terminateOwned();
    if (dedicated && !exitRequested) {
      exitRequested = true;
      exitUserServiceProcess();
    }
  }

  void exitUserServiceProcess() {
    System.exit(0);
  }

  String execAuthorizedSms(String command, int user) {
    DeviceShellPolicy.Plan query = DeviceShellPolicy.inspect(command);
    if (query.kind != DeviceShellPolicy.Kind.SENSITIVE_READ)
      return UiText.text("[POLICY_BLOCKED] 不是受支持的短信查询\n[EXIT=126]");
    return runDeviceArgv(SmsQuery.forUser(query.argv, user));
  }

  private String runDeviceArgv(List<String> argv) {
    List<String> systemArgv = new ArrayList<>(argv);
    systemArgv.set(0, "/system/bin/" + argv.get(0));
    return runCommand(new ProcessBuilder(systemArgv));
  }

  private String runCommand(ProcessBuilder builder) {
    Process child = null;
    try {
      builder.redirectErrorStream(true);
      builder.environment().put("PATH", DEVICE_PATH);
      child = startOwned(builder);
      if (child == null) return CLOSED;
      var capture =
          BoundedProcessRunner.collect(child, timeoutMillis, COMMAND_LIMIT, Compat::destroy);
      String text = capture.output;
      if (capture.truncated) text += UiText.text("\n[OUTPUT_TRUNCATED] 输出超过 256 KiB，已截断");
      if (!capture.timedOut) return text + "\n[EXIT=" + capture.exitCode + "]";
      String limit =
          timeoutMillis % 1000 == 0 ? (timeoutMillis / 1000) + "s" : timeoutMillis + "ms";
      return text + UiText.format("\n命令执行超时(%s)，已终止本次进程\n[EXIT=timeout]", limit);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return UiText.text("命令等待已中断，本次进程已关闭\n[EXIT=interrupted]");
    } catch (Exception failure) {
      return "ERROR: " + SensitiveData.redact(String.valueOf(failure));
    } finally {
      if (child != null) release(child);
    }
  }

  /** Native-only argv execution for the separately authorized USB target channel. */
  String execExternal(
      java.util.List<String> argv, java.util.Map<String, String> environment, long timeoutMillis) {
    if (android.os.Process.myUid() != 0)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
    if (argv == null
        || argv.isEmpty()
        || argv.size() > 32
        || timeoutMillis <= 0
        || timeoutMillis > 900_000)
      return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
    for (String value : argv)
      if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
        return "[POLICY_BLOCKED] EXTERNAL_ARGUMENT_INVALID\n[EXIT=126]";
    if (environment == null || environment.size() > 32)
      return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
    for (var entry : environment.entrySet())
      if (entry.getKey() == null
          || !entry.getKey().matches("[A-Z_][A-Z0-9_]{0,63}")
          || entry.getValue() == null
          || entry.getValue().length() > 4096
          || entry.getValue().indexOf('\0') >= 0)
        return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
    Process process = null;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(new java.util.ArrayList<>(argv)).redirectErrorStream(true);
      builder.environment().clear();
      builder.environment().put("PATH", DEVICE_PATH);
      builder.environment().putAll(environment);
      process = startOwned(builder);
      if (process == null) return CLOSED;
      var result =
          BoundedProcessRunner.collect(process, timeoutMillis, 768 * 1024, Compat::destroy);
      return result.output
          + (result.timedOut
              ? "\n[EXECUTION_UNKNOWN] external process timeout; do not replay\n[EXIT=125]"
              : result.truncated
                  ? "\n[EXECUTION_UNKNOWN] external output truncated\n[EXIT=125]"
                  : "\n[EXIT=" + result.exitCode + "]");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "[EXECUTION_UNKNOWN] external process interrupted; do not replay\n[EXIT=125]";
    } catch (Throwable error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    } finally {
      if (process != null) {
        release(process);
      }
    }
  }

  /**
   * 在同一个 root 子进程内拉起本次专属的 nodaemon ADB server，等它只在 loopback 就绪后执行一条客户端命令，
   * 结束后回收 server。server 是本进程的直接子进程，不经 start-server 的自我 re-exec，也不留常驻进程。
   */
  String execExternalAdbSession(
      java.util.List<String> serverArgv,
      java.util.List<String> clientArgv,
      java.util.Map<String, String> environment,
      long timeoutMillis) {
    if (android.os.Process.myUid() != 0)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
    int port =
        com.deepseekharness.app.util.ExternalToolPolicy.adbSessionPort(serverArgv, clientArgv);
    if (port < 0) return "[POLICY_BLOCKED] EXTERNAL_ADB_SESSION_PORT\n[EXIT=126]";
    if (timeoutMillis <= 0 || timeoutMillis > 900_000)
      return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
    if (environment == null || environment.size() > 32)
      return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
    Process server = null;
    try {
      server = runExternalServer(serverArgv, environment);
      if (server == null) return "[SHIZUKU_SERVICE_NOT_READY] Root service is closing\n[EXIT=124]";
      long deadline =
          System.nanoTime()
              + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 15_000));
      String waited = awaitLoopbackServer(server, port, deadline);
      // server 未就绪时客户端尚未执行，本次结果确定为失败，可以由用户决定是否重试。
      if (waited != null) return "[EXTERNAL_ADB_SERVER_FAILED] " + waited + "\n[EXIT=124]";
      return runExternalClient(clientArgv, environment, timeoutMillis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return "[EXECUTION_UNKNOWN] external adb session interrupted; do not replay\n[EXIT=125]";
    } catch (Throwable error) {
      return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
    } finally {
      if (server != null) {
        release(server);
      }
    }
  }

  /**
   * 多步版本：同一个专属 server 内按顺序执行多条客户端命令（无线 connect 后续命令、安装推送 + pm 会话）。
   * 每步前写 {@code [DSHA_STEP nonce i]}；默认遇到失败即停，continueOnFailure 用于只读探测。
   * 总期限用尽时把下一步记为未知并停止；已执行步骤的结果照常返回，调用方不得重放未知步骤。
   */
  String execExternalAdbSteps(
      java.util.List<String> serverArgv,
      java.util.List<java.util.List<String>> steps,
      java.util.Map<String, String> environment,
      long timeoutMillis,
      String nonce,
      boolean continueOnFailure) {
    if (android.os.Process.myUid() != 0)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
    if (steps == null
        || steps.isEmpty()
        || steps.size() > 64
        || !com.deepseekharness.app.util.ExternalStepOutput.validNonce(nonce))
      return "[POLICY_BLOCKED] EXTERNAL_ADB_STEPS_INVALID\n[EXIT=126]";
    int port = -1;
    for (java.util.List<String> step : steps) {
      int stepPort =
          com.deepseekharness.app.util.ExternalToolPolicy.adbSessionPort(serverArgv, step);
      if (stepPort < 0 || port >= 0 && stepPort != port)
        return "[POLICY_BLOCKED] EXTERNAL_ADB_SESSION_PORT\n[EXIT=126]";
      port = stepPort;
    }
    if (timeoutMillis <= 0 || timeoutMillis > 900_000)
      return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
    if (environment == null || environment.size() > 32)
      return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
    Process server = null;
    long deadline =
        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    StringBuilder out = new StringBuilder();
    try {
      server = runExternalServer(serverArgv, environment);
      if (server == null) return "[SHIZUKU_SERVICE_NOT_READY] Root service is closing\n[EXIT=124]";
      long ready =
          System.nanoTime()
              + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 15_000));
      String waited = awaitLoopbackServer(server, port, ready);
      if (waited != null) return "[EXTERNAL_ADB_SERVER_FAILED] " + waited + "\n[EXIT=124]";
      for (int i = 0; i < steps.size(); i++) {
        out.append(com.deepseekharness.app.util.ExternalStepOutput.marker(nonce, i));
        long left =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left < 1_000) {
          out.append("[STEP_NOT_RUN] session deadline reached; step was not started\n[EXIT=124]\n");
          break;
        }
        String result = runExternalClient(steps.get(i), environment, left);
        out.append(result).append('\n');
        boolean ok = result.endsWith("[EXIT=0]") && !result.contains("[EXECUTION_UNKNOWN]");
        if (result.contains("[EXECUTION_UNKNOWN]")) break;
        if (!ok && !continueOnFailure) break;
      }
      return out.toString();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return out
          + "[EXECUTION_UNKNOWN] external adb session interrupted; do not replay\n[EXIT=125]";
    } catch (Throwable error) {
      return out
          + "[EXECUTION_UNKNOWN] "
          + SensitiveData.redact(String.valueOf(error))
          + "\n[EXIT=125]";
    } finally {
      if (server != null) {
        release(server);
      }
    }
  }

  /** 启动 server；输出丢弃，避免管道写满阻塞 server。 */
  private Process runExternalServer(
      java.util.List<String> argv, java.util.Map<String, String> environment)
      throws java.io.IOException {
    ProcessBuilder builder =
        new ProcessBuilder(new java.util.ArrayList<>(argv))
            .redirectErrorStream(true)
            // Redirect.DISCARD 在旧 Android 上不可用，直接写 /dev/null。
            .redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
    builder.environment().clear();
    builder.environment().put("PATH", DEVICE_PATH);
    builder.environment().putAll(environment);
    return startOwned(builder);
  }

  /** 轮询 127.0.0.1:port，直到可连接；server 提前退出或超时返回原因，成功返回 null。 */
  private static String awaitLoopbackServer(Process server, int port, long deadline)
      throws InterruptedException {
    java.net.InetSocketAddress address =
        new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port);
    while (true) {
      if (!server.isAlive()) return "server exited " + server.exitValue();
      try (java.net.Socket socket = new java.net.Socket()) {
        socket.connect(address, 250);
        return null;
      } catch (java.io.IOException notYet) {
        // server 尚未监听，继续等待。
      }
      if (System.nanoTime() >= deadline) return "server not listening on loopback";
      Thread.sleep(50);
    }
  }

  private String runExternalClient(
      java.util.List<String> argv, java.util.Map<String, String> environment, long timeoutMillis) {
    return execExternal(argv, environment, timeoutMillis);
  }

  /**
   * 用户显式选择放宽/完全放宽档后的 root 原始 shell。
   *
   * <p>只允许由 {@link RootShellMain} 在 uid 0 下调用；方法不在 AIDL 接口里，Shizuku 的 Binder
   * 调用方无法触达。原生策略已在 App 进程判定过档位，这里只做执行身份、长度和并发生命周期检查。
   */
  String execUnrestricted(String command) {
    if (android.os.Process.myUid() != 0)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 原始 root shell 仅允许 uid 0 执行\n[EXIT=126]");
    if (command == null
        || command.isEmpty()
        || command.length() > com.deepseekharness.app.util.DeviceShellPolicy.MAX_SHELL_COMMAND
        || command.indexOf('\0') >= 0)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] ROOT_SHELL_PLAN_INVALID\n[EXIT=126]");
    return runCommand(new ProcessBuilder("/system/bin/sh", "-c", command));
  }
}
