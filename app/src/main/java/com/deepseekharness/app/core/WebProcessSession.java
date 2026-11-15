package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.WebLifecycleController.WebRun;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.StopCompletion;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** Owns exact launcher handles and bridge leases; unknown guest exit still requires PID proof. */
final class WebProcessSession {
  interface BridgeLease extends AutoCloseable {
    void ensureStarted();

    @Override
    void close();
  }

  interface Bridges {
    BridgeLease acquire();
  }

  interface SessionRecords {
    com.deepseekharness.app.runtime.StoredWebSession.Record read() throws IOException;
  }

  private final HarnessSessionState state;
  private final WebProcessManager manager;
  private final Bridges bridges;
  private Runnable stopEvidence = () -> {};

  WebProcessSession(HarnessSessionState state, WebProcessManager manager, Bridges bridges) {
    this.state = state;
    this.manager = manager;
    this.bridges = bridges;
  }

  WebProcessSession(
      HarnessSessionState state,
      WebProcessManager manager,
      Bridges bridges,
      SessionRecords records) {
    this(state, manager, bridges);
    installStopEvidence(records);
  }

  WebProcessSession(Context context, HarnessSessionState state, WebProcessManager manager) {
    this(
        state,
        manager,
        () -> {
          HttpShellService.Lease lease = HttpShellService.acquire(context);
          return new BridgeLease() {
            public void ensureStarted() {
              lease.ensureStarted();
            }

            public void close() {
              lease.close();
            }
          };
        });
    installStopEvidence(() -> new com.deepseekharness.app.runtime.StoredWebSession(context).read());
  }

  private void installStopEvidence(SessionRecords records) {
    stopEvidence =
        () -> {
          synchronized (state.webStopProofLock) {
            if (state.webStopProofLoaded) return;
            try {
              var record = records.read();
              if (record != null) {
                // 重读成功：以可核验的原记录代替先前“不可读”的保守结论，屏障仍按其原启动器判定。
                state.retainedWebStopProof = record.proof();
                state.unreadableWebStopProof = false;
              } else if (manager.hasRecordedSession()) state.unreadableWebStopProof = true;
              state.webStopProofLoaded = true;
            } catch (Exception unknown) {
              // 读取失败只保留“不可读”屏障，不锁存：覆盖安装后私有目录或密钥可能暂时不可用，
              // 若整进程只读一次，之后每次停止 / 启动都会卡在 retire-prior-launchers，直到进程被杀。
              state.unreadableWebStopProof = true;
            }
          }
        };
  }

  /** 清理前捕获精确对象；空共享 map 永远不能替代原对象退出事实。 */
  static final class StopCapture {
    final java.util.List<StoppedRun> runs;
    final WebRun current;
    final com.deepseekharness.app.util.WebSessionProof.Saved retained;
    final boolean unreadable;
    final boolean bindingUnchanged;

    StopCapture(HarnessSessionState state) {
      synchronized (state.lifecycle) {
        current = state.currentRun;
        retained = state.retainedWebStopProof;
        unreadable = state.unreadableWebStopProof;
        var selected = new java.util.ArrayList<>(state.runs.values());
        if (current != null && !selected.contains(current)) selected.add(current);
        java.util.List<StoppedRun> snapshots = new java.util.ArrayList<>();
        for (WebRun run : selected) snapshots.add(new StoppedRun(run, run.recovered, run.launcher));
        runs = java.util.List.copyOf(snapshots);
        bindingUnchanged = true;
      }
    }

    StopCapture(StopCapture requested, HarnessSessionState state) {
      synchronized (state.lifecycle) {
        current = requested.current;
        runs = requested.runs;
        retained = state.retainedWebStopProof;
        unreadable = state.unreadableWebStopProof;
        boolean same =
            requested.bindingUnchanged
                && state.currentRun == current
                && (requested.retained == null || requested.retained == retained);
        for (StoppedRun saved : runs) {
          WebRun value = state.runs.get(saved.run().generation);
          if (value != null && value != saved.run()
              || saved.run().launcher != null && saved.run().launcher != saved.launcher()
              || saved.run().recovered != null && saved.run().recovered != saved.recovered())
            same = false;
        }
        bindingUnchanged = same;
      }
    }
  }

  StopCapture captureStop() {
    return new StopCapture(state);
  }

  /** loader 可补入原保存证明，原 run/handle 则继续使用入队前捕获的对象。 */
  StopCapture loadedStopCapture(StopCapture requested) {
    return new StopCapture(requested, state);
  }

  WebStopCoordinator.Observation observeStop(
      StopCapture captured, WebProcessManager.StopTrace trace) {
    var platform = manager.finishStopObservation(trace);
    java.util.List<StopCompletion.Fact> facts = new java.util.ArrayList<>(platform.facts());
    boolean owned = true,
        recovered = !captured.unreadable,
        stable = platform.unchanged() && captured.bindingUnchanged;
    StringBuilder identities = new StringBuilder();
    for (StoppedRun saved : captured.runs) {
      WebRun run = saved.run();
      identities.append(run.generation).append(':').append(run.instanceId).append(';');
      if (saved.launcher() != null && Compat.isAlive(saved.launcher())) owned = false;
      if (saved.recovered() != null) {
        try {
          var proof = saved.recovered().proof();
          if (proof == null || !manager.recoveredLauncherExited(proof)) recovered = false;
          if (proof != null)
            identities
                .append(proof.boot())
                .append(':')
                .append(proof.launcherPid())
                .append(':')
                .append(proof.launcherStarted())
                .append(';');
        } catch (IOException | RuntimeException unknown) {
          recovered = false;
        }
      }
      WebRun now = state.runs.get(run.generation);
      if (now != null && now != run
          || run.launcher != null && run.launcher != saved.launcher()
          || run.recovered != null && run.recovered != saved.recovered()) stable = false;
    }
    if (captured.retained != null) {
      identities
          .append(captured.retained.boot())
          .append(':')
          .append(captured.retained.launcherPid())
          .append(':')
          .append(captured.retained.launcherStarted())
          .append(';');
      try {
        if (!manager.recoveredLauncherExited(captured.retained)) recovered = false;
      } catch (IOException | RuntimeException unknown) {
        recovered = false;
      }
    }
    synchronized (state.lifecycle) {
      if (state.currentRun != captured.current
          || state.retainedWebStopProof != null && state.retainedWebStopProof != captured.retained)
        stable = false;
      for (WebRun run : state.runs.values())
        if (captured.runs.stream().noneMatch(saved -> saved.run() == run)) stable = false;
    }
    long now = android.os.SystemClock.elapsedRealtime();
    StopCompletion.Fact guest =
        facts.stream()
            .filter(value -> value.getScope() == StopCompletion.Scope.GUEST)
            .findFirst()
            .orElse(null);
    java.util.List<StopCompletion.ProcessKey> requiredGuests = new java.util.ArrayList<>();
    boolean instancesMatch = true, targetBound = false;
    for (StoppedRun saved : captured.runs) {
      if (saved.recovered() == null) continue;
      var proof = saved.recovered().proof();
      requiredGuests.add(guestKey(proof));
      if (proof == null
          || proof.generation() != saved.run().generation
          || !java.util.Objects.equals(proof.instance(), saved.run().instanceId))
        instancesMatch = false;
      if (saved.run() == captured.current && proof != null) targetBound = true;
    }
    if (captured.retained != null) {
      requiredGuests.add(guestKey(captured.retained));
      boolean sameInstance =
          captured.current == null
              || captured.retained.generation() == captured.current.generation
                  && java.util.Objects.equals(
                      captured.retained.instance(), captured.current.instanceId);
      instancesMatch &= sameInstance;
      targetBound |= sameInstance;
    }
    facts.removeIf(value -> value.getScope() == StopCompletion.Scope.GUEST);
    facts.add(
        com.deepseekharness.app.util.StopHandoff.bindGuest(
            guest, requiredGuests, instancesMatch && targetBound, now));
    // 已捕获空集合只证明本次没有 owned handle；guest 与残留各有独立 scope。
    facts.add(
        new StopCompletion.Fact(
            StopCompletion.Scope.OWNED_LAUNCHERS,
            owned ? StopCompletion.Outcome.EXITED : StopCompletion.Outcome.UNCONFIRMED,
            now,
            "captured-owned:" + identities));
    facts.add(
        new StopCompletion.Fact(
            StopCompletion.Scope.RECOVERED_LAUNCHERS,
            recovered ? StopCompletion.Outcome.EXITED : StopCompletion.Outcome.UNKNOWN,
            now,
            "captured-recovered:" + identities));
    return new WebStopCoordinator.Observation(platform.environment(), facts, stable);
  }

  private static StopCompletion.ProcessKey guestKey(
      com.deepseekharness.app.util.WebSessionProof.Saved proof) {
    if (proof == null
        || proof.boot() == null
        || proof.boot().isEmpty()
        || proof.webPid() <= 1
        || proof.webStarted() <= 0) return null;
    return new StopCompletion.ProcessKey(proof.boot(), proof.webPid(), proof.webStarted());
  }

  boolean running() {
    return manager.isRunning();
  }

  void loadStopEvidence() {
    stopEvidence.run();
  }

  boolean confirmStopped() throws IOException {
    return confirmStopped(null);
  }

  boolean confirmStopped(WebProcessManager.StopTrace trace) throws IOException {
    // Start/Stop advances the lifecycle generation before proving the previous runtime exited.
    // Capture only those prior objects: a later run or a replaced proof cannot inherit this result.
    java.util.ArrayList<StoppedRun> candidates = new java.util.ArrayList<>();
    long through;
    synchronized (state.lifecycle) {
      through = state.lifecycle.generation() - 1;
      for (WebRun run : state.runs.values())
        if (run.generation <= through && run.recovered != null)
          candidates.add(new StoppedRun(run, run.recovered, run.launcher));
    }
    boolean live = hasLiveLaunchers();
    if (!(trace == null ? manager.confirmStopped(live) : manager.confirmStopped(live, trace)))
      return false;
    synchronized (state.lifecycle) {
      for (StoppedRun candidate : candidates) {
        WebRun run = candidate.run();
        if (run.generation > through
            || state.runs.get(run.generation) != run
            || run.recovered != candidate.recovered()
            || run.launcher != null && run.launcher != candidate.launcher()) continue;
        // Full guest + launcher exit is now proven. Keeping this old PID proof would allow its
        // future reuse or permission changes to turn an already completed Stop back into UNKNOWN.
        run.recovered = null;
        run.recoveredLive = false;
        run.confirmedWebExit = true;
        prune(run);
      }
    }
    return true;
  }

  private record StoppedRun(
      WebRun run,
      com.deepseekharness.app.runtime.StoredWebSession.Record recovered,
      Process launcher) {}

  boolean hasLiveLaunchers() {
    boolean alive = retainedLauncherPending();
    for (WebRun run : state.runs.values()) {
      if (run.recovered != null) {
        try {
          if (!manager.recoveredLauncherExited(run.recovered.proof())) alive = true;
        } catch (IOException unknown) {
          alive = true;
        }
      }
      Process process = run.launcher;
      if (process == null) prune(run);
      else if (Compat.isAlive(process)) alive = true;
      else {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      }
    }
    return alive;
  }

  java.util.List<com.deepseekharness.app.util.WebStopDiagnostic> stopOwnedLauncherFacts() {
    loadStopEvidence();
    Integer pendingPid = null;
    for (WebRun run : state.runs.values()) {
      Process process = run.launcher;
      if (process == null || !Compat.isAlive(process)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else if (Compat.requestGracefulStop(process, 4_000)) {
        if (run.launcher == process) run.launcher = null;
        prune(run);
      } else pendingPid = launcherPid(process);
    }
    boolean pending = pendingPid != null;
    pending |= hasLiveLaunchers();
    if (!pending) return java.util.List.of();
    // 启动前置停止的失败文案需要“已核验残留 PID + 命令摘要”；这里只读取，不发信号。
    java.util.List<Object> facts = new java.util.ArrayList<>();
    if (pendingPid != null) {
      facts.add("PID=" + pendingPid);
      String digest = manager.processCommandDigest(pendingPid);
      if (!digest.isEmpty()) facts.add("命令摘要=" + digest);
      facts.add("旧容器启动器");
    }
    return java.util.List.of(
        com.deepseekharness.app.util.WebStopDiagnostic.of(
            com.deepseekharness.app.util.WebStopDiagnostic.Code.OWNED_LAUNCHER_PENDING,
            facts.toArray()));
  }

  /** PID 只用于展示，读不到也不影响待确认结论（Android 的 Process 没有公开 pid 访问器）。 */
  private static Integer launcherPid(Process process) {
    try {
      int pid =
          com.deepseekharness.app.util.ProcessIdentity.androidPid(
              process.getClass().getName(), process.toString());
      return pid > 1 ? pid : null;
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  String stopOwnedLaunchers() {
    return WebStopText.renderAll(stopOwnedLauncherFacts());
  }

  private boolean retainedLauncherPending() {
    // 所有停止、维护和新启旧实例退休入口共用，不能被先到达的Stop跳过恢复读取。
    stopEvidence.run();
    try {
      if (state.retainedWebStopProof != null
          && !manager.recoveredLauncherExited(state.retainedWebStopProof)) return true;
      return state.unreadableWebStopProof && !manager.confirmNoUntrackedWebLaunchers();
    } catch (IOException unknown) {
      return true;
    }
  }

  void acquireBridge(WebRun run) {
    BridgeLease lease = run.bridge;
    BridgeLease candidate = lease == null ? bridges.acquire() : null;
    synchronized (state.lifecycle) {
      if (state.lifecycle.isCurrent(run.generation)) {
        synchronized (run) {
          if (run.bridge == null) {
            run.bridge = candidate;
            candidate = null;
          }
          lease = run.bridge;
        }
      } else lease = null;
    }
    if (candidate != null) candidate.close();
    if (lease != null) lease.ensureStarted();
  }

  void releaseBridge(long generation) {
    WebRun run = state.runs.get(generation);
    if (run == null) return;
    BridgeLease lease;
    synchronized (run) {
      lease = run.bridge;
      run.bridge = null;
    }
    if (lease != null) lease.close();
    prune(run);
  }

  void releasePrior(long generation) {
    for (long old : state.runs.keySet()) if (old != generation) releaseBridge(old);
  }

  private void prune(WebRun run) {
    if (run != state.currentRun
        && run.bridge == null
        && run.recovered == null
        && (run.launcher == null || !Compat.isAlive(run.launcher)))
      state.runs.remove(run.generation, run);
  }
}
