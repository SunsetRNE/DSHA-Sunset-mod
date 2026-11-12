package com.deepseekharness.app.saf;

/**
 * 跨应用文件访问的限额（纯逻辑，不 import Android API）。
 *
 * <p>这些数字与既有 {@code SafTree} 的公开上限一一对应；接入 slice 2 时由 {@code SafTree}
 * 引用本类常量，避免「桥拒了、界面没拒」两套数。本类只做上限判定，不做 IO、不持有状态。
 */
public final class SafCapabilityLimits {
  /** 授权目录数量上限；与既有授权页一致。 */
  public static final int MAX_TREES = 8;

  /** 单次列目录条目上限。 */
  public static final int MAX_ENTRIES = 500;

  /** 单次读取上限（与 /app/readfile 的 256 KiB 对齐）。 */
  public static final int MAX_READ_BYTES = 256 * 1024;

  /** 单次写入上限（push / pull）。 */
  public static final long MAX_WRITE_BYTES = 32L * 1024 * 1024;

  /** /app/saf/write 的小文本上限：受桥查询串预算约束。 */
  public static final int MAX_TEXT_BYTES = 16 * 1024;

  /** 相对授权根的路径段数上限，防止病态路径拖成 O(n²)。 */
  public static final int MAX_PATH_SEGMENTS = 32;

  /** 同时进行的 SAF 传输上限。 */
  public static final int MAX_CONCURRENT_TRANSFERS = 2;

  /** 单次 Provider 操作截止时间。 */
  public static final long DEADLINE_MS = 15_000L;

  /** 能力句柄有效期：一次下载/导出通常远小于此值。 */
  public static final long HANDLE_TTL_MS = 60_000L;

  /** 进程内未消费能力句柄总数上限。 */
  public static final int MAX_OUTSTANDING_HANDLES = 64;

  /** 单个授权目录未消费能力句柄数上限。 */
  public static final int MAX_HANDLES_PER_TREE = 8;

  /** tree URI 字符串长度上限（远大于任何正常 DocumentsProvider URI）。 */
  public static final int MAX_URI_LENGTH = 4096;

  public static final String OFFSET_INVALID = "OFFSET_INVALID";
  public static final String MAX_INVALID = "MAX_INVALID";
  public static final String MAX_TOO_LARGE = "MAX_TOO_LARGE";
  public static final String SIZE_INVALID = "SIZE_INVALID";
  public static final String TOO_LARGE = "TOO_LARGE";
  public static final String LIMIT_INVALID = "LIMIT_INVALID";
  public static final String LIMIT_TOO_LARGE = "LIMIT_TOO_LARGE";
  public static final String PATH_TOO_DEEP = "PATH_TOO_DEEP";
  public static final String URI_MISSING = "URI_MISSING";
  public static final String URI_TOO_LONG = "URI_TOO_LONG";

  private SafCapabilityLimits() {}

  /** 读请求上限判定；{@code null} 表示在限额内。 */
  public static String readProblem(long offset, int max) {
    if (offset < 0) return OFFSET_INVALID;
    if (max <= 0) return MAX_INVALID;
    if (max > MAX_READ_BYTES) return MAX_TOO_LARGE;
    return null;
  }

  /** 写入长度判定；{@code null} 表示在限额内。 */
  public static String writeProblem(long bytes) {
    if (bytes < 0) return SIZE_INVALID;
    if (bytes > MAX_WRITE_BYTES) return TOO_LARGE;
    return null;
  }

  /** /app/saf/write 文本长度判定；{@code null} 表示在限额内。 */
  public static String textProblem(int bytes) {
    if (bytes < 0) return SIZE_INVALID;
    if (bytes > MAX_TEXT_BYTES) return TOO_LARGE;
    return null;
  }

  /** 列目录条数判定；{@code null} 表示在限额内。 */
  public static String listProblem(int limit) {
    if (limit <= 0) return LIMIT_INVALID;
    if (limit > MAX_ENTRIES) return LIMIT_TOO_LARGE;
    return null;
  }

  /** 路径段数判定；{@code null} 表示在限额内。 */
  public static String pathSegmentProblem(int segments) {
    if (segments < 0) return "PATH_INVALID";
    if (segments > MAX_PATH_SEGMENTS) return PATH_TOO_DEEP;
    return null;
  }

  /** tree URI 字符串长度判定；{@code null} 表示在限额内。 */
  public static String uriProblem(String uri) {
    if (uri == null || uri.isBlank()) return URI_MISSING;
    if (uri.length() > MAX_URI_LENGTH) return URI_TOO_LONG;
    return null;
  }
}
