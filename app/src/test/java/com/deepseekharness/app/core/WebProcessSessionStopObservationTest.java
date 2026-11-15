package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.runtime.StoredWebSession;
import com.deepseekharness.app.util.StopCompletion;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** 真实会话快照与 coordinator；平台观察经窄 manager 端口注入，无真实进程或设备动作。 */
@RunWith(WebLifecycleStartProtectionTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = WebLifecycleResponsivenessTest.App.class)
public final class WebProcessSessionStopObservationTest {
  private static final class Handle extends Process {
    boolean alive;

    Handle(boolean alive) {
      this.alive = alive;
    }

    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    public int waitFor() {
      alive = false;
      return 0;
    }

    public int exitValue() {
      if (alive) throw new IllegalThreadStateException();
      return 0;
    }

    public void destroy() {
      alive = false;
    }
  }

  private static final class Manager extends WebProcessManager {
    boolean recoveredExited = true;
    StopCompletion.ProcessKey guest = new StopCompletion.ProcessKey("boot", 42, 100);

    Manager() {
      super(null);
    }

    @Override
    public boolean recoveredLauncherExited(
        com.deepseekharness.app.util.WebSessionProof.Saved proof) {
      return recoveredExited;
    }

    @Override
    public StopObservation finishStopObservation(StopTrace trace) {
      var facts = new java.util.ArrayList<StopCompletion.Fact>();
      for (var scope :
          List.of(
              StopCompletion.Scope.GUEST,
              StopCompletion.Scope.TRIALS,
              StopCompletion.Scope.RESIDUALS))
        facts.add(
            new StopCompletion.Fact(
                scope,
                StopCompletion.Outcome.EXITED,
                0,
                "injected-exact-target",
                scope == StopCompletion.Scope.GUEST ? guest : null));
      return new StopObservation(
          new StopCompletion.Environment("root", "boot", "inputs"), facts, true);
    }
  }

  private static final class Fixture implements AutoCloseable {
    final HarnessSessionState state = new HarnessSessionState();
    final Manager manager = new Manager();
    final WebLifecycleController.WebRun run;
    final WebProcessSession session;

    Fixture() {
      long generation = state.lifecycle.beginStart(false, false);
      state.lifecycle.finishStart(generation);
      run = new WebLifecycleController.WebRun(generation, "exact-instance");
      state.currentRun = run;
      state.runs.put(generation, run);
      session =
          new WebProcessSession(
              state,
              manager,
              () -> {
                throw new AssertionError();
              });
    }

    WebStopCoordinator.Attempt stop(Runnable mutate, Runnable finish) {
      var capture = session.captureStop();
      com.deepseekharness.app.util.StopOperationId operation;
      synchronized (state.lifecycle) {
        operation = state.identifyStop(run.generation, state.lifecycle.beginStop());
      }
      return WebStopCoordinator.stopObserved(
          operation,
          new WebStopCoordinator.ObservedPorts() {
            public String stopGuest() {
              mutate.run();
              return "";
            }

            public String stopOwnedLaunchers() {
              return "";
            }

            public boolean confirm() {
              return true;
            }

            public void finish(WebStopCoordinator.Result result) {
              finish.run();
            }

            public WebStopCoordinator.Observation observe() {
              return session.observeStop(capture, null);
            }

            public long now() {
              return android.os.SystemClock.elapsedRealtime();
            }
          });
    }

    public void close() {
      state.io.shutdownNow();
    }
  }

  @Test
  public void originalGuestKeyMatchesTheSavedRunBeforeItCanBeComplete() {
    try (var f = new Fixture()) {
      f.state.retainedWebStopProof =
          new com.deepseekharness.app.util.WebSessionProof.Saved(
              "boot",
              f.run.generation,
              f.run.instanceId,
              "nonce",
              42,
              100,
              "web",
              41,
              99,
              "launcher");
      var attempt = f.stop(() -> {}, () -> {});
      assertTrue(attempt.completion().complete());
      assertEquals(
          new StopCompletion.ProcessKey("boot", 42, 100),
          attempt.completion().facts().get(StopCompletion.Scope.GUEST).getTarget());
    }
  }

  @Test
  public void anotherDiskGuestCannotProveTheOriginalSavedGuestExited() {
    try (var f = new Fixture()) {
      f.state.retainedWebStopProof =
          new com.deepseekharness.app.util.WebSessionProof.Saved(
              "boot",
              f.run.generation,
              f.run.instanceId,
              "nonce",
              42,
              100,
              "web",
              41,
              99,
              "launcher");
      f.manager.guest = new StopCompletion.ProcessKey("boot", 84, 200);
      var attempt = f.stop(() -> {}, () -> {});
      assertTrue(attempt.result().stopped());
      assertEquals(
          StopCompletion.Outcome.UNKNOWN,
          attempt.completion().facts().get(StopCompletion.Scope.GUEST).getOutcome());
      assertFalse(attempt.completion().complete());
    }
  }

  @Test
  public void clearingTheMapDoesNotProveTheCapturedLauncherExited() {
    try (var f = new Fixture()) {
      f.run.launcher = new Handle(true);
      var attempt = f.stop(f.state.runs::clear, () -> {});
      assertTrue(attempt.result().stopped());
      assertEquals(
          StopCompletion.Outcome.UNCONFIRMED,
          attempt.completion().facts().get(StopCompletion.Scope.OWNED_LAUNCHERS).getOutcome());
      assertFalse(attempt.completion().complete());
    }
  }

  @Test
  public void replacingAnObjectWithTheSameGenerationAndInstanceStillRejects() {
    try (var f = new Fixture()) {
      var attempt =
          f.stop(
              () -> {
                var replacement =
                    new WebLifecycleController.WebRun(f.run.generation, f.run.instanceId);
                f.state.runs.put(replacement.generation, replacement);
                f.state.currentRun = replacement;
              },
              () -> {});
      assertTrue(attempt.result().stopped());
      assertFalse(attempt.completion().objectsUnchanged());
      assertFalse(attempt.completion().complete());
    }
  }

  @Test
  public void recoveredLauncherRemainsSeparateAfterGuestExitAndPruning() {
    try (var f = new Fixture()) {
      f.manager.recoveredExited = false;
      var proof =
          new com.deepseekharness.app.util.WebSessionProof.Saved(
              "boot",
              f.run.generation,
              f.run.instanceId,
              "nonce",
              42,
              100,
              "web",
              41,
              99,
              "launcher");
      f.run.recovered = new StoredWebSession.Record(proof, "", "", "", "", false, "");
      var attempt =
          f.stop(
              () -> {
                f.run.recovered = null;
                f.state.runs.clear();
              },
              () -> {});
      assertEquals(
          StopCompletion.Outcome.UNKNOWN,
          attempt.completion().facts().get(StopCompletion.Scope.RECOVERED_LAUNCHERS).getOutcome());
      assertFalse(attempt.completion().complete());
    }
  }

  @Test
  public void exactExitedHandleSurvivesPruningButBridgeCloseFailureDoesNot() {
    try (var f = new Fixture()) {
      var handle = new Handle(true);
      f.run.launcher = handle;
      f.run.bridge =
          new WebProcessSession.BridgeLease() {
            public void ensureStarted() {}

            public void close() {
              throw new IllegalStateException("BRIDGE_CLOSE_FAILED");
            }
          };
      var attempt =
          f.stop(
              () -> {
                handle.alive = false;
                f.run.launcher = null;
              },
              () -> f.session.releaseBridge(f.run.generation));
      assertEquals(
          StopCompletion.Outcome.EXITED,
          attempt.completion().facts().get(StopCompletion.Scope.OWNED_LAUNCHERS).getOutcome());
      assertEquals(StopCompletion.Finish.FAILED, attempt.completion().finish());
      assertFalse(attempt.completion().complete());
    }
  }
}
