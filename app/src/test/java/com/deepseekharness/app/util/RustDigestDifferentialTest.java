package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * JVM {@link MessageDigest} 与 Rust digest 后端的真实对拍（需要主机目标 .so）。
 *
 * <p>运行方式（Gradle）：{@code -Pdsha.rust.hostLib=<repo>/app/build/rust-host/libdsha_rs.so}
 * （可加 {@code -Pdsha.rust.required=true} 让缺少 .so 直接失败而不是跳过；CI
 * rust-differential 用后者保证 0 skipped）。测试 JVM 需
 * {@code --add-opens java.base/java.io=ALL-UNNAMED} 以读取 {@link FileDescriptor#fd}
 * （app/build.gradle 在设置 hostLib 时自动加）。
 *
 * <p>证明范围：同一源码的**主机** .so 与 JVM 逐字节一致；不等于 Android .so 已加载，
 * 后者由 R3 签名 .dev 包与 APK 静态门禁覆盖（方案 §5.1）。
 */
public class RustDigestDifferentialTest {
  static final String HOST_LIB_PROPERTY = "dsha.rust.hostLib";
  static final String REQUIRED_PROPERTY = "dsha.rust.required";

  private final List<Path> temporaryFiles = new ArrayList<>();

  @BeforeClass
  public static void loadHostLibrary() {
    String libraryPath = System.getProperty(HOST_LIB_PROPERTY, "").trim();
    boolean required = Boolean.parseBoolean(System.getProperty(REQUIRED_PROPERTY, "false"));
    if (libraryPath.isEmpty()) {
      if (required) fail("RUST_HOST_LIB_REQUIRED: 缺少 -D" + HOST_LIB_PROPERTY);
      Assume.assumeTrue("RUST_HOST_LIB_MISSING: 未提供 -D" + HOST_LIB_PROPERTY, false);
    }
    if (!RustNative.loadFromPath(libraryPath)) {
      if (required) fail("RUST_HOST_LIB_UNUSABLE: " + RustNative.statusCode());
      Assume.assumeTrue("RUST_HOST_LIB_UNUSABLE: " + RustNative.statusCode(), false);
    }
    assertEquals(RustNative.ABI_VERSION, RustNative.abiVersion());
    assertEquals(RustNative.Status.LOADED, RustNative.status());
  }

  @AfterClass
  public static void restoreSwitch() {
    RustBackends.setEnabled(false);
  }

  @After
  public void cleanUp() throws IOException {
    RustBackends.setEnabled(false);
    for (Path file : temporaryFiles) {
      Files.deleteIfExists(file);
    }
    temporaryFiles.clear();
  }

  private Path temporaryFile(byte[] payload) throws IOException {
    Path file = Files.createTempFile("dsha-rust-diff-", ".bin");
    Files.write(file, payload);
    temporaryFiles.add(file);
    return file;
  }

  private byte[] patterned(int size) {
    byte[] payload = new byte[size];
    for (int index = 0; index < size; index++) {
      payload[index] = (byte) ((index * 31 + 7) % 251);
    }
    return payload;
  }

  /** {@link FileDescriptor#fd} 是 JDK 私有字段：宿主差分测试用反射读取，生产代码不这样做。 */
  private static int rawFd(FileDescriptor descriptor) {
    try {
      Field field = FileDescriptor.class.getDeclaredField("fd");
      field.setAccessible(true);
      return field.getInt(descriptor);
    } catch (ReflectiveOperationException | RuntimeException error) {
      fail(
          "RUST_FD_REFLECTION: "
              + error.getClass().getSimpleName()
              + "（设置 -Pdsha.rust.hostLib 时 Gradle 会加 --add-opens java.base/java.io=ALL-UNNAMED）");
      return -1;
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >> 4) & 0x0f, 16));
      out.append(Character.forDigit(value & 0x0f, 16));
    }
    return out.toString();
  }

  private static void assertSameDigest(
      FileInputStream stream, FileChannel channel, long offset, long length, int algorithm) {
    RustDigest.Result jvm = RustDigest.digestJvm(channel, offset, length, algorithm);
    RustDigest.Result rust = RustDigest.digestRust(rawFd(stream.getFD()), offset, length, algorithm);
    assertTrue("jvm failure: " + jvm.failure(), jvm.ok());
    assertTrue("rust failure: " + rust.failure() + " code=" + rust.errorCode(), rust.ok());
    assertEquals(RustDigest.Backend.RUST, rust.backend());
    assertEquals("bytesRead", length, rust.bytesRead());
    assertEquals("digest", jvm.digestHex(), rust.digestHex());
  }

  @Test
  public void abiProbeAndKnownVectorsMatch() throws IOException {
    assertEquals(1, RustNative.abiVersion());
    Path file = temporaryFile("abc".getBytes(StandardCharsets.US_ASCII));
    try (FileInputStream stream = new FileInputStream(file.toFile());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      RustDigest.Result sha256 =
          RustDigest.digestRust(rawFd(stream.getFD()), 0, 3, RustDigest.ALGORITHM_SHA256);
      assertTrue(sha256.ok());
      assertEquals(
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
          sha256.digestHex());
      assertEquals(32, sha256.digest().length);
      RustDigest.Result sha1 =
          RustDigest.digestRust(rawFd(stream.getFD()), 0, 3, RustDigest.ALGORITHM_SHA1);
      assertTrue(sha1.ok());
      assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1.digestHex());
      assertEquals(20, sha1.digest().length);
      assertSameDigest(stream, channel, 0, 3, RustDigest.ALGORITHM_SHA256);
      assertSameDigest(stream, channel, 0, 3, RustDigest.ALGORITHM_SHA1);
    }
  }

  @Test
  public void emptyContentIsRejectedAndEndOfFileIsAShortRead() throws IOException {
    Path file = temporaryFile(new byte[0]);
    try (FileInputStream stream = new FileInputStream(file.toFile())) {
      int fd = rawFd(stream.getFD());
      // ABI 冻结的 length 下限是 1：0 字节不是“空内容摘要”，两侧都必须拒绝。
      RustDigest.Result zero =
          RustDigest.digestRust(fd, 0, 0, RustDigest.ALGORITHM_SHA256);
      assertFalse(zero.ok());
      assertEquals(RustDigest.INVALID_ARGUMENT, zero.errorCode());
      // offset 落在 EOF、length >= 1：Rust pread 读到 0 字节 → SHORT_READ。
      RustDigest.Result eof = RustDigest.digestRust(fd, 0, 1, RustDigest.ALGORITHM_SHA256);
      assertFalse(eof.ok());
      assertEquals(RustDigest.SHORT_READ, eof.errorCode());
    }
  }

  @Test
  public void offsetsAndCrossChunkRangesMatchTheJvm() throws IOException {
    byte[] payload = patterned(RustDigest.CHUNK * 3 + 23);
    Path file = temporaryFile(payload);
    try (FileInputStream stream = new FileInputStream(file.toFile());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      assertSameDigest(stream, channel, 0, payload.length, RustDigest.ALGORITHM_SHA256);
      assertSameDigest(stream, channel, 0, payload.length, RustDigest.ALGORITHM_SHA1);
      assertSameDigest(stream, channel, 17, 333, RustDigest.ALGORITHM_SHA256);
      assertSameDigest(
          stream,
          channel,
          RustDigest.CHUNK - 1,
          RustDigest.CHUNK + 2,
          RustDigest.ALGORITHM_SHA1);
    }
  }

  @Test
  public void thirtyTwoMiBSingleCallMatchesTheJvm() throws IOException {
    // R1 验收要求覆盖单次 32 MiB 上限；写文件分块，避免测试 JVM 额外占用大块堆。
    Path file = Files.createTempFile("dsha-rust-diff-32m-", ".bin");
    temporaryFiles.add(file);
    byte[] chunk = patterned(RustDigest.CHUNK);
    try (FileOutputStream out = new FileOutputStream(file.toFile())) {
      long remaining = RustDigest.MAX_LENGTH;
      while (remaining > 0) {
        int want = (int) Math.min((long) chunk.length, remaining);
        out.write(chunk, 0, want);
        remaining -= want;
      }
    }
    try (FileInputStream stream = new FileInputStream(file.toFile());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      assertSameDigest(
          stream, channel, 0, RustDigest.MAX_LENGTH, RustDigest.ALGORITHM_SHA256);
      assertSameDigest(stream, channel, 0, RustDigest.MAX_LENGTH, RustDigest.ALGORITHM_SHA1);
    }
  }

  @Test
  public void invalidScalarArgumentsReturnTheSameFrozenCodes() throws IOException {
    Path file = temporaryFile("0123456789".getBytes(StandardCharsets.US_ASCII));
    try (FileInputStream stream = new FileInputStream(file.toFile())) {
      int fd = rawFd(stream.getFD());
      long[][] ranges = {
        {-1, 1},
        {0, 0},
        {0, -1},
        {0, RustDigest.MAX_LENGTH + 1},
        {Long.MAX_VALUE, 1},
        {Long.MAX_VALUE, Long.MAX_VALUE}
      };
      for (long[] range : ranges) {
        int expected = RustDigest.validate(range[0], range[1], RustDigest.ALGORITHM_SHA256);
        assertNotEquals("case " + range[0] + "/" + range[1], 0, expected);
        RustDigest.Result rust =
            RustDigest.digestRust(fd, range[0], range[1], RustDigest.ALGORITHM_SHA256);
        assertEquals("case " + range[0] + "/" + range[1], expected, rust.errorCode());
        assertNull(rust.digest());
      }
      for (int algorithm : new int[] {0, -1, 3, 99, Integer.MAX_VALUE}) {
        RustDigest.Result rust = RustDigest.digestRust(fd, 0, 1, algorithm);
        assertEquals(RustDigest.UNSUPPORTED_ALGORITHM, rust.errorCode());
        assertEquals(RustDigest.Failure.UNSUPPORTED_ALGORITHM, rust.failure());
      }
    }
  }

  @Test
  public void invalidAndClosedFdsFailClosed() throws IOException {
    Path file = temporaryFile("0123456789".getBytes(StandardCharsets.US_ASCII));
    FileInputStream stream = new FileInputStream(file.toFile());
    try {
      int fd = rawFd(stream.getFD());
      // 负 fd 与不可能被打开的大 fd：Rust 侧统一映射为 INVALID_ARGUMENT，不 UB。
      for (int invalid : new int[] {-1, Integer.MIN_VALUE, 987_654_321, Integer.MAX_VALUE}) {
        RustDigest.Result result =
            RustDigest.digestRust(invalid, 0, 1, RustDigest.ALGORITHM_SHA256);
        assertFalse(result.ok());
        assertEquals("fd=" + invalid, RustDigest.INVALID_ARGUMENT, result.errorCode());
      }
      // 只写打开的 fd 读会 EBADF；类型化失败，绝不返回成功。
      try (FileOutputStream writeOnly = new FileOutputStream(file.toFile(), true)) {
        RustDigest.Result result =
            RustDigest.digestRust(rawFd(writeOnly.getFD()), 0, 1, RustDigest.ALGORITHM_SHA256);
        assertFalse(result.ok());
        assertEquals(RustDigest.INVALID_ARGUMENT, result.errorCode());
      }
      // 关闭的 fd：Rust 不 close 调用方 fd，但读到已关闭 fd 必须安全失败。
      int closedFd = fd;
      stream.close();
      RustDigest.Result closed =
          RustDigest.digestRust(closedFd, 0, 1, RustDigest.ALGORITHM_SHA256);
      assertFalse(closed.ok());
      assertEquals(RustDigest.INVALID_ARGUMENT, closed.errorCode());
    } finally {
      // 已手动 close 时再次 close 是安全的（幂等）。
      stream.close();
    }
  }

  @Test
  public void directBufferContractErrorsAreTyped() throws IOException {
    Path file = temporaryFile("0123456789".getBytes(StandardCharsets.US_ASCII));
    try (FileInputStream stream = new FileInputStream(file.toFile())) {
      int fd = rawFd(stream.getFD());
      ByteBuffer heap = ByteBuffer.allocate(32);
      assertEquals(
          RustDigest.INVALID_ARGUMENT,
          (int) RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, heap));
      ByteBuffer tooSmall = ByteBuffer.allocateDirect(8);
      assertEquals(
          RustDigest.OUTPUT_BOUND,
          (int) RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, tooSmall));
      // 失败不写半成品：8 字节仍是初始 0。
      for (int index = 0; index < tooSmall.capacity(); index++) {
        assertEquals(0, tooSmall.get(index));
      }
      assertEquals(
          RustDigest.INVALID_ARGUMENT,
          (int) RustNative.digest(fd, 0, 3, RustDigest.ALGORITHM_SHA256, null));
    }
  }

  @Test
  public void dispatcherUsesRustOnlyWhenTheSwitchIsOnAndNeverMislabelsIt() throws IOException {
    Path file = temporaryFile(patterned(4096));
    try (FileInputStream stream = new FileInputStream(file.toFile());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      int fd = rawFd(stream.getFD());
      RustBackends.setEnabled(false);
      RustDigest.Result jvm = RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      assertTrue(jvm.ok());
      assertEquals(RustDigest.Backend.JVM, jvm.backend());
      assertEquals("SWITCH_OFF", jvm.fallbackReason());
      RustBackends.setEnabled(true);
      RustDigest.Result rust = RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      assertTrue(rust.ok());
      assertEquals(RustDigest.Backend.RUST, rust.backend());
      assertFalse(rust.fellBack());
      assertEquals(jvm.digestHex(), rust.digestHex());
      // 一键回退：同一个调用点立刻回到 JVM，摘要不变。
      RustBackends.setEnabled(false);
      RustDigest.Result back = RustDigest.digest(fd, channel, 0, 4096, RustDigest.ALGORITHM_SHA256);
      assertTrue(back.ok());
      assertEquals(RustDigest.Backend.JVM, back.backend());
      assertEquals(jvm.digestHex(), back.digestHex());
    }
  }

  @Test
  public void jvmReferenceMatchesMessageDigestForTheSameFile() throws IOException {
    byte[] payload = patterned(1000);
    Path file = temporaryFile(payload);
    try (FileInputStream stream = new FileInputStream(file.toFile());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      MessageDigest expected = MessageDigest.getInstance("SHA-256");
      expected.update(payload, 10, 500);
      RustDigest.Result rust =
          RustDigest.digestRust(rawFd(stream.getFD()), 10, 500, RustDigest.ALGORITHM_SHA256);
      assertTrue(rust.ok());
      assertEquals(hex(expected.digest()), rust.digestHex());
    }
  }
}
