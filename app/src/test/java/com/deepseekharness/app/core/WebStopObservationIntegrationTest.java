package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.StopCompletion;
import com.deepseekharness.app.util.StopHandoff;
import com.deepseekharness.app.util.StopOperationId;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebStartProtection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 真实 coordinator 的一次停止事务；只注入 IO，不手写 lastStopResult 或完成 Void Future。 */
public final class WebStopObservationIntegrationTest {
  private static final class Io implements WebStopCoordinator.ObservedPorts {
    final Object owner = new Object();
    final WebLifecycle lifecycle = new WebLifecycle();
    final WebStartProtection protection = new WebStartProtection(lifecycle, () -> 10L);
    final StopCompletion.Environment environment =
        new StopCompletion.Environment("dev:inode", "boot", "input-1");
    final List<String> calls = new ArrayList<>();
    final WebStartProtection.Ticket ticket;
    final StopOperationId operation;
    StopCompletion.Scope unknown;
    boolean guestFailure, stable = true, finishFailure;
    Runnable beforeFinish = () -> {};

    Io() {
      long running = lifecycle.beginStart(false, false);
      lifecycle.finishStart(running);
      ticket = protection.begin();
      assertTrue(protection.promote(ticket.id(), this, () -> true));
      long stopped = protection.beginInternalStop(ticket);
      operation =
          new StopOperationId(
              owner, 1, running, "instance-A", stopped, protection.cancellationRevision());
    }

    public String stopGuest() throws IOException {
      calls.add("guest-signal");
      if (guestFailure) throw new IOException("SIGNAL_DENIED");
      return "";
    }

    public String stopOwnedLaunchers() {
      calls.add("launcher-stop");
      return "";
    }

    public boolean confirm() {
      calls.add("confirm");
      return true;
    }

    public void finish(WebStopCoordinator.Result result) {
      calls.add("finish");
      beforeFinish.run();
      lifecycle.finishStop(operation.stopGeneration());
      if (finishFailure) throw new IllegalStateException("LAST_SYNC_CLOSE_FAILED");
    }

    public WebStopCoordinator.Observation observe() {
      calls.add("observe");
      var facts = new ArrayList<StopCompletion.Fact>();
      for (var scope : StopCompletion.Scope.values())
        facts.add(
            new StopCompletion.Fact(
                scope,
                scope == unknown ? StopCompletion.Outcome.UNKNOWN : StopCompletion.Outcome.EXITED,
                10,
                "scope-target:" + scope));
      return new WebStopCoordinator.Observation(environment, facts, stable);
    }

    public long now() {
      return 11;
    }

    StopHandoff.Decision evaluate(StopCompletion completion) {
      return StopHandoff.evaluate(
          completion,
          operation,
          operation,
          owner,
          lifecycle.generation(),
          protection.cancellationRevision(),
          environment,
          protection.observesWaitingStop(ticket));
    }
  }

  @Test
  public void oneSignalTransactionFeedsBothPureProjections() {
    var io = new Io();
    var attempt = WebStopCoordinator.stopObserved(io.operation, io);
    assertTrue(attempt.result().stopped());
    assertEquals(StopHandoff.Decision.ELIGIBLE, io.evaluate(attempt.completion()));
    assertEquals(
        List.of("guest-signal", "launcher-stop", "confirm", "finish", "observe"), io.calls);
    // 再算纯投影不会重复 stop，也不改本次历史 completion。
    assertEquals(StopHandoff.Decision.ELIGIBLE, io.evaluate(attempt.completion()));
    assertEquals(5, io.calls.size());
  }

  @Test
  public void genericStoppedAndNormalFutureReturnCannotFillAnyMissingScope() throws Exception {
    for (var scope : StopCompletion.Scope.values()) {
      var io = new Io();
      io.unknown = scope;
      var future =
          new java.util.concurrent.FutureTask<>(
              () -> WebStopCoordinator.stopObserved(io.operation, io));
      future.run();
      var attempt = future.get();
      assertTrue(attempt.result().stopped());
      assertEquals(
          scope.name(), StopHandoff.Decision.INCOMPLETE, io.evaluate(attempt.completion()));
    }
  }

  @Test
  public void sameGuestFinalExitCanClearItsSignalErrorButCannotClearUnknownTrial() {
    var io = new Io();
    io.guestFailure = true;
    var completed = WebStopCoordinator.stopObserved(io.operation, io);
    assertTrue(completed.result().stopped());
    assertTrue(completed.completion().complete());
    var other = new Io();
    other.guestFailure = true;
    other.unknown = StopCompletion.Scope.TRIALS;
    var incomplete = WebStopCoordinator.stopObserved(other.operation, other);
    assertTrue(incomplete.result().stopped());
    assertFalse(incomplete.completion().complete());
    assertEquals(1, java.util.Collections.frequency(other.calls, "guest-signal"));
  }

  @Test
  public void stopACompletionCannotBeMatchedToStopBAfterSharedStateChanges() {
    var io = new Io();
    var a = WebStopCoordinator.stopObserved(io.operation, io);
    long previous = io.lifecycle.generation();
    long next = io.lifecycle.beginStop();
    var bId =
        new StopOperationId(
            io.owner, 2, previous, "instance-B", next, io.protection.cancellationRevision());
    var b = WebStopCoordinator.stopObserved(bId, io);
    assertNotSame(a.completion(), b.completion());
    assertSame(io.operation, a.completion().operation());
    assertSame(bId, b.completion().operation());
    assertEquals(
        StopHandoff.Decision.WRONG_OPERATION,
        StopHandoff.evaluate(
            a.completion(),
            bId,
            bId,
            io.owner,
            next,
            io.protection.cancellationRevision(),
            io.environment,
            true));
  }

  @Test
  public void repeatedExplicitStopAtSameGenerationRevokesThePendingRestart() {
    var io = new Io();
    long generation = io.lifecycle.generation();
    io.beforeFinish =
        () -> {
          io.protection.cancelAll();
          assertEquals(generation, io.lifecycle.beginStop());
        };
    var attempt = WebStopCoordinator.stopObserved(io.operation, io);
    assertTrue(attempt.completion().complete());
    assertEquals(generation, io.lifecycle.generation());
    assertEquals(StopHandoff.Decision.STALE_INTENT, io.evaluate(attempt.completion()));
    assertFalse(io.protection.resumeAfterStop(io.ticket));
  }

  @Test
  public void objectReplacementCannotInheritConfirmedExit() {
    var io = new Io();
    io.beforeFinish = () -> io.stable = false;
    var attempt = WebStopCoordinator.stopObserved(io.operation, io);
    assertTrue(attempt.result().stopped());
    assertFalse(attempt.completion().objectsUnchanged());
    assertEquals(StopHandoff.Decision.INCOMPLETE, io.evaluate(attempt.completion()));
  }

  @Test
  public void finalSynchronousCloseFailureIsFrozenAsFailureAfterGuestExit() {
    var io = new Io();
    io.finishFailure = true;
    var attempt = WebStopCoordinator.stopObserved(io.operation, io);
    assertEquals(WebStopCoordinator.Status.FAILED, attempt.result().status());
    assertEquals(StopCompletion.Finish.FAILED, attempt.completion().finish());
    assertFalse(attempt.completion().complete());
    assertEquals(
        List.of("guest-signal", "launcher-stop", "confirm", "finish", "observe"), io.calls);
  }

  @Test
  public void lostForegroundServiceKeepsTheHistoryButRejectsHandoff() {
    var io = new Io();
    io.beforeFinish = () -> io.protection.serviceDestroyed(io);
    var attempt = WebStopCoordinator.stopObserved(io.operation, io);
    assertTrue(attempt.completion().complete());
    assertEquals(StopHandoff.Decision.UNPROTECTED_PATH, io.evaluate(attempt.completion()));
  }
}
