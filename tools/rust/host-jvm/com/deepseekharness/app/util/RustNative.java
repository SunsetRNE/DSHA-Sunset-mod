package com.deepseekharness.app.util;

import java.nio.ByteBuffer;

/**
 * 方案 §4.2 的宿主 JVM 探针壳：类名/包名/方法签名与 APK 内的最终入口一致， 但本文件不进 APK、也不被生产代码引用。
 *
 * <p>R0 只用它证明“主机 .so + JDK17 JVM”的加载与调用链；R1 起由真正的 util/RustNative.java 取代。 这里不实现任何业务逻辑，也不做
 * System.loadLibrary 之外的副作用。
 */
public final class RustNative {
  public static final int ABI_VERSION = 1;

  private RustNative() {}

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
