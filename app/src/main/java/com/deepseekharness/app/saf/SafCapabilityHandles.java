package com.deepseekharness.app.saf;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 授权能力句柄（纯逻辑，不 import Android API，可用 JVM 单测）。
 *
 * <p>句柄是「某次操作被允许」的短期凭据，桥/工作台拿它换一次读写删而不是直接持有 URI。
 * 每个句柄绑定：授权短 id、授权代次、操作类型、相对路径、到期时间。因此：
 *
 * <ul>
 *   <li>撤销授权或重新授权（{@link SafGrantLedger} 换 epoch）→ 旧句柄立即失效；</li>
 *   <li>系统撤销读/写位或用户关掉「允许写入」→ 使用前复查当前记录，句柄失效；</li>
 *   <li>句柄只能用于签发时的树/操作/路径，换路径或换操作一律拒绝；</li>
 *   <li>一次性操作（删除）用 {@link #consume}，成功后同一句柄不能重放。</li>
 * </ul>
 *
 * <p>句柄只在进程内存里，不落盘、不进日志、不跨进程传输；重启后自然失效。
 */
public final class SafCapabilityHandles {
  public static final long TTL_MS = SafCapabilityLimits.HANDLE_TTL_MS;
  public static final int MAX_OUTSTANDING = SafCapabilityLimits.MAX_OUTSTANDING_HANDLES;
  public static final int MAX_PER_TREE = SafCapabilityLimits.MAX_HANDLES_PER_TREE;
  private static final int MAX_TOKEN_ATTEMPTS = 8;
  private static final int TOKEN_BYTES = 16;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** 句柄允许的操作；写类操作还要求用户写入偏好为开。 */
  public enum Operation {
    READ,
    WRITE,
    CREATE,
    DELETE
  }

  /** 校验结果；除 {@code OK} 外都 fail-closed。 */
  public enum Result {
    OK,
    UNKNOWN,
    EXPIRED,
    REVOKED,
    NOT_PERMITTED,
    MISMATCH,
    BAD_PATH,
    LIMIT,
    UNAVAILABLE
  }

  /** 测试/宿主可注入的 token 生成器；生产默认使用 {@link SecureRandom} 十六进制。 */
  @FunctionalInterface
  public interface TokenSource {
    String next();
  }

  /** 签发结果；失败时 {@code token} 为空。 */
  public static final class Issued {
    public final String token;
    public final long expiresAt;
    public final Result result;

    private Issued(String token, long expiresAt, Result result) {
      this.token = token;
      this.expiresAt = expiresAt;
      this.result = result;
    }

    public boolean ok() {
      return result == Result.OK;
    }
  }

  private static final class Entry {
    final String treeId;
    final long epoch;
    final Operation operation;
    final String path;
    final long expiresAt;

    Entry(String treeId, long epoch, Operation operation, String path, long expiresAt) {
      this.treeId = treeId;
      this.epoch = epoch;
      this.operation = operation;
      this.path = path;
      this.expiresAt = expiresAt;
    }
  }

  private final Map<String, Entry> tokens = new LinkedHashMap<>();
  private final TokenSource tokenSource;

  public SafCapabilityHandles() {
    this(SafCapabilityHandles::secureToken);
  }

  public SafCapabilityHandles(TokenSource tokenSource) {
    this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
  }

  /**
   * 为一条当前有效的授权签发句柄。
   *
   * @param ledger 当前授权簿快照
   * @param treeId 授权短 id（{@code /app/saf/trees} 里的 id）
   * @param operation 允许的操作
   * @param path 相对授权根的路径；内部规范化为去首尾斜杠形式
   * @param nowMs 当前时间（毫秒），测试可注入
   */
  public synchronized Issued issue(
      SafGrantLedger ledger, String treeId, Operation operation, String path, long nowMs) {
    long now = Math.max(0, nowMs);
    purgeExpiredLocked(now);
    String normalized = normalizePath(path);
    if (normalized == null) return failed(Result.BAD_PATH);
    if (ledger == null) return failed(Result.UNKNOWN);
    SafGrantRecord record = ledger.findById(treeId);
    if (record == null) return failed(Result.UNKNOWN);
    if (record.state() != SafGrantRecord.State.ACTIVE || record.epoch() < 1)
      return failed(Result.REVOKED);
    if (!permitted(record, operation)) return failed(Result.NOT_PERMITTED);
    if (tokens.size() >= MAX_OUTSTANDING) return failed(Result.LIMIT);
    int perTree = countForTree(treeId);
    if (perTree >= MAX_PER_TREE) return failed(Result.LIMIT);
    String token = nextToken();
    if (token == null) return failed(Result.UNAVAILABLE);
    long expiresAt = now > Long.MAX_VALUE - TTL_MS ? Long.MAX_VALUE : now + TTL_MS;
    tokens.put(token, new Entry(treeId, record.epoch(), operation, normalized, expiresAt));
    return new Issued(token, expiresAt, Result.OK);
  }

  /** 校验句柄可执行该操作；不消费，用于读/写这类可重复操作。 */
  public synchronized Result check(
      SafGrantLedger ledger, String token, Operation operation, String path, long nowMs) {
    String normalized = normalizePath(path);
    if (normalized == null) return Result.BAD_PATH;
    Entry entry = token == null ? null : tokens.get(token);
    if (entry == null) return Result.UNKNOWN;
    return validate(entry, ledger, operation, normalized, Math.max(0, nowMs));
  }

  /**
   * 校验并消费句柄（一次性操作，例如删除）。
   *
   * <p>只有在 {@code OK} 时移除句柄；路径/操作不匹配时保留句柄，避免误把「参数写错」当成
   * 已消费而掩盖调用方缺陷。过期或授权已撤销的句柄顺带清理。
   */
  public synchronized Result consume(
      SafGrantLedger ledger, String token, Operation operation, String path, long nowMs) {
    String normalized = normalizePath(path);
    if (normalized == null) return Result.BAD_PATH;
    Entry entry = token == null ? null : tokens.get(token);
    if (entry == null) return Result.UNKNOWN;
    Result result = validate(entry, ledger, operation, normalized, Math.max(0, nowMs));
    if (result == Result.OK || result == Result.EXPIRED || result == Result.REVOKED)
      tokens.remove(token);
    return result;
  }

  /** 界面关闭操作面板或短请求结束时主动释放；不把其它待确认操作的句柄一起撤销。 */
  public synchronized void discard(String token) {
    if (token != null) tokens.remove(token);
  }

  /** 撤销一棵树的全部句柄（撤销授权时调用；代次检查是第二道保险）。 */
  public synchronized int revokeTree(String treeId) {
    if (treeId == null) return 0;
    int removed = 0;
    Iterator<Map.Entry<String, Entry>> iterator = tokens.entrySet().iterator();
    while (iterator.hasNext()) {
      if (treeId.equals(iterator.next().getValue().treeId)) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  /** 清理到期句柄；返回清理数量。 */
  public synchronized int purgeExpired(long nowMs) {
    return purgeExpiredLocked(Math.max(0, nowMs));
  }

  public synchronized int size() {
    return tokens.size();
  }

  private Result validate(
      Entry entry, SafGrantLedger ledger, Operation operation, String normalizedPath, long now) {
    if (entry.expiresAt <= now) return Result.EXPIRED;
    SafGrantRecord record = ledger == null ? null : ledger.findById(entry.treeId);
    if (record == null
        || record.state() != SafGrantRecord.State.ACTIVE
        || record.epoch() != entry.epoch
        || record.epoch() < 1) return Result.REVOKED;
    if (!permitted(record, operation)) return Result.NOT_PERMITTED;
    if (entry.operation != operation) return Result.MISMATCH;
    if (!entry.path.equals(normalizedPath)) return Result.MISMATCH;
    return Result.OK;
  }

  private static boolean permitted(SafGrantRecord record, Operation operation) {
    if (operation == null) return false;
    return switch (operation) {
      case READ -> record.canRead();
      case WRITE, CREATE, DELETE -> record.usableWrite();
    };
  }

  private int purgeExpiredLocked(long now) {
    int removed = 0;
    Iterator<Map.Entry<String, Entry>> iterator = tokens.entrySet().iterator();
    while (iterator.hasNext()) {
      if (iterator.next().getValue().expiresAt <= now) {
        iterator.remove();
        removed++;
      }
    }
    return removed;
  }

  private int countForTree(String treeId) {
    int count = 0;
    for (Entry entry : tokens.values()) if (treeId.equals(entry.treeId)) count++;
    return count;
  }

  private String nextToken() {
    for (int attempt = 0; attempt < MAX_TOKEN_ATTEMPTS; attempt++) {
      String candidate;
      try {
        candidate = tokenSource.next();
      } catch (RuntimeException unavailable) {
        return null;
      }
      if (validToken(candidate) && !tokens.containsKey(candidate)) return candidate;
    }
    return null;
  }

  private static boolean validToken(String token) {
    if (token == null || token.isEmpty() || token.length() > 128) return false;
    for (int index = 0; index < token.length(); index++) {
      char value = token.charAt(index);
      if (value <= ' ' || value == 127) return false;
    }
    return true;
  }

  private static String normalizePath(String path) {
    try {
      return SafPaths.normalize(path);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static String secureToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    StringBuilder out = new StringBuilder(TOKEN_BYTES * 2);
    for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value));
    return out.toString();
  }

  private static Issued failed(Result result) {
    return new Issued("", 0, result);
  }
}
