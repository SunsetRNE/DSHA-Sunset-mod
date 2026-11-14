package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.Test;

public final class PluginExitProofGateTest {
  private static final String ID = "00000000-0000-4000-8000-000000000001";
  private static final String TASK = "a".repeat(32);
  private final PluginExitProofGate.Binding binding =
      new PluginExitProofGate.Binding(ID, TASK, 1L, "instance");

  @Test
  public void missingOwnerForgedStringsAndDirectMetadataCannotReleaseAnything() {
    var gate = new PluginExitProofGate();
    for (Object token :
        new Object[] {
          null,
          "EXITED_CONFIRMED",
          Map.of("outcome", "EXITED_CONFIRMED"),
          new PluginExitProofGate.Evidence(
              binding, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", 1000),
          new Object()
        }) assertFalse(gate.consume(token, binding, 900, 1000));
  }

  @Test
  public void onlyExactFreshOwnerProofIsConsumedOnce() {
    Map<Object, PluginExitProofGate.Evidence> issued = new IdentityHashMap<>();
    Object token = new Object();
    issued.put(
        token,
        new PluginExitProofGate.Evidence(
            binding, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", 1000));
    var gate = new PluginExitProofGate(issued::get);
    assertTrue(gate.consume(token, binding, 900, 1000));
    assertFalse(gate.consume(token, binding, 900, 1000));
    assertFalse(gate.consume(new Object(), binding, 900, 1000));
  }

  @Test
  public void staleFutureUnknownAndMismatchedBindingStayRetained() {
    var wrongId =
        new PluginExitProofGate.Binding(
            "00000000-0000-4000-8000-000000000002", TASK, 1L, "instance");
    var wrongTask = new PluginExitProofGate.Binding(ID, "b".repeat(32), 1L, "instance");
    var wrongGeneration = new PluginExitProofGate.Binding(ID, TASK, 2L, "instance");
    var wrongInstance = new PluginExitProofGate.Binding(ID, TASK, 1L, "other");
    var missingGeneration = new PluginExitProofGate.Binding(ID, TASK, null, "instance");
    var missingInstance = new PluginExitProofGate.Binding(ID, TASK, 1L, null);
    for (var actual :
        new PluginExitProofGate.Binding[] {
          wrongId, wrongTask, wrongGeneration, wrongInstance, missingGeneration, missingInstance
        }) {
      var gate =
          new PluginExitProofGate(
              token ->
                  new PluginExitProofGate.Evidence(
                      actual, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", 1000));
      assertFalse(gate.consume(new Object(), binding, 900, 1000));
    }
    for (long at : new long[] {0, 899, 1001}) {
      var gate =
          new PluginExitProofGate(
              token ->
                  new PluginExitProofGate.Evidence(
                      binding, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", at));
      assertFalse(gate.consume(new Object(), binding, 900, 1000));
    }
    var stale =
        new PluginExitProofGate(
            token ->
                new PluginExitProofGate.Evidence(
                    binding, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", 1000));
    assertFalse(stale.consume(new Object(), binding, 900, 31001));
    var unknown =
        new PluginExitProofGate(
            token ->
                new PluginExitProofGate.Evidence(
                    binding, PluginExitProofGate.Outcome.UNKNOWN, "A5_OWNER", 1000));
    assertFalse(unknown.consume(new Object(), binding, 900, 1000));
  }

  @Test
  public void missingContextOnBothSidesCannotBeTreatedAsExactBinding() {
    for (var missing :
        new PluginExitProofGate.Binding[] {
          new PluginExitProofGate.Binding(ID, TASK, null, null),
          new PluginExitProofGate.Binding(ID, TASK, 1L, null),
          new PluginExitProofGate.Binding(ID, TASK, null, "instance")
        }) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var gate =
          new PluginExitProofGate(
              token -> {
                calls.incrementAndGet();
                return new PluginExitProofGate.Evidence(
                    missing, PluginExitProofGate.Outcome.EXITED_CONFIRMED, "A5_OWNER", 1000);
              });
      assertFalse(gate.consume(new Object(), missing, 900, 1000));
      assertEquals("缺宿主执行上下文时不应请求 owner 降维核验", 0, calls.get());
    }
  }

  @Test
  public void ownerFailureDoesNotGrantExitProof() {
    var gate =
        new PluginExitProofGate(
            token -> {
              throw new IllegalStateException("unavailable");
            });
    assertFalse(gate.consume(new Object(), binding, 900, 1000));
  }
}
