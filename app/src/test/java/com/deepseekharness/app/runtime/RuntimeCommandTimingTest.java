package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class RuntimeCommandTimingTest {
  @Test
  public void stagesUseOneMonotonicClockAndIndependentDurations() {
    AtomicLong clock = new AtomicLong(5_000_000_000L);
    List<String> lines = new ArrayList<>();
    RuntimeCommandTiming timing = new RuntimeCommandTiming(lines::add, clock::get);
    clock.addAndGet(12_000_000);
    timing.mark("native-files");
    clock.addAndGet(8_000_000);
    timing.mark("launcher-prepare");
    assertEquals(
        List.of(
            "CAS_HOST_TIMING: phase=native-files durationMs=12 elapsedMs=12",
            "CAS_HOST_TIMING: phase=launcher-prepare durationMs=8 elapsedMs=20"),
        lines);
  }

  @Test
  public void brokenDiagnosticConsumerDoesNotInterruptExecutionOrHideOriginalFailure() {
    AtomicLong clock = new AtomicLong();
    RuntimeCommandTiming timing =
        new RuntimeCommandTiming(
            line -> {
              throw new IllegalStateException("diagnostic writer unavailable");
            },
            clock::get);
    clock.set(1_000_000);
    timing.mark("command-failed");
    clock.set(2_000_000);
    timing.mark("exit-finalize");
  }

  @Test
  public void invalidPhaseOrMissingObservationStaysOutOfTheDiagnosticStream() {
    AtomicLong clock = new AtomicLong(5_000_000_000L);
    List<String> lines = new ArrayList<>();
    RuntimeCommandTiming timing = new RuntimeCommandTiming(lines::add, clock::get);
    timing.mark(null);
    timing.mark("user path /secret");
    timing.mark("phase.with.dot");
    assertTrue(lines.isEmpty());
    clock.set(6_000_000_000L);
    timing.mark("native-files");
    assertEquals(
        List.of("CAS_HOST_TIMING: phase=native-files durationMs=1000 elapsedMs=1000"), lines);
  }

  @Test
  public void concurrentMarksRemainWellFormedAndNeverEscalateDiagnosticFailures() throws Exception {
    AtomicLong clock = new AtomicLong();
    List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
    RuntimeCommandTiming timing = new RuntimeCommandTiming(lines::add, clock::get);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int index = 0; index < 40; index++) {
        final long step = index + 1L;
        tasks.add(
            () -> {
              clock.addAndGet(1_000_000);
              timing.mark(step % 2 == 0 ? "process-collect" : "launcher-prepare");
              return null;
            });
      }
      for (var future : pool.invokeAll(tasks)) future.get();
    } finally {
      pool.shutdownNow();
    }
    assertEquals(40, lines.size());
    for (String line : lines)
      assertTrue(
          line,
          line.matches(
              "CAS_HOST_TIMING: phase=(?:launcher-prepare|process-collect) "
                  + "durationMs=\\d+ elapsedMs=\\d+"));
    RuntimeCommandTiming broken =
        new RuntimeCommandTiming(
            line -> {
              throw new IllegalStateException("observer");
            },
            clock::get);
    broken.mark("exit-finalize");
  }
}
