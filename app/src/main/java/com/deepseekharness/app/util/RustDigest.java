package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@code digest(fdRange, algorithm)} 的 JVM 后端、Rust 后端调用与错误码映射（方案 §4.3、§5.4）。
 *
 * <p>R1 不接任何生产调用点：本类只给 A3 准备可差分验证的桥梁。调用方持有 Kotlin 已核验的
 * 只读区间（int fd + offset + length，以及同一文件的 {@link FileChannel} 供 JVM 回退），
 * 先看 {@link RustBackends} 的运行层开关：
 *
 * <ul>
 *   <li>开关关闭 / 库不可用 / ABI 不符 → JVM 后端，{@code fallbackReason} 记录类型化原因；
 *   <li>Rust 成功 → {@link Backend#RUST}；
 *   <li>Rust 返回内部错误/panic → 丢弃本次结果，按 §5.4 用 JVM 重做一次并标 {@code fellBack=true}；
 *       重做失败即整体失败；
 *   <li>Rust 返回输入非法/短读等确定性错误 → <b>不重试</b>（重试只会掩盖调用方错误）；
 *   <li>绝不把失败写成“Rust 成功”，也不对已提交目标续写（digest 无输出目标）。
 * </ul>
 *
 * <p>失败域：{@code catch_unwind} / Rust 返回码之外，abort、SIGSEGV、OOM 会直接杀进程（ADR-0004
 * 失败域矩阵），本类不伪造“已回退”。JVM 侧 {@link IOException} 映射为 {@link Failure#INTERNAL}。
 *
 * <p>纯 Java、零 Android import。摘要输出不缓存、不写日志；错误码是冻结 ABI 值。
 */
public final class RustDigest {
  /** `algorithm` 取值：SHA-256（与 Rust/ABI 一致）。 */
  public static final int ALGORITHM_SHA256 = 1;
  /** `algorithm` 取值：SHA-1。 */
  public static final int ALGORITHM_SHA1 = 2;

  /** 单次调用允许的最大长度（32 MiB）；大文件由调用方分段循环。 */
  public static final long MAX_LENGTH = 33_554_432L;
  /** 与 Rust 一致的流式读取块（256 KiB）。 */
  public static final int CHUNK = 262_144;

  /** 冻结 ABI 错误码（方案 §4.2）：0 = 成功，负数 = 错误。 */
  public static final int OK = 0;

  public static final int INVALID_ARGUMENT = -1;
  public static final int OUTPUT_BOUND = -3;
  public static final int INTERNAL = -6;
  public static final int PANIC = -7;
  public static final int JNI_PENDING = -8;
  public static final int SHORT_READ = -9;
  public static final int UNSUPPORTED_ALGORITHM = -10;

  private RustDigest() {}

  /** 实际产出摘要的后端。 */
  public enum Backend {
    JVM,
    RUST
  }

  /** 错误投影：Rust 错误码 + 加载状态（加载状态没有 ABI 错误码，只有类型化名字）。 */
  public enum Failure {
    NONE,
    INVALID_ARGUMENT,
    OUTPUT_BOUND,
    INTERNAL,
    PANIC,
    JNI_PENDING,
    SHORT_READ,
    UNSUPPORTED_ALGORITHM,
    LIBRARY_MISSING,
    LIBRARY_LOAD_FAILED,
    ABI_MISMATCH,
    NOT_LOADED,
    UNKNOWN
  }

  /** 一次摘要调用的只读结果；成功时 {@link #digest()} 非空、{@link #errorCode()} 为 0。 */
  public static final class Result {
    private final Backend backend;
    private final byte[] digest;
    private final long bytesRead;
    private final int errorCode;
    private final Failure failure;
    private final boolean fellBack;
    private final String fallbackReason;

    private Result(
        Backend backend,
        byte[] digest,
        long bytesRead,
        int errorCode,
        Failure failure,
        boolean fellBack,
        String fallbackReason) {
      this.backend = backend;
      this.digest = digest;
      this.bytesRead = bytesRead;
      this.errorCode = errorCode;
      this.failure = failure;
      this.fellBack = fellBack;
      this.fallbackReason = fallbackReason;
    }

    public Backend backend() {
      return backend;
    }

    public boolean ok() {
      return failure == Failure.NONE && digest != null;
    }

    /** 摘要字节副本（失败为 null）。 */
    public byte[] digest() {
      return digest == null ? null : digest.clone();
    }

    /** 摘要十六进制（失败为 null，仅用于诊断/测试断言）。 */
    public String digestHex() {
      return digest == null ? null : hex(digest);
    }

    /** 成功时等于请求长度；失败时为 0。 */
    public long bytesRead() {
      return bytesRead;
    }

    /** 冻结 ABI 错误码；加载状态类失败没有 ABI 错误码时为 0。 */
    public int errorCode() {
      return errorCode;
    }

    public Failure failure() {
      return failure;
    }

    /** 是否“Rust 已尝试但结果被丢弃、由 JVM 重做”（方案 §5.4 的 fellBack）。 */
    public boolean fellBack() {
      return fellBack;
    }

    /** 后端选择/回退原因（类型化，不泄露路径）；正常 Rust 成功为空串。 */
    public String fallbackReason() {
      return fallbackReason;
    }

    private Result explain(String reason, boolean didFallBack) {
      return new Result(backend, digest, bytesRead, errorCode, failure, didFallBack, reason);
    }
  }

  /**
   * 共享参数契约（Rust {@code digest::validate} 的 JVM 镜像；fd 只由 Rust 校验）。
   *
   * @return 0 = 合法；否则冻结错误码
   */
  public static int validate(long offset, long length, int algorithm) {
    if (offset < 0) return INVALID_ARGUMENT;
    if (length < 1 || length > MAX_LENGTH) return INVALID_ARGUMENT;
    if (offset > Long.MAX_VALUE - length) return INVALID_ARGUMENT;
    if (algorithm != ALGORITHM_SHA256 && algorithm != ALGORITHM_SHA1) {
      return UNSUPPORTED_ALGORITHM;
    }
    return OK;
  }

  /**
   * 运行层分发（R1 无生产调用点；A3 接线时只调本方法）。
   *
   * @param fd 已核验只读区间所在 fd（Rust 后端使用）
   * @param channel 同一文件的 channel（JVM 后端/回退使用；null 表示无法 JVM 重做）
   * @param offset 区间起点（>= 0）
   * @param length 区间长度（1..={@link #MAX_LENGTH}）
   * @param algorithm {@link #ALGORITHM_SHA256} 或 {@link #ALGORITHM_SHA1}
   */
  public static Result digest(
      int fd, FileChannel channel, long offset, long length, int algorithm) {
    RustBackends.Selection selection = RustBackends.digestSelection();
    if (!selection.useRust()) {
      return digestJvm(channel, offset, length, algorithm).explain(selection.reasonCode(), false);
    }
    Result rust = digestRust(fd, offset, length, algorithm);
    if (rust.ok()) return rust;
    if (shouldRedoOnJvm(rust.failure())) {
      // §5.4：纯函数可重算，JVM 重做一次并标记；不得写成 Rust 成功。
      if (channel == null) return rust.explain("JVM_SOURCE_UNAVAILABLE", false);
      Result jvm = digestJvm(channel, offset, length, algorithm);
      return jvm.explain("RUST_" + rust.failure().name(), true);
    }
    return rust;
  }

  /**
   * 是否允许在 Rust 失败后按 §5.4 用 JVM 重做一次。
   *
   * <p>只有内部错误/panic 允许（纯函数可重算）；输入非法、短读、JNI pending、未知算法等
   * 确定性错误不重试，避免用 JVM 重做掩盖调用方错误。
   */
  static boolean shouldRedoOnJvm(Failure failure) {
    return failure == Failure.INTERNAL || failure == Failure.PANIC;
  }

  /**
   * Rust 后端：调用冻结 JNI 入口，把返回码映射为类型化结果。
   *
   * <p>参数非法也交给 Rust 判定（ABI 的权威侧）；只有库不可用时才在 JVM 侧短路为类型化失败。
   */
  public static Result digestRust(int fd, long offset, long length, int algorithm) {
    if (!RustNative.isAvailable()) {
      return new Result(
          Backend.RUST, null, 0, 0, statusFailure(RustNative.status()), false, "NOT_LOADED");
    }
    ByteBuffer out = ByteBuffer.allocateDirect(32);
    long value;
    try {
      value = RustNative.digest(fd, offset, length, algorithm, out);
    } catch (UnsatisfiedLinkError error) {
      // 加载成功后仍缺符号/ABI 不符：不重试加载，类型化失败由调用方决定是否回 JVM。
      return new Result(
          Backend.RUST, null, 0, 0, Failure.LIBRARY_LOAD_FAILED, false, "NATIVE_BINDING_FAILED");
    }
    if (value < 0) {
      int code = (int) value;
      return new Result(Backend.RUST, null, 0, code, mapFailure(code), false, "");
    }
    int size = digestBytes(algorithm);
    if (size < 0) {
      // 不会发生（未知算法已在 Rust 侧返回错误码），保持 fail-closed。
      return new Result(
          Backend.RUST, null, 0, UNSUPPORTED_ALGORITHM, Failure.UNSUPPORTED_ALGORITHM, false, "");
    }
    byte[] digest = new byte[size];
    out.position(0);
    out.get(digest);
    return new Result(Backend.RUST, digest, value, OK, Failure.NONE, false, "");
  }

  /**
   * JVM 后端：对已打开 {@link FileChannel} 的绝对区间做 {@link MessageDigest}。
   *
   * <p>使用带 position 的绝对读（{@code channel.read(buffer, position)}），不改变 channel 的
   * 共享 position。读到 0 字节视为内部错误（避免无进展死循环）；提前 EOF 为
   * {@link Failure#SHORT_READ}。
   */
  public static Result digestJvm(
      FileChannel channel, long offset, long length, int algorithm) {
    int validation = validate(offset, length, algorithm);
    if (validation != OK) {
      return new Result(Backend.JVM, null, 0, validation, mapFailure(validation), false, "");
    }
    if (channel == null) {
      return new Result(Backend.JVM, null, 0, INVALID_ARGUMENT, Failure.INVALID_ARGUMENT, false, "");
    }
    MessageDigest hasher = newMessageDigest(algorithm);
    if (hasher == null) {
      return new Result(
          Backend.JVM, null, 0, UNSUPPORTED_ALGORITHM, Failure.UNSUPPORTED_ALGORITHM, false, "");
    }
    byte[] buffer = new byte[(int) Math.min((long) CHUNK, length)];
    ByteBuffer view = ByteBuffer.wrap(buffer);
    long position = offset;
    long remaining = length;
    while (remaining > 0) {
      int want = (int) Math.min((long) buffer.length, remaining);
      view.clear();
      view.limit(want);
      int read;
      try {
        read = channel.read(view, position);
      } catch (IOException error) {
        return new Result(Backend.JVM, null, 0, INTERNAL, Failure.INTERNAL, false, "");
      }
      if (read < 0) {
        return new Result(Backend.JVM, null, 0, SHORT_READ, Failure.SHORT_READ, false, "");
      }
      if (read == 0) {
        // 文件 channel 是阻塞的；0 字节说明没有进展，按内部错误终止而不是空转。
        return new Result(Backend.JVM, null, 0, INTERNAL, Failure.INTERNAL, false, "");
      }
      hasher.update(buffer, 0, read);
      position += read;
      remaining -= read;
    }
    return new Result(Backend.JVM, hasher.digest(), length, OK, Failure.NONE, false, "");
  }

  /** 冻结 ABI 错误码 → 错误投影。 */
  public static Failure mapFailure(int code) {
    switch (code) {
      case INVALID_ARGUMENT:
        return Failure.INVALID_ARGUMENT;
      case OUTPUT_BOUND:
        return Failure.OUTPUT_BOUND;
      case INTERNAL:
        return Failure.INTERNAL;
      case PANIC:
        return Failure.PANIC;
      case JNI_PENDING:
        return Failure.JNI_PENDING;
      case SHORT_READ:
        return Failure.SHORT_READ;
      case UNSUPPORTED_ALGORITHM:
        return Failure.UNSUPPORTED_ALGORITHM;
      default:
        return Failure.UNKNOWN;
    }
  }

  /** 摘要长度；未知算法返回 -1。 */
  static int digestBytes(int algorithm) {
    if (algorithm == ALGORITHM_SHA256) return 32;
    if (algorithm == ALGORITHM_SHA1) return 20;
    return -1;
  }

  private static MessageDigest newMessageDigest(int algorithm) {
    String name;
    if (algorithm == ALGORITHM_SHA256) {
      name = "SHA-256";
    } else if (algorithm == ALGORITHM_SHA1) {
      name = "SHA-1";
    } else {
      return null;
    }
    try {
      return MessageDigest.getInstance(name);
    } catch (NoSuchAlgorithmException error) {
      return null;
    }
  }

  private static Failure statusFailure(RustNative.Status status) {
    switch (status) {
      case LIBRARY_MISSING:
        return Failure.LIBRARY_MISSING;
      case LOAD_FAILED:
        return Failure.LIBRARY_LOAD_FAILED;
      case ABI_MISMATCH:
        return Failure.ABI_MISMATCH;
      default:
        return Failure.NOT_LOADED;
    }
  }

  /** 小写十六进制（诊断/测试断言用；不写日志、不写文件）。 */
  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >> 4) & 0x0f, 16));
      out.append(Character.forDigit(value & 0x0f, 16));
    }
    return out.toString();
  }
}
