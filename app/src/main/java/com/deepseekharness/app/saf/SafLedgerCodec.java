package com.deepseekharness.app.saf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 授权簿持久化编解码（纯逻辑，不 import Android API）。
 *
 * <p>存储是一个扁平 key/value 命名空间（Android 侧为独立 SharedPreferences），布局：
 *
 * <pre>
 *   schema      = "1"
 *   revision    = 单调递增的十进制
 *   nextEpoch   = 下一个可分配的授权代次
 *   ids         = 记录短 id 的 StringSet
 *   grant.&lt;id&gt;.uri/state/epoch/read/write/userWrite/acquired/takenAt/pendingBefore/pendingIntent
 * </pre>
 *
 * <p><b>坏值回默认</b>：revision/nextEpoch/标志位等标量损坏时按安全默认恢复并给出警告；
 * <b>未知条目保留</b>：不属于本版本字段的 key（例如更新版本写入的字段）原样进入
 * {@link Decoded#preserved}，保存时不会被删除。<b>结构损坏 fail-closed</b>：记录缺 URI、
 * id 与 URI 摘要不符、state 不认识时整组保留但不激活——宁可让用户看到少一条授权，也不能把
 * 一条可能已撤销的授权恢复成可用。
 */
public final class SafLedgerCodec {
  public static final int SCHEMA = 1;

  public static final String PREF_SCHEMA = "schema";
  public static final String PREF_REVISION = "revision";
  public static final String PREF_NEXT_EPOCH = "nextEpoch";
  public static final String PREF_IDS = "ids";
  public static final String PREF_GRANT_PREFIX = "grant.";

  public static final String FIELD_URI = "uri";
  public static final String FIELD_APP = "app";
  public static final String FIELD_LABEL = "label";
  public static final String FIELD_READ = "read";
  public static final String FIELD_WRITE = "write";
  public static final String FIELD_USER_WRITE = "userWrite";
  public static final String FIELD_ACQUIRED = "acquired";
  public static final String FIELD_STATE = "state";
  public static final String FIELD_EPOCH = "epoch";
  public static final String FIELD_TAKEN_AT = "takenAt";
  public static final String FIELD_PENDING_BEFORE = "pendingBefore";
  public static final String FIELD_PENDING_INTENT = "pendingIntent";
  public static final String FIELD_PREVIOUS = "prev";
  public static final String FIELD_PREV_READ = "prevRead";
  public static final String FIELD_PREV_WRITE = "prevWrite";
  public static final String FIELD_PREV_ACQUIRED = "prevAcquired";
  public static final String FIELD_PREV_TAKEN_AT = "prevTakenAt";

  private static final Set<String> TOP_LEVEL =
      Set.of(PREF_SCHEMA, PREF_REVISION, PREF_NEXT_EPOCH, PREF_IDS);
  private static final Set<String> GRANT_FIELDS =
      Set.of(
          FIELD_URI,
          FIELD_APP,
          FIELD_LABEL,
          FIELD_READ,
          FIELD_WRITE,
          FIELD_USER_WRITE,
          FIELD_ACQUIRED,
          FIELD_STATE,
          FIELD_EPOCH,
          FIELD_TAKEN_AT,
          FIELD_PENDING_BEFORE,
          FIELD_PENDING_INTENT,
          FIELD_PREVIOUS,
          FIELD_PREV_READ,
          FIELD_PREV_WRITE,
          FIELD_PREV_ACQUIRED,
          FIELD_PREV_TAKEN_AT);

  private SafLedgerCodec() {}

  /** 解码结果：不是坏数据就抛异常，而是返回空/降级授权簿 + 警告 + 需要原样保留的未知项。 */
  public static final class Decoded {
    public final SafGrantLedger ledger;
    public final Map<String, Object> preserved;
    public final List<String> warnings;

    Decoded(SafGrantLedger ledger, Map<String, Object> preserved, List<String> warnings) {
      this.ledger = ledger;
      this.preserved = Map.copyOf(preserved);
      this.warnings = List.copyOf(warnings);
    }
  }

  /** 从扁平 key/value 快照解码；不修改输入、不抛异常。 */
  public static Decoded decode(Map<String, ?> raw) {
    Map<String, Object> preserved = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    if (raw == null || raw.isEmpty()) {
      return new Decoded(SafGrantLedger.empty(), preserved, warnings);
    }
    String schema = text(raw.get(PREF_SCHEMA));
    if (!Integer.toString(SCHEMA).equals(schema)) {
      // 不认识的版本（或 schema 整段损坏）：整份原样保留，不激活任何记录，等新版本读回。
      preserved.putAll(raw);
      warnings.add("SCHEMA_UNSUPPORTED");
      return new Decoded(SafGrantLedger.empty(), preserved, warnings);
    }

    long revision = 0L;
    Object revisionRaw = raw.get(PREF_REVISION);
    if (revisionRaw != null) {
      Long parsed = optionalNumber(revisionRaw);
      if (parsed == null || parsed < 0) {
        warnings.add("REVISION_DEFAULTED");
      } else {
        revision = parsed;
      }
    }
    long nextEpoch = 0L;
    Object nextEpochRaw = raw.get(PREF_NEXT_EPOCH);
    if (nextEpochRaw != null) {
      Long parsed = optionalNumber(nextEpochRaw);
      if (parsed != null && parsed >= 0) nextEpoch = parsed;
    }

    List<String> ids = readIds(raw, warnings);
    Set<String> acceptedIds = new LinkedHashSet<>();
    Set<String> acceptedUris = new LinkedHashSet<>();
    List<SafGrantRecord> records = new ArrayList<>();
    for (String id : ids) {
      String prefix = PREF_GRANT_PREFIX + id + ".";
      boolean present = false;
      for (String key : raw.keySet()) {
        if (key.startsWith(prefix)) {
          present = true;
          break;
        }
      }
      if (!present) {
        warnings.add("RECORD_MISSING:" + id);
        continue;
      }
      if (records.size() >= SafCapabilityLimits.MAX_TREES) {
        warnings.add("RECORD_LIMIT");
        break;
      }
      SafGrantRecord record = decodeRecord(raw, id, prefix, warnings);
      if (record == null) continue;
      if (!acceptedUris.add(record.uri())) {
        warnings.add("RECORD_DUPLICATE_URI:" + id);
        continue;
      }
      records.add(record);
      acceptedIds.add(id);
    }

    for (Map.Entry<String, ?> entry : raw.entrySet()) {
      String key = entry.getKey();
      if (TOP_LEVEL.contains(key)) continue;
      if (consumedGrantKey(key, acceptedIds)) continue;
      preserved.put(key, entry.getValue());
    }
    SafGrantLedger ledger = SafGrantLedger.of(records, revision, nextEpoch);
    return new Decoded(ledger, preserved, warnings);
  }

  /** 编码为可交给 SharedPreferences 的扁平快照；只写本版本认识的 key。 */
  public static Map<String, Object> encode(SafGrantLedger ledger) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(PREF_SCHEMA, Integer.toString(SCHEMA));
    out.put(PREF_REVISION, Long.toString(ledger.revision()));
    out.put(PREF_NEXT_EPOCH, Long.toString(ledger.nextEpoch()));
    Set<String> ids = new LinkedHashSet<>();
    for (SafGrantRecord record : ledger.records()) {
      ids.add(record.id());
      String prefix = PREF_GRANT_PREFIX + record.id() + ".";
      out.put(prefix + FIELD_URI, record.uri());
      out.put(prefix + FIELD_APP, record.app());
      out.put(prefix + FIELD_LABEL, record.label());
      out.put(prefix + FIELD_READ, record.readable() ? "1" : "0");
      out.put(prefix + FIELD_WRITE, record.writable() ? "1" : "0");
      out.put(prefix + FIELD_USER_WRITE, record.userWritable() ? "1" : "0");
      out.put(prefix + FIELD_ACQUIRED, flags(record.acquired()));
      out.put(prefix + FIELD_STATE, record.state().name());
      out.put(prefix + FIELD_EPOCH, Long.toString(record.epoch()));
      out.put(prefix + FIELD_TAKEN_AT, Long.toString(record.takenAt()));
      out.put(prefix + FIELD_PENDING_BEFORE, Integer.toString(record.pendingBefore()));
      out.put(prefix + FIELD_PENDING_INTENT, Integer.toString(record.pendingIntent()));
      SafGrantRecord.Previous previous = record.previous();
      if (previous != null) {
        out.put(prefix + FIELD_PREVIOUS, "1");
        out.put(prefix + FIELD_PREV_READ, previous.readable() ? "1" : "0");
        out.put(prefix + FIELD_PREV_WRITE, previous.writable() ? "1" : "0");
        out.put(prefix + FIELD_PREV_ACQUIRED, flags(previous.acquired()));
        out.put(prefix + FIELD_PREV_TAKEN_AT, Long.toString(previous.takenAt()));
      }
    }
    out.put(PREF_IDS, ids);
    return out;
  }

  /** 是否为当前版本拥有的 key；保存时只清理这些，未知字段原样保留。 */
  public static boolean knownKey(String key) {
    if (key == null) return false;
    if (TOP_LEVEL.contains(key)) return true;
    if (!key.startsWith(PREF_GRANT_PREFIX)) return false;
    String rest = key.substring(PREF_GRANT_PREFIX.length());
    int separator = rest.indexOf('.');
    if (separator <= 0) return false;
    if (!SafGrantRecord.validId(rest.substring(0, separator))) return false;
    return GRANT_FIELDS.contains(rest.substring(separator + 1));
  }

  private static SafGrantRecord decodeRecord(
      Map<String, ?> raw, String id, String prefix, List<String> warnings) {
    String uri = text(raw.get(prefix + FIELD_URI));
    if (SafCapabilityPolicy.treeUriProblem(uri, null) != SafCapabilityPolicy.TreeUri.OK) {
      warnings.add("RECORD_INVALID_URI:" + id);
      return null;
    }
    if (!id.equals(SafGrantRecord.idOf(uri))) {
      warnings.add("RECORD_ID_MISMATCH:" + id);
      return null;
    }
    SafGrantRecord.State state = state(text(raw.get(prefix + FIELD_STATE)));
    if (state == null) {
      warnings.add("RECORD_INVALID_STATE:" + id);
      return null;
    }
    long epoch = number(raw.get(prefix + FIELD_EPOCH), 0L);
    if (epoch < 0) {
      epoch = 0;
      warnings.add("RECORD_EPOCH_DEFAULTED:" + id);
    }
    boolean readable = bool(raw.get(prefix + FIELD_READ), false);
    boolean writable = bool(raw.get(prefix + FIELD_WRITE), false);
    boolean userWritable = bool(raw.get(prefix + FIELD_USER_WRITE), true);
    int acquired = acquired(raw.get(prefix + FIELD_ACQUIRED));
    long takenAt = Math.max(0, number(raw.get(prefix + FIELD_TAKEN_AT), 0L));
    int pendingBefore = mask(number(raw.get(prefix + FIELD_PENDING_BEFORE), 0L));
    int pendingIntent = mask(number(raw.get(prefix + FIELD_PENDING_INTENT), SafGrantRecord.READ));
    if (pendingIntent == 0) pendingIntent = SafGrantRecord.READ;
    SafGrantRecord.Previous previous = null;
    if (bool(raw.get(prefix + FIELD_PREVIOUS), false)) {
      previous =
          new SafGrantRecord.Previous(
              bool(raw.get(prefix + FIELD_PREV_READ), false),
              bool(raw.get(prefix + FIELD_PREV_WRITE), false),
              acquired(raw.get(prefix + FIELD_PREV_ACQUIRED)),
              Math.max(0, number(raw.get(prefix + FIELD_PREV_TAKEN_AT), 0L)));
    }
    try {
      return new SafGrantRecord(
          id,
          uri,
          text(raw.get(prefix + FIELD_APP)),
          text(raw.get(prefix + FIELD_LABEL)),
          readable,
          writable,
          userWritable,
          acquired,
          state,
          epoch,
          takenAt,
          pendingBefore,
          pendingIntent,
          previous);
    } catch (IllegalArgumentException invalid) {
      // 记录级不变量（id/URI/flags）在该版本里被破坏：整组保留，不激活。
      warnings.add("RECORD_INVALID:" + id);
      return null;
    }
  }

  private static List<String> readIds(Map<String, ?> raw, List<String> warnings) {
    Set<String> indexed = strings(raw.get(PREF_IDS));
    if (indexed == null) {
      warnings.add("IDS_INVALID");
      indexed = scanIds(raw);
      if (!indexed.isEmpty()) warnings.add("IDS_RECOVERED");
    }
    List<String> ids = new ArrayList<>();
    for (String id : new TreeSet<>(indexed)) {
      if (SafGrantRecord.validId(id)) ids.add(id);
      else warnings.add("IDS_ENTRY_INVALID");
    }
    return ids;
  }

  private static Set<String> scanIds(Map<String, ?> raw) {
    Set<String> ids = new TreeSet<>();
    for (String key : raw.keySet()) {
      if (!key.startsWith(PREF_GRANT_PREFIX)) continue;
      String rest = key.substring(PREF_GRANT_PREFIX.length());
      int separator = rest.indexOf('.');
      if (separator <= 0) continue;
      String id = rest.substring(0, separator);
      if (SafGrantRecord.validId(id)) ids.add(id);
    }
    return ids;
  }

  private static boolean consumedGrantKey(String key, Set<String> acceptedIds) {
    if (!key.startsWith(PREF_GRANT_PREFIX)) return false;
    String rest = key.substring(PREF_GRANT_PREFIX.length());
    int separator = rest.indexOf('.');
    if (separator <= 0) return false;
    String id = rest.substring(0, separator);
    return acceptedIds.contains(id) && GRANT_FIELDS.contains(rest.substring(separator + 1));
  }

  private static SafGrantRecord.State state(String value) {
    if (SafGrantRecord.State.ACTIVE.name().equals(value)) return SafGrantRecord.State.ACTIVE;
    if (SafGrantRecord.State.PENDING.name().equals(value)) return SafGrantRecord.State.PENDING;
    if (SafGrantRecord.State.RELEASING.name().equals(value)) return SafGrantRecord.State.RELEASING;
    return null;
  }

  private static String text(Object value) {
    if (value instanceof String string) return string;
    if (value instanceof Number number) return number.toString();
    return null;
  }

  private static long number(Object value, long fallback) {
    Long parsed = optionalNumber(value);
    return parsed == null ? fallback : parsed;
  }

  private static Long optionalNumber(Object value) {
    if (value instanceof Number number) return number.longValue();
    String text = text(value);
    if (text == null || text.isBlank()) return null;
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static boolean bool(Object value, boolean fallback) {
    String text = text(value);
    if ("1".equals(text) || "true".equalsIgnoreCase(text)) return true;
    if ("0".equals(text) || "false".equalsIgnoreCase(text)) return false;
    return fallback;
  }

  private static int acquired(Object value) {
    String rawFlags = text(value);
    if (rawFlags == null) return 0;
    if (rawFlags.chars().allMatch(c -> c == 'r' || c == 'w' || c == ' ')) {
      int flags = 0;
      if (rawFlags.indexOf('r') >= 0) flags |= SafGrantRecord.READ;
      if (rawFlags.indexOf('w') >= 0) flags |= SafGrantRecord.WRITE;
      return flags;
    }
    return mask(number(value, 0L));
  }

  private static int mask(long value) {
    return (int) (value & (SafGrantRecord.READ | SafGrantRecord.WRITE));
  }

  private static String flags(int flags) {
    StringBuilder out = new StringBuilder(2);
    if ((flags & SafGrantRecord.READ) != 0) out.append('r');
    if ((flags & SafGrantRecord.WRITE) != 0) out.append('w');
    return out.toString();
  }

  private static Set<String> strings(Object value) {
    if (!(value instanceof Set<?> set)) return null;
    Set<String> result = new LinkedHashSet<>();
    for (Object item : set) {
      if (!(item instanceof String string)) return null;
      result.add(string);
    }
    return result;
  }
}
