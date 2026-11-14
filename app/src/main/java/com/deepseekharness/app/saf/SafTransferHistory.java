package com.deepseekharness.app.saf;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.transfer.DownloadTask;
import com.deepseekharness.app.transfer.TransferArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 有界的本机传输记录；不保存 URI、路径、句柄或文件内容，重启后的在途项明确显示中断。 */
public final class SafTransferHistory {
  public static final int MAX_RECORDS = 40;
  private static final Object LOCK = new Object();
  private static final Set<String> ACTIVE = new HashSet<>();
  private final SharedPreferences preferences;
  private final Set<String> active;

  public static final class Record {
    public final String id, name, code;
    public final boolean importing;
    public final long time, bytes;
    public final DownloadTask.Status status;

    private Record(
        String id,
        String name,
        boolean importing,
        long time,
        long bytes,
        DownloadTask.Status status,
        String code) {
      this.id = id;
      this.name = name;
      this.importing = importing;
      this.time = time;
      this.bytes = bytes;
      this.status = status;
      this.code = code;
    }
  }

  public static SafTransferHistory open(Context context) {
    return new SafTransferHistory(
        context.getSharedPreferences("saf_workbench_transfers", Context.MODE_PRIVATE), ACTIVE);
  }

  SafTransferHistory(SharedPreferences preferences, Set<String> active) {
    this.preferences = preferences;
    this.active = active;
  }

  /** 持久化失败时不开始写入；最多保留最近 40 项，活跃项不被轮换。 */
  public String begin(String name, boolean importing, long time) {
    if (!TransferArtifact.safeName(name)) throw new IllegalArgumentException("BAD_NAME");
    synchronized (LOCK) {
      List<Record> records = listLocked();
      if (records.size() >= MAX_RECORDS) {
        Record oldest = null;
        for (int index = records.size() - 1; index >= 0; index--)
          if (!active.contains(records.get(index).id)) {
            oldest = records.remove(index);
            break;
          }
        if (oldest == null) throw new IllegalStateException("HISTORY_FULL");
      }
      String id = UUID.randomUUID().toString();
      records.add(
          new Record(
              id, name, importing, Math.max(0, time), 0, DownloadTask.Status.DOWNLOADING, ""));
      if (!saveLocked(records)) throw new IllegalStateException("HISTORY_UNAVAILABLE");
      active.add(id);
      return id;
    }
  }

  public boolean finish(String id, long bytes, String code) {
    synchronized (LOCK) {
      List<Record> records = listLocked();
      boolean found = false;
      for (int index = 0; index < records.size(); index++) {
        Record record = records.get(index);
        if (!record.id.equals(id)) continue;
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "IO_ERROR";
        boolean ok = "OK".equals(safeCode);
        records.set(
            index,
            new Record(
                record.id,
                record.name,
                record.importing,
                record.time,
                ok ? Math.max(0, bytes) : 0,
                ok ? DownloadTask.Status.COMPLETE : DownloadTask.Status.FAILED,
                ok ? "" : safeCode));
        found = true;
        break;
      }
      boolean saved = found && saveLocked(records);
      active.remove(id);
      return saved;
    }
  }

  public List<Record> list() {
    synchronized (LOCK) {
      return List.copyOf(listLocked());
    }
  }

  private List<Record> listLocked() {
    List<Record> records = new ArrayList<>();
    Set<String> ids;
    try {
      ids = preferences.getStringSet("records", Set.of());
    } catch (RuntimeException bad) {
      return records;
    }
    for (String id : ids) {
      if (id == null || !id.matches("[0-9a-f-]{36}")) continue;
      try {
        String name = preferences.getString(id + ":name", "");
        if (!TransferArtifact.safeName(name)) continue;
        DownloadTask.Status status =
            DownloadTask.Status.valueOf(preferences.getString(id + ":status", "FAILED"));
        String code = preferences.getString(id + ":code", "");
        if (status != DownloadTask.Status.COMPLETE && status != DownloadTask.Status.FAILED) {
          if (active.contains(id)) status = DownloadTask.Status.DOWNLOADING;
          else {
            status = DownloadTask.Status.FAILED;
            code = "INTERRUPTED";
          }
        }
        if (!code.isEmpty() && !code.matches("[A-Z][A-Z0-9_]{0,63}")) code = "IO_ERROR";
        records.add(
            new Record(
                id,
                name,
                preferences.getBoolean(id + ":import", true),
                Math.max(0, preferences.getLong(id + ":time", 0)),
                Math.max(0, preferences.getLong(id + ":bytes", 0)),
                status,
                code));
      } catch (RuntimeException bad) {
        // 损坏的记录只跳过，不把任意外部字符串当错误文案展示。
      }
    }
    records.sort(
        Comparator.comparingLong((Record record) -> record.time)
            .reversed()
            .thenComparing(record -> record.id));
    if (records.size() > MAX_RECORDS) records = new ArrayList<>(records.subList(0, MAX_RECORDS));
    return records;
  }

  private boolean saveLocked(List<Record> records) {
    try {
      SharedPreferences.Editor editor = preferences.edit();
      Set<String> next = new LinkedHashSet<>();
      for (Record record : records) {
        next.add(record.id);
        editor
            .putString(record.id + ":name", record.name)
            .putBoolean(record.id + ":import", record.importing)
            .putLong(record.id + ":time", record.time)
            .putLong(record.id + ":bytes", record.bytes)
            .putString(record.id + ":status", record.status.name())
            .putString(record.id + ":code", record.code);
      }
      for (String previous : preferences.getStringSet("records", Set.of())) {
        if (next.contains(previous)) continue;
        for (String field : List.of(":name", ":import", ":time", ":bytes", ":status", ":code"))
          editor.remove(previous + field);
      }
      return editor.putStringSet("records", next).commit();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
