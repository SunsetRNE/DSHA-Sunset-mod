package com.deepseekharness.app.util;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 设备授权的进程内围栏；只委托原存储，不保存第二份授权，也不执行设备动作。 */
public final class DeviceAuthorization {
  private static final DeviceAuthorization SHARED = new DeviceAuthorization();

  public enum ReadState {
    READY,
    CHANGING,
    UNAVAILABLE
  }

  /** 一次原偏好读取中的完整组合；短信/屏幕仍走各自独立的授权门。 */
  public record Values(boolean rootAllowed, DeviceShellPolicy.Mode rootMode, boolean adbAllowed) {
    public Values {
      Objects.requireNonNull(rootMode, "rootMode");
    }

    public static Values fromPreferences(Map<String, ?> values) {
      Objects.requireNonNull(values, "values");
      return new Values(
          Boolean.TRUE.equals(values.get(Constants.KEY_ALLOW_ROOT_SHELL)),
          DeviceShellPolicy.Mode.parse(
              PreferenceValue.text(values.get(Constants.KEY_ROOT_POLICY_MODE), "strict")),
          Boolean.TRUE.equals(values.get("adb_enabled")));
    }
  }

  /** 快照不是长期许可；执行点还须复核本 owner、策略与真实后端身份。 */
  public static final class Snapshot {
    private final DeviceAuthorization owner;
    private final Values values;
    public final ReadState state;
    public final long revision;

    private Snapshot(DeviceAuthorization owner, ReadState state, long revision, Values values) {
      this.owner = owner;
      this.state = state;
      this.revision = revision;
      this.values = values;
    }

    public boolean readable() {
      return state == ReadState.READY;
    }

    public boolean rootAllowed() {
      return readable() && values.rootAllowed();
    }

    public boolean adbAllowed() {
      return readable() && values.adbAllowed();
    }

    public DeviceShellPolicy.Mode rootMode() {
      return readable() ? values.rootMode() : DeviceShellPolicy.Mode.STRICT;
    }

    /** 与原生 devicePlan 的既有档位选择等价，不改变白名单或 Root 允许规则。 */
    public DeviceShellPolicy.Mode planningMode(boolean forceRoot, boolean rootPresent) {
      return forceRoot || rootAllowed() && rootPresent ? rootMode() : DeviceShellPolicy.Mode.STRICT;
    }
  }

  private static final Values CLOSED = new Values(false, DeviceShellPolicy.Mode.STRICT, false);
  private final Object authorityLock = new Object();
  private long revision;
  private int writers;
  private boolean exhausted;

  public static DeviceAuthorization shared() {
    return SHARED;
  }

  /** 锁内只读进程内状态；源读取始终在锁外，变更期间不等待源或写者。 */
  public Snapshot snapshot(Supplier<Values> source) {
    Objects.requireNonNull(source, "source");
    long before;
    synchronized (authorityLock) {
      if (exhausted) return closed(ReadState.UNAVAILABLE);
      if (writers != 0) return closed(ReadState.CHANGING);
      before = revision;
    }
    Values values;
    try {
      values = Objects.requireNonNull(source.get(), "values");
    } catch (RuntimeException unavailable) {
      synchronized (authorityLock) {
        return closed(ReadState.UNAVAILABLE);
      }
    }
    synchronized (authorityLock) {
      if (exhausted) return closed(ReadState.UNAVAILABLE);
      if (writers != 0 || before != revision) return closed(ReadState.CHANGING);
      return new Snapshot(this, ReadState.READY, revision, values);
    }
  }

  /** 一次原偏好写入：先围栏，锁外撤销与持久化，最后同锁域发布新的 revision。 */
  public boolean change(Runnable invalidate, BooleanSupplier persist) {
    Objects.requireNonNull(invalidate, "invalidate");
    Objects.requireNonNull(persist, "persist");
    synchronized (authorityLock) {
      if (exhausted || writers == Integer.MAX_VALUE) return false;
      if (!advance()) return false;
      writers++;
    }
    try {
      invalidate.run();
      return persist.getAsBoolean();
    } finally {
      synchronized (authorityLock) {
        writers--;
        advance();
      }
    }
  }

  public boolean current(Snapshot snapshot) {
    synchronized (authorityLock) {
      return snapshot != null
          && snapshot.owner == this
          && snapshot.readable()
          && !exhausted
          && writers == 0
          && snapshot.revision == revision;
    }
  }

  private Snapshot closed(ReadState state) {
    return new Snapshot(this, state, revision, CLOSED);
  }

  private boolean advance() {
    if (revision == Long.MAX_VALUE) {
      exhausted = true;
      return false;
    }
    revision++;
    return true;
  }
}
