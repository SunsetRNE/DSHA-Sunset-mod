package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ControlTaskAuthorityTest {
  private static ControlTaskAuthority.Binding binding(
      ControlTaskAuthority.Owner owner, String connection, String task, String turn) {
    return new ControlTaskAuthority.Binding(owner, connection, task, turn);
  }

  @Test
  public void onePhysicalScreenAcrossIndependentLocalAndRemoteOwners() {
    var authority = new ControlTaskAuthority();
    var local =
        authority.begin(
            binding(ControlTaskAuthority.Owner.LOCAL_DSH, "bridge:17", "local-session", "turn-1"));
    assertFalse(authority.actionAllowed(local));
    assertTrue(authority.observed(local));
    assertTrue(authority.actionAllowed(local));
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(
                    ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "pc-session", "turn-1")));
    authority.stop("LOCAL_DONE");
    assertFalse(authority.current(local));
    var remote =
        authority.begin(
            binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "pc-session", "turn-1"));
    assertTrue(authority.current(remote));
    assertFalse(authority.actionAllowed(remote));
  }

  @Test
  public void pauseFencesImmediatelyAndResumeCannotReuseObservation() {
    var authority = new ControlTaskAuthority();
    var binding =
        binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session-full", "turn-full");
    var old = authority.begin(binding);
    authority.observed(old);
    authority.pause("LOCAL_USER_PAUSE");
    assertFalse(authority.current(old));
    assertFalse(authority.actionAllowed(old));
    assertThrows(IllegalStateException.class, () -> authority.begin(binding));
    authority.resume();
    assertFalse(authority.current(old));
    var resumed = authority.lease(binding);
    assertFalse(authority.actionAllowed(resumed));
    assertTrue(authority.observed(resumed));
    assertTrue(authority.actionAllowed(resumed));
  }

  @Test
  public void taskStopAndNewConnectionCannotClearLocalPause() {
    var authority = new ControlTaskAuthority();
    authority.begin(
        binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session", "turn"));
    authority.pause("LOCAL_USER_PAUSE");
    authority.stop("REMOTE_STOP");
    assertTrue(authority.snapshot().paused());
    assertEquals("LOCAL_USER_PAUSE", authority.snapshot().reason());
    assertThrows(
        IllegalStateException.class,
        () ->
            authority.begin(
                binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:2", "session", "turn")));
    assertNull(authority.snapshot().binding());
  }

  @Test
  public void fullConnectionTaskTurnAndOwnerAreDistinctEvenWithSameSuffix() {
    var authority = new ControlTaskAuthority();
    var full =
        binding(
            ControlTaskAuthority.Owner.REMOTE_DEVICE,
            "connection-A",
            "session-abc0001",
            "turn-abc0001");
    var active = authority.begin(full);
    for (var wrong :
        new ControlTaskAuthority.Binding[] {
          binding(
              ControlTaskAuthority.Owner.LOCAL_DSH,
              "connection-A",
              "session-abc0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-B",
              "session-abc0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-A",
              "session-xyz0001",
              "turn-abc0001"),
          binding(
              ControlTaskAuthority.Owner.REMOTE_DEVICE,
              "connection-A",
              "session-abc0001",
              "turn-xyz0001")
        }) {
      assertThrows(IllegalStateException.class, () -> authority.lease(wrong));
      assertNotEquals(full.identity(), wrong.identity());
    }
    assertTrue(authority.current(active));
  }

  @Test
  public void oldTurnCannotBeRevivedBySameIdentifiersAfterCompletion() {
    var authority = new ControlTaskAuthority();
    var binding = binding(ControlTaskAuthority.Owner.REMOTE_DEVICE, "native:1", "session", "turn");
    var first = authority.begin(binding);
    authority.observed(first);
    authority.stop("DONE");
    var next = authority.begin(binding);
    assertFalse(authority.current(first));
    assertFalse(authority.actionAllowed(next));
  }
}
