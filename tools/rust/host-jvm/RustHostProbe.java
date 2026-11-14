package com.deepseekharness.app.util;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/**
 * R1 主机 JVM 差分探针：加载主机目标 libdsha_rs.so，直接调用生产桥
 * {@link RustNative}/{@link RustDigest}/{@link RustBackends}，与 JDK {@link MessageDigest} 对拍。
 *
 * <p>由 tools/rust/host-jvm-differential.py 以 {@code -Xcheck:jni} 运行；不依赖 Gradle、
 * 不依赖 Robolectric、不产生生产行为。只证明“同一源码的主机实现与 JVM 一致”，不等于
 * Android .so 已加载（后者由 R3 签名 .dev 包与 APK 静态门禁覆盖）。
 *
 * <p>输出：每个检查一行 JSON（{@code {"check":...,"status":"PASS|FAIL",...}}），最后一行为
 * 汇总（{@code {"status":...,"failures":...,"javaVersion":...}}）。
 */
public final class RustHostProbe {
  private static int failures = 0;
  private static int checks = 0;

  private RustHostProbe() {}

  private static void check(String name, boolean value, String detail) {
    checks++;
    System.out.println(
        "{\"check\":\""
            + name
            + "\",\"status\":\""
            + (value ? "PASS" : "FAIL")
            + "\""
            + (detail == null || detail.isEmpty() ? "" : ",\"detail\":\"" + detail + "\"")
            + "}");
    if (!value) {
      failures++;
    }
  }

  private static String hex(byte[] bytes) {
    return RustDigest.hex(bytes);
  }

  private static byte[] patterned(int size) {
    byte[] payload = new byte[size];
    for (int index = 0; index < size; index++) {
      payload[index] = (byte) ((index * 31 + 7) % 251);
    }
    return payload;
  }

  private static Path temporaryFile(byte[] payload) throws IOException {
    Path file = Files.createTempFile("dsha-rust-probe-", ".bin");
    Files.write(file, payload);
    file.toFile().deleteOnExit();
    return file;
  }

  /** 只用于宿主探针：反射读取 {@link FileDescriptor#fd}（运行参数已加 --add-opens）。 */
  private static int rawFd(FileDescriptor descriptor) throws Exception {
    Field field = FileDescriptor.class.getDeclaredField("fd");
    field.setAccessible(true);
    return field.getInt(descriptor);
  }

  private static void checkDigest(
      String name, FileInputStream stream, FileChannel channel, long offset, long length, int algorithm) {
    try {
      RustDigest.Result jvm = RustDigest.digestJvm(channel, offset, length, algorithm);
      RustDigest.Result rust =
          RustDigest.digestRust(rawFd(stream.getFD()), offset, length, algorithm);
      boolean same = jvm.ok() && rust.ok() && jvm.digestHex().equals(rust.digestHex());
      check(
          name,
          same && rust.bytesRead() == length && rust.backend() == RustDigest.Backend.RUST,
          "jvm="
              + jvm.digestHex()
              + " rust="
              + rust.digestHex()
              + " bytes="
              + rust.bytesRead()
              + " failure="
              + rust.failure());
    } catch (Exception error) {
      check(name, false, error.getClass().getSimpleName());
    }
  }

  private static void checkError(String name, RustDigest.Result result, int expectedCode) {
    check(
        name,
        !result.ok() && result.errorCode() == expectedCode && result.digest() == null,
        "errorCode=" + result.errorCode() + " failure=" + result.failure());
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("用法: RustHostProbe <libdsha_rs.so 绝对路径>");
      System.exit(2);
    }
    if (!RustNative.loadFromPath(args[0])) {
      check("loadLibrary", false, "status=" + RustNative.statusCode());
      finish();
      return;
    }
    check("loadLibrary", RustNative.isAvailable(), "status=" + RustNative.statusCode());
    check("abiVersion", RustNative.abiVersion() == RustNative.ABI_VERSION, "abi=" + RustNative.abiVersion());
    check(
        "classPinned",
        RustNative.class.getName().equals("com.deepseekharness.app.util.RustNative"),
        RustNative.class.getName());
    // R3 起 archive 入口是真实实现：非法 level 必须在 JNI 之前被拒绝，且不创建会话。
    check(
        "archiveEntryIsImplemented",
        RustNative.archivePackCreate(-1) == -1,
        "return=archivePackCreate(-1)");

    // 已知向量（与 Rust 单测、JUnit 差分同一组）。
    Path abc = temporaryFile("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    try (FileInputStream stream = new FileInputStream(abc.toFile());
        FileChannel channel = FileChannel.open(abc, StandardOpenOption.READ)) {
      checkDigest("sha256KnownVector", stream, channel, 0, 3, RustDigest.ALGORITHM_SHA256);
      checkDigest("sha1KnownVector", stream, channel, 0, 3, RustDigest.ALGORITHM_SHA1);
      RustDigest.Result sha256 =
          RustDigest.digestRust(rawFd(stream.getFD()), 0, 3, RustDigest.ALGORITHM_SHA256);
      check(
          "sha256Hex",
          sha256.ok()
              && "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                  .equals(sha256.digestHex()),
          "hex=" + sha256.digestHex());
      RustDigest.Result sha1 =
          RustDigest.digestRust(rawFd(stream.getFD()), 0, 3, RustDigest.ALGORITHM_SHA1);
      check(
          "sha1Hex",
          sha1.ok() && "a9993e364706816aba3e25717850c26c9cd0d89d".equals(sha1.digestHex()),
          "hex=" + sha1.digestHex());
    }

    // 空内容（length=0 按 ABI 拒绝）+ EOF 短读。
    Path empty = temporaryFile(new byte[0]);
    try (FileInputStream stream = new FileInputStream(empty.toFile())) {
      int fd = rawFd(stream.getFD());
      checkError(
          "zeroLengthRejected",
          RustDigest.digestRust(fd, 0, 0, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "eofShortRead",
          RustDigest.digestRust(fd, 0, 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.SHORT_READ);
      // 错误零写入：空文件 length=1 → SHORT_READ，direct 缓冲必须保持哨兵值。
      ByteBuffer noWrite = ByteBuffer.allocateDirect(32);
      for (int index = 0; index < 32; index++) {
        noWrite.put(index, (byte) 0xAA);
      }
      long noWriteResult =
          RustNative.digest(fd, 0, 1, RustDigest.ALGORITHM_SHA256, noWrite);
      boolean unchanged = noWriteResult == RustDigest.SHORT_READ;
      for (int index = 0; index < 32 && unchanged; index++) {
        unchanged = (noWrite.get(index) & 0xff) == 0xAA;
      }
      check(
          "errorZeroWrite",
          unchanged,
          "return=" + noWriteResult + " buffer=" + (unchanged ? "unchanged" : "modified"));
    }

    // 偏移/跨块/32 MiB 单次上限。
    byte[] payload = patterned(RustDigest.CHUNK * 3 + 23);
    Path mixed = temporaryFile(payload);
    try (FileInputStream stream = new FileInputStream(mixed.toFile());
        FileChannel channel = FileChannel.open(mixed, StandardOpenOption.READ)) {
      checkDigest("offsetMiddle", stream, channel, 17, 333, RustDigest.ALGORITHM_SHA256);
      checkDigest(
          "crossChunk",
          stream,
          channel,
          0,
          payload.length,
          RustDigest.ALGORITHM_SHA1);
      checkDigest(
          "chunkBoundaryPair",
          stream,
          channel,
          RustDigest.CHUNK - 1,
          RustDigest.CHUNK + 2,
          RustDigest.ALGORITHM_SHA256);
    }
    Path large = Files.createTempFile("dsha-rust-probe-32m-", ".bin");
    large.toFile().deleteOnExit();
    byte[] chunk = patterned(RustDigest.CHUNK);
    try (FileOutputStream out = new FileOutputStream(large.toFile())) {
      long remaining = RustDigest.MAX_LENGTH;
      while (remaining > 0) {
        int want = (int) Math.min((long) chunk.length, remaining);
        out.write(chunk, 0, want);
        remaining -= want;
      }
    }
    try (FileInputStream stream = new FileInputStream(large.toFile());
        FileChannel channel = FileChannel.open(large, StandardOpenOption.READ)) {
      checkDigest("maxLength32MiB", stream, channel, 0, RustDigest.MAX_LENGTH, RustDigest.ALGORITHM_SHA256);
    }

    // 非法参数映射（与 JVM validate 同码）。
    Path ten = temporaryFile("0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    try (FileInputStream stream = new FileInputStream(ten.toFile())) {
      int fd = rawFd(stream.getFD());
      checkError(
          "invalidOffset",
          RustDigest.digestRust(fd, -1, 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "invalidLengthZero",
          RustDigest.digestRust(fd, 0, 0, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "invalidLengthTooLarge",
          RustDigest.digestRust(fd, 0, RustDigest.MAX_LENGTH + 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "offsetOverflow",
          RustDigest.digestRust(fd, Long.MAX_VALUE, 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "unknownAlgorithm",
          RustDigest.digestRust(fd, 0, 1, 99),
          RustDigest.UNSUPPORTED_ALGORITHM);
      checkError(
          "negativeFd",
          RustDigest.digestRust(-1, 0, 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      checkError(
          "hugeFd",
          RustDigest.digestRust(Integer.MAX_VALUE, 0, 1, RustDigest.ALGORITHM_SHA256),
          RustDigest.INVALID_ARGUMENT);
      // 只写 fd → pread EBADF。
      try (FileOutputStream writeOnly = new FileOutputStream(ten.toFile(), true)) {
        checkError(
            "writeOnlyFd",
            RustDigest.digestRust(rawFd(writeOnly.getFD()), 0, 1, RustDigest.ALGORITHM_SHA256),
            RustDigest.INVALID_ARGUMENT);
      }
      // 非 direct / 过小 direct 缓冲：类型化契约错误。
      ByteBuffer heap = ByteBuffer.allocate(32);
      check(
          "heapBufferRejected",
          RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, heap)
              == RustDigest.INVALID_ARGUMENT,
          "return=heap");
      ByteBuffer tooSmall = ByteBuffer.allocateDirect(8);
      long small =
          RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, tooSmall);
      check("outputBound", small == RustDigest.OUTPUT_BOUND, "return=" + small);
      boolean untouched = true;
      for (int index = 0; index < tooSmall.capacity(); index++) {
        untouched &= tooSmall.get(index) == 0;
      }
      check("outputBoundNoPartialWrite", untouched, "buffer 未被写");
      check(
          "nullBufferRejected",
          RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, null)
              == RustDigest.INVALID_ARGUMENT,
          "return=null");
    }

    // 关闭的 fd：Rust 不 close 调用方 fd，读到已关闭 fd 必须安全失败。
    Path closing = temporaryFile(patterned(64));
    FileInputStream stream = new FileInputStream(closing.toFile());
    int closedFd = rawFd(stream.getFD());
    stream.close();
    checkError(
        "closedFd",
        RustDigest.digestRust(closedFd, 0, 1, RustDigest.ALGORITHM_SHA256),
        RustDigest.INVALID_ARGUMENT);

    // 运行层开关：默认 off → JVM；显式 on → RUST；一键 off → JVM，同一输入摘要不变。
    Path dispatch = temporaryFile(patterned(4096));
    try (FileInputStream stream2 = new FileInputStream(dispatch.toFile());
        FileChannel channel = FileChannel.open(dispatch, StandardOpenOption.READ)) {
      int fd = rawFd(stream2.getFD());
      RustBackends.setEnabled(false);
      RustDigest.Result off = RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      check(
          "switchOffUsesJvm",
          off.ok()
              && off.backend() == RustDigest.Backend.JVM
              && "SWITCH_OFF".equals(off.fallbackReason()),
          "backend=" + off.backend() + " reason=" + off.fallbackReason());
      RustBackends.setEnabled(true);
      RustDigest.Result on = RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      check(
          "switchOnUsesRust",
          on.ok() && on.backend() == RustDigest.Backend.RUST && !on.fellBack(),
          "backend=" + on.backend() + " fellBack=" + on.fellBack());
      check(
          "switchDigestEqual",
          off.ok() && on.ok() && off.digestHex().equals(on.digestHex()),
          "off=" + off.digestHex() + " on=" + on.digestHex());
      RustBackends.setEnabled(false);
      RustDigest.Result back =
          RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      check(
          "oneKeyRollback",
          back.ok() && back.backend() == RustDigest.Backend.JVM,
          "backend=" + back.backend());
    }

    // JVM 参照：同一输入的 MessageDigest 直接对拍（证明不是两边同一个 bug 循环）。
    Path reference = temporaryFile(patterned(1000));
    try (FileInputStream stream3 = new FileInputStream(reference.toFile())) {
      MessageDigest expected = MessageDigest.getInstance("SHA-256");
      expected.update(patterned(1000), 10, 500);
      RustDigest.Result rust =
          RustDigest.digestRust(
              rawFd(stream3.getFD()), 10, 500, RustDigest.ALGORITHM_SHA256);
      // MessageDigest.digest() 会重置状态：先取一次十六进制，避免 detail 参数里
      // 第二次调用变成空摘要（R1 首次运行时这里误报 expected=e3b0c44…）。
      String expectedHex = hex(expected.digest());
      check(
          "messageDigestReference",
          rust.ok() && expectedHex.equals(rust.digestHex()),
          "expected=" + expectedHex + " rust=" + rust.digestHex());
    }

    finish();
  }

  private static void finish() {
    System.out.println(
        "{\"status\":\""
            + (failures == 0 ? "PASS" : "FAIL")
            + "\",\"failures\":"
            + failures
            + ",\"checks\":"
            + checks
            + ",\"javaVersion\":\""
            + System.getProperty("java.version")
            + "\"}");
    if (failures != 0) {
      System.exit(1);
    }
  }
}
