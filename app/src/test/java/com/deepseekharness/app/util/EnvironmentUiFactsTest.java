package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 当前三条页面读取路径的结构对照；读口失败不重试，也不顺带读取其他入口的事实。 */
public final class EnvironmentUiFactsTest {
  private static final class Reads implements EnvironmentUiFacts.Reader {
    final List<String> calls = new ArrayList<>();
    boolean runnable, installed, latest, pending, busy, maintenance, entry, repairs;
    String fail = "";

    private boolean read(String name, boolean value) {
      calls.add(name);
      if (name.equals(fail)) throw new IllegalStateException(name);
      return value;
    }

    public boolean runnable() {
      return read("runnable", runnable);
    }

    public boolean installed() {
      return read("installed", installed);
    }

    public boolean latest() {
      return read("latest", latest);
    }

    public boolean hostPending() {
      return read("pending", pending);
    }

    public boolean maintenanceBusy() {
      return read("busy", busy);
    }

    public boolean requiresMaintenance() {
      return read("maintenance", maintenance);
    }

    public boolean blocksEntry() {
      return read("entry", entry);
    }

    public boolean repairsPending() {
      return read("repairs", repairs);
    }
  }

  @Test
  public void deploymentPreservesAllThreeFactsAndTheirReadOrder() {
    for (int mask = 0; mask < 8; mask++) {
      Reads reads = new Reads();
      reads.runnable = bit(mask, 0);
      reads.pending = bit(mask, 1);
      reads.installed = bit(mask, 2);
      var result = EnvironmentUiFacts.deployment(reads);
      assertEquals(reads.runnable, result.runnable());
      assertEquals(reads.pending, result.hostPending());
      assertEquals(reads.installed, result.installed());
      assertEquals(List.of("runnable", "pending", "installed"), reads.calls);
    }
  }

  @Test
  public void deploymentFailureRetainsOnlyFactsReadBeforeFailure() {
    List<String> order = List.of("runnable", "pending", "installed");
    for (int failure = 0; failure < order.size(); failure++) {
      Reads reads = new Reads();
      reads.runnable = reads.pending = reads.installed = true;
      reads.fail = order.get(failure);
      var result = EnvironmentUiFacts.deployment(reads);
      assertFalse(result.runnable());
      // 原实现安装探测失败时仍保留已采到的 pending，不能把它折成无待恢复事务。
      assertEquals(failure == 2, result.hostPending());
      assertFalse(result.installed());
      assertEquals(order.subList(0, failure + 1), reads.calls);
    }
  }

  @Test
  public void bannerMatchesExistingProjectionWithoutEagerEntryReads() {
    for (int mask = 0; mask < 16; mask++) {
      Reads reads = new Reads();
      reads.runnable = bit(mask, 0);
      reads.latest = bit(mask, 1);
      reads.entry = bit(mask, 2);
      reads.repairs = bit(mask, 3);
      var result = EnvironmentUiFacts.banner(reads);
      boolean runtimeMaintenance = !reads.runnable || !reads.latest;
      assertEquals(reads.runnable, result.runnable());
      assertEquals(runtimeMaintenance || reads.entry, result.recovery());
      assertEquals(reads.repairs, result.repairs());
      assertEquals(
          runtimeMaintenance
              ? List.of("runnable", "latest", "repairs")
              : List.of("runnable", "latest", "entry", "repairs"),
          reads.calls);
    }
  }

  @Test
  public void bannerDoesNotTouchUnavailableEntryWhenRuntimeMaintenanceAlreadyWins() {
    Reads reads = new Reads();
    reads.runnable = true;
    reads.latest = false;
    reads.fail = "entry";
    assertTrue(EnvironmentUiFacts.banner(reads).recovery());
    assertEquals(List.of("runnable", "latest", "repairs"), reads.calls);
  }

  @Test
  public void bannerReadFailuresPropagateOnceWithoutPublishingASuccess() {
    List<String> order = List.of("runnable", "latest", "entry", "repairs");
    for (int failure = 0; failure < order.size(); failure++) {
      Reads reads = new Reads();
      reads.runnable = reads.latest = true;
      reads.fail = order.get(failure);
      assertThrows(IllegalStateException.class, () -> EnvironmentUiFacts.banner(reads));
      assertEquals(order.subList(0, failure + 1), reads.calls);
    }
  }

  @Test
  public void onboardingKeepsOriginalShortCircuitForEveryInput() {
    for (int mask = 0; mask < 16; mask++) {
      Reads reads = new Reads();
      reads.busy = bit(mask, 0);
      reads.pending = bit(mask, 1);
      reads.runnable = bit(mask, 2);
      reads.maintenance = bit(mask, 3);
      boolean expected = !reads.busy && !reads.pending && reads.runnable && !reads.maintenance;
      assertEquals(expected, EnvironmentUiFacts.onboardingReady(reads));
      List<String> expectedReads = new ArrayList<>();
      expectedReads.add("busy");
      if (!reads.busy) {
        expectedReads.add("pending");
        if (!reads.pending) {
          expectedReads.add("runnable");
          if (reads.runnable) expectedReads.add("maintenance");
        }
      }
      assertEquals(expectedReads, reads.calls);
    }
  }

  @Test
  public void onboardingLeavesExceptionHandlingAtTheExistingCallerBoundary() {
    List<String> order = List.of("busy", "pending", "runnable", "maintenance");
    for (int failure = 0; failure < order.size(); failure++) {
      Reads reads = new Reads();
      reads.runnable = true;
      reads.fail = order.get(failure);
      assertThrows(IllegalStateException.class, () -> EnvironmentUiFacts.onboardingReady(reads));
      assertEquals(order.subList(0, failure + 1), reads.calls);
    }
  }

  @Test
  public void bannerOwnerExceptionDoesNotRelaxOnboardingDisplayGate() {
    Reads reads = new Reads();
    reads.runnable = reads.latest = true;
    // 主界面 live owner 不驱逐页面，但引导仍按原 pending 门禁留在引导。
    reads.pending = true;
    reads.entry = false;
    assertFalse(EnvironmentUiFacts.banner(reads).recovery());
    reads.calls.clear();
    assertFalse(EnvironmentUiFacts.onboardingReady(reads));
    assertEquals(List.of("busy", "pending"), reads.calls);
  }

  private static boolean bit(int mask, int bit) {
    return (mask & (1 << bit)) != 0;
  }
}
