package com.deepseekharness.app.core;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Monotonic preparation phases only; these observations never authorize launch or retry. */
final class StartupPreparationTiming {
  interface Operation<T> {
    T run() throws Exception;
  }

  private static final Pattern PHASE = Pattern.compile("[a-z][a-z-]{0,47}");
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,31}");
  private static final long MAX_OBSERVATION = 1_000_000_000L;

  private final Consumer<String> output;
  private final LongSupplier nanos;
  private final long started;
  private final Object emission = new Object();

  StartupPreparationTiming(Consumer<String> output) {
    this(output, System::nanoTime);
  }

  StartupPreparationTiming(Consumer<String> output, LongSupplier nanos) {
    this.output = Objects.requireNonNull(output);
    this.nanos = Objects.requireNonNull(nanos);
    started = nanos.getAsLong();
  }

  <T> T run(String phase, Operation<T> operation) throws Exception {
    if (!PHASE.matcher(phase).matches()) throw new IllegalArgumentException("STARTUP_TIMING_PHASE");
    long before = nanos.getAsLong();
    boolean completed = false;
    try {
      T result = operation.run();
      completed = true;
      return result;
    } finally {
      long now = nanos.getAsLong();
      emit(
          "STARTUP_PREP_TIMING: phase="
              + phase
              + " durationMs="
              + Math.max(0, now - before) / 1_000_000
              + " elapsedMs="
              + Math.max(0, now - started) / 1_000_000
              + " outcome="
              + (completed ? "returned" : "threw"));
    }
  }

  /**
   * 只记录固定阶段/键的整数观测（残留事实数、helper 调用数、固定受管条目数等）。
   *
   * <p>phase/key 不是用户文本：非法 token 或负数直接丢弃，超大计数封顶。这样诊断既不会把
   * 路径、凭据或异常消息带进启动记录，也不会因为观测拼写错误改变启动；缺数据时只是少一行。
   */
  void observe(String phase, String key, long value) {
    if (phase == null || key == null || value < 0) return;
    if (!PHASE.matcher(phase).matches() || !KEY.matcher(key).matches()) return;
    emit(
        "STARTUP_PREP_OBSERVATION: phase="
            + phase
            + " "
            + key
            + "="
            + Math.min(MAX_OBSERVATION, value));
  }

  private void emit(String line) {
    // 观测可以并发调用；串行化写出口，避免共用诊断收集器被交错写入。
    synchronized (emission) {
      try {
        output.accept(line);
      } catch (RuntimeException ignored) {
        // Diagnostic output must not replace an actual operation result or launch decision.
      }
    }
  }
}
