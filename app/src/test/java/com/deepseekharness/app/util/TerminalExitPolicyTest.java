package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 可控内核观察反例；不发送宿主 PID 信号，不冒充 Android /proc 或 JNI 验收。 */
public class TerminalExitPolicyTest {
  private static final int LEADER = 31, GUEST = 42, UID = 10001;

  private static ProcessIdentity identity(int pid, int parent, int group, int session, long birth) {
    return ProcessIdentity.inSession(
        pid
            + " (terminal job) S "
            + parent
            + " "
            + group
            + " "
            + session
            + " 0".repeat(15)
            + " "
            + birth
            + " 0 0",
        pid,
        session);
  }

  private static ProcessIdentity leader() {
    return identity(LEADER, 7, LEADER, LEADER, 100);
  }

  private interface Read {
    ProcessIdentity get(int pid, int session) throws IOException;
  }

  private interface Scan {
    int[] get() throws IOException;
  }

  private interface Sent {
    void accept(int pid, TerminalExitPolicy.Signal signal) throws IOException;
  }

  private static final class Kernel implements TerminalExitPolicy.Ports {
    final Map<Integer, ProcessIdentity> live = new LinkedHashMap<>();
    final Map<Integer, Integer> uids = new HashMap<>();
    final List<String> signals = new ArrayList<>();
    long clock;
    int scans;
    boolean ignoreKill, interrupted;
    Read reading;
    Scan scanning;
    Sent sent;
    Runnable paused = () -> {};

    Kernel() {
      add(leader());
    }

    void add(ProcessIdentity identity) {
      live.put(identity.pid, identity);
      uids.put(identity.pid, UID);
    }

    void guest() {
      add(identity(GUEST, LEADER, GUEST, LEADER, 200));
    }

    ProcessIdentity current(int pid, int session) {
      ProcessIdentity value = live.get(pid);
      return value != null && value.session == session ? value : null;
    }

    @Override
    public ProcessIdentity read(int pid, int session) throws IOException {
      return reading == null ? current(pid, session) : reading.get(pid, session);
    }

    @Override
    public boolean belongsToSession(int pid, int session) {
      return current(pid, session) != null;
    }

    @Override
    public int uid(int pid) {
      return live.containsKey(pid) ? uids.get(pid) : -1;
    }

    @Override
    public int ownUid() {
      return UID;
    }

    @Override
    public int[] pids() throws IOException {
      scans++;
      return scanning == null
          ? live.keySet().stream().mapToInt(Integer::intValue).toArray()
          : scanning.get();
    }

    @Override
    public void signal(int pid, TerminalExitPolicy.Signal signal) throws IOException {
      assertTrue("never signal a raw/negative group", pid > 1);
      signals.add(signal + ":" + pid);
      if (sent != null) sent.accept(pid, signal);
      if (signal == TerminalExitPolicy.Signal.KILL && !ignoreKill) live.remove(pid);
    }

    @Override
    public long now() {
      return clock;
    }

    @Override
    public void pause(long millis) throws InterruptedException {
      assertTrue(millis > 0 && millis <= 20);
      if (interrupted) throw new InterruptedException("fixture interrupted");
      clock += millis;
      paused.run();
    }
  }

  private static TerminalExitPolicy.Failure failed(Kernel kernel) {
    return assertThrows(
        TerminalExitPolicy.Failure.class, () -> TerminalExitPolicy.close(leader(), 100, kernel));
  }

  @Test
  public void guestInAnotherJobGroupExitsBeforeLauncherAndAnEmptySessionIsObserved()
      throws Exception {
    Kernel kernel = new Kernel();
    kernel.guest();
    kernel.add(identity(50, 7, 50, 50, 500));
    TerminalExitPolicy.close(leader(), 100, kernel);
    assertEquals(List.of("STOP:31", "KILL:42", "KILL:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(50));
    assertTrue("two empty guest scans and final exit scan", kernel.scans >= 4);
    TerminalExitPolicy.requireSessionEmpty(LEADER, kernel);
  }

  @Test
  public void aChildBornAfterTheFirstEmptySnapshotIsReapedBeforeLauncher() throws Exception {
    Kernel kernel = new Kernel();
    kernel.scanning =
        () -> {
          if (kernel.scans == 1) {
            kernel.guest();
            return new int[] {LEADER};
          }
          return kernel.live.keySet().stream().mapToInt(Integer::intValue).toArray();
        };
    TerminalExitPolicy.close(leader(), 100, kernel);
    assertEquals(List.of("STOP:31", "KILL:42", "KILL:31"), kernel.signals);
    assertTrue(kernel.scans >= 5);
  }

  @Test
  public void aLateGuestAfterLauncherSignalBlocksSuccessWithoutOrphanKillFallback() {
    Kernel kernel = new Kernel();
    kernel.sent =
        (pid, signal) -> {
          if (pid == LEADER && signal == TerminalExitPolicy.Signal.KILL) kernel.guest();
        };
    assertEquals(TerminalExitPolicy.Code.EXIT_TIMEOUT, failed(kernel).code());
    assertEquals(List.of("STOP:31", "KILL:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(GUEST));
    assertThrows(
        TerminalExitPolicy.Failure.class,
        () -> TerminalExitPolicy.requireSessionEmpty(LEADER, kernel));
  }

  @Test
  public void finalEmptySnapshotIsRecheckedBeforeReturningSuccess() {
    Kernel kernel = new Kernel();
    AtomicInteger finalScans = new AtomicInteger();
    kernel.scanning =
        () -> {
          if (kernel.signals.contains("KILL:31") && finalScans.incrementAndGet() == 1) {
            kernel.guest();
            return new int[0];
          }
          return kernel.live.keySet().stream().mapToInt(Integer::intValue).toArray();
        };
    assertEquals(TerminalExitPolicy.Code.EXIT_TIMEOUT, failed(kernel).code());
    assertEquals(List.of("STOP:31", "KILL:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(GUEST));
  }

  @Test
  public void naturalExitAbsenceChecksDoNotTrustASingleEmptySnapshot() {
    Kernel kernel = new Kernel();
    kernel.live.clear();
    kernel.scanning =
        () -> {
          if (kernel.scans == 1) {
            kernel.guest();
            return new int[0];
          }
          return new int[] {GUEST};
        };
    assertEquals(TerminalExitPolicy.Code.SESSION_NOT_EMPTY, failed(kernel).code());
    assertTrue(kernel.signals.isEmpty());
  }

  @Test
  public void foreignLeaderUidCannotAuthorizeAnySignal() {
    Kernel kernel = new Kernel();
    kernel.uids.put(LEADER, UID + 1);
    assertEquals(TerminalExitPolicy.Code.FOREIGN_UID, failed(kernel).code());
    assertTrue(kernel.signals.isEmpty());
  }

  @Test
  public void foreignGuestUidKeepsLauncherAndNeverReceivesSignal() {
    Kernel kernel = new Kernel();
    kernel.guest();
    kernel.uids.put(GUEST, UID + 1);
    assertEquals(TerminalExitPolicy.Code.FOREIGN_UID, failed(kernel).code());
    assertEquals(List.of("STOP:31", "CONTINUE:31"), kernel.signals);
  }

  @Test
  public void secondLeaderBirthReadChangedBeforeStopCannotSignal() {
    Kernel kernel = new Kernel();
    AtomicInteger reads = new AtomicInteger();
    kernel.reading =
        (pid, session) ->
            reads.incrementAndGet() == 1 ? leader() : identity(LEADER, 7, LEADER, LEADER, 101);
    assertEquals(TerminalExitPolicy.Code.IDENTITY_CHANGED, failed(kernel).code());
    assertTrue(kernel.signals.isEmpty());
  }

  @Test
  public void memberBirthChangesBetweenCaptureAndSignalAndRetainsTheLeader() {
    Kernel kernel = new Kernel();
    kernel.guest();
    AtomicInteger reads = new AtomicInteger();
    kernel.reading =
        (pid, session) -> {
          if (pid == GUEST && reads.incrementAndGet() > 1)
            return identity(GUEST, LEADER, GUEST, LEADER, 201);
          return kernel.current(pid, session);
        };
    assertEquals(TerminalExitPolicy.Code.IDENTITY_CHANGED, failed(kernel).code());
    assertEquals(List.of("STOP:31", "CONTINUE:31"), kernel.signals);
  }

  @Test
  public void memberLeavingTheSessionIsNotSignalledUsingTheOldPid() throws Exception {
    Kernel kernel = new Kernel();
    kernel.guest();
    AtomicInteger reads = new AtomicInteger();
    kernel.reading =
        (pid, session) -> {
          if (pid == GUEST && reads.incrementAndGet() == 2)
            kernel.add(identity(GUEST, 7, GUEST, GUEST, 200));
          return kernel.current(pid, session);
        };
    TerminalExitPolicy.close(leader(), 100, kernel);
    assertEquals(List.of("STOP:31", "KILL:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(GUEST));
  }

  @Test
  public void leaderGoneDuringGuestCaptureDoesNotAuthorizeAGuestSignal() {
    Kernel kernel = new Kernel();
    kernel.guest();
    kernel.reading =
        (pid, session) -> {
          if (pid == GUEST) kernel.live.remove(LEADER);
          return kernel.current(pid, session);
        };
    assertEquals(TerminalExitPolicy.Code.IDENTITY_CHANGED, failed(kernel).code());
    assertEquals(List.of("STOP:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(GUEST));
  }

  @Test
  public void deniedOrTruncatedIdentityIsUnknownAndNeverExitEvidence() {
    for (String message : List.of("EPERM", "truncated stat")) {
      Kernel kernel = new Kernel();
      kernel.reading =
          (pid, session) -> {
            throw new IOException(message);
          };
      assertEquals(
          message,
          assertThrows(IOException.class, () -> TerminalExitPolicy.close(leader(), 100, kernel))
              .getMessage());
      assertTrue(kernel.signals.isEmpty());
    }
  }

  @Test
  public void nullOrInvalidSnapshotRetainsProtectionAndResumesOnlyVerifiedLeader() {
    for (int[] snapshot : new int[][] {null, {0}, {-31}}) {
      Kernel kernel = new Kernel();
      kernel.scanning = () -> snapshot;
      assertEquals(TerminalExitPolicy.Code.SCAN_UNAVAILABLE, failed(kernel).code());
      assertEquals(List.of("STOP:31", "CONTINUE:31"), kernel.signals);
    }
  }

  @Test
  public void unreadableSnapshotIsNotReplayedAndCleanupPreservesOriginalFailure() {
    Kernel kernel = new Kernel();
    kernel.scanning =
        () -> {
          throw new IOException("scan denied");
        };
    kernel.sent =
        (pid, signal) -> {
          if (signal == TerminalExitPolicy.Signal.CONTINUE) throw new IOException("resume denied");
        };
    IOException failure =
        assertThrows(IOException.class, () -> TerminalExitPolicy.close(leader(), 100, kernel));
    assertEquals("scan denied", failure.getMessage());
    assertEquals("resume denied", failure.getSuppressed()[0].getMessage());
    assertEquals(1, kernel.scans);
    assertEquals(List.of("STOP:31", "CONTINUE:31"), kernel.signals);
  }

  @Test
  public void deniedStopDoesNotTryKillOrClaimExit() {
    Kernel kernel = new Kernel();
    kernel.sent =
        (pid, signal) -> {
          throw new IOException("signal denied");
        };
    assertThrows(IOException.class, () -> TerminalExitPolicy.close(leader(), 100, kernel));
    assertEquals(List.of("STOP:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(LEADER));
  }

  @Test
  public void deniedGuestKillAbortsWithoutReplayingOrKillingLauncher() {
    Kernel kernel = new Kernel();
    kernel.guest();
    kernel.sent =
        (pid, signal) -> {
          if (pid == GUEST) throw new IOException("guest signal denied");
        };
    assertThrows(IOException.class, () -> TerminalExitPolicy.close(leader(), 100, kernel));
    assertEquals(List.of("STOP:31", "KILL:42", "CONTINUE:31"), kernel.signals);
    assertTrue(kernel.live.containsKey(LEADER));
  }

  @Test
  public void ignoredGuestKillExpiresAndDoesNotSignalLauncherKill() {
    Kernel kernel = new Kernel();
    kernel.guest();
    kernel.ignoreKill = true;
    assertEquals(TerminalExitPolicy.Code.EXIT_TIMEOUT, failed(kernel).code());
    assertFalse(kernel.signals.contains("KILL:31"));
    assertEquals("CONTINUE:31", kernel.signals.get(kernel.signals.size() - 1));
  }

  @Test
  public void ignoredLauncherKillDoesNotClaimSuccessOrResumeAfterKillWasSent() {
    Kernel kernel = new Kernel();
    kernel.ignoreKill = true;
    assertEquals(TerminalExitPolicy.Code.EXIT_TIMEOUT, failed(kernel).code());
    assertEquals(List.of("STOP:31", "KILL:31"), kernel.signals);
  }

  @Test
  public void delayedLauncherExitIsObservedBeforeSuccessfulReturn() throws Exception {
    Kernel kernel = new Kernel();
    kernel.ignoreKill = true;
    kernel.paused =
        () -> {
          if (kernel.signals.contains("KILL:31")) kernel.live.remove(LEADER);
        };
    TerminalExitPolicy.close(leader(), 100, kernel);
    assertTrue(kernel.clock >= 40);
    assertFalse(kernel.live.containsKey(LEADER));
  }

  @Test
  public void goneLeaderOnlyChecksAbsenceAndNeverKillsRemainingGuests() throws Exception {
    Kernel empty = new Kernel();
    empty.live.clear();
    TerminalExitPolicy.close(leader(), 100, empty);
    assertTrue(empty.signals.isEmpty());
    Kernel orphan = new Kernel();
    orphan.live.clear();
    orphan.guest();
    assertEquals(TerminalExitPolicy.Code.SESSION_NOT_EMPTY, failed(orphan).code());
    assertTrue(orphan.signals.isEmpty());
  }

  @Test
  public void prootQuitRemainsARequestNotAnExitReceipt() throws Exception {
    Kernel kernel = new Kernel();
    kernel.guest();
    TerminalExitPolicy.requestProotExit(leader(), kernel);
    assertEquals(List.of("QUIT:31"), kernel.signals);
    assertThrows(
        TerminalExitPolicy.Failure.class,
        () -> TerminalExitPolicy.requireSessionEmpty(LEADER, kernel));
    kernel.live.clear();
    TerminalExitPolicy.requestProotExit(leader(), kernel);
    assertEquals(1, kernel.signals.size());
  }

  @Test
  public void interruptionRestoresFlagAndDoesNotReleaseUnconfirmedSession() {
    Kernel kernel = new Kernel();
    kernel.interrupted = true;
    try {
      assertThrows(
          InterruptedException.class, () -> TerminalExitPolicy.close(leader(), 100, kernel));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(List.of("STOP:31", "CONTINUE:31"), kernel.signals);
      assertTrue(kernel.live.containsKey(LEADER));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void rollbackAndExhaustedBudgetsCannotBecomeUnlimitedCloseLoops() {
    Kernel rollback = new Kernel();
    rollback.paused = () -> rollback.clock = -1;
    assertEquals(TerminalExitPolicy.Code.EXIT_TIMEOUT, failed(rollback).code());
    assertEquals(List.of("STOP:31", "CONTINUE:31"), rollback.signals);
    for (long budget : new long[] {0, -1}) {
      Kernel kernel = new Kernel();
      assertEquals(
          TerminalExitPolicy.Code.EXIT_TIMEOUT,
          assertThrows(
                  TerminalExitPolicy.Failure.class,
                  () -> TerminalExitPolicy.close(leader(), budget, kernel))
              .code());
      assertTrue(kernel.signals.isEmpty());
    }
  }

  @Test
  public void invalidIdentityNeverReachesPlatformSignal() {
    for (ProcessIdentity identity :
        new ProcessIdentity[] {null, identity(LEADER, 7, 9, LEADER, 100)}) {
      Kernel kernel = new Kernel();
      assertEquals(
          TerminalExitPolicy.Code.INVALID_IDENTITY,
          assertThrows(
                  TerminalExitPolicy.Failure.class,
                  () -> TerminalExitPolicy.close(identity, 100, kernel))
              .code());
      assertTrue(kernel.signals.isEmpty());
    }
  }
}
