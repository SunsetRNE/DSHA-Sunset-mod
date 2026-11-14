package com.deepseekharness.app.plugins;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A5 退出证明的消费者；默认没有生产者，不从自由文本、PID 或界面确认创建证明。 */
public final class PluginExitProofGate {
  public static final long MAX_AGE_MILLIS = 30_000;
  private static final int MAX_CONSUMED = 64;

  public enum Outcome {
    EXITED_CONFIRMED,
    UNKNOWN
  }

  public record Binding(
      String operationId, String backendTaskId, Long generation, String instance) {
    public Binding {
      try {
        if (operationId == null
            || !operationId.matches("[a-f0-9]{32}")
                && !UUID.fromString(operationId).toString().equals(operationId))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException | NullPointerException invalid) {
        throw new IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING");
      }
      if (backendTaskId == null || !backendTaskId.matches("[a-f0-9]{32}"))
        throw new IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING");
      if (generation != null && generation < 0)
        throw new IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING");
      if (instance != null && (instance.isEmpty() || instance.length() > 128))
        throw new IllegalArgumentException("PLUGIN_EXIT_PROOF_BINDING");
    }
  }

  /** 数据只是可信 owner 解码的结果，本类型的公开构造器本身不授予证明效力。 */
  public record Evidence(
      Binding binding, Outcome outcome, String evidenceSource, long observedAt) {}

  public interface Owner {
    /** A5 须核对真实执行代次并撤销同一任务先前投递的 token；错配/未知返回 null。 */
    Evidence verify(Object opaqueToken);
  }

  private final Owner owner;
  private final Map<Object, Long> consumed = new IdentityHashMap<>();

  public PluginExitProofGate() {
    this(ignored -> null);
  }

  public PluginExitProofGate(Owner owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  public synchronized boolean consume(Object token, Binding expected, long retainedAt, long now) {
    if (token == null
        || token instanceof CharSequence
        || token instanceof Map<?, ?>
        || token instanceof Evidence
        || expected == null
        // 未接齐 A5 执行上下文时保持拒绝；null==null 不能变成精确绑定。
        || expected.generation == null
        || expected.instance == null
        || retainedAt <= 0
        || now < retainedAt) return false;
    consumed
        .entrySet()
        .removeIf(entry -> now >= entry.getValue() && now - entry.getValue() > MAX_AGE_MILLIS);
    if (consumed.containsKey(token) || consumed.size() >= MAX_CONSUMED) return false;
    Evidence evidence;
    try {
      evidence = owner.verify(token);
    } catch (RuntimeException unavailable) {
      return false;
    }
    if (evidence == null
        || evidence.outcome != Outcome.EXITED_CONFIRMED
        || evidence.binding == null
        || evidence.observedAt < retainedAt
        || evidence.observedAt > now
        || now - evidence.observedAt > MAX_AGE_MILLIS
        || evidence.evidenceSource == null
        || !evidence.evidenceSource.matches("[A-Z0-9_]{1,64}")) return false;
    Binding actual = evidence.binding;
    if (actual.generation == null || actual.instance == null) return false;
    if (!expected.operationId.equals(actual.operationId)
        || !expected.backendTaskId.equals(actual.backendTaskId)
        || !Objects.equals(expected.generation, actual.generation)
        || !Objects.equals(expected.instance, actual.instance)) return false;
    consumed.put(token, evidence.observedAt);
    return true;
  }
}
