package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * {@link RustArchiveCodec} 的纯 JVM 契约测试（不加载 .so）。
 *
 * <p>只锁冻结 ABI 的常量、输出缓冲公式、错误码/消息映射与结果解码；真实 gzip 差分见
 * {@link RustArchiveDifferentialTest}。
 */
public class RustArchiveCodecTest {
  @Test
  public void pinnedConstantsMatchTheAbi() {
    assertEquals(262_144, RustArchiveCodec.CHUNK);
    assertEquals(262_144, RustArchiveCodec.MAX_OUTPUT_CHUNK);
    assertEquals(8_192, RustArchiveCodec.MAX_METADATA);
    assertEquals(0, RustArchiveCodec.MIN_LEVEL);
    assertEquals(9, RustArchiveCodec.MAX_LEVEL);
    assertEquals(6, RustArchiveCodec.DEFAULT_LEVEL);
    assertEquals(128 * 1024, RustArchiveCodec.FINISH_BOUND);
    assertEquals(10, RustArchiveCodec.HEADER_HEX.length() / 2);
    assertEquals("1f8b0800000000000000", RustArchiveCodec.HEADER_HEX);
    assertEquals(17_448_304_640L, RustArchiveCodec.MAX_TOTAL_BYTES);
    assertEquals(1, RustArchiveCodec.FLAG_EOF);
    assertEquals(2, RustArchiveCodec.FLAG_NEED_INPUT);
    assertEquals(4, RustArchiveCodec.FLAG_NEED_OUTPUT);
  }

  @Test
  public void packBoundMatchesTheRustFormula() {
    assertEquals(128, RustArchiveCodec.packBound(0));
    assertEquals(129, RustArchiveCodec.packBound(1));
    assertEquals(262_144 + 32_768 + 128, RustArchiveCodec.packBound(RustArchiveCodec.CHUNK));
    for (int size : new int[] {1, 2, 4_096, 65_536, RustArchiveCodec.CHUNK}) {
      assertTrue(RustArchiveCodec.packBound(size) > size + size / 9);
    }
    assertEquals(
        RustArchiveCodec.packBound(RustArchiveCodec.CHUNK), RustArchiveCodec.MAX_PACK_OUTPUT);
  }

  @Test
  public void packBoundRejectsIntOverflowInsteadOfWrapping() {
    // 评审 P2-8：n + n/8 + 128 对超大 n 会溢出 int，回绕成看似合法的值。
    long fit = ((long) (Integer.MAX_VALUE - 128) * 8) / 9;
    while (fit + fit / 8 + 128 > Integer.MAX_VALUE) {
      fit--;
    }
    while ((fit + 1) + (fit + 1) / 8 + 128 <= Integer.MAX_VALUE) {
      fit++;
    }
    int largestFitting = (int) fit;
    assertEquals(
        (int) ((long) largestFitting + largestFitting / 8 + 128),
        RustArchiveCodec.packBound(largestFitting));
    for (int size : new int[] {largestFitting + 1, Integer.MAX_VALUE}) {
      try {
        RustArchiveCodec.packBound(size);
        fail("packBound(" + size + ") 必须拒绝 int 溢出");
      } catch (IllegalArgumentException expected) {
        // 预期：不返回回绕后的负/错误上限。
      }
    }
    try {
      RustArchiveCodec.packBound(-1);
      fail("packBound(-1) 必须拒绝");
    } catch (IllegalArgumentException expected) {
      // 预期。
    }
  }

  @Test
  public void levelValidationIsTheFrozenRange() {
    assertTrue(RustArchiveCodec.levelValid(0));
    assertTrue(RustArchiveCodec.levelValid(6));
    assertTrue(RustArchiveCodec.levelValid(9));
    assertFalse(RustArchiveCodec.levelValid(-1));
    assertFalse(RustArchiveCodec.levelValid(10));
  }

  @Test
  public void errorMessagesMatchTheStrictGzipReader() {
    assertEquals("GZIP_HEADER", RustArchiveCodec.message(RustArchiveCodec.GZIP_HEADER));
    assertEquals("GZIP_HEADER_CRC", RustArchiveCodec.message(RustArchiveCodec.GZIP_HEADER_CRC));
    assertEquals(
        "GZIP_METADATA_LIMIT", RustArchiveCodec.message(RustArchiveCodec.GZIP_METADATA_LIMIT));
    assertEquals("GZIP_TRUNCATED", RustArchiveCodec.message(RustArchiveCodec.GZIP_TRUNCATED));
    assertEquals("GZIP_DEFLATE", RustArchiveCodec.message(RustArchiveCodec.GZIP_DEFLATE));
    assertEquals("GZIP_TRAILER", RustArchiveCodec.message(RustArchiveCodec.GZIP_TRAILER));
    assertEquals(
        "GZIP_TRAILING_DATA", RustArchiveCodec.message(RustArchiveCodec.GZIP_TRAILING_DATA));
    assertEquals("OUTPUT_BOUND", RustArchiveCodec.message(RustArchiveCodec.OUTPUT_BOUND));
    assertEquals("NOT_IMPLEMENTED", RustArchiveCodec.message(RustArchiveCodec.NOT_IMPLEMENTED));
    assertEquals("RUST_ERROR_-99", RustArchiveCodec.message(-99));
    // unpack 语境的累计上限映射为 JVM 参考读取器的 ARCHIVE_LIMIT，其余码不变。
    assertEquals("ARCHIVE_LIMIT", RustArchiveCodec.unpackMessage(RustArchiveCodec.OUTPUT_BOUND));
    assertEquals("GZIP_TRAILER", RustArchiveCodec.unpackMessage(RustArchiveCodec.GZIP_TRAILER));
    assertEquals("INTERNAL", RustArchiveCodec.unpackMessage(RustArchiveCodec.INTERNAL));
  }

  @Test
  public void jvmMessagesMapBackToFrozenCodes() {
    assertEquals(
        RustArchiveCodec.GZIP_HEADER, RustArchiveCodec.errorCodeForJvmMessage("GZIP_FLAGS"));
    assertEquals(
        RustArchiveCodec.GZIP_HEADER, RustArchiveCodec.errorCodeForJvmMessage("GZIP_HEADER"));
    assertEquals(
        RustArchiveCodec.GZIP_TRUNCATED, RustArchiveCodec.errorCodeForJvmMessage("GZIP_TRUNCATED"));
    assertEquals(
        RustArchiveCodec.INVALID_ARGUMENT,
        RustArchiveCodec.errorCodeForJvmMessage("STREAM_CLOSED"));
    // 评审 P2-7：JVM 参考读取器的累计上限必须映射回冻结 ABI 的 -3（unpack 语境）。
    assertEquals(
        RustArchiveCodec.OUTPUT_BOUND, RustArchiveCodec.errorCodeForJvmMessage("ARCHIVE_LIMIT"));
    assertEquals(Integer.MIN_VALUE, RustArchiveCodec.errorCodeForJvmMessage("SOMETHING_ELSE"));
    assertEquals(Integer.MIN_VALUE, RustArchiveCodec.errorCodeForJvmMessage(null));
    assertEquals("GZIP_HEADER", RustArchiveCodec.canonicalJvmMessage("GZIP_FLAGS"));
    assertEquals("GZIP_TRAILER", RustArchiveCodec.canonicalJvmMessage("GZIP_TRAILER"));
  }

  @Test
  public void updateAndReadResultsAreDecodedAsDocumented() {
    long update = (123L << 32) | 456L;
    assertEquals(123, RustArchiveCodec.consumed(update));
    assertEquals(456, RustArchiveCodec.produced(update));
    long read = (789L << 32) | RustArchiveCodec.FLAG_EOF;
    assertEquals(789, RustArchiveCodec.unpackProduced(read));
    assertEquals(RustArchiveCodec.FLAG_EOF, RustArchiveCodec.unpackFlags(read));
  }

  @Test
  public void unavailableMessageIsTypedAndPathFree() {
    String message = RustArchiveCodec.unavailableMessage();
    assertTrue(message.startsWith("RUST_"));
    assertFalse(message.contains("/"));
    assertFalse(message.contains("\\"));
  }
}
