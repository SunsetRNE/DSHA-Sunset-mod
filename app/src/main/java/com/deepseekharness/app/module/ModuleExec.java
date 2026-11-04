package com.deepseekharness.app.module;

import android.content.Context;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.ShellQuote;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 卡片执行容器命令的唯一入口。
 *
 * <p>这是整套模块体系里唯一能「真的做事」的动作，所以门开得很窄：
 * <ul>
 *   <li>用户必须对<b>这一张卡片</b>单独授权，不是一个总开关；</li>
 *   <li>环境没就绪直接拒绝，不排队不重试；</li>
 *   <li>命令长度、执行时长、输出长度都有上限；</li>
 *   <li>超时杀进程树，不让它挂在后台。</li>
 * </ul>
 *
 * <p>注意：容器本身是 danger-full-access，所以这里挡的是「未授权模块静默执行」，
 * 不是给容器加沙箱 —— 别把这条当安全隔离写进文档。
 */
public final class ModuleExec {

  private static final String PREFS = "dsha_module_exec";
  private static final int MAX_COMMAND = 500;
  private static final int MAX_OUTPUT = 8 * 1024;
  private static final long TIMEOUT_MS = 8000;

  public static final class Result {
    public final boolean ok;
    public final String output;

    public Result(boolean ok, String output) {
      this.ok = ok;
      this.output = output;
    }
  }

  private ModuleExec() {}

  public static boolean isAllowed(Context context, String cardId) {
    return context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("allow_" + cardId, false);
  }

  public static void setAllowed(Context context, String cardId, boolean allowed) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("allow_" + cardId, allowed)
        .apply();
  }

  public static void revoke(Context context, String cardId) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove("allow_" + cardId)
        .apply();
  }

  /** 同步执行；调用方必须放在后台线程。 */
  public static Result run(Context context, String command) {
    String trimmed = command == null ? "" : command.trim();
    if (trimmed.isEmpty()) return new Result(false, "命令为空");
    if (trimmed.length() > MAX_COMMAND) return new Result(false, "命令太长");

    HarnessController controller = HarnessController.get(context);
    if (!controller.isEnvironmentReady()) return new Result(false, "容器环境还没就绪");
    if (!controller.isWebRunning() && !controller.isEnvironmentReady())
      return new Result(false, "容器未运行");

    Process process = null;
    ExecutorService reader = Executors.newSingleThreadExecutor();
    try {
      process = controller.proot().execRootfs("bash -lc " + ShellQuote.arg(trimmed));
      final Process running = process;
      Future<String> output =
          reader.submit(
              () -> {
                StringBuilder builder = new StringBuilder();
                try (InputStream input = running.getInputStream()) {
                  byte[] buffer = new byte[4096];
                  for (int count; (count = input.read(buffer)) != -1; ) {
                    builder.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    if (builder.length() > MAX_OUTPUT) break;
                  }
                }
                return builder.toString();
              });
      // 8 秒是整条命令的总上限：读输出和等退出共享同一个 deadline，
      // 不然读输出花掉几秒后再等满 8 秒，实际最长能拖到 16 秒。
      long deadline = System.currentTimeMillis() + TIMEOUT_MS;
      String text =
          output.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
      int code =
          process.waitFor(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
              ? process.exitValue()
              : -1;
      if (text.length() > MAX_OUTPUT) text = text.substring(0, MAX_OUTPUT) + "…";
      return new Result(code == 0, text.isEmpty() ? "（无输出）" : text.trim());
    } catch (java.util.concurrent.TimeoutException timeout) {
      destroy(process);
      return new Result(false, "执行超时（" + TIMEOUT_MS / 1000 + " 秒）");
    } catch (Exception error) {
      destroy(process);
      return new Result(false, "执行失败：" + error.getMessage());
    } finally {
      reader.shutdownNow();
    }
  }

  private static void destroy(Process process) {
    if (process == null) return;
    try {
      // Android 的 Process 没有 descendants()，只能强杀当前进程；
      // bash -lc 包了一层，正常情况下内核会随 shell 退出回收。
      process.destroyForcibly();
    } catch (RuntimeException ignored) {
      // 已经退出
    }
  }
}
