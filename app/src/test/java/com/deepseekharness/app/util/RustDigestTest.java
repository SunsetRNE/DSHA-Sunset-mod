package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * digest 的 JVM 后端与共享契约测试（不加载 .so、不依赖 Robolectric）。
 *
 * <p>覆盖：已知向量、0 字节/短读、偏移与跨块、参数边界（非法 offset/length/algorithm）、
 * channel position 不变、运行层开关默认关闭，以及 §5.4 的“哪些 Rust 失败允许 JVM 重做”判定。
 * Rust↔JVM 的真实对拍在 RustDigestDifferentialTest。
 */
public class RustDigestTest {
  private final List<Path> temporaryFiles = new ArrayList<>();

  @Before
  public void resetSwitch() {
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
    Path file = Files.createTempFile("dsha-rust-digest-", ".bin");
    Files.write(file, payload);
    temporaryFiles.add(file);
    return file;
  }

  private static FileChannel openRead(Path file) throws IOException {
    return FileChannel.open(file, StandardOpenOption.READ);
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >> 4) & 0x0f, 16));
      out.append(Character.forDigit(value & 0x0f, 16));
    }
    return out.toString();
  }

  @Test
  public void knownVectorsMatchMessageDigest() throws IOException {
    Path file = temporaryFile("abc".getBytes(StandardCharsets.US_ASCII));
    try (FileChannel channel = openRead(file)) {
      RustDigest.Result sha256 = RustDigest.digestJvm(channel, 0, 3, RustDigest.ALGORITHM_SHA256);
      assertTrue(sha256.ok());
      assertEquals(
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256.digestHex());
      assertEquals(3, sha256.bytesRead());
      assertEquals(RustDigest.Backend.JVM, sha256.backend());
      RustDigest.Result sha1 = RustDigest.digestJvm(channel, 0, 3, RustDigest.ALGORITHM_SHA1);
      assertTrue(sha1.ok());
      assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1.digestHex());
      assertEquals(20, sha1.digest().length);
    }
  }

  @Test
  public void offsetsAndChunkBoundariesMatchMessageDigest()
      throws IOException, NoSuchAlgorithmException {
    byte[] payload = new byte[RustDigest.CHUNK * 3 + 17];
    for (int index = 0; index < payload.length; index++) {
      payload[index] = (byte) (index % 251);
    }
    Path file = temporaryFile(payload);
    try (FileChannel channel = openRead(file)) {
      // 跨块（> 3 个 CHUNK）：JVM 分块循环必须与一次性 MessageDigest 等价。
      MessageDigest oneShot = MessageDigest.getInstance("SHA-256");
      oneShot.update(payload);
      RustDigest.Result across =
          RustDigest.digestJvm(channel, 0, payload.length, RustDigest.ALGORITHM_SHA256);
      assertTrue(across.ok());
      assertEquals(hex(oneShot.digest()), across.digestHex());
      // 中间偏移 + 非整块长度。
      MessageDigest slice = MessageDigest.getInstance("SHA-256");
      slice.update(payload, 17, 333);
      RustDigest.Result middle =
          RustDigest.digestJvm(channel, 17, 333, RustDigest.ALGORITHM_SHA256);
      assertTrue(middle.ok());
      assertEquals(hex(slice.digest()), middle.digestHex());
    }
  }

  @Test
  public void jvmDigestDoesNotMoveTheChannelPosition() throws IOException {
    Path file = temporaryFile("0123456789".getBytes(StandardCharsets.US_ASCII));
    try (FileChannel channel = openRead(file)) {
      channel.position(4);
      RustDigest.Result result = RustDigest.digestJvm(channel, 0, 10, RustDigest.ALGORITHM_SHA1);
      assertTrue(result.ok());
      assertEquals(4, channel.position());
    }
  }

  @Test
  public void zeroLengthAndShortReadAreRejected() throws IOException {
    Path file = temporaryFile(new byte[] {1, 2, 3});
    try (FileChannel channel = openRead(file)) {
      RustDigest.Result zero = RustDigest.digestJvm(channel, 0, 0, RustDigest.ALGORITHM_SHA256);
      assertFalse(zero.ok());
      assertEquals(RustDigest.INVALID_ARGUMENT, zero.errorCode());
      assertEquals(RustDigest.Failure.INVALID_ARGUMENT, zero.failure());
      // ABI 下限是 1 字节，所以“空内容摘要”只能由 Rust 单测的分块层覆盖；
      // 这里覆盖提前 EOF 的短读语义。
      RustDigest.Result shortRead =
          RustDigest.digestJvm(channel, 2, 2, RustDigest.ALGORITHM_SHA256);
      assertFalse(shortRead.ok());
      assertEquals(RustDigest.SHORT_READ, shortRead.errorCode());
      assertEquals(RustDigest.Failure.SHORT_READ, shortRead.failure());
      RustDigest.Result atEnd = RustDigest.digestJvm(channel, 3, 1, RustDigest.ALGORITHM_SHA1);
      assertEquals(RustDigest.SHORT_READ, atEnd.errorCode());
    }
  }

  @Test
  public void validatesBoundsAndAlgorithms() {
    assertEquals(0, RustDigest.validate(0, 1, RustDigest.ALGORITHM_SHA256));
    assertEquals(0, RustDigest.validate(0, RustDigest.MAX_LENGTH, RustDigest.ALGORITHM_SHA1));
    assertEquals(
        RustDigest.INVALID_ARGUMENT, RustDigest.validate(-1, 1, RustDigest.ALGORITHM_SHA256));
    assertEquals(
        RustDigest.INVALID_ARGUMENT, RustDigest.validate(0, 0, RustDigest.ALGORITHM_SHA256));
    assertEquals(
        RustDigest.INVALID_ARGUMENT, RustDigest.validate(0, -1, RustDigest.ALGORITHM_SHA256));
    assertEquals(
        RustDigest.INVALID_ARGUMENT,
        RustDigest.validate(0, RustDigest.MAX_LENGTH + 1, RustDigest.ALGORITHM_SHA256));
    assertEquals(
        RustDigest.INVALID_ARGUMENT,
        RustDigest.validate(Long.MAX_VALUE, 1, RustDigest.ALGORITHM_SHA256));
    assertEquals(RustDigest.UNSUPPORTED_ALGORITHM, RustDigest.validate(0, 1, 0));
    assertEquals(RustDigest.UNSUPPORTED_ALGORITHM, RustDigest.validate(0, 1, 99));
    // 常量与 ABI 冻结值一致（方案 §4.2/§4.3）。
    assertEquals(1, RustDigest.ALGORITHM_SHA256);
    assertEquals(2, RustDigest.ALGORITHM_SHA1);
    assertEquals(33_554_432L, RustDigest.MAX_LENGTH);
    assertEquals(262_144, RustDigest.CHUNK);
  }

  @Test
  public void nullChannelAndInvalidArgumentsFailClosed() {
    RustDigest.Result nullChannel = RustDigest.digestJvm(null, 0, 1, RustDigest.ALGORITHM_SHA256);
    assertFalse(nullChannel.ok());
    assertEquals(RustDigest.INVALID_ARGUMENT, nullChannel.errorCode());
    assertNull(nullChannel.digest());
    // 开关关闭时 dispatcher 只看 JVM：fd 不被使用，非法长度仍按共享契约失败。
    RustDigest.Result result = RustDigest.digest(-1, null, 0, 2, RustDigest.ALGORITHM_SHA256);
    assertFalse(result.ok());
    assertEquals(RustDigest.INVALID_ARGUMENT, result.errorCode());
    assertEquals(RustDigest.Backend.JVM, result.backend());
    assertEquals("SWITCH_OFF", result.fallbackReason());
    assertFalse(result.fellBack());
  }

  @Test
  public void switchOffRunsTheWholeJvmPath() throws IOException, NoSuchAlgorithmException {
    Path file = temporaryFile("0123456789".getBytes(StandardCharsets.US_ASCII));
    try (FileChannel channel = openRead(file)) {
      assertFalse(RustBackends.enabled());
      RustDigest.Result result = RustDigest.digest(-1, channel, 0, 10, RustDigest.ALGORITHM_SHA1);
      assertTrue(result.ok());
      assertEquals(RustDigest.Backend.JVM, result.backend());
      assertEquals("SWITCH_OFF", result.fallbackReason());
      assertFalse(result.fellBack());
      MessageDigest expected = MessageDigest.getInstance("SHA-1");
      expected.update("0123456789".getBytes(StandardCharsets.US_ASCII));
      assertEquals(hex(expected.digest()), result.digestHex());
    }
  }

  @Test
  public void onlyInternalAndPanicAllowTheJvmRedo() {
    // §5.4：确定性输入错误不重试（重试只会掩盖调用方错误）；只有内部错误/panic 允许重做。
    assertTrue(RustDigest.shouldRedoOnJvm(RustDigest.Failure.INTERNAL));
    assertTrue(RustDigest.shouldRedoOnJvm(RustDigest.Failure.PANIC));
    for (RustDigest.Failure failure :
        new RustDigest.Failure[] {
          RustDigest.Failure.INVALID_ARGUMENT,
          RustDigest.Failure.SHORT_READ,
          RustDigest.Failure.UNSUPPORTED_ALGORITHM,
          RustDigest.Failure.OUTPUT_BOUND,
          RustDigest.Failure.JNI_PENDING,
          RustDigest.Failure.LIBRARY_MISSING,
          RustDigest.Failure.LIBRARY_LOAD_FAILED,
          RustDigest.Failure.ABI_MISMATCH,
          RustDigest.Failure.NOT_LOADED,
          RustDigest.Failure.UNKNOWN,
          RustDigest.Failure.NONE
        }) {
      assertFalse(failure.name(), RustDigest.shouldRedoOnJvm(failure));
    }
  }

  @Test
  public void mapsFrozenErrorCodes() {
    assertEquals(RustDigest.Failure.INVALID_ARGUMENT, RustDigest.mapFailure(-1));
    assertEquals(RustDigest.Failure.OUTPUT_BOUND, RustDigest.mapFailure(-3));
    assertEquals(RustDigest.Failure.INTERNAL, RustDigest.mapFailure(-6));
    assertEquals(RustDigest.Failure.PANIC, RustDigest.mapFailure(-7));
    assertEquals(RustDigest.Failure.JNI_PENDING, RustDigest.mapFailure(-8));
    assertEquals(RustDigest.Failure.SHORT_READ, RustDigest.mapFailure(-9));
    assertEquals(RustDigest.Failure.UNSUPPORTED_ALGORITHM, RustDigest.mapFailure(-10));
    assertEquals(RustDigest.Failure.UNKNOWN, RustDigest.mapFailure(-999));
    assertEquals(RustDigest.Failure.UNKNOWN, RustDigest.mapFailure(0));
    assertEquals("00ff10", RustDigest.hex(new byte[] {0x00, (byte) 0xff, 0x10}));
  }

  @Test
  public void rustInternalAndPanicAreRedoneByJvmAndMarkedAsFallback()
      throws IOException, NoSuchAlgorithmException {
    // §5.4 的端到端投影：Rust 失败结果 → JVM 重做一次 → backend=JVM、fellBack=true、
    // fallbackReason=RUST_*；确定性错误不重试；无 channel 时不能伪装成功。
    Path file = temporaryFile("0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    MessageDigest expected = MessageDigest.getInstance("SHA-1");
    expected.update("0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    // MessageDigest.digest() 会重置状态：只取一次十六进制，供循环内两次比较。
    String expectedHex = RustDigest.hex(expected.digest());
    try (FileChannel channel = openRead(file)) {
      for (RustDigest.Failure failure :
          new RustDigest.Failure[] {RustDigest.Failure.INTERNAL, RustDigest.Failure.PANIC}) {
        RustDigest.Result settled =
            RustDigest.settleRustResult(
                RustDigest.Result.simulatedFailure(
                    failure, failure == RustDigest.Failure.INTERNAL ? -6 : -7),
                channel,
                0,
                10,
                RustDigest.ALGORITHM_SHA1);
        assertTrue(failure.name(), settled.ok());
        assertEquals(RustDigest.Backend.JVM, settled.backend());
        assertTrue(settled.fellBack());
        assertEquals("RUST_" + failure.name(), settled.fallbackReason());
        assertEquals(expectedHex, settled.digestHex());
        assertEquals(10, settled.bytesRead());
      }
      RustDigest.Result deterministic =
          RustDigest.settleRustResult(
              RustDigest.Result.simulatedFailure(RustDigest.Failure.INVALID_ARGUMENT, -1),
              channel,
              0,
              10,
              RustDigest.ALGORITHM_SHA1);
      assertFalse(deterministic.ok());
      assertEquals(RustDigest.Backend.RUST, deterministic.backend());
      assertFalse(deterministic.fellBack());
      assertEquals(RustDigest.INVALID_ARGUMENT, deterministic.errorCode());
      RustDigest.Result withoutChannel =
          RustDigest.settleRustResult(
              RustDigest.Result.simulatedFailure(RustDigest.Failure.INTERNAL, -6),
              null,
              0,
              10,
              RustDigest.ALGORITHM_SHA1);
      assertFalse(withoutChannel.ok());
      assertFalse(withoutChannel.fellBack());
      assertEquals("JVM_SOURCE_UNAVAILABLE", withoutChannel.fallbackReason());
      RustDigest.Result jvmAlsoFails =
          RustDigest.settleRustResult(
              RustDigest.Result.simulatedFailure(RustDigest.Failure.INTERNAL, -6),
              channel,
              100,
              5,
              RustDigest.ALGORITHM_SHA1);
      assertFalse(jvmAlsoFails.ok());
      assertTrue(jvmAlsoFails.fellBack());
      assertEquals(RustDigest.SHORT_READ, jvmAlsoFails.errorCode());
    }
  }

  @Test
  public void digestRustFailureIsTypedWithOrWithoutANativeLibrary() {
    // 本测试不假设 native 是否已加载：JUnit 运行顺序可能先跑 RustDigestDifferentialTest
    // （加载 host .so 后这里得到 INVALID_ARGUMENT），也可能先跑 RustBackendsTest
    // （ensureLoaded 失败后这里是类型化加载失败，errorCode=0）。两种都必须 fail-closed。
    RustDigest.Result result = RustDigest.digestRust(-1, 0, 1, RustDigest.ALGORITHM_SHA256);
    assertFalse(result.ok());
    assertNull(result.digest());
    assertEquals(RustDigest.Backend.RUST, result.backend());
    if (RustNative.isAvailable()) {
      // 已加载：非法 fd 由 Rust 侧返回冻结错误码 -1。
      assertEquals(RustDigest.INVALID_ARGUMENT, result.errorCode());
      assertEquals(RustDigest.Failure.INVALID_ARGUMENT, result.failure());
    } else {
      // 未加载：不触碰 JNI，只有类型化加载失败，没有 ABI 错误码。
      assertEquals(0, result.errorCode());
      assertTrue(
          "failure=" + result.failure(),
          result.failure() == RustDigest.Failure.NOT_LOADED
              || result.failure() == RustDigest.Failure.LIBRARY_MISSING
              || result.failure() == RustDigest.Failure.LIBRARY_LOAD_FAILED
              || result.failure() == RustDigest.Failure.ABI_MISMATCH);
    }
    // 非 direct / 过小缓冲的映射由 Rust 侧返回；这里只验证 Java 常量与分配形状。
    assertEquals(32, ByteBuffer.allocateDirect(32).capacity());
  }
}
