package com.deepseekharness.app.saf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SAF 授权簿的不可变状态快照（纯逻辑，不 import Android API，可用 JVM 单测）。
 *
 * <p>状态机（每次被接受的变更 revision + 1）：
 *
 * <pre>
 *   （无） --beginTake--> PENDING --commitTake--> ACTIVE
 *                              \--abortTake-->（无）
 *   ACTIVE --refreshSystemFlags--> ACTIVE（系统撤销后 readable/writable 归零，记录保留）
 *   ACTIVE/PENDING --forget-->（无）
 * </pre>
 *
 * <p><b>撤销语义</b>：每个记录带一个单调递增的 {@code epoch}；{@code forget} 或对同一 URI 重新
 * {@code beginTake} 都会让旧记录消失或换成新代次，于是绑定旧 epoch 的能力句柄
 * （{@link SafCapabilityHandles}）一律失效。{@code refreshSystemFlags} 不换代次，但句柄使用前
 * 会重新检查当前的读/写位，所以系统撤销同样会让写句柄失败。
 *
 * <p><b>不变量</b>：一个 URI 只有一条记录；记录数不超过 {@link SafCapabilityLimits#MAX_TREES}；
 * 坏参数不改变状态、不递增 revision、不消耗 epoch。
 */
public final class SafGrantLedger {
  public static final String INVALID_URI = "INVALID_URI";
  public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
  public static final String LIMIT = "LIMIT";
  public static final String UNKNOWN_GRANT = "UNKNOWN_GRANT";
  public static final String BAD_STATE = "BAD_STATE";

  private final Map<String, SafGrantRecord> byUri;
  private final long revision;
  private final long nextEpoch;

  private SafGrantLedger(Map<String, SafGrantRecord> byUri, long revision, long nextEpoch) {
    this.byUri = byUri;
    this.revision = revision;
    this.nextEpoch = nextEpoch;
  }

  public static SafGrantLedger empty() {
    return new SafGrantLedger(Map.of(), 0, 1);
  }

  /**
   * 从已验证记录重建快照；{@link SafLedgerCodec} 与测试使用。
   *
   * <p>调用方负责把坏值挡在外面：重复 URI、超过上限、epoch &lt; 1 的记录一律拒绝，
   * 不做静默丢弃（静默丢弃会把持久化损坏伪装成空授权簿）。
   */
  public static SafGrantLedger of(List<SafGrantRecord> records, long revision, long nextEpoch) {
    Objects.requireNonNull(records, "records");
    Map<String, SafGrantRecord> map = new LinkedHashMap<>();
    long maxEpoch = 0;
    for (SafGrantRecord record : records) {
      if (record == null) throw new IllegalArgumentException("GRANT_NULL");
      if (map.putIfAbsent(record.uri(), record) != null)
        throw new IllegalArgumentException("GRANT_DUPLICATE_URI");
      maxEpoch = Math.max(maxEpoch, record.epoch());
    }
    if (map.size() > SafCapabilityLimits.MAX_TREES)
      throw new IllegalArgumentException("GRANT_LIMIT");
    return new SafGrantLedger(
        map, Math.max(0, revision), Math.max(Math.max(1, nextEpoch), maxEpoch + 1));
  }

  /** 稳定顺序（按短 id 升序）的全部记录；含 PENDING 与系统位已归零的记录。 */
  public List<SafGrantRecord> records() {
    List<SafGrantRecord> list = new ArrayList<>(byUri.values());
    list.sort(Comparator.comparing(SafGrantRecord::id));
    return List.copyOf(list);
  }

  /** 桥/句柄按短 id 查；未知查询返回 null，不猜近义 id。 */
  public SafGrantRecord findById(String id) {
    if (!SafGrantRecord.validId(id)) return null;
    for (SafGrantRecord record : byUri.values()) if (record.id().equals(id)) return record;
    return null;
  }

  public SafGrantRecord findByUri(String uri) {
    return uri == null ? null : byUri.get(uri);
  }

  public long revision() {
    return revision;
  }

  public long nextEpoch() {
    return nextEpoch;
  }

  public int size() {
    return byUri.size();
  }

  /** 开始一次系统 take：先落 PENDING 记录；同一 URI 重选时换新代次并保留写入偏好。 */
  public Change beginTake(
      String uri, String app, String label, int beforeFlags, int intentFlags, long now) {
    if (SafCapabilityPolicy.treeUriProblem(uri, null) != SafCapabilityPolicy.TreeUri.OK)
      return fail(INVALID_URI);
    if ((beforeFlags & ~(SafGrantRecord.READ | SafGrantRecord.WRITE)) != 0
        || (intentFlags & ~(SafGrantRecord.READ | SafGrantRecord.WRITE)) != 0
        || intentFlags == 0) return fail(INVALID_ARGUMENT);
    SafGrantRecord existing = byUri.get(uri);
    if (existing == null && byUri.size() >= SafCapabilityLimits.MAX_TREES) return fail(LIMIT);
    long epoch = nextEpoch;
    boolean userWritable = existing == null || existing.userWritable();
    SafGrantRecord pending =
        new SafGrantRecord(
            SafGrantRecord.idOf(uri),
            uri,
            app,
            label,
            false,
            false,
            userWritable,
            0,
            SafGrantRecord.State.PENDING,
            epoch,
            Math.max(0, now),
            beforeFlags,
            intentFlags);
    Map<String, SafGrantRecord> copy = new LinkedHashMap<>(byUri);
    copy.put(uri, pending);
    return new Change(new SafGrantLedger(copy, revision + 1, epoch + 1), null);
  }

  /** 确认 take：PENDING → ACTIVE。{@code acquired} 必须是本次实际新拿到的位。 */
  public Change commitTake(
      String uri, int takenFlags, int acquiredFlags, boolean userWritable, long now) {
    SafGrantRecord record = uri == null ? null : byUri.get(uri);
    if (record == null) return fail(UNKNOWN_GRANT);
    if (record.state() != SafGrantRecord.State.PENDING) return fail(BAD_STATE);
    if ((takenFlags & ~(SafGrantRecord.READ | SafGrantRecord.WRITE)) != 0
        || (acquiredFlags & ~takenFlags) != 0) return fail(INVALID_ARGUMENT);
    SafGrantRecord active =
        new SafGrantRecord(
            record.id(),
            record.uri(),
            record.app(),
            record.label(),
            (takenFlags & SafGrantRecord.READ) != 0,
            (takenFlags & SafGrantRecord.WRITE) != 0,
            userWritable,
            acquiredFlags,
            SafGrantRecord.State.ACTIVE,
            record.epoch(),
            now >= 0 ? now : record.takenAt(),
            0,
            0);
    return replace(active);
  }

  /** 放弃 take（含进程在 take/remember 之间死亡后的清理）；对不存在记录幂等。 */
  public Change abortTake(String uri) {
    SafGrantRecord record = uri == null ? null : byUri.get(uri);
    if (record == null) return new Change(this, null);
    if (record.state() != SafGrantRecord.State.PENDING) return fail(BAD_STATE);
    return remove(uri);
  }

  /** 撤销授权：删除记录即让旧 id/旧 URI/旧 epoch 句柄全部失效；对不存在记录幂等。 */
  public Change forget(String uri) {
    if (uri == null || !byUri.containsKey(uri)) return new Change(this, null);
    return remove(uri);
  }

  /** 用户「允许助手写入」开关；不改变系统写位，也不改变代次。 */
  public Change setUserWritable(String uri, boolean writable) {
    SafGrantRecord record = uri == null ? null : byUri.get(uri);
    if (record == null) return fail(UNKNOWN_GRANT);
    if (record.userWritable() == writable) return new Change(this, null);
    SafGrantRecord updated =
        new SafGrantRecord(
            record.id(),
            record.uri(),
            record.app(),
            record.label(),
            record.readable(),
            record.writable(),
            writable,
            record.acquired(),
            record.state(),
            record.epoch(),
            record.takenAt(),
            record.pendingBefore(),
            record.pendingIntent());
    return replace(updated);
  }

  /**
   * 用系统 {@code getPersistedUriPermissions()} 的最新观测值刷新读/写位。
   * 系统撤销后位归零，但记录保留，用户仍能在设置页看到并手动移除。
   */
  public Change refreshSystemFlags(String uri, int observedFlags) {
    SafGrantRecord record = uri == null ? null : byUri.get(uri);
    if (record == null) return fail(UNKNOWN_GRANT);
    if (record.state() != SafGrantRecord.State.ACTIVE) return fail(BAD_STATE);
    if ((observedFlags & ~(SafGrantRecord.READ | SafGrantRecord.WRITE)) != 0)
      return fail(INVALID_ARGUMENT);
    boolean readable = (observedFlags & SafGrantRecord.READ) != 0;
    boolean writable = (observedFlags & SafGrantRecord.WRITE) != 0;
    if (record.readable() == readable && record.writable() == writable)
      return new Change(this, null);
    SafGrantRecord updated =
        new SafGrantRecord(
            record.id(),
            record.uri(),
            record.app(),
            record.label(),
            readable,
            writable,
            record.userWritable(),
            record.acquired(),
            record.state(),
            record.epoch(),
            record.takenAt(),
            record.pendingBefore(),
            record.pendingIntent());
    return replace(updated);
  }

  private Change replace(SafGrantRecord record) {
    Map<String, SafGrantRecord> copy = new LinkedHashMap<>(byUri);
    copy.put(record.uri(), record);
    return new Change(new SafGrantLedger(copy, revision + 1, nextEpoch), null);
  }

  private Change remove(String uri) {
    Map<String, SafGrantRecord> copy = new LinkedHashMap<>(byUri);
    copy.remove(uri);
    return new Change(new SafGrantLedger(copy, revision + 1, nextEpoch), null);
  }

  private Change fail(String failure) {
    return new Change(this, failure);
  }

  /** 一次变更的结果：{@code failure == null} 表示已接受，否则 {@code ledger} 是原快照。 */
  public static final class Change {
    public final SafGrantLedger ledger;
    public final String failure;

    Change(SafGrantLedger ledger, String failure) {
      this.ledger = Objects.requireNonNull(ledger, "ledger");
      this.failure = failure;
    }

    public boolean accepted() {
      return failure == null;
    }
  }
}
