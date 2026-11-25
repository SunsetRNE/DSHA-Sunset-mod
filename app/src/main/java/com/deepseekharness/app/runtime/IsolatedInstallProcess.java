package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.ProcessBirthHandshake;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 冷安装及有界 proroot 命令的独立会话监督器；关闭前核验本次整组退出。 */
final class IsolatedInstallProcess extends Process implements AutoCloseable {
  private final Process supervisor;
  private final ProcessIdentity identity;
  private final File status;
  private final BoundedGuestSessions.Operation record;
  private final boolean closeWholeSession;
  private volatile boolean closing, closed, recordRetired;

  interface ExitEvidence {
    boolean groupGone();

    boolean sessionEmpty();
  }

  private final ExitEvidence evidence;

  static boolean supported(android.content.Context context) {
    return sessionLauncher(context) != null;
  }

  static String sessionLauncher(android.content.Context context) {
    String bundled = bundledLauncher(context);
    if (bundled != null) return bundled;
    File system = new File("/system/bin/setsid");
    return system.canExecute() ? system.getAbsolutePath() : null;
  }

  static String bundledLauncher(android.content.Context context) {
    File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libdsha-session.so");
    return binary.isFile() && binary.length() > 0 ? binary.getAbsolutePath() : null;
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target, File temporary, android.content.Context context) throws IOException {
    return start(target, temporary, context, null);
  }

  static IsolatedInstallProcess start(
      ProcessBuilder target,
      File temporary,
      android.content.Context context,
      BoundedGuestSessions.Operation record)
      throws IOException {
    return start(target, temporary, context, record, null);
  }

  static IsolatedInstallProcess startProbe(
      ProcessBuilder target,
      File temporary,
      android.content.Context context,
      BoundedGuestSessions.Operation record,
      BooleanSupplier cancelled)
      throws IOException {
    return start(
        target,
        temporary,
        context,
        Objects.requireNonNull(record),
        Objects.requireNonNull(cancelled));
  }

  private static IsolatedInstallProcess start(
      ProcessBuilder target,
      File temporary,
      android.content.Context context,
      BoundedGuestSessions.Operation record,
      BooleanSupplier probeCancelled)
      throws IOException {
    // 调用方保留 ProcessBuilder 默认 PIPE，避免依赖 API 26 的 Redirect。
    File status = null;
    Process supervisor = null;
    boolean accepted = false;
    IsolatedInstallProcess ready = null;
    Throwable original = null;
    try {
      checkProbeCancellation(probeCancelled);
      String launcher = record == null ? sessionLauncher(context) : bundledLauncher(context);
      if (launcher == null)
        throw new IOException(com.deepseekharness.app.util.UiText.text("此系统不支持独立安装进程组"));
      status = new File(temporary, "cold-install-" + java.util.UUID.randomUUID() + ".status");
      // 完成后仍保留组长，直到宿主核验身份并回收，避免 PID/进程组号复用的歧义。
      boolean nativeLauncher = !launcher.equals("/system/bin/setsid");
      String shell =
          com.deepseekharness.app.util.IsolatedCommand.script(
              target.command(), status.getAbsolutePath(), !nativeLauncher);
      target.command(
          nativeLauncher
              ? Arrays.asList(launcher, "--birth-handshake", "/system/bin/sh", "-c", shell)
              : Arrays.asList(launcher, "/system/bin/sh", "-c", shell));
      checkProbeCancellation(probeCancelled);
      Process launched = target.start();
      supervisor = launched;
      ProcessIdentity birth =
          ProcessBirthHandshake.read(
              supervisor.getInputStream(),
              android.os.Process.myPid(),
              () -> {
                checkProbeCancellation(probeCancelled);
                return !ProcessTermination.exited(launched);
              },
              2000);
      ProcessIdentity identity = readIdentity(birth.pid);
      if (birth.sameProcess(identity) && identity.ownsSession()) {
        ready =
            new IsolatedInstallProcess(
                supervisor, identity, status, record, probeCancelled != null);
        if (record != null) record.beforeLaunch(identity);
        checkProbeCancellation(probeCancelled);
        confirmStart(
            supervisor,
            nativeLauncher ? new File(launcher) : null,
            new File("/system/bin/app_process"));
        accepted = true;
        return ready;
      }
      throw new IOException("PROCESS_BIRTH_RECHECK_UNCONFIRMED");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      IOException interrupted =
          new IOException(com.deepseekharness.app.util.UiText.text("安装准备被中断"), error);
      original = interrupted;
      throw interrupted;
    } catch (IOException | RuntimeException | Error error) {
      original = error;
      throw error;
    } finally {
      // 握手前只等 stdin，没有创建 guest 子进程；握手写入失败也回收整个已核验的组。
      if (!accepted) {
        if (probeCancelled != null) {
          try {
            if (ready != null) ready.close();
            else closeBeforeProbeStart(supervisor, record);
          } catch (IOException | RuntimeException unconfirmed) {
            record.uncertain();
            throw new InstallProcess.OwnedExitFailure(unconfirmed, original);
          } finally {
            if (supervisor != null) closeProbePipes(supervisor);
          }
        } else {
          try {
            if (ready != null) ready.close();
            else if (supervisor != null) Compat.destroy(supervisor);
          } catch (RuntimeException unconfirmed) {
            if (original != null) unconfirmed.addSuppressed(original);
            throw unconfirmed;
          }
        }
        if (status != null) status.delete();
      }
    }
  }

  static void confirmStart(Process supervisor, File bundledLauncher, File systemProcess)
      throws IOException {
    try {
      OutputStream gate = supervisor.getOutputStream();
      gate.write("DSHA_START\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      gate.flush();
    } catch (IOException gateFailure) {
      String detail;
      try {
        int code = supervisor.exitValue();
        detail = com.deepseekharness.app.util.UiText.format("发送启动确认失败。安装监督进程已退出（退出码 %d）。", code);
      } catch (IllegalThreadStateException pending) {
        detail = com.deepseekharness.app.util.UiText.text("发送启动确认失败。安装监督进程的退出状态尚未确认。");
      }
      // The installed system executable and the actual bundled helper identify both ABIs.
      // SUPPORTED_ABIS may advertise translated ARM support on x86 Android.
      if (bundledLauncher != null && "libdsha-session.so".equals(bundledLauncher.getName())) {
        int system = elfMachine(systemProcess);
        if ((system == 3 || system == 62) && elfMachine(bundledLauncher) == 183) {
          detail +=
              " "
                  + com.deepseekharness.app.util.UiText.text(
                      "当前 Android 系统为 x86，此版本支持 ARM64 Android。可在设置中查看诊断。");
        }
      }
      throw new IOException(detail, gateFailure);
    }
  }

  /** Returns the machine of a little-endian Android executable, or zero if evidence is missing. */
  private static int elfMachine(File file) {
    try (java.io.DataInputStream input =
        new java.io.DataInputStream(new java.io.FileInputStream(file))) {
      byte[] header = new byte[64];
      input.readFully(header);
      var elf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      if (elf.getInt(0) != 0x464c457f
          || header[5] != 1
          || header[6] != 1
          || elf.getInt(20) != 1
          || (elf.getShort(16) != 2 && elf.getShort(16) != 3)) return 0;
      if (header[4] == 1 && elf.getShort(40) != 52 || header[4] == 2 && elf.getShort(52) != 64)
        return 0;
      int machine = Short.toUnsignedInt(elf.getShort(18));
      return (machine == 3 && header[4] == 1) || (machine != 3 && header[4] == 2) ? machine : 0;
    } catch (IOException | SecurityException unavailable) {
      return 0;
    }
  }

  private IsolatedInstallProcess(
      Process supervisor,
      ProcessIdentity identity,
      File status,
      BoundedGuestSessions.Operation record,
      boolean closeWholeSession) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = record;
    this.closeWholeSession = closeWholeSession;
    evidence = null;
  }

  IsolatedInstallProcess(
      Process supervisor, ProcessIdentity identity, File status, ExitEvidence evidence) {
    this.supervisor = supervisor;
    this.identity = identity;
    this.status = status;
    this.record = null;
    this.closeWholeSession = false;
    this.evidence = evidence;
  }

  private static void checkProbeCancellation(BooleanSupplier cancelled) {
    if (cancelled != null && cancelled.getAsBoolean()) throw new InstallTask.Cancelled();
  }

  private static void closeBeforeProbeStart(
      Process supervisor, BoundedGuestSessions.Operation record) throws IOException {
    if (supervisor != null) {
      // No DSHA_START byte was sent. EOF lets the native launcher reap its waiting shell.
      supervisor.getOutputStream().close();
      if (!ProcessTermination.awaitExit(supervisor, 2000))
        throw new IOException("INSTALL_PROBE_LAUNCHER_EXIT_UNCONFIRMED");
    }
    record.completed();
  }

  private static void closeProbePipes(Process supervisor) {
    for (var pipe :
        new java.io.Closeable[] {
          supervisor.getInputStream(), supervisor.getErrorStream(), supervisor.getOutputStream()
        })
      try {
        pipe.close();
      } catch (IOException ignored) {
      }
  }

  private static ProcessIdentity readIdentity(int pid) {
    if (pid <= 1) return null;
    try {
      return ProcessIdentity.fromStat(
          Compat.readAll(new File("/proc/" + pid + "/stat")), pid, android.os.Process.myPid());
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  private synchronized Integer poll() {
    if (closing
        && !closed
        && groupGone()
        && ProcessTermination.exited(supervisor)
        && boundedSessionEmpty()) {
      return supervisor.exitValue();
    }
    if (closed) return supervisor.exitValue();
    if (!closing && status.isFile()) {
      try {
        int code = com.deepseekharness.app.util.IsolatedCommand.status(Compat.readAll(status));
        if (code >= 0) return code;
      } catch (IOException ignored) {
      }
    }
    if (!closing && ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
      // Native leader ends the group if its shell exits before publishing status.
      // No status is never successful, even if a shell returned zero.
      int code = supervisor.exitValue();
      return code == 0 ? 125 : code;
    }
    return null;
  }

  @Override
  public int exitValue() {
    Integer value = poll();
    if (value != null) return value;
    throw new IllegalThreadStateException(com.deepseekharness.app.util.UiText.text("安装进程组尚未结束"));
  }

  @Override
  public boolean isAlive() {
    return poll() == null;
  }

  @Override
  public int waitFor() throws InterruptedException {
    long delay = 10;
    while (true) {
      try {
        return exitValue();
      } catch (IllegalThreadStateException running) {
        Thread.sleep(delay);
        delay = Math.min(100, delay * 2);
      }
    }
  }

  @Override
  public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit)
      throws InterruptedException {
    long started = System.nanoTime(), budget = unit.toNanos(timeout), delay = 10;
    do {
      if (poll() != null) return true;
      long remaining = budget - (System.nanoTime() - started);
      if (remaining <= 0) return false;
      java.util.concurrent.TimeUnit.NANOSECONDS.sleep(
          Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)));
      delay = Math.min(100, delay * 2);
    } while (true);
  }

  @Override
  public InputStream getInputStream() {
    return supervisor.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return supervisor.getErrorStream();
  }

  @Override
  public OutputStream getOutputStream() {
    return supervisor.getOutputStream();
  }

  @Override
  public void destroy() {
    close();
  }

  @Override
  public Process destroyForcibly() {
    close();
    return this;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      retireRecord();
      return;
    }
    closing = true;
    if (closeWholeSession) {
      closeProbeSession();
      return;
    }
    ProcessIdentity current = readIdentity(identity.pid);
    if (!identity.sameProcess(current) || !current.ownsSession()) {
      if (ProcessTermination.exited(supervisor) && groupGone() && boundedSessionEmpty()) {
        closed = true;
        status.delete();
        retireRecord();
        return;
      }
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装进程组身份不可确认，已停止后续维护"));
    }
    try {
      Os.kill(-identity.pid, OsConstants.SIGKILL);
    } catch (android.system.ErrnoException error) {
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("无法回收安装进程组"), error);
    }
    if (!ProcessTermination.awaitExit(supervisor, 3000))
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("安装监督进程未退出"));
    boolean interrupted = Thread.interrupted();
    long deadline = SystemClock.elapsedRealtime() + 3000;
    try {
      while (!groupGone() && SystemClock.elapsedRealtime() < deadline) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (!groupGone())
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("安装子进程尚未完全退出，已暂停后续维护"));
    if (!boundedSessionEmpty())
      throw new IllegalStateException("BOUNDED_GUEST_SESSION_UNCONFIRMED");
    closed = true;
    status.delete();
    retireRecord();
  }

  private void closeProbeSession() {
    // GNU timeout can assign a child a different process group in the same session.
    // The existing terminal policy verifies and closes those members individually.
    boolean interrupted = Thread.interrupted();
    try {
      TerminalProcessCloser.close(identity, 3000);
      if (!ProcessTermination.awaitExit(supervisor, 2000))
        throw new IOException("INSTALL_PROBE_LAUNCHER_EXIT_UNCONFIRMED");
      if (status.exists() && !status.delete() && status.exists())
        throw new IOException("INSTALL_PROBE_STATUS_RETAINED");
      retireRecord();
      closed = true;
    } catch (InterruptedException error) {
      interrupted = true;
      throw new IllegalStateException("INSTALL_PROBE_SESSION_INTERRUPTED", error);
    } catch (IOException error) {
      throw new IllegalStateException("INSTALL_PROBE_SESSION_UNCONFIRMED", error);
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void retireRecord() {
    if (record == null || recordRetired) return;
    try {
      record.completed();
      recordRetired = true;
    } catch (IOException failure) {
      throw new IllegalStateException("BOUNDED_GUEST_RECORD_RETAINED", failure);
    }
  }

  void markRecordUncertain() {
    if (record != null) record.uncertain();
  }

  private boolean groupGone() {
    if (evidence != null) return evidence.groupGone();
    try {
      Os.kill(-identity.pid, 0);
      return false;
    } catch (android.system.ErrnoException error) {
      return error.errno == OsConstants.ESRCH;
    }
  }

  private boolean boundedSessionEmpty() {
    if (evidence != null) return evidence.sessionEmpty();
    if (record == null) return true;
    try {
      TerminalProcessCloser.requireSessionEmpty(identity.pid);
      return true;
    } catch (IOException unknown) {
      return false;
    }
  }
}
