package com.deepseekharness.app.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 执行链本身的行为：成功、超时、输出截断、环境未就绪、命令过长、未授权。
 *
 * <p>真实容器跑不了单测，所以启动器可注入：{@code ModuleExec.setLauncher} 换成一个假进程，
 * 三条收尾路径（成功 / 超时 / 截断）都能走到，并且能断言"还活着的进程会被强杀"。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ModuleExecTest {

  private static final String CARD = "exec.probe";
  private static final String DIGEST = "digest-1";

  private Context context;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    ModuleExec.setAllowed(context, CARD, DIGEST);
  }

  @After
  public void tearDown() {
    ModuleExec.setLauncher(null);
    ModuleExec.revoke(context, CARD);
  }

  private static final class FakeProcess extends Process {
    private final byte[] output;
    private final int exit;
    private final boolean hang;
    volatile boolean killed;

    FakeProcess(String output, int exit, boolean hang) {
      this.output = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      this.exit = exit;
      this.hang = hang;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(output);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exit;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return !hang || killed;
    }

    @Override
    public int exitValue() {
      if (hang && !killed) throw new IllegalThreadStateException("still running");
      return exit;
    }

    @Override
    public void destroy() {
      killed = true;
    }

    @Override
    public Process destroyForcibly() {
      killed = true;
      return this;
    }

    @Override
    public boolean isAlive() {
      return hang && !killed;
    }
  }

  @Test
  public void successfulRunReturnsOutput() {
    ModuleExec.setLauncher((ctx, command) -> new FakeProcess("hello", 0, false));
    ModuleExec.Result result = ModuleExec.run(context, CARD, DIGEST, "echo hello");
    assertTrue(result.output, result.ok);
    assertEquals("hello", result.output);
  }

  @Test
  public void timeoutKillsTheProcessThatIsStillAlive() {
    FakeProcess process = new FakeProcess("", 0, true);
    ModuleExec.setLauncher((ctx, command) -> process);
    ModuleExec.Result result = ModuleExec.run(context, CARD, DIGEST, "sleep 1000");
    assertFalse(result.ok);
    assertTrue(result.output, result.output.contains("超时"));
    // 超时之后进程必须已经被强杀，否则它会挂在后台继续跑。
    assertTrue("超时后进程仍在运行", process.killed);
  }

  @Test
  public void outputOverTheLimitIsTruncatedAndMarked() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 2000; i++) big.append("0123456789");
    ModuleExec.setLauncher((ctx, command) -> new FakeProcess(big.toString(), 0, false));
    ModuleExec.Result result = ModuleExec.run(context, CARD, DIGEST, "seq 1 20000");
    assertTrue(result.ok);
    assertTrue(result.output, result.output.endsWith("…"));
    assertTrue(result.output.length() <= ModuleExec.MAX_OUTPUT + 1);
  }

  @Test
  public void containerNotReadyIsReportedWithoutRunning() {
    ModuleExec.setLauncher((ctx, command) -> null);
    ModuleExec.Result result = ModuleExec.run(context, CARD, DIGEST, "echo hi");
    assertFalse(result.ok);
    assertTrue(result.output, result.output.contains("还没就绪"));
  }

  @Test
  public void overlongCommandIsRejectedBeforeStarting() {
    ModuleExec.setLauncher(
        (ctx, command) -> {
          throw new AssertionError("超长命令不应该启动进程");
        });
    StringBuilder command = new StringBuilder();
    for (int i = 0; i < 600; i++) command.append('a');
    ModuleExec.Result result = ModuleExec.run(context, CARD, DIGEST, command.toString());
    assertFalse(result.ok);
    assertTrue(result.output, result.output.contains("太长"));
  }

  @Test
  public void ungrantedCallNeverReachesTheLauncher() {
    ModuleExec.setLauncher(
        (ctx, command) -> {
          throw new AssertionError("未授权不应该启动进程");
        });
    ModuleExec.Result result = ModuleExec.run(context, CARD, "other-digest", "echo hi");
    assertFalse(result.ok);
    assertTrue(result.output, result.output.contains("未授权"));
  }
}
