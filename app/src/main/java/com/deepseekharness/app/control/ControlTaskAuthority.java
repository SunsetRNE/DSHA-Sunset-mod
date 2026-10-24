package com.deepseekharness.app.control;

import java.util.Objects;
import java.util.HashMap;
import java.util.Map;

/** One physical screen owner. Every transition fences queued work before cancellation callbacks. */
public final class ControlTaskAuthority {
  public enum Owner {
    LOCAL_DSH,
    REMOTE_DEVICE
  }

  public record Binding(Owner owner, String connection, String taskSessionId, String turnId) {
    public Binding {
      Objects.requireNonNull(owner);
      check(connection);
      check(taskSessionId);
      check(turnId);
    }

    private static void check(String value) {
      if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("CONTROL_ID_INVALID");
    }

    public String identity() {
      return owner + "\0" + connection + "\0" + taskSessionId + "\0" + turnId;
    }
  }

  public record Lease(Binding binding, long revision) {}

  public record Snapshot(
      Binding binding, long revision, boolean paused, boolean needsObservation, String reason) {}

  private Binding binding;
  private long revision;
  private boolean paused, needsObservation = true;
  private String reason = "IDLE";
  // Local Agent turn numbers are monotonic within a full conversation. Keep
  // completion watermarks so a delayed old request cannot reacquire the screen.
  private final Map<String, Long> completedLocalTurns = new HashMap<>();

  public synchronized Lease begin(Binding requested) {
    if (requested.owner() == Owner.LOCAL_DSH) {
      long turn = localTurn(requested.turnId());
      String key = localKey(requested);
      if (turn <= completedLocalTurns.getOrDefault(key, 0L))
        throw new IllegalStateException("SCREEN_TURN_ENDED");
      if (!completedLocalTurns.containsKey(key) && completedLocalTurns.size() >= 4096)
        throw new IllegalStateException("CONTROL_SESSION_LIMIT");
      // Reserve the completion entry before accepting work, so unrelated ended
      // conversations cannot fill the bound and prevent this turn's retirement.
      completedLocalTurns.putIfAbsent(key, 0L);
    }
    if (paused) throw new IllegalStateException("SCREEN_CONTROL_PAUSED");
    if (binding != null && !binding.equals(requested))
      throw new IllegalStateException("PHYSICAL_SCREEN_BUSY");
    if (binding == null) {
      binding = requested;
      revision++;
      needsObservation = true;
      reason = "OBSERVE_REQUIRED";
    }
    return new Lease(binding, revision);
  }

  private static String localKey(Binding requested) {
    return requested.connection() + "\0" + requested.taskSessionId();
  }

  /** The pinned local Agent uses positive safe integer turn numbers. */
  public static long localTurn(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,15}"))
      throw new IllegalArgumentException("CONTROL_TURN_INVALID");
    long turn = Long.parseLong(value);
    if (turn > 9007199254740991L) throw new IllegalArgumentException("CONTROL_TURN_INVALID");
    return turn;
  }

  /** An exact actual turn/end; never cancels another conversation or a newer turn. */
  public synchronized boolean completeLocal(Binding ended) {
    if (ended.owner() != Owner.LOCAL_DSH)
      throw new IllegalArgumentException("CONTROL_OWNER_INVALID");
    long turn = localTurn(ended.turnId());
    String key = localKey(ended);
    // Bound hostile/invalid traffic without evicting a live completion fence.
    if (!completedLocalTurns.containsKey(key) && completedLocalTurns.size() >= 4096)
      throw new IllegalStateException("CONTROL_SESSION_LIMIT");
    completedLocalTurns.merge(key, turn, Math::max);
    if (binding == null
        || binding.owner() != Owner.LOCAL_DSH
        || !binding.connection().equals(ended.connection())
        || !binding.taskSessionId().equals(ended.taskSessionId())
        || localTurn(binding.turnId()) > turn) return false;
    binding = null;
    needsObservation = true;
    revision++;
    if (!paused) reason = "LOCAL_TURN_ENDED";
    return true;
  }

  public synchronized Lease lease(Binding requested) {
    if (binding == null || !binding.equals(requested) || paused)
      throw new IllegalStateException("SCREEN_TASK_CHANGED");
    return new Lease(binding, revision);
  }

  public synchronized boolean current(Lease lease) {
    return lease != null
        && !paused
        && binding != null
        && binding.equals(lease.binding)
        && revision == lease.revision;
  }

  public synchronized boolean actionAllowed(Lease lease) {
    return current(lease) && !needsObservation;
  }

  public synchronized boolean observed(Lease lease) {
    if (!current(lease)) return false;
    needsObservation = false;
    reason = "ACTIVE";
    return true;
  }

  public synchronized Binding pause(String why) {
    Binding previous = binding;
    paused = true;
    needsObservation = true;
    revision++;
    reason = why;
    return previous;
  }

  public synchronized void resume() {
    paused = false;
    needsObservation = true;
    revision++;
    reason = "OBSERVE_REQUIRED";
  }

  public synchronized Binding stop(String why) {
    Binding previous = binding;
    binding = null;
    needsObservation = true;
    revision++;
    if (!paused) reason = why;
    return previous;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(binding, revision, paused, needsObservation, reason);
  }
}
