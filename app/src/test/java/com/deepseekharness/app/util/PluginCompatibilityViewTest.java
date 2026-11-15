package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PluginCompatibilityViewTest {
  @Test
  public void knownFailureStatesCannotBecomeConfirmable() {
    for (String value : new String[] {"incompatible", "unsupported-platform", "check-failed"}) {
      assertTrue(value, PluginCompatibilityView.blocked(value));
      assertFalse(value, PluginCompatibilityView.checked(value));
    }
  }

  @Test
  public void noConstraintAndExactExemptionRemainDistinctFromCompatible() {
    assertEquals(
        PluginCompatibilityView.State.UNCONSTRAINED,
        PluginCompatibilityView.state("unconstrained"));
    assertEquals(PluginCompatibilityView.State.EXEMPTED, PluginCompatibilityView.state("exempted"));
    for (String value : new String[] {"compatible", "unconstrained", "exempted"}) {
      assertTrue(value, PluginCompatibilityView.checked(value));
      assertFalse(value, PluginCompatibilityView.blocked(value));
    }
  }

  @Test
  public void legacyMissingUnknownAndLocalizedMessagesDoNotGrantCheckedStatus() {
    for (String value :
        new String[] {null, "", "unknown", "兼容", "compatible but failed", "future"}) {
      assertEquals(PluginCompatibilityView.State.UNKNOWN, PluginCompatibilityView.state(value));
      assertFalse(PluginCompatibilityView.checked(value));
    }
  }
}
