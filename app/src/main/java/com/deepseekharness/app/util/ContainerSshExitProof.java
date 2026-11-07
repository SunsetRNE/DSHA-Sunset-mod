package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * guest sshd 退出证明：读 HOME/sshd.pid 并轮询 /proc，出生身份只取 starttime。
 *
 * <p>proot/proroot 的 {@code destroyForcibly()} 只作用于启动器，容器内 sshd 可能
 * 变孤儿继续监听。维护屏障必须等「脚本自己记录的 pid + 启动时刻」都消失才算数；
 * 确认不了就保持未确认态，禁止按端口/进程名猜杀。
 *
 * <p>出生身份不能拿 {@code /proc/<pid>/stat} 全文：那份文本含 utime/stime/num_threads/rss
 * 等随时间变化的字段，正常会话一有 CPU 活动就会被判成「不是同一个进程」→ 活进程被当成
 * 已退出（fail-open）。这里只取 pid + 第 22 字段 starttime；读不到 /proc 时返回
 * {@link State#UNKNOWN}，调用方必须继续持有租约，不能把「读不到」当「已退出」。
 */
public final class ContainerSshExitProof {
  private ContainerSshExitProof() {}

  /** 观察结果：只有 {@link #GONE} 能作为「已退出」的证据。 */
  public enum State {
    ALIVE,
    GONE,
    UNKNOWN
  }

  /** 解析 pid 文件；内容不是纯数字或缺失返回 null。 */
  public static Integer readPid(Path pidFile) {
    if (pidFile == null) return null;
    String text;
    try {
      text = new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim();
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
    if (!text.matches("[0-9]{1,9}")) return null;
    try {
      int pid = Integer.parseInt(text);
      return pid > 0 ? pid : null;
    } catch (NumberFormatException unreadable) {
      return null;
    }
  }

  /**
   * 从 {@code /proc/<pid>/stat} 取 starttime（第 22 字段）。
   *
   * <p>第 2 字段 comm 用括号包住且可能含空格与右括号，所以先找最后一个 {@code ')'}，
   * 再从它后面按空格切；starttime 是第 22 字段，去掉 pid/comm/state 后落在下标 19。
   */
  static String startTime(String stat) {
    if (stat == null) return null;
    int close = stat.lastIndexOf(')');
    if (close < 0 || close + 2 >= stat.length()) return null;
    String[] rest = stat.substring(close + 2).trim().split("\\s+");
    if (rest.length < 20) return null;
    String value = rest[19];
    return value.matches("[0-9]{1,20}") ? value : null;
  }

  /**
   * 观察进程状态并记住出生身份。
   *
   * <p>{@code birthIdentity} 第一次为空时写入 starttime，之后每次比对；不一致说明 pid 被
   * 复用，按已退出处理。
   */
  public static State observe(int pid, StringBuilder birthIdentity) {
    if (pid <= 0) return State.GONE;
    Path stat = Path.of("/proc", String.valueOf(pid), "stat");
    String content;
    try {
      content = Files.readString(stat, StandardCharsets.UTF_8);
    } catch (NoSuchFileException gone) {
      return State.GONE;
    } catch (IOException | RuntimeException unreadable) {
      // 权限/瞬时错误：没有证据，不能当成已退出。
      return State.UNKNOWN;
    }
    return observeStat(content, birthIdentity);
  }

  /** 纯判定：给定一份 stat 文本与已记录的出生身份，判断是不是同一个活进程。 */
  static State observeStat(String content, StringBuilder birthIdentity) {
    String start = startTime(content);
    if (start == null) return State.UNKNOWN;
    if (birthIdentity == null) return State.ALIVE;
    if (birthIdentity.length() == 0) {
      birthIdentity.append(start);
      return State.ALIVE;
    }
    return start.contentEquals(birthIdentity) ? State.ALIVE : State.GONE;
  }

  /** 便捷判断：只有明确存活才返回 true（UNKNOWN 既不算存活，也不算退出）。 */
  public static boolean alive(int pid, StringBuilder birthIdentity) {
    return observe(pid, birthIdentity) == State.ALIVE;
  }

  /**
   * 等待脚本记录的 sshd pid 退出。返回 true 表示确认退出（或从未见过该 pid）；
   * false 表示超时仍未确认——调用方必须保持维护屏障与租约，不得改写为已停止。
   *
   * <p>{@link State#UNKNOWN} 不算退出：继续等，直到超时后如实返回未确认。
   */
  public static boolean awaitPidExit(Path pidFile, long timeoutMs) {
    Integer pid = readPid(pidFile);
    if (pid == null) return true; // 没有可观察证据时不阻塞收尾（sshd 可能没起来）。
    StringBuilder birth = new StringBuilder();
    long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
    while (true) {
      if (observe(pid, birth) == State.GONE) return true;
      if (System.currentTimeMillis() >= deadline) return false;
      try {
        Thread.sleep(200);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }
}
