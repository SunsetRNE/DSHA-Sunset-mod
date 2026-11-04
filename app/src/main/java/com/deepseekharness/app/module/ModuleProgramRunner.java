package com.deepseekharness.app.module;

import android.content.Context;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;

/**
 * 受管程序的运行时入口。
 *
 * <p>执行前重新校验摘要与 ELF ABI，再通过 {@link com.deepseekharness.app.runtime.ProotBootstrap}
 * 把模块目录只读绑定到固定 guest 路径，按固定 argv 运行。程序参数走 shell 转义，模块字符串
 * 永远不直接拼进 shell；结果由 {@link BoundedProcessRunner} 限时限长收口。
 */
public final class ModuleProgramRunner {

  public static final class Result {
    public final boolean ok;
    public final String output;
    public final boolean timedOut;
    public final int exitCode;

    Result(boolean ok, String output, boolean timedOut, int exitCode) {
      this.ok = ok;
      this.output = output == null ? "" : output;
      this.timedOut = timedOut;
      this.exitCode = exitCode;
    }
  }

  private static final String GUEST_ROOT = "/run/dsha-module-program/";

  private ModuleProgramRunner() {}

  /** 同步执行；调用方必须放在后台线程。 */
  public static Result run(Context context, ModuleCard card, ModuleCard.Program program) {
    if (context == null || card == null || program == null) return fail("缺少程序身份");
    if (!ModuleProgramAuth.isAllowed(context, card.id, program.id))
      return fail("未授权：先在模块中心确认运行这个程序");
    if (!card.id.matches("[a-z0-9][a-z0-9._-]{0,47}")) return fail("模块 id 不能用于 guest 路径");
    File file = ModuleCardStore.assetOf(context, card, program.file);
    String validation = ModuleProgram.validate(context, program, file);
    if (validation != null) return fail(validation);
    HarnessController controller = HarnessController.get(context);
    if (!controller.isEnvironmentReady()) return fail("容器环境还没就绪");

    File hostDir = file.getParentFile();
    String guestDir = GUEST_ROOT + card.id;
    String guestFile = guestDir + "/" + program.entry;
    StringBuilder command = new StringBuilder(ShellQuote.arg(guestFile));
    for (String arg : program.args) command.append(' ').append(ShellQuote.arg(arg));
    try {
      BoundedProcessRunner.Result result =
          controller
              .proot()
              .execProgram(
                  command.toString(),
                  Math.max(1, program.timeoutSeconds) * 1000L,
                  hostDir,
                  guestDir);
      String output =
          result.output == null || result.output.trim().isEmpty() ? "（无输出）" : result.output.trim();
      boolean ok = !result.timedOut && result.exitCode == 0;
      if (result.timedOut) output = "执行超时（" + program.timeoutSeconds + " 秒）\n" + output;
      return new Result(ok, output, result.timedOut, result.exitCode);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return fail("程序等待被中断");
    } catch (Exception error) {
      return fail("程序执行失败：" + (error.getMessage() == null ? error.toString() : error.getMessage()));
    }
  }

  private static Result fail(String message) {
    return new Result(false, message, false, -1);
  }
}
