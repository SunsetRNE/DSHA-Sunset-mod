package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * guest sshd 退出证明的解析与等待语义。/proc 只在 Linux 存在，
 * Windows 开发机上的「视为已退出」由 assumeTrue 分支锁定。
 */
public final class ContainerSshExitProofTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

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
  public void missingPidFileMeansConfirmedExitForCleanupPurposes() {
    // 没有可观察证据（sshd 没起来 / 已退出）不阻塞收尾。
    assertTrue(
        ContainerSshExitProof.awaitPidExit(temp.getRoot().toPath().resolve("missing.pid"), 50));
  }

  @Test
  public void aliveIsFalseOnPlatformsWithoutProc() {
    // Windows 开发机没有 /proc：视为已退出，等待立即结束。
    org.junit.Assume.assumeFalse(java.io.File.separatorChar == '/');
    assertFalse(ContainerSshExitProof.alive(1, new StringBuilder()));
    assertTrue(ContainerSshExitProof.awaitPidExit(writePid(1), 100));
  }

  private Path writePid(int pid) {
    try {
      Path file = temp.newFile("pid-" + pid + ".pid").toPath();
      Files.writeString(file, String.valueOf(pid));
      return file;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
