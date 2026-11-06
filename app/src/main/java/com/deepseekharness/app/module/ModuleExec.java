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

  /**
   * 授权与<b>内容摘要</b>绑定，不是与卡片 id 绑定。
   *
   * <p>同一 id 的卡片会被自动更新（容器投放、重新导入），只按 id 记授权的话，
   * 用户批准过的旧脚本被替换成新脚本后仍然放行 —— 等于用户零感知执行了没同意过的内容。
   * 所以这里存的是「这次批准时的 {@link ModuleCardStore#contentDigest}」，内容一变
   * 摘要就变，旧授权自动失效，必须重新确认。
   */
  public static boolean isAllowed(Context context, String cardId, String digest) {
    if (cardId == null || digest == null || digest.isEmpty()) return false;
    Object stored =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().get("allow_" + cardId);
    // 旧版本存的是 boolean 总开关，无法判断它批准的是哪一版内容 → 视为未授权并清掉。
    if (!(stored instanceof String)) {
      if (stored != null) revoke(context, cardId);
      return false;
    }
    return digest.equals(stored);
  }

  /** 记录一次针对当前内容摘要的授权。 */
  public static void setAllowed(Context context, String cardId, String digest) {
    if (cardId == null || digest == null || digest.isEmpty()) return;
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("allow_" + cardId, digest)
        .apply();
  }

  public static void revoke(Context context, String cardId) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove("allow_" + cardId)
        .apply();
  }

  /**
   * 执行入口：先以「当前内容摘要」校验授权，再真正执行。
   *
   * <p>门放在这里而不是只放调用方，是因为执行是整套模块体系里唯一能真的做事的地方；
   * 任何新增调用点（脚本、UI、未来的能力总线）都必须经过同一道检查。
   */
  public static Result run(Context context, String cardId, String digest, String command) {
    if (!isAllowed(context, cardId, digest)) return new Result(false, "未授权：这张卡片没有当前内容的执行授权");
    return execute(context, command);
  }

  /** 同步执行；调用方必须放在后台线程，且必须已经过 {@link #run(Context,String,String,String)}。 */
  private static Result execute(Context context, String command) {
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
