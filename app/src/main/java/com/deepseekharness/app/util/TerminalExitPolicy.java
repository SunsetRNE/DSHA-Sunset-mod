package com.deepseekharness.app.util;

import java.io.IOException;

/** 本次独立终端会话的退出判据；平台只提供观察/信号，不据裸 PID 或一次快照宣布退出。 */
public final class TerminalExitPolicy {
  private static final long POLL_MILLIS = 20;

  public enum Signal {
    STOP,
    CONTINUE,
    KILL,
    QUIT
  }

  public enum Code {
    INVALID_IDENTITY,
    IDENTITY_CHANGED,
    FOREIGN_UID,
    SCAN_UNAVAILABLE,
    SESSION_NOT_EMPTY,
    EXIT_TIMEOUT
  }

  public static final class Failure extends IOException {
    private final Code code;

    private Failure(Code code) {
      super("TERMINAL_" + code);
      this.code = code;
    }

    public Code code() {
      return code;
    }
  }

  public interface Ports {
    /** null 只能表示已消失/已离开指定会话；拒读、截断与未知必须抛出。 */
    ProcessIdentity read(int pid, int session) throws IOException;

    boolean belongsToSession(int pid, int session) throws IOException;

    /** 进程已消失返回 -1；其余未知抛出，不能发明 UID。 */
    int uid(int pid) throws IOException;

    int ownUid();

    int[] pids() throws IOException;

    /** 只接收本策略已经复核的正 PID；ESRCH 可返回，其余失败必须抛出。 */
    void signal(int pid, Signal signal) throws IOException;

    long now();

    void pause(long millis) throws InterruptedException;
  }

  private TerminalExitPolicy() {}

  /** proot 的 QUIT 只是一条请求；调用方仍须等待 launcher 并确认整个会话为空。 */
  public static void requestProotExit(ProcessIdentity expected, Ports ports) throws IOException {
    requireIdentity(expected);
    ProcessIdentity leader = ports.read(expected.pid, expected.pid);
    if (leader == null || leader.exited()) return;
    verifyLeader(expected, leader, ports);
    send(leader, Signal.QUIT, ports);
  }

  /**
   * 保留组长直到两次独立扫描都未见 guest，然后结束组长并等待本次会话为空。
   *
   * <p>有限扫描不是任意 daemon 的原子退出证明；后续观察到残留、读取失败或预算耗尽时抛出，
   * 上层继续保留标签与工作锁。组长已消失时只确认空会话，不按旧 SID 回收未知后代。
   */
  public static void close(ProcessIdentity expected, long timeoutMs, Ports ports)
      throws IOException, InterruptedException {
    requireIdentity(expected);
    long started = ports.now();
    if (timeoutMs <= 0) throw new Failure(Code.EXIT_TIMEOUT);
    ProcessIdentity leader = ports.read(expected.pid, expected.pid);
    if (leader == null || leader.exited()) {
      requireSessionEmpty(expected.pid, ports);
      return;
    }
    verifyLeader(expected, leader, ports);
    boolean paused = false, launcherSignalled = false;
    try {
      checkBudget(started, timeoutMs, ports);
      paused = send(leader, Signal.STOP, ports);
      if (!paused) {
        requireSessionEmpty(expected.pid, ports);
        return;
      }
      int emptyScans = 0;
      while (emptyScans < 2) {
        checkInterrupted();
        checkBudget(started, timeoutMs, ports);
        verifyLeader(expected, ports.read(expected.pid, expected.pid), ports);
        boolean active = false;
        for (int pid : snapshot(ports)) {
          if (pid == expected.pid || !ports.belongsToSession(pid, expected.pid)) continue;
          ProcessIdentity member = ports.read(pid, expected.pid);
          if (member == null || member.exited()) continue;
          if (!requireOwnUid(member.pid, ports)) continue;
          active = true;
          checkInterrupted();
          checkBudget(started, timeoutMs, ports);
          verifyLeader(expected, ports.read(expected.pid, expected.pid), ports);
          send(member, Signal.KILL, ports);
        }
        emptyScans = active ? 0 : emptyScans + 1;
        if (emptyScans < 2) pause(started, timeoutMs, ports);
      }
      checkBudget(started, timeoutMs, ports);
      verifyLeader(expected, ports.read(expected.pid, expected.pid), ports);
      send(leader, Signal.KILL, ports);
      launcherSignalled = true;
      // KILL 已发送仍不是退出证明；延迟退出或扫描中新出现的后代必须继续阻塞。
      int settledScans = 0;
      while (settledScans < 2) {
        checkInterrupted();
        settledScans = hasMembers(expected.pid, ports) ? 0 : settledScans + 1;
        if (settledScans < 2) pause(started, timeoutMs, ports);
      }
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (paused && !launcherSignalled) {
        try {
          send(leader, Signal.CONTINUE, ports);
        } catch (IOException | RuntimeException resume) {
          failure.addSuppressed(resume);
        }
      }
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw failure;
    }
  }

  public static void requireSessionEmpty(int session, Ports ports) throws IOException {
    if (session <= 1) throw new Failure(Code.INVALID_IDENTITY);
    if (hasMembers(session, ports) || hasMembers(session, ports))
      throw new Failure(Code.SESSION_NOT_EMPTY);
  }

  private static boolean hasMembers(int session, Ports ports) throws IOException {
    for (int pid : snapshot(ports)) {
      if (!ports.belongsToSession(pid, session)) continue;
      ProcessIdentity member = ports.read(pid, session);
      if (member != null && !member.exited()) return true;
    }
    return false;
  }

  private static int[] snapshot(Ports ports) throws IOException {
    int[] values = ports.pids();
    if (values == null) throw new Failure(Code.SCAN_UNAVAILABLE);
    for (int pid : values) if (pid <= 1) throw new Failure(Code.SCAN_UNAVAILABLE);
    return values;
  }

  private static void requireIdentity(ProcessIdentity expected) throws IOException {
    if (expected == null || expected.pid <= 1 || !expected.ownsSession() || expected.exited())
      throw new Failure(Code.INVALID_IDENTITY);
  }

  private static void verifyLeader(ProcessIdentity expected, ProcessIdentity current, Ports ports)
      throws IOException {
    if (current == null
        || current.exited()
        || !expected.sameProcess(current)
        || !current.ownsSession()) throw new Failure(Code.IDENTITY_CHANGED);
    if (!requireOwnUid(current.pid, ports)) throw new Failure(Code.IDENTITY_CHANGED);
  }

  /** 捕获后的第二次内核身份/UID 检查；失败不进入平台信号口。 */
  private static boolean send(ProcessIdentity expected, Signal signal, Ports ports)
      throws IOException {
    ProcessIdentity current = ports.read(expected.pid, expected.session);
    if (current == null || current.exited()) return false;
    if (!expected.sameProcess(current) || current.session != expected.session)
      throw new Failure(Code.IDENTITY_CHANGED);
    if (!requireOwnUid(current.pid, ports)) return false;
    ports.signal(expected.pid, signal);
    return true;
  }

  private static boolean requireOwnUid(int pid, Ports ports) throws IOException {
    int uid = ports.uid(pid);
    if (uid == -1) return false;
    if (ports.ownUid() < 0 || uid != ports.ownUid()) throw new Failure(Code.FOREIGN_UID);
    return true;
  }

  private static long remaining(long started, long budget, Ports ports) throws IOException {
    long elapsed = ports.now() - started;
    if (elapsed < 0 || elapsed >= budget) throw new Failure(Code.EXIT_TIMEOUT);
    return budget - elapsed;
  }

  private static void checkBudget(long started, long budget, Ports ports) throws IOException {
    remaining(started, budget, ports);
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedException("TERMINAL_EXIT_INTERRUPTED");
  }

  private static void pause(long started, long budget, Ports ports)
      throws IOException, InterruptedException {
    checkInterrupted();
    ports.pause(Math.min(POLL_MILLIS, remaining(started, budget, ports)));
  }
}
