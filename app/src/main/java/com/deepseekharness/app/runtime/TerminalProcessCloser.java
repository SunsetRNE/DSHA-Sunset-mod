package com.deepseekharness.app.runtime;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.TerminalExitPolicy;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/** 终端退出的 Android 观察与信号适配；纯判据不把 launcher 退出或一次快照当作会话退出。 */
public final class TerminalProcessCloser {
  private TerminalProcessCloser() {}

  /** 简易终端固定 READY 只有声明，宿主核验本次出生身份后才能接受命令。 */
  public static ProcessIdentity captureSimpleLeader(int session, long started) throws IOException {
    if (session <= 1 || started <= 0) throw new IOException(UiText.text("终端进程身份无效"));
    ProcessIdentity leader = read(session, session);
    if (leader == null || leader.exited() || leader.started != started || !leader.ownsSession())
      throw new IOException(UiText.text("终端本次进程身份不匹配，原环境保持保护"));
    AndroidPorts ports = new AndroidPorts();
    int uid = ports.uid(session);
    if (uid == -1) throw new IOException(UiText.text("终端进程身份暂不可读，保留环境保护"));
    if (uid != ports.ownUid()) throw new IOException(UiText.text("终端进程 UID 不属于本应用"));
    if (!belongsToSession(session, session)) throw new IOException(UiText.text("终端会话号已变化，原环境保持保护"));
    return leader;
  }

  public static void closeSimple(ProcessIdentity expected, long timeoutMs)
      throws IOException, InterruptedException {
    close(expected, timeoutMs);
  }

  /** proot 自己回收 tracee；调用者仍须确认 launcher 退出与整个会话为空。 */
  public static void closeProot(ProcessIdentity expected) throws IOException {
    try {
      TerminalExitPolicy.requestProotExit(expected, new AndroidPorts());
    } catch (TerminalExitPolicy.Failure failure) {
      throw present(failure);
    }
  }

  public static void close(ProcessIdentity expected, long timeoutMs)
      throws IOException, InterruptedException {
    try {
      TerminalExitPolicy.close(expected, timeoutMs, new AndroidPorts());
    } catch (TerminalExitPolicy.Failure failure) {
      throw present(failure);
    }
  }

  /** 已自然退出的组长不授权按旧 SID 发信号；剩余成员或观察未知继续保留环境保护。 */
  public static void requireSessionEmpty(int session) throws IOException {
    try {
      TerminalExitPolicy.requireSessionEmpty(session, new AndroidPorts());
    } catch (TerminalExitPolicy.Failure failure) {
      throw present(failure);
    }
  }

  private static IOException present(TerminalExitPolicy.Failure failure) {
    String message =
        switch (failure.code()) {
          case INVALID_IDENTITY -> "终端本次进程身份缺失";
          case IDENTITY_CHANGED -> "终端进程身份已变化，未发送信号";
          case FOREIGN_UID -> "终端进程 UID 不属于本应用";
          case SCAN_UNAVAILABLE -> "无法核验终端后台进程";
          case SESSION_NOT_EMPTY -> "终端后台命令仍在退出，原环境保持保护；稍后可直接重试";
          case EXIT_TIMEOUT -> "终端后台命令尚未退出，原环境保持保护";
        };
    return new IOException(UiText.text(message), failure);
  }

  private static final class AndroidPorts implements TerminalExitPolicy.Ports {
    @Override
    public ProcessIdentity read(int pid, int session) throws IOException {
      return TerminalProcessCloser.read(pid, session);
    }

    @Override
    public boolean belongsToSession(int pid, int session) throws IOException {
      return TerminalProcessCloser.belongsToSession(pid, session);
    }

    @Override
    public int uid(int pid) throws IOException {
      try {
        return Os.stat("/proc/" + pid).st_uid;
      } catch (android.system.ErrnoException error) {
        if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) return -1;
        throw new IOException(UiText.text("无法核验终端进程 UID"), error);
      }
    }

    @Override
    public int ownUid() {
      return android.os.Process.myUid();
    }

    @Override
    public int[] pids() throws IOException {
      File[] files = new File("/proc").listFiles();
      if (files == null) throw new IOException(UiText.text("无法核验终端后台进程"));
      ArrayList<Integer> pids = new ArrayList<>();
      for (File file : files) {
        if (!file.getName().matches("[1-9][0-9]*")) continue;
        try {
          int pid = Integer.parseInt(file.getName());
          if (pid > 1) pids.add(pid);
        } catch (NumberFormatException ignored) {
        }
      }
      return pids.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public void signal(int pid, TerminalExitPolicy.Signal signal) throws IOException {
      if (pid <= 1) throw new IOException(UiText.text("终端进程身份无效"));
      int number =
          switch (signal) {
            case STOP -> OsConstants.SIGSTOP;
            case CONTINUE -> OsConstants.SIGCONT;
            case KILL -> OsConstants.SIGKILL;
            case QUIT -> OsConstants.SIGQUIT;
          };
      try {
        Os.kill(pid, number);
      } catch (android.system.ErrnoException error) {
        if (error.errno != OsConstants.ESRCH) throw new IOException(UiText.text("终端进程回收失败"), error);
      }
    }

    @Override
    public long now() {
      return SystemClock.elapsedRealtime();
    }

    @Override
    public void pause(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  private static boolean belongsToSession(int pid, int session) throws IOException {
    int actual = NativeProcess.sessionId(pid);
    if (actual == -OsConstants.ESRCH) return false;
    if (actual < 0) throw new IOException(UiText.format("无法查询终端会话（errno=%s），请稍后重试", -actual));
    return actual == session;
  }

  private static ProcessIdentity read(int pid, int session) throws IOException {
    long deadline = SystemClock.elapsedRealtime() + 500;
    IOException failure = null;
    do {
      try {
        ProcessIdentity current =
            ProcessIdentity.inSession(
                Compat.readAll(new File("/proc/" + pid + "/stat")), pid, session);
        if (current != null) return current;
        failure = new IOException(UiText.text("终端进程身份暂不可读，保留环境保护"));
      } catch (IOException error) {
        failure = error;
      }
      // exec/退出窗口内短读仍是未知；由独立内核会话查询区分 ESRCH 与权限/读取错误。
      int actual = NativeProcess.sessionId(pid);
      if (actual == -OsConstants.ESRCH || actual > 0 && actual != session) return null;
      if (actual < 0)
        throw new IOException(UiText.format("无法查询终端会话（errno=%s），请稍后重试", -actual), failure);
      if (SystemClock.elapsedRealtime() >= deadline) break;
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("TERMINAL_IDENTITY_INTERRUPTED", interrupted);
      }
    } while (true);
    throw new IOException(UiText.text("无法核验终端独立会话，原环境保持保护"), failure);
  }
}
