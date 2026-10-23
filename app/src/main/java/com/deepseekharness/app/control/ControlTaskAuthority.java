package com.deepseekharness.app.control;

import java.util.Objects;

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

  public synchronized Lease begin(Binding requested) {
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

  public synchronized void pause(String why) {
    paused = true;
    needsObservation = true;
    revision++;
    reason = why;
  }

  public synchronized void resume() {
    paused = false;
    needsObservation = true;
    revision++;
    reason = "OBSERVE_REQUIRED";
  }

  public synchronized void stop(String why) {
    binding = null;
    needsObservation = true;
    revision++;
    if (!paused) reason = why;
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(binding, revision, paused, needsObservation, reason);
  }
}
