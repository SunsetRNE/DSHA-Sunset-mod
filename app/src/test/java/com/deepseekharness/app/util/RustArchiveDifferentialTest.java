package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.StrictGzipInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * JVM {@link StrictGzipInputStream}/{@link GZIPOutputStream} 与 Rust archive 后端的真实对拍
 *（需要主机目标 .so）。
 *
 * <p>运行方式与 {@link RustDigestDifferentialTest} 相同：{@code -Pdsha.rust.hostLib=<repo>/app/build/rust-host/libdsha_rs.so}，
 * 可加 {@code -Pdsha.rust.required=true} 让缺少 .so 直接失败（CI 差分/全量单测用后者保证
 * 0 skipped）。证明范围是“同一源码的主机 .so 与 JVM 逐字节/逐错误码一致”，不等于 Android
 * .so 已加载（后者由 R3 签名 .dev 包与 APK 静态门禁覆盖）。
 */
public class RustArchiveDifferentialTest {
  static final String HOST_LIB_PROPERTY = "dsha.rust.hostLib";
  static final String REQUIRED_PROPERTY = "dsha.rust.required";

  private static int nativeCalls = 0;

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
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  private static byte[] patterned(int size) {
    byte[] payload = new byte[size];
    for (int index = 0; index < size; index++) {
      payload[index] = (byte) ((index * 31 + 7) % 251);
    }
    return payload;
  }

  private static byte[] javaGzip(byte[] payload) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream stream = new GZIPOutputStream(bytes)) {
      stream.write(payload);
    }
    return bytes.toByteArray();
  }

  private static byte[] rustPack(byte[] payload, int level, int writeChunk) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (RustGzipOutputStream stream = new RustGzipOutputStream(bytes, level)) {
      int offset = 0;
      while (offset < payload.length) {
        int count = Math.min(writeChunk, payload.length - offset);
        stream.write(payload, offset, count);
        offset += count;
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] out = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, out, first.length, second.length);
    return out;
  }

  private static byte[] u16le(int value) {
    return new byte[] {(byte) value, (byte) (value >>> 8)};
  }

  private static byte[] withOptionalHeader(byte[] gzip, byte[] extra, byte[] name, boolean fhcrc) {
    int flags = gzip[3];
    ByteArrayOutputStream fields = new ByteArrayOutputStream();
    if (extra != null) {
      flags |= 0x04;
      fields.writeBytes(u16le(extra.length));
      fields.writeBytes(extra);
    }
    if (name != null) {
      flags |= 0x08;
      fields.writeBytes(name);
      fields.write(0);
    }
    byte[] header = Arrays.copyOf(gzip, 10);
    if (fhcrc) {
      flags |= 0x02;
    }
    header[3] = (byte) flags;
    byte[] out = concat(header, fields.toByteArray());
    if (fhcrc) {
      CRC32 crc = new CRC32();
      crc.update(out);
      out = concat(out, u16le((int) (crc.getValue() & 0xffff)));
    }
    return concat(out, Arrays.copyOfRange(gzip, 10, gzip.length));
  }

  private static final class Outcome {
    final byte[] bytes;
    final String error;

    Outcome(byte[] bytes, String error) {
      this.bytes = bytes;
      this.error = error;
    }
  }

  private static Outcome drain(InputStream stream) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      byte[] buffer = new byte[257];
      while (true) {
        int read = stream.read(buffer, 0, buffer.length);
        if (read < 0) {
          break;
        }
        if (read > 0) {
          bytes.write(buffer, 0, read);
        }
      }
      return new Outcome(bytes.toByteArray(), null);
    } catch (IOException error) {
      return new Outcome(
          bytes.toByteArray(), RustArchiveCodec.canonicalJvmMessage(error.getMessage()));
    } finally {
      try {
        stream.close();
      } catch (IOException ignored) {
        // 关闭失败不改变读取结果。
      }
    }
  }

  private static Outcome readJvm(byte[] gzip) {
    try {
      return drain(new StrictGzipInputStream(new ByteArrayInputStream(gzip)));
    } catch (IOException error) {
      return new Outcome(new byte[0], RustArchiveCodec.canonicalJvmMessage(error.getMessage()));
    }
  }

  private static Outcome readRust(byte[] gzip) {
    try {
      return drain(new RustGzipInputStream(new ByteArrayInputStream(gzip)));
    } catch (IOException error) {
      return new Outcome(new byte[0], RustArchiveCodec.canonicalJvmMessage(error.getMessage()));
    }
  }

  private static void assertSameOutcome(String name, Outcome jvm, Outcome rust) {
    assertEquals(name + " bytes", jvm.bytes.length, rust.bytes.length);
    assertArrayEquals(name + " bytes", jvm.bytes, rust.bytes);
    assertEquals(name + " error", jvm.error, rust.error);
  }

  private static final class ChunkedInputStream extends InputStream {
    private final byte[] data;
    private final int chunk;
    private int offset;

    ChunkedInputStream(byte[] data, int chunk) {
      this.data = data;
      this.chunk = chunk;
    }

    @Override
    public int read() {
      if (offset >= data.length) {
        return -1;
      }
      return data[offset++] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int at, int length) {
      if (offset >= data.length) {
        return -1;
      }
      int count = Math.min(Math.min(length, chunk), data.length - offset);
      System.arraycopy(data, offset, bytes, at, count);
      offset += count;
      return count;
    }
  }

  @Test
  public void abiProbeAndGzipHeaderTrailerArePinned() throws IOException {
    assertEquals(1, RustNative.abiVersion());
    byte[] payload = patterned(1_024);
    byte[] gzip = rustPack(payload, 6, 4_096);
    assertEquals(RustArchiveCodec.HEADER_HEX, RustDigest.hex(Arrays.copyOf(gzip, 10)));
    CRC32 crc = new CRC32();
    crc.update(payload);
    assertEquals((int) crc.getValue(), littleEndianInt(gzip, gzip.length - 8));
    assertEquals(payload.length, littleEndianInt(gzip, gzip.length - 4));
    assertSameOutcome("rust pack", readJvm(gzip), new Outcome(payload, null));
  }

  @Test
  public void rustPackIsReadByTheJvmReaderAcrossLevelsAndSizes() throws IOException {
    for (int level : new int[] {0, 1, 6, 9}) {
      for (int size : new int[] {0, 1, 64, 65_536, 262_144}) {
        byte[] payload = patterned(size);
        byte[] gzip = rustPack(payload, level, level == 0 ? 1 : 7_919);
        Outcome jvm = readJvm(gzip);
        assertEquals("level=" + level + " size=" + size, null, jvm.error);
        assertArrayEquals("level=" + level + " size=" + size, payload, jvm.bytes);
      }
    }
  }

  @Test
  public void jvmPackIsReadByRustAcrossChunkSizes() throws IOException {
    for (int size : new int[] {0, 1, 17, 64, 65_536, 262_144}) {
      byte[] payload = patterned(size);
      byte[] gzip = javaGzip(payload);
      for (int chunk : new int[] {1, 7, 4_096}) {
        Outcome rust = drain(new RustGzipInputStream(new ChunkedInputStream(gzip, chunk)));
        Outcome jvm = drain(new StrictGzipInputStream(new ChunkedInputStream(gzip, chunk)));
        assertEquals("size=" + size + " chunk=" + chunk, null, rust.error);
        assertArrayEquals("size=" + size + " chunk=" + chunk, payload, rust.bytes);
        assertSameOutcome("size=" + size + " chunk=" + chunk, jvm, rust);
      }
    }
  }

  @Test
  public void validOptionalHeadersAreAcceptedByBothImplementations() throws IOException {
    byte[] payload = patterned(512);
    byte[] base = javaGzip(payload);
    byte[] extra = new byte[8_193];
    Arrays.fill(extra, (byte) 0x5a);
    byte[][] variants = {
      withOptionalHeader(base, extra, null, false),
      withOptionalHeader(
          base, null, "name.bin".getBytes(java.nio.charset.StandardCharsets.US_ASCII), false),
      withOptionalHeader(
          base,
          new byte[] {1, 2, 3},
          "n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          true),
    };
    for (byte[] gzip : variants) {
      Outcome jvm = readJvm(gzip);
      Outcome rust = readRust(gzip);
      assertEquals(null, rust.error);
      assertSameOutcome("optional header", jvm, rust);
      assertArrayEquals(payload, rust.bytes);
    }
  }

  @Test
  public void invalidCorpusMatchesTheJvmReader() throws IOException {
    byte[] base = javaGzip(patterned(256));
    byte[] name8k = new byte[8_192];
    Arrays.fill(name8k, (byte) 'n');
    Object[][] cases = {
      {"bad-magic", mutate(base, 0, 0x1e), "GZIP_HEADER"},
      {"bad-cm", mutate(base, 2, 7), "GZIP_HEADER"},
      {"bad-flags", mutate(base, 3, base[3] | 0x20), "GZIP_HEADER"},
      {"bad-crc", mutate(base, base.length - 8, base[base.length - 8] ^ 0xff), "GZIP_TRAILER"},
      {"bad-isize", mutate(base, base.length - 4, base[base.length - 4] ^ 0xff), "GZIP_TRAILER"},
      {"trailing", concat(base, new byte[] {0}), "GZIP_TRAILING_DATA"},
      {"two-members", concat(base, javaGzip(patterned(32))), "GZIP_TRAILING_DATA"},
      {"fname-too-long", withUnterminatedName(base, name8k), "GZIP_METADATA_LIMIT"},
      {"deflate-garbage", concat(Arrays.copyOf(base, 10), new byte[64]), "GZIP_DEFLATE"},
    };
    for (Object[] testCase : cases) {
      String name = (String) testCase[0];
      byte[] gzip = (byte[]) testCase[1];
      String expected = (String) testCase[2];
      Outcome jvm = readJvm(gzip);
      Outcome rust = readRust(gzip);
      assertEquals(name + " jvm error", expected, jvm.error);
      assertSameOutcome(name, jvm, rust);
    }
  }

  @Test
  public void truncationAtEveryPrefixMatchesTheJvmReader() throws IOException {
    byte[] gzip = javaGzip(patterned(96));
    for (int length = 0; length < gzip.length; length++) {
      byte[] prefix = Arrays.copyOf(gzip, length);
      Outcome jvm = readJvm(prefix);
      Outcome rust = readRust(prefix);
      assertEquals("prefix=" + length, "GZIP_TRUNCATED", jvm.error);
      assertSameOutcome("prefix=" + length, jvm, rust);
    }
  }

  @Test
  public void nativeContractsReturnTheFrozenCodes() throws IOException {
    assertEquals(RustArchiveCodec.INVALID_ARGUMENT, (int) RustNative.archivePackCreate(-1));
    long pack = RustNative.archivePackCreate(6);
    nativeCalls++;
    assertTrue(pack > 0);
    ByteBuffer oneByte = ByteBuffer.allocateDirect(1);
    oneByte.put(0, (byte) 0x42);
    ByteBuffer output = ByteBuffer.allocateDirect(RustArchiveCodec.MAX_PACK_OUTPUT);
    ByteBuffer small = ByteBuffer.allocateDirect(8);
    ByteBuffer heap = ByteBuffer.allocate(1);
    ByteBuffer big = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK + 1);
    assertEquals(
        RustArchiveCodec.INVALID_ARGUMENT, (int) RustNative.archivePackUpdate(pack, heap, output));
    assertEquals(
        RustArchiveCodec.INPUT_BOUND, (int) RustNative.archivePackUpdate(pack, big, output));
    assertEquals(
        RustArchiveCodec.OUTPUT_BOUND, (int) RustNative.archivePackUpdate(pack, oneByte, small));
    long update = RustNative.archivePackUpdate(pack, oneByte, output);
    nativeCalls++;
    assertEquals(1, RustArchiveCodec.consumed(update));
    assertEquals(10, RustArchiveCodec.produced(update));
    assertEquals(RustArchiveCodec.OUTPUT_BOUND, (int) RustNative.archivePackFinish(pack, small));
    long finish = RustNative.archivePackFinish(pack, output);
    nativeCalls++;
    assertTrue(finish > 0);
    assertEquals(
        RustArchiveCodec.STALE_HANDLE, (int) RustNative.archivePackUpdate(pack, oneByte, output));
    assertEquals(RustArchiveCodec.STALE_HANDLE, RustNative.archivePackAbort(pack));

    long unpack = RustNative.archiveUnpackCreate();
    nativeCalls++;
    assertTrue(unpack > 0);
    assertEquals(
        RustArchiveCodec.INVALID_ARGUMENT, (int) RustNative.archiveUnpackWrite(unpack, null));
    assertEquals(RustArchiveCodec.INPUT_BOUND, (int) RustNative.archiveUnpackWrite(unpack, big));
    ByteBuffer zeroOutput = ByteBuffer.allocateDirect(1);
    zeroOutput.limit(0);
    assertEquals(
        RustArchiveCodec.INVALID_ARGUMENT,
        (int) RustNative.archiveUnpackRead(unpack, zeroOutput.slice()));
    assertEquals(
        RustArchiveCodec.INVALID_ARGUMENT, (int) RustNative.archiveUnpackRead(unpack, big));
    long pack2 = RustNative.archivePackCreate(6);
    nativeCalls++;
    assertEquals(RustArchiveCodec.STALE_HANDLE, (int) RustNative.archiveUnpackRead(pack2, oneByte));
    assertEquals(RustArchiveCodec.OK, RustNative.archivePackAbort(pack2));
    assertEquals(RustArchiveCodec.OK, RustNative.archiveUnpackAbort(unpack));
    assertTrue(nativeCalls > 0);
  }

  @Test
  public void sessionLimitIsFourAndOneKeySwitchRollsBack() {
    long[] handles = new long[4];
    try {
      for (int index = 0; index < handles.length; index++) {
        handles[index] = RustNative.archivePackCreate(6);
        nativeCalls++;
        assertTrue(handles[index] > 0);
      }
      assertEquals(RustArchiveCodec.SESSION_LIMIT, (int) RustNative.archivePackCreate(6));
      assertEquals(RustArchiveCodec.SESSION_LIMIT, (int) RustNative.archiveUnpackCreate());
      for (int index = 0; index < handles.length; index++) {
        assertEquals(RustArchiveCodec.OK, RustNative.archivePackAbort(handles[index]));
        handles[index] = 0;
      }
    } finally {
      for (long handle : handles) {
        if (handle > 0) {
          RustNative.archivePackAbort(handle);
        }
      }
    }

    RustBackends.setEnabled(false);
    RustBackends.Selection off = RustBackends.archiveSelection();
    assertFalse(off.useRust());
    assertEquals(RustBackends.SelectionReason.SWITCH_OFF, off.reason());
    RustBackends.setEnabled(true);
    RustBackends.Selection on = RustBackends.archiveSelection();
    assertTrue(on.useRust());
    assertEquals(RustBackends.SelectionReason.RUST_ENABLED, on.reason());
    RustBackends.setEnabled(false);
    assertFalse(RustBackends.archiveSelection().useRust());
  }

  @Test
  public void closedStreamsRejectFurtherUse() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    RustGzipOutputStream out = new RustGzipOutputStream(bytes);
    out.write(patterned(64));
    out.finish();
    try {
      out.write(1);
      fail("finish 后写入必须失败");
    } catch (IOException error) {
      assertEquals("STREAM_CLOSED", error.getMessage());
    }
    out.close();
    byte[] gzip = bytes.toByteArray();
    RustGzipInputStream in = new RustGzipInputStream(new ByteArrayInputStream(gzip));
    assertEquals(64, in.read(new byte[64], 0, 64));
    in.close();
    try {
      in.read();
      fail("close 后读取必须失败");
    } catch (IOException error) {
      assertEquals("STREAM_CLOSED", error.getMessage());
    }
  }

  private static byte[] mutate(byte[] source, int index, int value) {
    byte[] out = source.clone();
    out[index] = (byte) value;
    return out;
  }

  private static byte[] withUnterminatedName(byte[] gzip, byte[] name) {
    byte[] header = Arrays.copyOf(gzip, 10);
    header[3] = (byte) (header[3] | 0x08);
    return concat(concat(header, name), Arrays.copyOfRange(gzip, 10, gzip.length));
  }

  private static int littleEndianInt(byte[] data, int offset) {
    return (data[offset] & 0xff)
        | ((data[offset + 1] & 0xff) << 8)
        | ((data[offset + 2] & 0xff) << 16)
        | ((data[offset + 3] & 0xff) << 24);
  }
}
