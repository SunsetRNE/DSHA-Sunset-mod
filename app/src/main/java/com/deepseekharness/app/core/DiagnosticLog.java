package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.ColdInstallPackages;
import com.deepseekharness.app.util.DiagnosticOperationSummary;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 不主动读取业务正文；自由格式操作错误仍可能引用业务内容，导出前须检查。 */
public final class DiagnosticLog {
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT);
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
  private static final ScheduledExecutorService WORKER =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "diagnostic-history");
            thread.setDaemon(true);
            return thread;
          });
  private static Context owner;
  private static DiagnosticHistory.Writer writer;

  private DiagnosticLog() {}

  public static void record(Context context, String stage, String message) {
    try {
      writer(context).add(line(stage, message));
    } catch (RuntimeException ignored) {
      // 诊断不能阻断记录它的操作。
    }
  }

  static String line(String stage, String message) {
    String value = String.valueOf(message);
    String safe =
        value.length() > 16 * 1024
            ? "[DIAGNOSTIC_EVENT_OVERSIZED]"
            : CONTROL.matcher(DiagnosticOperationSummary.safe(value)).replaceAll(" ");
    if (safe.length() > 800) {
      int end = Character.isHighSurrogate(safe.charAt(799)) ? 799 : 800;
      safe = safe.substring(0, end) + "…";
    }
    String label = CONTROL.matcher(stage).replaceAll(" ").replace(']', ' ').trim();
    if (label.isEmpty()) label = "OPERATION";
    if (label.length() > 80) label = label.substring(0, 80);
    return TIMESTAMP.format(LocalDateTime.now()) + " [" + label + "] " + safe + "\n";
  }

  private static synchronized DiagnosticHistory.Writer writer(Context context) {
    Context app = context.getApplicationContext();
    if (writer != null && owner == app) return writer;
    owner = app;
    writer =
        new DiagnosticHistory.Writer(
            WORKER,
            new DiagnosticHistory.Store() {
              private final BackupFileSystem fs = new AndroidBackupFileSystem();

              private File directory() throws IOException {
                return ColdInstallPackages.privateFiles(
                    fs, new File(app.getApplicationInfo().dataDir), app.getFilesDir());
              }

              @Override
              public String read() throws IOException {
                return DiagnosticHistory.read(fs, directory());
              }

              @Override
              public void publish(String value) throws IOException {
                DiagnosticHistory.publish(fs, directory(), value);
              }

              @Override
              public void syncPublished() throws IOException {
                fs.syncDirectory(directory());
              }
            });
    return writer;
  }

  public static String read(Context context) {
    try {
      String value = readExportTail(context);
      return value.isEmpty()
          ? com.deepseekharness.app.util.UiText.text("暂无失败或操作记录\n")
          : com.deepseekharness.app.util.LogCompactor.compact(value, 8000);
    } catch (java.io.IOException | RuntimeException error) {
      return com.deepseekharness.app.util.UiText.text("无法读取操作记录\n");
    }
  }

  /** Bounded original operation records, without display-only clue/context duplication. */
  public static String readExportTail(Context context) throws IOException {
    return DiagnosticOperationSummary.history(writer(context).read());
  }

  static String exportTail(BackupFileSystem fs, File folder) throws IOException {
    return DiagnosticOperationSummary.history(DiagnosticHistory.read(fs, folder));
  }

  public static void installCrashHandler(Context context) {
    Context app = context.getApplicationContext();
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          try {
            StringBuilder summary = new StringBuilder(error.getClass().getName());
            StackTraceElement[] trace = error.getStackTrace();
            for (int i = 0; i < Math.min(12, trace.length); i++)
              summary.append("\n at ").append(trace[i]);
            DiagnosticHistory.Writer current = writer(app);
            current.add(line("CRASH", summary.toString()));
            // 崩溃处理留一个有时限的落盘机会，常规调用不等待 I/O。
            WORKER.submit(current::flush).get(450, TimeUnit.MILLISECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (Exception ignored) {
          } finally {
            if (previous != null) previous.uncaughtException(thread, error);
            else {
              android.os.Process.killProcess(android.os.Process.myPid());
              System.exit(10);
            }
          }
        });
  }
}
