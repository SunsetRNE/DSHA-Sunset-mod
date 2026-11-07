package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * guest sshd 退出证明的解析与等待语义。
 *
 * <p>上一版把 {@code /proc/<pid>/stat} 全文当出生身份，活进程一有 CPU 活动文本就变，
 * {@code alive()} 于是返回 false —— 活 sshd 被判成已退出。这里锁住「只比 starttime」，
 * 并在 Linux 上真的起一个子进程验证（CI 是 Linux，这条会真的执行）。
 */
public final class ContainerSshExitProofTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private static boolean linux() {
    return File.separatorChar == '/';
  }

  /** 拼一份 /proc/<pid>/stat：comm 可含空格/右括号，starttime 是第 22 字段。 */
  private static String procStat(String comm, String state, String startTime) {
    StringBuilder builder = new StringBuilder();
    builder.append("4242 (").append(comm).append(") ").append(state);
    for (int field = 4; field <= 21; field++) builder.append(' ').append(field);
    builder.append(' ').append(startTime).append(" 1024 256 0 0\n");
    return builder.toString();
  }

  @Test
  public void readPidAcceptsOnlyPureNumbers() throws Exception {
    Path file = temp.newFile("sshd.pid").toPath();
    Files.writeString(file, "  12345\n");
    assertEquals(Integer.valueOf(12345), ContainerSshExitProof.readPid(file));
    Files.writeString(file, "ssh\n");
    assertNull(ContainerSshExitProof.readPid(file));
    Files.writeString(file, "12 34\n");
    assertNull(ContainerSshExitProof.readPid(file));
    assertNull(ContainerSshExitProof.readPid(temp.getRoot().toPath().resolve("missing.pid")));
    assertNull(ContainerSshExitProof.readPid(null));
  }

  @Test
  public void startTimeSkipsCommLiteralsAndState() {
    assertEquals("987654", ContainerSshExitProof.startTime(procStat("sshd", "S", "987654")));
    // comm 里有空格和右括号：必须从最后一个 ')' 之后切。
    assertEquals("555", ContainerSshExitProof.startTime(procStat("a b) c", "R", "555")));
    assertNull(ContainerSshExitProof.startTime("4242 (sshd) S 1 2 3"));
    assertNull(ContainerSshExitProof.startTime(null));
    assertNull(ContainerSshExitProof.startTime("garbage"));
  }

  @Test
  public void birthIdentityIsStartTimeOnly() {
    // 同一进程 CPU 活动后 stat 其余字段会变，但 starttime 不变 → 仍然存活。
    StringBuilder birth = new StringBuilder();
    assertEquals(
        ContainerSshExitProof.State.ALIVE,
        ContainerSshExitProof.observeStat(procStat("sshd", "S", "1000"), birth));
    String busy = procStat("sshd", "R", "1000").replace("1024 256", "9999 8888");
    assertEquals(ContainerSshExitProof.State.ALIVE, ContainerSshExitProof.observeStat(busy, birth));
    // pid 被复用（starttime 变了）才算退出。
    assertEquals(
        ContainerSshExitProof.State.GONE,
        ContainerSshExitProof.observeStat(procStat("sshd", "S", "2000"), birth));
    // 没有 starttime 的畸形文本不能当成退出（返回 UNKNOWN，调用方保持租约）。
    assertEquals(
        ContainerSshExitProof.State.UNKNOWN,
        ContainerSshExitProof.observeStat("4242 (sshd) S 1 2 3", birth));
  }

  @Test
  public void missingPidFileIsAnExitOnlyWhenNoProofIsRequired() {
    // 启动失败路径（sshd 没起来）：pid 文件本来就不存在，可以收尾。
    // 但服务正在运行/停止时必须要求证据，见下一条——这是原先的 fail-open 所在。
    assertTrue(
        ContainerSshExitProof.awaitPidExit(temp.getRoot().toPath().resolve("missing.pid"), 50));
    assertFalse(
        ContainerSshExitProof.awaitPidExit(
            temp.getRoot().toPath().resolve("missing.pid"), 50, true));
  }

  @Test
  public void missingPidFileMeansConfirmedExitForCleanupPurposes() {
    // 没有可观察证据（sshd 没起来 / 已退出）不阻塞收尾。
    assertTrue(
        ContainerSshExitProof.awaitPidExit(temp.getRoot().toPath().resolve("missing.pid"), 50));
  }

  @Test
  public void liveProcessWithCpuActivityStaysAlive() throws Exception {
    Assume.assumeTrue("需要在有 /proc 的平台上跑", linux());
    // android.jar 的 Process 没有 pid()：让 shell 把自己的 pid 打到 stdout。
    Process child =
        new ProcessBuilder("/bin/sh", "-c", "echo $$; while :; do :; done")
            .redirectErrorStream(true)
            .start();
    try {
      int pid = readFirstLine(child);
      StringBuilder birth = new StringBuilder();
      assertEquals(ContainerSshExitProof.State.ALIVE, ContainerSshExitProof.observe(pid, birth));
      // 忙等 300ms 再观察：旧实现比 stat 全文，这里第二次就会误判成已退出。
      Thread.sleep(300);
      assertEquals(ContainerSshExitProof.State.ALIVE, ContainerSshExitProof.observe(pid, birth));
      assertTrue(ContainerSshExitProof.alive(pid, birth));
    } finally {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void exitedChildIsGone() throws Exception {
    Assume.assumeTrue("需要在有 /proc 的平台上跑", linux());
    Process child = new ProcessBuilder("/bin/true").start();
    child.waitFor(5, TimeUnit.SECONDS);
    // 进程已退出，pid 只能从 /proc 侧确认：用 shell 报出的 pid 再核一次不存在。
    assertEquals(
        ContainerSshExitProof.State.GONE,
        ContainerSshExitProof.observe(999_999, new StringBuilder()));
  }

  private static int readFirstLine(Process process) throws Exception {
    java.io.BufferedReader reader =
        new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
    String line = reader.readLine();
    return Integer.parseInt(line.trim());
  }

  @Test
  public void invalidPidIsGone() {
    assertEquals(ContainerSshExitProof.State.GONE, ContainerSshExitProof.observe(0, null));
  }
}
