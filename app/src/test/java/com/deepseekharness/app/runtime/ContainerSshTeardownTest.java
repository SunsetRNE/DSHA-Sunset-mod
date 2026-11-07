package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 停止顺序的负控：租约只能在「guest 确认退出」之后交给 watcher。
 *
 * <p>上一版先 {@code retainUntilExit(process)} 再等 sshd pid：watcher 在启动器退出时就
 * close，proroot 下这比 guest 退出早，维护屏障被提前放开。这里用 fake Process + fake 租约
 * 断言两个条件都满足才 retain；未确认时既不能 retain 也不能 close。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ContainerSshTeardownTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  private static boolean linux() {
    return File.separatorChar == '/';
  }

  /** 只记录调用顺序的假租约。 */
  private static final class FakeLease implements RuntimeWorkPort.Work {
    int retains;
    int closes;
    Process retained;

    @Override
    public void retainUntilExit(Process process) {
      retains++;
      retained = process;
    }

    @Override
    public void close() {
      closes++;
    }
  }

  private static final class FakeProcess extends Process {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    int destroyed;

    @Override
    public OutputStream getOutputStream() {
      return out;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      destroyed++;
    }

    @Override
    public Process destroyForcibly() {
      destroyed++;
      return this;
    }
  }

  @Test
  public void guestConfirmedBeforeTheLeaseGoesToTheWatcher() {
    FakeProcess process = new FakeProcess();
    FakeLease lease = new FakeLease();
    Path missingPid = temp.getRoot().toPath().resolve("sshd.pid");

    assertTrue(ContainerSshService.teardownGuestFirst(process, lease, missingPid, 50));
    assertEquals("启动器要被强杀", 1, process.destroyed);
    assertEquals("确认 guest 退出后才交租约", 1, lease.retains);
    assertEquals("交出去之后不能自己 close", 0, lease.closes);
  }

  @Test
  public void unconfirmedGuestKeepsTheLease() throws Exception {
    Assume.assumeTrue("需要有 /proc 才能构造「仍存活」的 pid", linux());
    FakeProcess process = new FakeProcess();
    FakeLease lease = new FakeLease();
    Path pidFile = temp.newFile("sshd.pid").toPath();
    // 子进程必须活到 teardown 执行期间：先起、后杀。
    Process live = new ProcessBuilder("/bin/sh", "-c", "echo $$; exec sleep 30").start();
    try {
      java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(live.getInputStream()));
      java.nio.file.Files.writeString(pidFile, reader.readLine().trim());

      // guest 还活着：150ms 内不可能确认退出。
      assertFalse(ContainerSshService.teardownGuestFirst(process, lease, pidFile, 150));
      assertEquals("未确认时不能 retain（那会提前释放维护屏障）", 0, lease.retains);
      assertEquals("未确认时也不能 close", 0, lease.closes);
      assertTrue("启动器仍应被强杀", process.destroyed >= 1);
    } finally {
      live.destroyForcibly();
      live.waitFor(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void noProcessClosesTheLease() {
    FakeLease lease = new FakeLease();
    assertTrue(ContainerSshService.teardownGuestFirst(null, lease, null, 0));
    assertEquals(1, lease.closes);
    assertEquals(0, lease.retains);
  }

  @Test
  public void exitedChildIsConfirmedWithinTimeout() throws Exception {
    Assume.assumeTrue("需要有 /proc", linux());
    FakeProcess process = new FakeProcess();
    FakeLease lease = new FakeLease();
    Path pidFile = temp.newFile("exited.pid").toPath();
    java.nio.file.Files.writeString(pidFile, "999999");

    assertTrue(ContainerSshService.teardownGuestFirst(process, lease, pidFile, 1_000));
    assertEquals(1, lease.retains);
  }
}
