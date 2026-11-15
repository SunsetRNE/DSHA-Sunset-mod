package com.deepseekharness.app.util;

/**
 * Rust gzip 旁路（{@code archive_pack}/{@code archive_unpack}）的常量、错误码与结果解码（方案
 * §4.4、§4.5、§5.4）。
 *
 * <p>纯 Java、零 Android import。本类只保存冻结 ABI 的数值与映射，不做 I/O、不加载库、不写日志；
 * 流式实现见 {@link RustGzipOutputStream}（编码）与 {@link RustGzipInputStream}（严格解码）。
 *
 * <p>R3 不接生产调用点：{@code backup/} 的接线属 A3 slice4，由 Lead 串行安排；本类只作为
 * 可差分验证的宿主桥，运行层开关仍由 {@link RustBackends#archiveSelection()} 默认关闭。
 *
 * <p>错误消息与 {@code app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream}
 * 的 {@code IOException} 文本一一对应；冻结 ABI 把该读取器的 {@code GZIP_FLAGS} 合入
 * {@link #GZIP_HEADER}（方案 §4.2），差分比较前由 {@link #canonicalJvmMessage(String)} 归一化。
 * 冻结 ABI v1 没有独立的“累计上限”码：unpack 语境的 {@link #OUTPUT_BOUND} 由
 * {@link #unpackMessage(int)} 映射为 JVM 的 {@link #ARCHIVE_LIMIT}（硬失败、不可重试）。
 * {@code GZIP_TRUNCATED} 由 JVM 桥在底层流返回 -1 时给出：native 侧只知道“还需要输入”，
 * 不知道调用方的字节流是否已经结束。
 */
public final class RustArchiveCodec {
  /** 冻结 ABI 错误码（方案 §4.2）：0 = 成功，负数 = 错误。 */
  public static final int OK = 0;

  public static final int INVALID_ARGUMENT = -1;
  public static final int INPUT_BOUND = -2;
  public static final int OUTPUT_BOUND = -3;
  public static final int STALE_HANDLE = -4;
  public static final int SESSION_LIMIT = -5;
  public static final int INTERNAL = -6;
  public static final int PANIC = -7;
  public static final int JNI_PENDING = -8;
  public static final int SHORT_READ = -9;
  public static final int UNSUPPORTED_ALGORITHM = -10;
  public static final int GZIP_HEADER = -11;
  public static final int GZIP_HEADER_CRC = -12;
  public static final int GZIP_METADATA_LIMIT = -13;
  public static final int GZIP_TRUNCATED = -14;
  public static final int GZIP_DEFLATE = -15;
  public static final int GZIP_DICTIONARY = -16;
  public static final int GZIP_STALLED = -17;
  public static final int GZIP_TRAILER = -18;
  public static final int GZIP_TRAILING_DATA = -19;
  public static final int NOT_IMPLEMENTED = -26;

  /** 单次喂入的压缩/明文字节上限（256 KiB，与 Rust/ABI 一致）。 */
  public static final int CHUNK = 262_144;

  /** 单次解压输出上限（256 KiB）。 */
  public static final int MAX_OUTPUT_CHUNK = 262_144;

  /**
   * JVM 参考读取器（{@code StrictGzipInputStream} / {@code BackupLimits.add}）的累计上限消息。
   *
   * <p>冻结 ABI v1 没有独立的“累计上限”错误码：unpack 语境下
   * {@link #OUTPUT_BOUND} 表示累计明文超 16 GiB + 256 MiB 的**硬上限**（会话进入失败态，
   * 不可重试），与 pack 语境的“输出缓冲不足、换大缓冲重试”是两个含义。A3 接线必须按
   * 操作区分（见 {@link #unpackMessage(int)}），不得把 unpack 的 -3 当作可重试。
   */
  public static final String ARCHIVE_LIMIT = "ARCHIVE_LIMIT";

  /** {@code FNAME}/{@code FCOMMENT} 最大长度（含终止 0）。 */
  public static final int MAX_METADATA = 8_192;

  /** 编码级别范围（0 = store，默认 6）。 */
  public static final int MIN_LEVEL = 0;

  public static final int MAX_LEVEL = 9;
  public static final int DEFAULT_LEVEL = 6;

  /** 固定 gzip 头：{@code 1f 8b 08 00 00000000 00 00}（FLG/MTIME/XFL/OS 全 0）。 */
  public static final String HEADER_HEX = "1f8b0800000000000000";

  /** gzip 尾长度（CRC32 + ISIZE）。 */
  public static final int TRAILER_BYTES = 8;

  /** {@code finish} 输出缓冲下限（Rust {@code gzip::FINISH_BOUND}）。 */
  public static final int FINISH_BOUND = 128 * 1024;

  /** 单次 {@code pack update} 输出上限（{@code packBound(CHUNK)}）。 */
  public static final int MAX_PACK_OUTPUT = packBound(CHUNK);

  /** 累计压缩输入/解压输出上限：{@code BackupLimits.BYTES + 256 MiB}（16 GiB + 256 MiB）。 */
  public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024 + 256L * 1024 * 1024;

  /** {@code archiveUnpackRead} 返回值的位标志（方案 §4.5）。 */
  public static final int FLAG_EOF = 0x1;

  public static final int FLAG_NEED_INPUT = 0x2;
  public static final int FLAG_NEED_OUTPUT = 0x4;

  /** 非 gzip 错误消息前缀；仅用于本桥自检，不跨 FFI。 */
  static final String RUST_ERROR_PREFIX = "RUST_ERROR_";

  private RustArchiveCodec() {}

  /** {@code pack} 单次输出缓冲下限 {@code n + n/8 + 128}（与 Rust 同一公式）。 */
  public static int packBound(int inputLength) {
    if (inputLength < 0) {
      throw new IllegalArgumentException("inputLength < 0");
    }
    // 用 long 计算并在转 int 前检查：超大 inputLength 直接相加会回绕成负数，
    // 让调用方拿到一个看似合法的“上限”（R3 评审 P2-8）。
    long bound = (long) inputLength + (long) inputLength / 8 + 128L;
    if (bound > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("inputLength too large for a packed bound");
    }
    return (int) bound;
  }

  /** 编码级别是否落在冻结 ABI 的 0..=9。 */
  public static boolean levelValid(int level) {
    return level >= MIN_LEVEL && level <= MAX_LEVEL;
  }

  /** {@code pack update/finish} 返回值里的已消费输入字节数。 */
  public static int consumed(long value) {
    return (int) (value >>> 32);
  }

  /** {@code pack update} 返回值里的已产出压缩字节数。 */
  public static int produced(long value) {
    return (int) (value & 0xffff_ffffL);
  }

  /** {@code unpack read} 返回值里的已产出明文字节数。 */
  public static int unpackProduced(long value) {
    return (int) (value >>> 32);
  }

  /** {@code unpack read} 返回值里的位标志。 */
  public static int unpackFlags(long value) {
    return (int) (value & 0xffff_ffffL);
  }

  /**
   * 冻结错误码 → 与 {@code StrictGzipInputStream} 相同的异常消息（非 gzip 码给出类型化名字）。
   *
   * <p>{@link #OUTPUT_BOUND} 在这里保持 pack 语境的名字；unpack 的累计上限必须用
   * {@link #unpackMessage(int)} 得到 JVM 的 {@link #ARCHIVE_LIMIT}（冻结 ABI v1 复用同一数值，
   * 语义按操作区分，见 {@link #ARCHIVE_LIMIT}）。
   */
  public static String message(int code) {
    switch (code) {
      case OK:
        return "OK";
      case INVALID_ARGUMENT:
        return "INVALID_ARGUMENT";
      case INPUT_BOUND:
        return "INPUT_BOUND";
      case OUTPUT_BOUND:
        return "OUTPUT_BOUND";
      case STALE_HANDLE:
        return "STALE_HANDLE";
      case SESSION_LIMIT:
        return "SESSION_LIMIT";
      case INTERNAL:
        return "INTERNAL";
      case PANIC:
        return "PANIC";
      case JNI_PENDING:
        return "JNI_PENDING";
      case GZIP_HEADER:
        return "GZIP_HEADER";
      case GZIP_HEADER_CRC:
        return "GZIP_HEADER_CRC";
      case GZIP_METADATA_LIMIT:
        return "GZIP_METADATA_LIMIT";
      case GZIP_TRUNCATED:
        return "GZIP_TRUNCATED";
      case GZIP_DEFLATE:
        return "GZIP_DEFLATE";
      case GZIP_DICTIONARY:
        return "GZIP_DICTIONARY";
      case GZIP_STALLED:
        return "GZIP_STALLED";
      case GZIP_TRAILER:
        return "GZIP_TRAILER";
      case GZIP_TRAILING_DATA:
        return "GZIP_TRAILING_DATA";
      case NOT_IMPLEMENTED:
        return "NOT_IMPLEMENTED";
      default:
        return RUST_ERROR_PREFIX + code;
    }
  }

  /**
   * {@code archive_unpack_read} 的错误码 → JVM 参考读取器消息。
   *
   * <p>unpack 语境的 {@link #OUTPUT_BOUND} 只可能来自累计明文超上限（单次输出缓冲由本桥固定
   * 为 256 KiB，不会不足），对应 JVM 的 {@link #ARCHIVE_LIMIT}；它是硬失败，会话进入失败态，
   * 调用方不得当作“缓冲不足”重试。其余码沿用 {@link #message(int)}。
   */
  public static String unpackMessage(int code) {
    return code == OUTPUT_BOUND ? ARCHIVE_LIMIT : message(code);
  }

  /**
   * {@code StrictGzipInputStream} 的异常消息 → 冻结 ABI 错误码；未知消息返回
   * {@link Integer#MIN_VALUE}。
   *
   * <p>只有差分/测试使用（该映射不跨 FFI）；{@code GZIP_FLAGS} 按 ABI 合入
   * {@link #GZIP_HEADER}。
   */
  public static int errorCodeForJvmMessage(String jvmMessage) {
    if (jvmMessage == null) {
      return Integer.MIN_VALUE;
    }
    switch (jvmMessage) {
      case "GZIP_HEADER":
      case "GZIP_FLAGS":
        return GZIP_HEADER;
      case "GZIP_HEADER_CRC":
        return GZIP_HEADER_CRC;
      case "GZIP_METADATA_LIMIT":
        return GZIP_METADATA_LIMIT;
      case "GZIP_TRUNCATED":
        return GZIP_TRUNCATED;
      case "GZIP_DEFLATE":
        return GZIP_DEFLATE;
      case "GZIP_DICTIONARY":
        return GZIP_DICTIONARY;
      case "GZIP_STALLED":
        return GZIP_STALLED;
      case "GZIP_TRAILER":
        return GZIP_TRAILER;
      case "GZIP_TRAILING_DATA":
        return GZIP_TRAILING_DATA;
      case ARCHIVE_LIMIT:
        // JVM 参考读取器的累计上限。冻结 ABI 复用 OUTPUT_BOUND（unpack 语境），
        // 差分按该映射比较；A3 不得把它当 pack 的“缓冲不足”重试。
        return OUTPUT_BOUND;
      case "STREAM_CLOSED":
        return INVALID_ARGUMENT;
      default:
        return Integer.MIN_VALUE;
    }
  }

  /** 把 JVM 读取器的消息归一化到冻结 ABI：{@code GZIP_FLAGS} 合入 {@link #GZIP_HEADER}。 */
  public static String canonicalJvmMessage(String jvmMessage) {
    if ("GZIP_FLAGS".equals(jvmMessage)) {
      return "GZIP_HEADER";
    }
    return jvmMessage;
  }

  /** 库不可用时的类型化消息（不含路径）；由流式桥在构造时 fail-closed。 */
  public static String unavailableMessage() {
    return "RUST_" + RustNative.statusCode();
  }
}
