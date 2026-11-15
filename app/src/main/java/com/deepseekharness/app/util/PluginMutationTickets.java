package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** 变更票据的内存归属；只验证调用者的观察，不生产进程退出证明。 */
public final class PluginMutationTickets<S> {
  public enum WebState {
    READY,
    ABSENT,
    UNAVAILABLE
  }

  public static final class Web {
    public final WebState state;
    public final long generation;
    private final String instance, authDigest;

    private Web(WebState state, long generation, String instance, String authDigest) {
      this.state = state;
      this.generation = generation;
      this.instance = instance;
      this.authDigest = authDigest;
    }

    public static Web ready(long generation, String instance, String authUrl) {
      if (generation < 0
          || instance == null
          || instance.isEmpty()
          || authUrl == null
          || authUrl.isEmpty()) return unavailable(generation);
      try {
        byte[] digest =
            MessageDigest.getInstance("SHA-256").digest(authUrl.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) {
          hex.append(Character.forDigit((value & 255) >>> 4, 16));
          hex.append(Character.forDigit(value & 15, 16));
        }
        return new Web(WebState.READY, generation, instance, hex.toString());
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }

    public static Web absent(long generation) {
      return generation < 0
          ? unavailable(generation)
          : new Web(WebState.ABSENT, generation, "", "");
    }

    public static Web unavailable(long generation) {
      return new Web(WebState.UNAVAILABLE, generation, "", "");
    }

    @Override
    public boolean equals(Object value) {
      if (!(value instanceof Web other)) return false;
      return state == other.state
          && generation == other.generation
          && instance.equals(other.instance)
          && authDigest.equals(other.authDigest);
    }

    @Override
    public int hashCode() {
      return Objects.hash(state, generation, instance, authDigest);
    }
  }

  /** 私有构造，不能把自由字段包装成另一 owner 的执行凭据。 */
  public static final class Ticket<S> {
    public final S source;
    private final PluginMutationTickets<S> owner;
    private final long sourceEpoch, webEpoch, eventSeq;
    private final Web web;
    private int state; // 0 待领取，1 当前执行，2 已消耗。

    private Ticket(
        PluginMutationTickets<S> owner,
        S source,
        long sourceEpoch,
        long webEpoch,
        long eventSeq,
        Web web,
        int state) {
      this.owner = owner;
      this.source = source;
      this.sourceEpoch = sourceEpoch;
      this.webEpoch = webEpoch;
      this.eventSeq = eventSeq;
      this.web = web;
      this.state = state;
    }
  }

  public static final class Stop<S> {
    private final PluginMutationTickets<S> owner;
    private final Ticket<S> before;
    private final S afterSource;
    private final Web afterWeb;
    private final long cancellationRevision;
    private boolean invalid, closed, eventObserved;

    private Stop(
        PluginMutationTickets<S> owner,
        Ticket<S> before,
        S afterSource,
        Web afterWeb,
        long revision) {
      this.owner = owner;
      this.before = before;
      this.afterSource = afterSource;
      this.afterWeb = afterWeb;
      this.cancellationRevision = revision;
    }
  }

  private S source;
  private long sourceEpoch, webEpoch, eventSeq;
  private Web web = Web.unavailable(-1);
  private Stop<S> stop;

  public synchronized void observeSource(S value, long epoch) {
    if (Objects.equals(source, value) && sourceEpoch == epoch) return;
    if (stop != null
        && !(sourceEpoch == stop.before.sourceEpoch
            && Objects.equals(source, stop.before.source)
            && epoch == stop.before.sourceEpoch + 1
            && Objects.equals(value, stop.afterSource))) stop.invalid = true;
    source = value;
    sourceEpoch = epoch;
  }

  public synchronized void observeWeb(Web value) {
    Objects.requireNonNull(value);
    if (web.equals(value)) return;
    if (stop != null
        && !(webEpoch == stop.before.webEpoch
            && web.equals(stop.before.web)
            && value.equals(stop.afterWeb))) stop.invalid = true;
    web = value;
    webEpoch++;
  }

  /** 通知没有旧身份 payload；即使 getter 又回到 A，也不让旧票复活。 */
  public synchronized void observeWebEvent(Web value) {
    eventSeq++;
    if (stop != null) {
      if (stop.eventObserved
          || eventSeq != stop.before.eventSeq + 1
          || !stop.afterWeb.equals(value)) stop.invalid = true;
      stop.eventObserved = true;
    }
    observeWeb(value);
  }

  public synchronized boolean stopping() {
    return stop != null;
  }

  public synchronized Ticket<S> issue() {
    if (stop != null || source == null || web.state == WebState.UNAVAILABLE) return null;
    return new Ticket<>(this, source, sourceEpoch, webEpoch, eventSeq, web, 0);
  }

  private boolean current(Ticket<S> ticket) {
    return ticket != null
        && ticket.owner == this
        && ticket.sourceEpoch == sourceEpoch
        && ticket.webEpoch == webEpoch
        && ticket.eventSeq == eventSeq
        && Objects.equals(ticket.source, source)
        && ticket.web.equals(web);
  }

  public synchronized boolean fresh(Ticket<S> ticket) {
    return current(ticket) && ticket.state == 0 && stop == null;
  }

  public synchronized boolean claim(Ticket<S> ticket) {
    if (!fresh(ticket)) return false;
    ticket.state = 1;
    return true;
  }

  public synchronized boolean active(Ticket<S> ticket) {
    return current(ticket) && ticket.state == 1 && stop == null;
  }

  public synchronized void close(Ticket<S> ticket) {
    if (ticket != null && ticket.owner == this) ticket.state = 2;
  }

  /** 在本操作的独占租约内、调用既有停止流程前登记；原票立即作废。 */
  public synchronized Stop<S> armStop(
      Ticket<S> ticket, S afterSource, long revision, boolean ownsExclusive) {
    if (afterSource == null) return null;
    if (!ownsExclusive
        || !active(ticket)
        || revision < 0
        || revision == Long.MAX_VALUE
        || ticket.web.generation == Long.MAX_VALUE
        || Objects.equals(afterSource, source)) return null;
    stop =
        new Stop<>(
            this,
            ticket,
            Objects.requireNonNull(afterSource),
            Web.absent(ticket.web.generation + 1),
            revision);
    ticket.state = 2;
    return stop;
  }

  public synchronized boolean canFinish(
      Stop<S> token, S afterSource, Web afterWeb, long revision, boolean maintenanceOwner) {
    if (!maintenanceOwner
        || token == null
        || token.owner != this
        || token != stop
        || token.closed
        || token.invalid
        || revision != token.cancellationRevision + 1
        || !token.afterWeb.equals(afterWeb)
        || !Objects.equals(token.afterSource, afterSource)) return false;
    boolean sourceBefore =
        sourceEpoch == token.before.sourceEpoch && Objects.equals(source, token.before.source);
    boolean sourceAfter =
        sourceEpoch == token.before.sourceEpoch + 1 && Objects.equals(source, token.afterSource);
    boolean webBefore = webEpoch == token.before.webEpoch && web.equals(token.before.web);
    boolean webAfter = webEpoch == token.before.webEpoch + 1 && web.equals(token.afterWeb);
    boolean events =
        eventSeq == token.before.eventSeq
            || token.eventObserved && eventSeq == token.before.eventSeq + 1;
    return events && (sourceBefore || sourceAfter) && (webBefore || webAfter);
  }

  /** 成功的既有 exclusive 回调才可调用；只为本次执行生成一张新 active 票。 */
  public synchronized Ticket<S> finishStop(
      Stop<S> token, S afterSource, Web afterWeb, long revision, boolean maintenanceOwner) {
    if (!canFinish(token, afterSource, afterWeb, revision, maintenanceOwner)) return null;
    if (!Objects.equals(source, afterSource)) observeSource(afterSource, sourceEpoch + 1);
    observeWeb(afterWeb);
    token.closed = true;
    stop = null;
    return new Ticket<>(this, source, sourceEpoch, webEpoch, eventSeq, web, 1);
  }

  public synchronized void abortStop(Stop<S> token) {
    if (token != null && token.owner == this && token == stop) {
      token.closed = true;
      stop = null;
    }
  }
}
