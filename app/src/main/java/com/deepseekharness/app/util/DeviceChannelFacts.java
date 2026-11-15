package com.deepseekharness.app.util;

import java.util.Objects;

/**
 * 一次只读采样的通道事实；不读取设备、偏好或时钟，不触发探活、执行或授权。
 *
 * <p>这些字段分别来自用户意图、后端观察和本次业务授权，不能互相补齐。尤其不能把候选 su、Binder、 TCP 可达、
 * connected 文案或一次普通响应当成真实执行 UID；没有实际身份回执时 {@code executionUid} 保持 -1。
 *
 * <p>本类不提供可执行许可。调用方仍须在派发点核对原 owner、授权修订、当前后端和目标；时间戳与修订号只描述采样归属，
 * 不使旧事实自动续期，也不替代一次性许可。
 *
 * @param enabled 用户允许本通道参与的意图；不由后端连接状态推导
 * @param backendVisible 已发现候选 su、可见 Binder 或本通道后端；不代表已获执行权限
 * @param channelAuthorized 独立核验的通道许可；未核验时为 false，不等同于本次业务许可
 * @param bound 本次观察中执行服务已绑定；不把 Binder 可见当成已绑定
 * @param connected 后端上报连接存在；可能只有 TCP 证据，须另看 connectionEvidence
 * @param connectionEvidence 本次连接证据；不从 connected 字段升级为已鉴权
 * @param requestedRoot 本次请求要求 root；不因此推断实际执行身份
 * @param executionUid 已核验的实际执行 UID，未知为 -1；普通通道响应不足以填入 0 或 2000
 * @param businessGranted 独立取得的本次业务授权结论；不由任何可用性字段自动生成
 * @param observedAtMillis 采样的单调时钟毫秒值，未知为 -1；本类不读取时钟或判断新鲜度
 * @param authorizationRevision 采样所归属的进程内授权修订号；不得作为永久许可
 */
public record DeviceChannelFacts(
    Channel channel,
    boolean enabled,
    boolean backendVisible,
    boolean channelAuthorized,
    boolean bound,
    boolean connected,
    ConnectionEvidence connectionEvidence,
    boolean requestedRoot,
    int executionUid,
    boolean businessGranted,
    long observedAtMillis,
    long authorizationRevision) {
  public static final int UNKNOWN_UID = -1;
  public static final long UNKNOWN_OBSERVED_AT_MILLIS = -1L;

  public enum Channel {
    ROOT,
    SHIZUKU,
    ADB
  }

  /** 只保留已取得的连接证据；TCP 可达和应用层鉴权是不同事实。 */
  public enum ConnectionEvidence {
    UNVERIFIED,
    TCP_REACHABLE,
    AUTHENTICATED
  }

  /** 连接证据只属于原服务 owner；短锁内不调用外部代码、偏好、时钟或设备接口。 */
  public static final class ConnectionLedger {
    public static final class Observation {
      private final String owner;
      private final long epoch;
      private final ConnectionEvidence evidence;
      private final long observedAtMillis;

      private Observation(
          String owner, long epoch, ConnectionEvidence evidence, long observedAtMillis) {
        this.owner = owner;
        this.epoch = epoch;
        this.evidence = evidence;
        this.observedAtMillis = observedAtMillis;
      }

      public ConnectionEvidence evidence() {
        return evidence;
      }

      public long observedAtMillis() {
        return observedAtMillis;
      }

      public boolean connected() {
        return evidence != ConnectionEvidence.UNVERIFIED;
      }
    }

    private static final Observation UNKNOWN =
        new Observation("", -1, ConnectionEvidence.UNVERIFIED, UNKNOWN_OBSERVED_AT_MILLIS);
    private String owner = "";
    private long epoch = -1;
    private Observation observation;

    public synchronized boolean activate(String owner, long epoch) {
      if (owner == null || owner.isEmpty() || epoch < 0 || epoch < this.epoch) return false;
      this.owner = owner;
      this.epoch = epoch;
      observation = unknown();
      return true;
    }

    /** 只接纳新意图 epoch；迟到的旧失效通知不能清除后来实例的证据。 */
    public synchronized boolean invalidate(long epoch) {
      if (epoch <= this.epoch) return false;
      this.epoch = epoch;
      observation = unknown();
      return true;
    }

    public synchronized boolean publish(
        String owner, long epoch, ConnectionEvidence evidence, long observedAtMillis) {
      if (!matches(owner, epoch) || evidence == null || observedAtMillis < -1) return false;
      if (evidence != ConnectionEvidence.UNVERIFIED
          && (observedAtMillis < 0 || observedAtMillis < observation.observedAtMillis))
        return false;
      observation =
          new Observation(
              owner,
              epoch,
              evidence,
              evidence == ConnectionEvidence.UNVERIFIED ? -1 : observedAtMillis);
      return true;
    }

    public synchronized void disconnect(String owner) {
      if (matches(owner, epoch)) observation = unknown();
    }

    public synchronized void retire(String owner) {
      if (!matches(owner, epoch)) return;
      this.owner = "";
      observation = null;
    }

    public synchronized Observation observe(String owner, long epoch) {
      return matches(owner, epoch) ? observation : UNKNOWN;
    }

    public synchronized boolean current(Observation observation) {
      return observation != null
          && observation == this.observation
          && matches(observation.owner, observation.epoch);
    }

    private boolean matches(String owner, long epoch) {
      return !this.owner.isEmpty() && this.owner.equals(owner) && this.epoch == epoch;
    }

    private Observation unknown() {
      return new Observation(owner, epoch, ConnectionEvidence.UNVERIFIED, -1);
    }
  }

  public DeviceChannelFacts {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(connectionEvidence, "connectionEvidence");
    if (executionUid < UNKNOWN_UID)
      throw new IllegalArgumentException("DEVICE_CHANNEL_UID_INVALID");
    if (observedAtMillis < UNKNOWN_OBSERVED_AT_MILLIS)
      throw new IllegalArgumentException("DEVICE_CHANNEL_OBSERVATION_TIME_INVALID");
    if (authorizationRevision < 0)
      throw new IllegalArgumentException("DEVICE_CHANNEL_AUTHORIZATION_REVISION_INVALID");
  }

  public boolean executionUidKnown() {
    return executionUid != UNKNOWN_UID;
  }

  /** 仅表示本次采样包含 UID 0 证据；不代表当前仍有 Root 许可或本次业务已获准。 */
  public boolean rootIdentityVerified() {
    return executionUid == 0;
  }

  /** 仅表示传入的连接证据已经鉴权，不授予业务权限，也不推断执行 UID。 */
  public boolean connectionAuthenticated() {
    return connectionEvidence == ConnectionEvidence.AUTHENTICATED;
  }
}
