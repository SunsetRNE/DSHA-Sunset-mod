package com.deepseekharness.app.util;

import java.nio.ByteBuffer;

/**
 * Rust 旁路 ABI 的唯一入口（方案 §4.2；ABI v1 在 R0 冻结）。
 *
 * <p>类名/方法名与 JNI 符号名一一对应（{@code Java_com_deepseekharness_app_util_RustNative_<方法>}），
 * 改名即 ABI 变更，必须新 ABI 版本 + 新评审。本类纯 Java、零 Android import；不在类初始化时
 * 自动加载 .so，由 {@link #ensureLoaded()}（生产）或 {@link #loadFromPath(String)}（主机差分测试）
 * 显式加载。加载失败只记录类型化状态、从不抛给调用方、失败后不重试（方案 §5.4）。
 *
 * <p>R1 只接 {@code digest}；其余入口仍是冻结符号的 {@code NOT_IMPLEMENTED} 占位（R2/R3 范围）。
 */
public final class RustNative {
  /** ABI 版本；{@code abiVersion()} 不匹配即拒绝加载。 */
  public static final int ABI_VERSION = 1;

  /** JNI 库名；与 crate 的 {@code lib.name = "dsha_rs"}（libdsha_rs.so）一致。 */
  public static final String LIBRARY_NAME = "dsha_rs";

  /** 加载状态；只含类型化枚举，不携带路径/异常文本（可安全写进诊断回执）。 */
  public enum Status {
    NOT_LOADED,
    LOADED,
    LIBRARY_MISSING,
    LOAD_FAILED,
    ABI_MISMATCH
  }

  private static Status status = Status.NOT_LOADED;

  private RustNative() {}

  /**
   * 用 {@code System.loadLibrary("dsha_rs")} 加载并校验 ABI；幂等。
   *
   * <p>失败不抛异常、不重试：构建未带 .so（{@link Status#LIBRARY_MISSING}）、加载失败
   * （{@link Status#LOAD_FAILED}）或 ABI 不符（{@link Status#ABI_MISMATCH}）由调用方读
   * {@link #status()} 决定回退；绝不假装加载成功。
   *
   * @return 是否已加载且 ABI 匹配
   */
  public static synchronized boolean ensureLoaded() {
    if (status == Status.LOADED) return true;
    if (status != Status.NOT_LOADED) return false;
    try {
      System.loadLibrary(LIBRARY_NAME);
    } catch (UnsatisfiedLinkError error) {
      // 未打包 .so / 依赖库缺失 / 目标 ABI 不符都落在这里；不区分细节、不泄露路径。
      status = Status.LIBRARY_MISSING;
      return false;
    } catch (SecurityException error) {
      status = Status.LOAD_FAILED;
      return false;
    }
    return verifyAbi();
  }

  /**
   * 主机差分测试与 {@code tools/rust/host-jvm} 探针专用：加载指定绝对路径的 .so。
   *
   * <p>包内可见：生产代码不调用；APK 只走 {@link #ensureLoaded()} 的 {@code loadLibrary}。
   *
   * @param absolutePath 已存在的宿主目标 .so 绝对路径
   * @return 是否加载成功且 ABI 匹配
   */
  static synchronized boolean loadFromPath(String absolutePath) {
    if (status == Status.LOADED) return true;
    if (status != Status.NOT_LOADED) return false;
    if (absolutePath == null || absolutePath.isEmpty()) {
      status = Status.LOAD_FAILED;
      return false;
    }
    try {
      System.load(absolutePath);
    } catch (UnsatisfiedLinkError error) {
      status = Status.LOAD_FAILED;
      return false;
    } catch (SecurityException error) {
      status = Status.LOAD_FAILED;
      return false;
    }
    return verifyAbi();
  }

  private static boolean verifyAbi() {
    try {
      if (abiVersion() == ABI_VERSION) {
        status = Status.LOADED;
        return true;
      }
      status = Status.ABI_MISMATCH;
      return false;
    } catch (UnsatisfiedLinkError error) {
      // 符号缺失/版本不符：库已加载但 ABI 不可用，不重试。
      status = Status.LOAD_FAILED;
      return false;
    } catch (RuntimeException error) {
      status = Status.LOAD_FAILED;
      return false;
    }
  }

  /** 当前加载状态（只读）。 */
  public static synchronized Status status() {
    return status;
  }

  /** 当前加载状态码（枚举名；供诊断回执，不含路径）。 */
  public static String statusCode() {
    return status().name();
  }

  /** 是否已加载并通过 ABI 校验。 */
  public static boolean isAvailable() {
    return status() == Status.LOADED;
  }

  public static native int abiVersion();

  public static native long digest(int fd, long offset, long length, int algorithm, ByteBuffer out);

  public static native long archivePackCreate(int level);

  public static native long archivePackUpdate(long session, ByteBuffer in, ByteBuffer out);

  public static native long archivePackFinish(long session, ByteBuffer out);

  public static native int archivePackAbort(long session);

  public static native long archiveUnpackCreate();

  public static native long archiveUnpackWrite(long session, ByteBuffer in);

  public static native long archiveUnpackRead(long session, ByteBuffer out);

  public static native int archiveUnpackAbort(long session);

  public static native int verifyPlan(
      ByteBuffer view, int viewLength, ByteBuffer reasons, int maxReasons);
}
