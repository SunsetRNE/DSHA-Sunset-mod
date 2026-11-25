package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 有界应用事件记录；沿用安全文件层的完整发布与中断恢复。 */
public final class DiagnosticHistory {
  static final int MAX_BYTES = 32 * 1024;
  private static final String NAME = "diagnostic-events.txt";

  private DiagnosticHistory() {}

  public static String append(String previous, String line) {
    if (line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      line = "[DIAGNOSTIC_EVENT_OVERSIZED]\n";
    byte[] value = (previous + line).getBytes(StandardCharsets.UTF_8);
    if (value.length <= MAX_BYTES) return new String(value, StandardCharsets.UTF_8);
    int start = value.length - MAX_BYTES;
    // Advance to a complete line, which also guarantees a UTF-8 code point boundary.
    while (start < value.length && value[start - 1] != '\n') start++;
    return new String(value, start, value.length - start, StandardCharsets.UTF_8);
  }

  public static String read(BackupFileSystem fs, File directory) throws IOException {
    File file = fs.child(directory, NAME);
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) {
      file = fs.child(directory, NAME + ".previous");
      node = fs.stat(file);
      if (node.type.equals("MISSING")) return "";
    }
    if (!node.type.equals("FILE")) throw new IOException("DIAGNOSTIC_TARGET_TYPE");
    return new String(fs.small(file, 64 * 1024), StandardCharsets.UTF_8);
  }

  public static void write(BackupFileSystem fs, File directory, String line) throws IOException {
    publish(fs, directory, append(read(fs, directory), line));
  }

  static void publish(BackupFileSystem fs, File directory, String value) throws IOException {
    fs.atomic(directory, NAME, value.getBytes(StandardCharsets.UTF_8));
  }

  interface Store {
    String read() throws IOException;

    void publish(String value) throws IOException;

    void syncPublished() throws IOException;
  }

  /** 待落盘记录与写入互不持锁。导出读取磁盘和当前待处理尾部，能立即看到已入队的记录。 */
  static final class Writer {
    private record Row(long sequence, String line, int bytes) {}

    private record Attempt(long sequence, String value) {}

    private final ScheduledExecutorService worker;
    private final Store store;
    private final Object io = new Object();
    private final ArrayDeque<Row> pending = new ArrayDeque<>();
    private int bytes;
    private long sequence;
    private long evictedThrough;
    private long publishedThrough;
    private boolean scheduled;
    private Attempt attempted;

    Writer(ScheduledExecutorService worker, Store store) {
      this.worker = worker;
      this.store = store;
    }

    synchronized void add(String line) {
      int length = line.getBytes(StandardCharsets.UTF_8).length;
      if (length > MAX_BYTES) {
        line = "[DIAGNOSTIC_EVENT_OVERSIZED]\n";
        length = line.length();
      }
      pending.addLast(new Row(++sequence, line, length));
      bytes += length;
      while (bytes > MAX_BYTES) {
        Row removed = pending.removeFirst();
        bytes -= removed.bytes();
        evictedThrough = removed.sequence();
      }
      if (!scheduled) schedule(false);
    }

    private void schedule(boolean retry) {
      worker.schedule(() -> writeScheduled(retry), retry ? 1000 : 150, TimeUnit.MILLISECONDS);
      scheduled = true;
    }

    private void writeScheduled(boolean retry) {
      long before;
      synchronized (this) {
        before = sequence;
      }
      boolean success = flush();
      synchronized (this) {
        scheduled = false;
        if (!pending.isEmpty() && (success || !retry || sequence > before))
          schedule(!success && !retry);
      }
    }

    String read() throws IOException {
      synchronized (io) {
        String committed = store.read();
        long apparent = matchesAttempt(committed) ? attempted.sequence() : 0;
        Attempt snapshot = snapshot(committed, apparent);
        return snapshot == null ? committed : snapshot.value();
      }
    }

    boolean flush() {
      synchronized (io) {
        try {
          synchronized (this) {
            if (pending.isEmpty()) return true;
          }
          String committed = store.read();
          if (matchesAttempt(committed)) {
            // rename 后可读的记录仍需同步目录；导出路径只合并读取结果。
            store.syncPublished();
            finish(attempted.sequence());
            attempted = null;
          }
          Attempt snapshot = snapshot(committed, 0);
          if (snapshot == null) return true;
          attempted = snapshot;
          store.publish(snapshot.value());
          finish(snapshot.sequence());
          attempted = null;
          return true;
        } catch (IOException | RuntimeException unavailable) {
          return false;
        }
      }
    }

    private boolean matchesAttempt(String committed) {
      return attempted != null && attempted.value().equals(committed);
    }

    private Attempt snapshot(String committed, long apparent) {
      StringBuilder lines = new StringBuilder();
      long last;
      synchronized (this) {
        if (pending.isEmpty() || pending.getLast().sequence() <= apparent) return null;
        // 新记录已挤出未发布的行时，旧磁盘内容不能跨过断点出现在新尾部之前。
        if (evictedThrough > Math.max(publishedThrough, apparent)) committed = "";
        for (Row row : pending) if (row.sequence() > apparent) lines.append(row.line());
        last = pending.getLast().sequence();
      }
      return new Attempt(last, append(committed, lines.toString()));
    }

    private synchronized void finish(long last) {
      publishedThrough = last;
      while (!pending.isEmpty() && pending.getFirst().sequence() <= last)
        bytes -= pending.removeFirst().bytes();
    }
  }
}
