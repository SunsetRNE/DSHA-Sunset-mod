package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.PluginStateView.Evidence;
import com.deepseekharness.app.util.PluginStateView.OperationState;
import com.deepseekharness.app.util.PluginStateView.RuntimePhase;
import org.junit.Test;

public final class PluginStateViewTest {
  @Test
  public void loaderPhasesAndHistoricalAliasesShareOneMapping() {
    String[][] cases = {
      {"pending", "PENDING"}, {"waiting", "PENDING"}, {"loading", "LOADING"},
      {"unloading", "UNLOADING"}, {"active", "ACTIVE"}, {"loaded", "ACTIVE"},
      {"running", "ACTIVE"}, {"failed", "FAILED"}, {"unloaded", "ABSENT"},
      {"disabled", "ABSENT"}, {"stopped", "ABSENT"}, {"inactive", "ABSENT"},
      {"unknown", "UNKNOWN"}, {"", "UNKNOWN"}, {"future-phase", "UNKNOWN"}
    };
    for (String[] value : cases)
      assertEquals(
          value[0], RuntimePhase.valueOf(value[1]), PluginStateView.runtimePhase(value[0], false));
    assertEquals(RuntimePhase.ABSENT, PluginStateView.runtimePhase(null, false));
  }

  @Test
  public void staleOrHistoricalEvidenceNeverClaimsCurrentLoadedFailedOrAbsent() {
    for (String phase : new String[] {"active", "failed", "unloading", null}) {
      assertEquals(RuntimePhase.UNKNOWN, PluginStateView.runtimePhase(phase, true));
      for (Evidence evidence : new Evidence[] {Evidence.LEDGER, Evidence.NONE, null})
        assertEquals(RuntimePhase.UNKNOWN, PluginStateView.runtimePhase(phase, false, evidence));
    }
    assertEquals(
        RuntimePhase.ACTIVE, PluginStateView.runtimePhase("active", false, Evidence.HEARTBEAT));
    assertEquals(
        RuntimePhase.UNLOADING, PluginStateView.runtimePhase("unloading", false, Evidence.LOADER));
  }

  @Test
  public void everyNonSuccessResultAndUnknownTagRemainsProminent() {
    for (OperationState state : OperationState.values()) {
      OperationState parsed =
          PluginStateView.operationState(state == OperationState.NONE ? "" : state.name());
      assertEquals(state, parsed);
      assertEquals(
          state != OperationState.NONE && state != OperationState.SUCCEEDED,
          PluginStateView.prominent(parsed));
    }
    assertEquals(OperationState.UNCONFIRMED, PluginStateView.operationState("future-result"));
    assertEquals(OperationState.UNCONFIRMED, PluginStateView.operationState("UNKNOWN"));
    assertEquals(OperationState.TIMEOUT, PluginStateView.operationState("TIMED_OUT"));
    assertEquals(OperationState.NONE, PluginStateView.operationState(null));
    assertFalse(PluginStateView.failed(OperationState.UNCONFIRMED));
    assertTrue(PluginStateView.failed(OperationState.FAILED));
    assertTrue(PluginStateView.failed(OperationState.REJECTED));
  }
}
