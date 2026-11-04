package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.WebSessionProof;
import java.io.IOException;
import org.junit.Test;

/** Complete stop evidence cannot become unknown again because an already-retired PID was reused. */
public final class ConfirmedWebRunRetirementTest {
  private static final class Manager extends WebProcessManager {
    boolean exited = true, readable = true, guestStopped = true, confirmationUnavailable;
    Runnable duringConfirmation = () -> {};

    Manager() {
      super(null);
    }

    @Override
    public boolean recoveredLauncherExited(WebSessionProof.Saved proof) throws IOException {
      if (!readable) throw new IOException("OLD_PID_REUSED_AND_UNREADABLE");
      return exited;
    }

    @Override
    public boolean confirmStopped(boolean launcherAlive) throws IOException {
      duringConfirmation.run();
      if (confirmationUnavailable) throw new IOException("FULL_EXIT_PROOF_UNAVAILABLE");
      return guestStopped && !launcherAlive;
    }
  }

  private static StoredWebSession.Record record(long generation) {
    return new StoredWebSession.Record(
        new WebSessionProof.Saved(
            "boot", generation, "instance", "nonce", 42, 100, "web", 41, 99, "launcher"),
        "",
        "",
        "",
        "",
        false,
        "");
  }

  private static WebLifecycleController.WebRun savedRun(
      HarnessSessionState state, long generation) {
    var run = new WebLifecycleController.WebRun(generation);
    run.recovered = record(generation);
    state.runs.put(generation, run);
    return run;
  }

  private static WebProcessSession session(HarnessSessionState state, Manager manager) {
    return new WebProcessSession(
        state,
        manager,
        () -> {
          throw new AssertionError("No new bridge");
        });
  }

  @Test
  public void fullyConfirmedStopRetiresPriorRunBeforeItsPidCanBecomeUnknownAgain()
      throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      state.currentRun = old;
      state.lifecycle.beginStop();
      var manager = new Manager();
      var session = session(state, manager);
      assertTrue(session.confirmStopped());
      manager.readable = false;
      assertFalse(
          "An unrelated future PID cannot revive retired proof", session.hasLiveLaunchers());
      assertNull("The original run is proved stopped", old.recovered);
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void partialGuestOrLauncherConfirmationNeverClearsRecoveredEvidence() throws Exception {
    for (int blocked = 0; blocked < 2; blocked++) {
      var state = new HarnessSessionState();
      try {
        assertTrue(state.lifecycle.adoptRunning(7));
        var old = savedRun(state, 7);
        state.currentRun = old;
        state.lifecycle.beginStop();
        var manager = new Manager();
        if (blocked == 0) manager.guestStopped = false;
        else manager.readable = false;
        assertFalse(session(state, manager).confirmStopped());
        assertNotNull(old.recovered);
        assertSame(old, state.runs.get(7L));
      } finally {
        state.io.shutdownNow();
      }
    }
  }

  @Test
  public void laterGenerationAddedDuringProofKeepsItsEvidenceAndOwnership() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      state.currentRun = old;
      state.lifecycle.beginStop();
      var newer = new WebLifecycleController.WebRun(9);
      newer.recovered = record(9);
      var manager = new Manager();
      manager.duringConfirmation =
          () -> {
            state.runs.put(9L, newer);
            state.currentRun = newer;
          };
      assertTrue(session(state, manager).confirmStopped());
      assertNull(old.recovered);
      assertFalse(state.runs.containsKey(7L));
      assertNotNull(newer.recovered);
      assertSame(newer, state.runs.get(9L));
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void replacedEvidenceWithinTheSameRunIsNotClearedByAnEarlierCheck() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      state.currentRun = old;
      state.lifecycle.beginStop();
      var replacement = record(7);
      var manager = new Manager();
      manager.duringConfirmation = () -> old.recovered = replacement;
      assertTrue(session(state, manager).confirmStopped());
      assertSame(replacement, old.recovered);
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void currentGenerationIsOutsideThePriorRuntimeRetirementBoundary() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      long currentGeneration = state.lifecycle.beginStop();
      var current = savedRun(state, currentGeneration);
      state.currentRun = current;
      assertTrue(session(state, new Manager()).confirmStopped());
      assertNull(old.recovered);
      assertNotNull(current.recovered);
      assertSame(current, state.runs.get(currentGeneration));
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void failedFullConfirmationRetainsTheExactRecoveredEvidence() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      var proof = old.recovered;
      state.currentRun = old;
      state.lifecycle.beginStop();
      var manager = new Manager();
      manager.confirmationUnavailable = true;
      assertThrows(IOException.class, session(state, manager)::confirmStopped);
      assertSame(proof, old.recovered);
      assertSame(old, state.runs.get(7L));
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void zeroGenerationHasNoPriorRuntimeToRetire() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertEquals(0, state.lifecycle.generation());
      var current = savedRun(state, 0);
      var proof = current.recovered;
      state.currentRun = current;
      assertTrue(session(state, new Manager()).confirmStopped());
      assertSame(proof, current.recovered);
      assertSame(current, state.runs.get(0L));
    } finally {
      state.io.shutdownNow();
    }
  }

  @Test
  public void aNewLauncherHandleIsNotRetiredByProofOfThePreviousHandle() throws Exception {
    var state = new HarnessSessionState();
    try {
      assertTrue(state.lifecycle.adoptRunning(7));
      var old = savedRun(state, 7);
      state.currentRun = old;
      state.lifecycle.beginStop();
      var manager = new Manager();
      Process newer =
          new Process() {
            public int exitValue() {
              throw new IllegalThreadStateException();
            }

            public int waitFor() {
              throw new AssertionError("No wait");
            }

            public void destroy() {
              throw new AssertionError("No signal");
            }

            public java.io.InputStream getInputStream() {
              throw new AssertionError("No output read");
            }

            public java.io.InputStream getErrorStream() {
              throw new AssertionError("No output read");
            }

            public java.io.OutputStream getOutputStream() {
              throw new AssertionError("No input");
            }
          };
      manager.duringConfirmation = () -> old.launcher = newer;
      var session = session(state, manager);
      assertTrue(session.confirmStopped());
      assertNotNull(old.recovered);
      assertSame(newer, old.launcher);
      assertTrue(session.hasLiveLaunchers());
    } finally {
      state.io.shutdownNow();
    }
  }
}
