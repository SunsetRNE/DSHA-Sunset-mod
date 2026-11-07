package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * guest sshd 退出证明：读 HOME/sshd.pid 并轮询 /proc，出生身份字段一并核对。
 *
 * <p>proot/proroot 的 {@code destroyForcibly()} 只作用于启动器，容器内 sshd 可能
 * 变孤儿继续监听。维护屏障必须等「脚本自己记录的 pid + 启动时刻 birth 身份」都
 * 消失才算数；确认不了就保持未确认态，禁止按端口/进程名猜杀。
 */
public final class ContainerSshExitProof {
  private ContainerSshExitProof() {}

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
   * 进程是否仍存活：读 /proc/{@code pid}/stat 的 state 字段并记录 birth 身份。
   * 第一次看到该 pid 时记录 stat 全文，之后必须逐字一致——内核 pid 复用会让
   * “存在”不再等价于“同一个进程”。非 Linux（含 Windows 开发机）一律视为已退出。
   */
  public static boolean alive(int pid, StringBuilder birthIdentity) {
    Path stat = Path.of("/proc", String.valueOf(pid), "stat");
    String content;
    try {
      content = Files.readString(stat, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
    if (birthIdentity != null) {
      if (birthIdentity.length() == 0) birthIdentity.append(content);
      else if (!content.equals(birthIdentity.toString())) return false;
    }
    return true;
  }

  /**
   * 等待脚本记录的 sshd pid 退出。返回 true 表示确认退出（或从未见过该 pid /
   * 平台无法观察）；false 表示超时仍未确认——调用方必须保持维护屏障与通知，
   * 不得改写为已停止。
   */
  public static boolean awaitPidExit(Path pidFile, long timeoutMs) {
    Integer pid = readPid(pidFile);
    if (pid == null) return true; // 没有可观察证据时不阻塞收尾（sshd 可能没起来）。
    StringBuilder birth = new StringBuilder();
    long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
    while (System.currentTimeMillis() < deadline) {
      if (!alive(pid, birth)) return true;
      try {
        Thread.sleep(200);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !alive(pid, birth);
  }
}
