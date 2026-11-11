package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.RebuildDiagnosisText;
import java.io.File;

/**
 * 把环境重建失败落成一条可检索的事实行：稳定错误码、errno、已读字节、事务目录。
 *
 * <p>界面上的失败说明只保留压缩后的一句，原始 errno 与字节进度在拼接时被丢掉；这里在失败现场采集，
 * 供维护页的诊断报告读取。只写操作记录，不改事务状态、不触发重建、不移动任何原件。
 */
public final class RebuildFailureRecord {
  private RebuildFailureRecord() {}

  /** 只保留最近的失败行，避免反复失败把记录撑大。 */
  public static final int MAX_CHAIN = 6;

  /**
   * 失败事实：{@code code=... errno=... read=... total=... dir=...}。
   *
   * <p>键值之间用空格分隔且值不含空格，保证 {@link RebuildDiagnosisText#keyValues} 能逐项解析；
   * 自由文本只放在行尾，不参与解析。
   */
  public static String describe(
      Throwable failure, File directory, String substage, long read, long total) {
    String code = codeOf(failure);
    String errno = errnoOf(failure);
    StringBuilder line = new StringBuilder("code=").append(value(code));
    line.append(" errno=").append(value(errno));
    if (read >= 0) line.append(" read=").append(read);
    if (total > 0) line.append(" total=").append(total);
    if (directory != null) line.append(" dir=").append(value(directory.getAbsolutePath()));
    if (substage != null && !substage.isEmpty())
      line.append(" | ").append(RebuildDiagnosisText.clip(substage, 80));
    String detail = messageOf(failure);
    if (!detail.isEmpty()) line.append(" | ").append(detail);
    return RebuildDiagnosisText.clip(line.toString(), 800);
  }

  /** 失败现场采集；任何异常都吞掉，诊断记录不能反过来让维护失败。 */
  public static void record(
      Context context, Throwable failure, File directory, String substage, long read, long total) {
    if (context == null || failure == null) return;
    try {
      DiagnosticLog.record(
          context, "REBUILD_FAIL", describe(failure, directory, substage, read, total));
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  /** 记录解压子阶段进入；重建只在维护锁内发生，写记录不影响事务。 */
  public static void stage(Context context, String substage) {
    if (context == null || substage == null || substage.isEmpty()) return;
    try {
      DiagnosticLog.record(context, "REBUILD_STAGE", RebuildDiagnosisText.clip(substage, 120));
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  /** 开始记录：候选身份与可用空间，用于区分「从没开始」和「中途失败」。 */
  public static void begin(Context context, String candidate, File rootfs, long available) {
    if (context == null) return;
    try {
      DiagnosticLog.record(
          context,
          "REBUILD_BEGIN",
          "candidate="
              + value(candidate)
              + " available="
              + available
              + " rootfsExists="
              + (rootfs != null && rootfs.exists()));
    } catch (RuntimeException | LinkageError ignored) {
    }
  }

  /** 稳定错误码优先取链上第一个全大写码；没有就用最外层异常类名。 */
  static String codeOf(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CHAIN; depth++) {
      String message = current.getMessage();
      if (message != null) {
        String trimmed = message.trim();
        if (trimmed.matches("[A-Z][A-Z0-9_]{2,99}")) return trimmed;
        // 兼容「ENVIRONMENT_INSTALL_INCOMPLETE: ...」这类带后缀的写法。
        String head = trimmed.split("[\\s:]", 2)[0];
        if (head.matches("[A-Z][A-Z0-9_]{2,99}")) return head;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return failure.getClass().getSimpleName();
  }

  /** errno 只认系统 strerror 的已知文本；认不出返回空串，不猜数字。 */
  static String errnoOf(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < MAX_CHAIN; depth++) {
      String errno = RebuildDiagnosisText.errnoOf(current.getMessage());
      if (!errno.isEmpty()) return errno;
      current = current.getCause() == current ? null : current.getCause();
    }
    return "";
  }

  /** 异常原文：只保留最外层一句，去掉换行并限长，避免报告被日志正文淹没。 */
  static String messageOf(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.trim().isEmpty()) return failure.getClass().getSimpleName();
    return RebuildDiagnosisText.clip(message, 240);
  }

  private static String value(String text) {
    String clipped = RebuildDiagnosisText.clip(text, 200);
    return clipped.isEmpty() ? "-" : clipped;
  }
}
