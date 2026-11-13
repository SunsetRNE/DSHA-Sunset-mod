package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPreparationTimingTest {
  @Test
  public void reportsSeparateDurationsAndCumulativeMonotonicTimeWithoutOperationContents()
      throws Exception {
    var clock = new AtomicLong(8_000_000);
    var messages = new ArrayList<String>();
    var timing = new StartupPreparationTiming(messages::add, clock::get);
    assertEquals(
        "private result",
        timing.run(
            "register-builtins",
            () -> {
              clock.addAndGet(17_000_000);
              return "private result";
            }));
    clock.addAndGet(3_000_000);
    timing.run(
        "legacy-plugin-reviews",
        () -> {
          clock.addAndGet(5_000_000);
          return null;
        });
    assertEquals(
        List.of(
            "STARTUP_PREP_TIMING: phase=register-builtins durationMs=17 elapsedMs=17 outcome=returned",
            "STARTUP_PREP_TIMING: phase=legacy-plugin-reviews durationMs=5 elapsedMs=25 outcome=returned"),
        messages);
    assertFalse(messages.toString().contains("private result"));
  }

  @Test
  public void diagnosticFailureDoesNotHideTheOriginalOperationFailureOrChangeItsResult()
      throws Exception {
    var timing =
        new StartupPreparationTiming(
            line -> {
              throw new IllegalStateException("observer");
            });
    var failure = new IOException("original failure");
    assertSame(
        failure,
        assertThrows(
            IOException.class,
            () ->
                timing.run(
                    "recover-data",
                    () -> {
                      throw failure;
                    })));
    assertEquals(42, (int) timing.run("runtime-tools", () -> 42));
  }

  @Test
  public void observationsAreBoundedSensitiveFreeAndMissingDataStaysAbsent() throws Exception {
    var messages = new ArrayList<String>();
    var timing = new StartupPreparationTiming(messages::add, () -> 0L);
    assertEquals(List.of(), messages);
    assertFalse(messages.stream().anyMatch(line -> line.startsWith("STARTUP_PREP_TIMING:")));
    timing.observe("stop-web", "facts", 3);
    timing.observe("guest-helper", "register-builtins", 1);
    timing.observe("runtime-tools", "files", 0);
    assertEquals(
        List.of(
            "STARTUP_PREP_OBSERVATION: phase=stop-web facts=3",
            "STARTUP_PREP_OBSERVATION: phase=guest-helper register-builtins=1",
            "STARTUP_PREP_OBSERVATION: phase=runtime-tools files=0"),
        messages);
    // 非固定 token 或缺失值（负数）不进入诊断，避免把用户路径 / 凭据 / 异常消息拼进来。
    timing.observe("user path /secret", "facts", 1);
    timing.observe("stop-web", "token sk-private", 1);
    timing.observe("stop-web", "facts", -1);
    timing.observe(null, "facts", 1);
    assertEquals(3, messages.size());
    timing.observe("stop-web", "facts", Long.MAX_VALUE);
    assertTrue(messages.get(3).endsWith("facts=1000000000"));
    String observed = messages.toString();
    assertFalse(observed.contains("secret"));
    assertFalse(observed.contains("/"));
  }

  @Test
  public void concurrentAndRepeatedCallsProduceIndependentWellFormedLines() throws Exception {
    var messages = java.util.Collections.synchronizedList(new ArrayList<String>());
    var timing = new StartupPreparationTiming(messages::add, System::nanoTime);
    String[] phases = {"stop-web", "retire-prior", "recover-data", "runtime-tools"};
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    try {
      var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
      for (int index = 0; index < 40; index++) {
        final String phase = phases[index % phases.length];
        tasks.add(
            () -> {
              timing.run(
                  phase,
                  () -> {
                    timing.observe("guest-helper", "register-builtins", 1);
                    return null;
                  });
              return null;
            });
      }
      for (var future : pool.invokeAll(tasks)) future.get();
    } finally {
      pool.shutdownNow();
    }
    var timingLines =
        messages.stream().filter(line -> line.startsWith("STARTUP_PREP_TIMING:")).toList();
    var observationLines =
        messages.stream().filter(line -> line.startsWith("STARTUP_PREP_OBSERVATION:")).toList();
    assertEquals(40, timingLines.size());
    assertEquals(40, observationLines.size());
    for (String line : timingLines) {
      assertTrue(
          line,
          line.matches(
              "STARTUP_PREP_TIMING: phase=[a-z-]+ durationMs=\\d+ elapsedMs=\\d+ outcome=returned"));
    }
    for (String line : observationLines)
      assertEquals("STARTUP_PREP_OBSERVATION: phase=guest-helper register-builtins=1", line);
  }

  @Test
  public void brokenObserverCannotEscapeFromObservationOrBreakRepeatedRuns() throws Exception {
    var timing =
        new StartupPreparationTiming(
            line -> {
              throw new IllegalStateException("observer");
            });
    timing.observe("stop-web", "facts", 2);
    assertEquals(7, (int) timing.run("runtime-tools", () -> 7));
  }

  @Test
  public void failureIsObservedWithoutBeingConvertedIntoSuccessAndInvalidPhasesNeverRun()
      throws Exception {
    var messages = new ArrayList<String>();
    var timing = new StartupPreparationTiming(messages::add, () -> 0L);
    assertThrows(
        IOException.class,
        () ->
            timing.run(
                "register-builtins",
                () -> {
                  throw new IOException();
                }));
    assertEquals(
        List.of(
            "STARTUP_PREP_TIMING: phase=register-builtins durationMs=0 elapsedMs=0 outcome=threw"),
        messages);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            timing.run(
                "user path /secret",
                () -> {
                  fail();
                  return null;
                }));
  }
}
