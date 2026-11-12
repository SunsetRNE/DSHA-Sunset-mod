package com.deepseekharness.app.saf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/**
 * 授权簿的一条记录（纯逻辑，不 import Android API，可用 JVM 单测）。
 *
 * <p>记录只描述「用户选中的 tree、系统当前给的读/写位、本功能新拿到的位、用户写入偏好」，
 * 不保存文档内容。{@code epoch} 是这个 tree 的授权代次：撤销或重新授权后代次变化，旧能力句柄
 * 因此失效；{@code PENDING} 表示「已开始 take、尚未确认」的中间态，只在崩溃清理时使用。
 *
 * <p>系统持久 URI 授权是唯一真相：记录里的 {@code readable}/{@code writable} 只是上次观测值，
 * 每次真正使用授权前都必须用 {@link SafGrantLedger#refreshSystemFlags} 重新核对。
 */
public record SafGrantRecord(
    String id,
    String uri,
    String app,
    String label,
    boolean readable,
    boolean writable,
    boolean userWritable,
    int acquired,
    State state,
    long epoch,
    long takenAt,
    int pendingBefore,
    int pendingIntent) {

  /** 系统读位。 */
  public static final int READ = 1;

  /** 系统写位。 */
  public static final int WRITE = 2;

  /** 短 id 的十六进制长度；与桥 {@code /app/saf/trees} 的 id 保持一致。 */
  public static final int ID_LENGTH = 12;

  private static final int FLAGS = READ | WRITE;
  private static final int MAX_TEXT = 300;
  private static final java.util.regex.Pattern ID_PATTERN =
      java.util.regex.Pattern.compile("[0-9a-f]{" + ID_LENGTH + "}");

  /** 授权生命周期：待确认（take 之前/之后、remember 之前）与已登记。 */
  public enum State {
    PENDING,
    ACTIVE
  }

  public SafGrantRecord {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(state, "state");
    if (!ID_PATTERN.matcher(id).matches()) throw new IllegalArgumentException("GRANT_ID_INVALID");
    if (!id.equals(idOf(uri))) throw new IllegalArgumentException("GRANT_ID_MISMATCH");
    // 0 只出现在「持久化 epoch 字段损坏、按默认值恢复」的记录上：仍可展示与撤销，
    // 但能力句柄拒绝签发（见 SafCapabilityHandles#issue），是 fail-closed 的降级态。
    if (epoch < 0) throw new IllegalArgumentException("GRANT_EPOCH_INVALID");
    if (takenAt < 0) throw new IllegalArgumentException("GRANT_TIME_INVALID");
    if ((acquired & ~FLAGS) != 0) throw new IllegalArgumentException("GRANT_FLAGS_INVALID");
    if ((pendingBefore & ~FLAGS) != 0 || (pendingIntent & ~FLAGS) != 0)
      throw new IllegalArgumentException("GRANT_PENDING_FLAGS_INVALID");
    app = clip(app);
    label = clip(label);
  }

  /** 系统读位是否仍可用；同时要求记录已登记。 */
  public boolean canRead() {
    return state == State.ACTIVE && readable;
  }

  /** 系统写位是否仍可用；同时要求记录已登记。 */
  public boolean canWrite() {
    return state == State.ACTIVE && writable;
  }

  /** 用户允许助手写，且系统确实给了写位。 */
  public boolean usableWrite() {
    return canWrite() && userWritable;
  }

  /** 系统里至少还剩一个可用位；{@code false} 表示用户可能在系统设置里撤销了。 */
  public boolean valid() {
    return canRead() || canWrite();
  }

  /** 「应用 · 目录」；界面与桥列表共用，不查询目标 Provider（那要跨进程）。 */
  public String summary() {
    String name = label.isEmpty() ? lastSegment(uri) : label;
    if (app.isEmpty()) return name;
    return app + " · " + name;
  }

  /** 授权短 id：URI 的 SHA-256 前 6 字节十六进制，与既有 {@code SafGrants.id(Uri)} 算法一致。 */
  public static String idOf(String uri) {
    if (uri == null) return "";
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(ID_LENGTH);
      for (int i = 0; i < ID_LENGTH / 2; i++)
        out.append(String.format(Locale.ROOT, "%02x", hash[i]));
      return out.toString();
    } catch (Exception unavailable) {
      // 与既有实现保持同一退化路径；哈希算法在 JVM/Android 上都可用，此分支只为不抛出。
      return String.format(Locale.ROOT, "%08x", uri.hashCode());
    }
  }

  public static boolean validId(String id) {
    return id != null && ID_PATTERN.matcher(id).matches();
  }

  private static String clip(String value) {
    if (value == null) return "";
    String trimmed = value.trim();
    return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT);
  }

  private static String lastSegment(String uri) {
    String value = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    int slash = value.lastIndexOf('/');
    return slash < 0 || slash == value.length() - 1 ? value : value.substring(slash + 1);
  }
}
