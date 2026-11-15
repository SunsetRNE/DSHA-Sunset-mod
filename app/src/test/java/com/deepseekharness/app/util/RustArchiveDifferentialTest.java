package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.LegacyTarReader;
import com.deepseekharness.app.backup.PortableBackupCrypto;
import com.deepseekharness.app.backup.PortableBackupEnvelope;
import com.deepseekharness.app.backup.StrictGzipInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

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
    byte[] comment8k = new byte[8_192];
    Arrays.fill(comment8k, (byte) 'c');
    byte[] fhcrc = withOptionalHeader(base, null, null, true);
    Object[][] cases = {
      {"bad-magic", mutate(base, 0, 0x1e), "GZIP_HEADER"},
      {"bad-cm", mutate(base, 2, 7), "GZIP_HEADER"},
      {"bad-flags", mutate(base, 3, base[3] | 0x20), "GZIP_HEADER"},
      // 评审 P2-9：FHCRC 不符与 FCOMMENT 超长都必须与 JVM 同码。
      {"bad-fhcrc", mutate(fhcrc, 10, fhcrc[10] ^ 0xff), "GZIP_HEADER_CRC"},
      {"fname-too-long", withUnterminatedName(base, name8k), "GZIP_METADATA_LIMIT"},
      {"fcomment-too-long", withUnterminatedComment(base, comment8k), "GZIP_METADATA_LIMIT"},
      {"bad-crc", mutate(base, base.length - 8, base[base.length - 8] ^ 0xff), "GZIP_TRAILER"},
      {"bad-isize", mutate(base, base.length - 4, base[base.length - 4] ^ 0xff), "GZIP_TRAILER"},
      {"trailing", concat(base, new byte[] {0}), "GZIP_TRAILING_DATA"},
      {"two-members", concat(base, javaGzip(patterned(32))), "GZIP_TRAILING_DATA"},
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
  public void optionalHeaderTruncationAtEveryPrefixMatchesTheJvmReader() throws IOException {
    // 评审 P2-9：带 FEXTRA + FNAME + FHCRC 的可选头逐前缀截断，两边都必须报截断而不是头错误。
    byte[] base = javaGzip(patterned(192));
    byte[] mixed =
        withOptionalHeader(
            base,
            new byte[] {0x5a, 0x01, 0x02},
            "name.bin".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            true);
    for (int length = 0; length < mixed.length; length++) {
      byte[] prefix = Arrays.copyOf(mixed, length);
      Outcome jvm = readJvm(prefix);
      Outcome rust = readRust(prefix);
      assertEquals("optional prefix=" + length, "GZIP_TRUNCATED", jvm.error);
      assertSameOutcome("optional prefix=" + length, jvm, rust);
    }
  }

  @Test
  public void finishTailWriteFailureKeepsTheOriginalErrorAndReleasesTheSession()
      throws IOException {
    // 评审 P2-6：native finish 成功后再写尾部；若 target.write 失败，finished/session
    // 必须已更新，再次 finish() 是幂等 no-op 而不是 STALE_HANDLE。
    final boolean[] fail = {false};
    OutputStream target =
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            if (fail[0]) {
              throw new IOException("TARGET_WRITE_FAILED");
            }
          }

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            if (fail[0]) {
              throw new IOException("TARGET_WRITE_FAILED");
            }
          }
        };
    RustGzipOutputStream out = new RustGzipOutputStream(target);
    nativeCalls++;
    out.write(patterned(64));
    fail[0] = true;
    try {
      out.finish();
      fail("尾部写失败必须抛出原错误");
    } catch (IOException error) {
      assertEquals("TARGET_WRITE_FAILED", error.getMessage());
    }
    assertTrue("native 已结束，必须标记 finished", out.isFinished());
    out.finish(); // 幂等 no-op：不得因 session 已释放报 STALE_HANDLE。
    out.close();
  }

  @Test
  public void constructorFailureClosesSourceAndMaySurfaceTrailerErrors() throws IOException {
    // 评审 P2-4：构造失败也必须关闭 source；且“小/空载荷的首个读块已含完整成员”时，
    // 构造期会暴露 body/尾部错误（JVM 参考读取器把同类错误推迟到 read）。
    final int[] truncatedCloses = {0};
    InputStream truncated =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            truncatedCloses[0]++;
          }
        };
    try {
      new RustGzipInputStream(truncated);
      fail("空流必须在构造期报截断");
    } catch (IOException error) {
      assertEquals("GZIP_TRUNCATED", error.getMessage());
    }
    assertEquals("构造失败必须关闭 source", 1, truncatedCloses[0]);

    byte[] badTrailer = javaGzip(new byte[0]);
    badTrailer[badTrailer.length - 8] ^= 0xff;
    final int[] trailerCloses = {0};
    InputStream trailer =
        new ByteArrayInputStream(badTrailer) {
          @Override
          public void close() {
            trailerCloses[0]++;
          }
        };
    try {
      new RustGzipInputStream(trailer);
      fail("空载荷坏 CRC 在构造期已解完尾部，必须报 GZIP_TRAILER");
    } catch (IOException error) {
      assertEquals("GZIP_TRAILER", error.getMessage());
    }
    assertEquals(1, trailerCloses[0]);

    // 对照（时机差异已写进 RustGzipInputStream 类注释）：JVM 构造不抛，read 才抛。
    StrictGzipInputStream jvm = new StrictGzipInputStream(new ByteArrayInputStream(badTrailer));
    try {
      jvm.read();
      fail("JVM 参考读取器必须在 read 抛 GZIP_TRAILER");
    } catch (IOException error) {
      assertEquals("GZIP_TRAILER", error.getMessage());
    } finally {
      jvm.close();
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

  @Rule public TemporaryFolder envelopeTemp = new TemporaryFolder();

  private static final BackupControl ENVELOPE_CONTROL = new BackupControl(null);

  private File saveEnvelopeBytes(byte[] bytes) throws IOException {
    File file = envelopeTemp.newFile();
    Files.write(file.toPath(), bytes);
    return file;
  }

  private static byte[] v5Payload() throws IOException {
    byte[] body = new byte[1_048_597];
    new Random(31).nextBytes(body);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BackupArchive.Writer writer = new BackupArchive.Writer(out, ENVELOPE_CONTROL);
    writer.add(BackupArchive.Record.of("sessions", "", "DIRECTORY", "sessions", 0, ""), null);
    writer.add(
        BackupArchive.Record.of("sessions", "a", "FILE", "sessions", body.length, sha256(body)),
        new ByteArrayInputStream(body));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("operation", "EXPORT");
    summary.put("integrity", "QUIESCENT");
    summary.put("createdAt", 1L);
    summary.put("appVersion", "slice4-test");
    summary.put("runtime", "test");
    summary.put("dataFormat", "UNINSPECTED");
    summary.put("sensitivePolicy", "NATIVE_API_KEYS_EXCLUDED");
    summary.put("plugins", Map.of());
    summary.put("roots", List.of(Map.of("id", "sessions", "scope", "sessions")));
    summary.put("requestedScope", "sessions");
    writer.finish(summary);
    return out.toByteArray();
  }

  private static String sha256(byte[] bytes) {
    return BackupArchive.hex(BackupArchive.sha().digest(bytes));
  }

  private byte[] envelope(byte[] payload, boolean rust) throws IOException {
    RustBackends.setEnabled(rust);
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    PortableBackupEnvelope.writeUnencrypted(
        new JvmBackupFileSystem(),
        saveEnvelopeBytes(payload),
        sha256(payload),
        target,
        ENVELOPE_CONTROL,
        "bounded diagnostics".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return target.toByteArray();
  }

  private byte[] unwrapEnvelope(byte[] bytes, boolean rust) throws IOException {
    RustBackends.setEnabled(rust);
    File source = saveEnvelopeBytes(bytes);
    File target = new File(envelopeTemp.newFolder(), "payload");
    assertTrue(
        PortableBackupEnvelope.unwrapIfPresent(
            new JvmBackupFileSystem(), source, target, ENVELOPE_CONTROL));
    return Files.readAllBytes(target.toPath());
  }

  private static long[] holdNativeSessions() {
    long[] handles = new long[4];
    for (int index = 0; index < handles.length; index++) {
      handles[index] = RustNative.archivePackCreate(6);
      assertTrue("原生会话泄漏: " + handles[index], handles[index] > 0);
    }
    return handles;
  }

  private static void releaseNativeSessions(long[] handles) {
    for (long handle : handles) assertEquals(0, RustNative.archivePackAbort(handle));
  }

  @Test
  public void envelopeCrossReadsPreserveV5BytesDigestManifestAndPartialScope() throws Exception {
    byte[] payload = v5Payload();
    Map<String, Object> expected =
        BackupArchive.read(new ByteArrayInputStream(payload), null, ENVELOPE_CONTROL);
    for (boolean writerRust : new boolean[] {false, true}) {
      byte[] packed = envelope(payload, writerRust);
      if (writerRust) {
        assertEquals(RustArchiveCodec.HEADER_HEX, BackupArchive.hex(Arrays.copyOf(packed, 10)));
      }
      assertNull(readJvm(packed).error);
      for (boolean readerRust : new boolean[] {false, true}) {
        byte[] unpacked = unwrapEnvelope(packed, readerRust);
        assertArrayEquals(payload, unpacked);
        assertEquals(sha256(payload), sha256(unpacked));
        Map<String, Object> actual =
            BackupArchive.read(new ByteArrayInputStream(unpacked), null, ENVELOPE_CONTROL);
        assertEquals(expected, actual);
        assertEquals("sessions", actual.get("requestedScope"));
        assertEquals(5L, actual.get("formatVersion"));
      }
    }
    // 旧加密原件也原样装入/取出；gzip 不替代密码认证。
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    char[] password = "slice4 old encrypted backup".toCharArray();
    PortableBackupCrypto.encrypt(
        new ByteArrayInputStream(payload), encrypted, password, ENVELOPE_CONTROL);
    File source = saveEnvelopeBytes(encrypted.toByteArray());
    RustBackends.setEnabled(true);
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    PortableBackupEnvelope.write(
        new JvmBackupFileSystem(),
        source,
        sha256(encrypted.toByteArray()),
        packed,
        ENVELOPE_CONTROL);
    byte[] restored = unwrapEnvelope(packed.toByteArray(), false);
    ByteArrayOutputStream plain = new ByteArrayOutputStream();
    PortableBackupCrypto.decrypt(
        new ByteArrayInputStream(restored), plain, password, ENVELOPE_CONTROL);
    assertArrayEquals(payload, plain.toByteArray());
    assertThrows(
        IOException.class,
        () ->
            PortableBackupCrypto.decrypt(
                new ByteArrayInputStream(restored),
                new ByteArrayOutputStream(),
                "wrong password".toCharArray(),
                ENVELOPE_CONTROL));
  }

  @Test
  public void envelopeUsesNativeSessionsAndNeverSilentlyFallsBackOnCreateFailure()
      throws Exception {
    byte[] payload = v5Payload();
    byte[] jvmEnvelope = envelope(payload, false);
    File source = saveEnvelopeBytes(payload), compressed = saveEnvelopeBytes(jvmEnvelope);
    long[] handles = holdNativeSessions();
    try {
      RustBackends.setEnabled(true);
      CountingTarget output = new CountingTarget();
      IOException packFailure =
          assertThrows(
              IOException.class,
              () ->
                  PortableBackupEnvelope.write(
                      new JvmBackupFileSystem(),
                      source,
                      sha256(payload),
                      output,
                      ENVELOPE_CONTROL));
      assertEquals("SESSION_LIMIT", packFailure.getMessage());
      assertEquals(0, output.size());
      assertEquals(0, output.closes);
      CountingFs fs = new CountingFs();
      File target = new File(envelopeTemp.newFolder(), "payload");
      IOException unpackFailure =
          assertThrows(
              IOException.class,
              () ->
                  PortableBackupEnvelope.unwrapIfPresent(fs, compressed, target, ENVELOPE_CONTROL));
      assertEquals("SESSION_LIMIT", unpackFailure.getMessage());
      assertEquals(1, fs.opens);
      assertEquals(1, fs.closes);
      assertFalse(target.exists());
      // 相同四个原生会话仍占满时关闭开关，真实 envelope JVM 路径照常成功。
      assertArrayEquals(payload, unwrapEnvelope(jvmEnvelope, false));
      assertArrayEquals(payload, unwrapEnvelope(envelope(payload, false), false));
    } finally {
      releaseNativeSessions(handles);
    }
  }

  @Test
  public void envelopeRejectsGzipCorruptionTruncationConcatenationAndBadV5() throws Exception {
    byte[] payload = v5Payload();
    byte[] packed = envelope(payload, false);
    byte[] crc = packed.clone(), size = packed.clone();
    crc[crc.length - 8] ^= 1;
    size[size.length - 4] ^= 1;
    List<byte[]> bad =
        new java.util.ArrayList<>(
            List.of(
                crc,
                size,
                concat(packed, new byte[] {0}),
                concat(packed, packed),
                mutate(packed, 3, 0xe0)));
    for (int length :
        new int[] {0, 1, 2, 9, packed.length / 2, packed.length - 8, packed.length - 1}) {
      bad.add(Arrays.copyOf(packed, length));
    }
    for (byte[] bytes : bad) {
      String jvm = null;
      for (boolean rust : new boolean[] {false, true}) {
        IOException failure = assertThrows(IOException.class, () -> unwrapEnvelope(bytes, rust));
        String code = RustArchiveCodec.canonicalJvmMessage(failure.getMessage());
        if (!rust) jvm = code;
        else assertEquals("同一损坏 envelope 两后端均拒绝", jvm, code);
      }
    }
    // 外层 gzip 完好仍不能证明 v5 完好：两后端取出后都由权威读者拒绝。
    byte[] damagedV5 = payload.clone();
    damagedV5[damagedV5.length - 1] ^= 1;
    File source = saveEnvelopeBytes(damagedV5);
    for (boolean rust : new boolean[] {false, true}) {
      RustBackends.setEnabled(rust);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      PortableBackupEnvelope.write(
          new JvmBackupFileSystem(), source, sha256(damagedV5), output, ENVELOPE_CONTROL);
      byte[] unpacked = unwrapEnvelope(output.toByteArray(), !rust);
      assertEquals(
          "ARCHIVE_FOOTER",
          assertThrows(
                  IOException.class,
                  () ->
                      BackupArchive.read(
                          new ByteArrayInputStream(unpacked), null, ENVELOPE_CONTROL))
              .getMessage());
    }
    releaseNativeSessions(holdNativeSessions());
  }

  private static final class CountingTarget extends ByteArrayOutputStream {
    int writes, closes, afterFailure;
    int failAt = Integer.MAX_VALUE;
    boolean failed;
    boolean failClose;
    BackupControl cancelOnClose;

    @Override
    public synchronized void write(byte[] bytes, int offset, int count) {
      // ByteArrayOutputStream 不允许受检异常；专门的失败包装见 failingTarget。
      writes++;
      if (failed) afterFailure++;
      super.write(bytes, offset, count);
    }

    @Override
    public void close() throws IOException {
      closes++;
      if (cancelOnClose != null) cancelOnClose.cancel();
      if (failClose) throw new IOException("TARGET_CLOSE");
    }
  }

  private static OutputStream failingTarget(CountingTarget bytes) {
    return new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        write(new byte[] {(byte) value});
      }

      @Override
      public void write(byte[] data, int offset, int length) throws IOException {
        if (bytes.failed) bytes.afterFailure++;
        if (bytes.writes >= bytes.failAt) {
          bytes.write(data, offset, Math.min(7, length));
          bytes.failed = true;
          throw new IOException("TARGET_PARTIAL_WRITE");
        }
        bytes.write(data, offset, length);
      }

      @Override
      public void close() throws IOException {
        bytes.close();
      }
    };
  }

  private static class CountingFs extends JvmBackupFileSystem {
    int opens, closes;
    BackupControl cancelDuringRead;
    BackupControl cancelAfterSourceClose;
    boolean failSourceClose;

    @Override
    public InputStream read(File file, BackupFileSystem.Node expected) throws IOException {
      opens++;
      return new FilterInputStream(super.read(file, expected)) {
        boolean closed;

        @Override
        public int read(byte[] data, int offset, int count) throws IOException {
          int n = in.read(data, offset, count);
          if (cancelDuringRead != null) cancelDuringRead.cancel();
          return n;
        }

        @Override
        public void close() throws IOException {
          if (closed) return;
          closed = true;
          closes++;
          super.close();
          if (cancelAfterSourceClose != null) cancelAfterSourceClose.cancel();
          if (failSourceClose) throw new IOException("SOURCE_CLOSE");
        }
      };
    }
  }

  @Test
  public void envelopePartialWritesAndCopyFailuresDoNotAppendDuringCleanupOrReplay()
      throws Exception {
    byte[] payload = v5Payload();
    File source = saveEnvelopeBytes(payload);
    for (boolean rust : new boolean[] {false, true}) {
      RustBackends.setEnabled(rust);
      CountingFs fs = new CountingFs();
      CountingTarget bytes = new CountingTarget();
      bytes.failAt = 1;
      bytes.failClose = true;
      IOException partial =
          assertThrows(
              IOException.class,
              () ->
                  PortableBackupEnvelope.write(
                      fs, source, sha256(payload), failingTarget(bytes), ENVELOPE_CONTROL));
      assertEquals("TARGET_PARTIAL_WRITE", partial.getMessage());
      assertTrue(bytes.size() > 0);
      assertEquals(0, bytes.afterFailure);
      assertEquals(1, bytes.closes);
      assertEquals(1, fs.opens);
      assertEquals(1, fs.closes);
      assertTrue(
          Arrays.stream(partial.getSuppressed())
              .anyMatch(e -> "TARGET_CLOSE".equals(e.getMessage())));

      // 小成员将目标错误推到最终 finish，仍保留最初错误且不在 close 重写尾部。
      byte[] tiny = new byte[] {'D', 'S', 'H', 'A', 'D', 'A', 'T', 'A'};
      File tinySource = saveEnvelopeBytes(tiny);
      CountingTarget tail = new CountingTarget();
      tail.failAt = 1;
      tail.failClose = true;
      assertEquals(
          "TARGET_PARTIAL_WRITE",
          assertThrows(
                  IOException.class,
                  () ->
                      PortableBackupEnvelope.write(
                          new JvmBackupFileSystem(),
                          tinySource,
                          sha256(tiny),
                          failingTarget(tail),
                          ENVELOPE_CONTROL))
              .getMessage());
      assertEquals(0, tail.afterFailure);
      assertEquals(1, tail.closes);

      // 源复制后的摘要/close 失败不能通过 gzip.close 追加最终块。
      for (boolean sourceClose : new boolean[] {false, true}) {
        CountingFs copyFs = new CountingFs();
        copyFs.failSourceClose = sourceClose;
        CountingTarget target = new CountingTarget();
        int[] atLastProgress = {0};
        BackupControl control =
            new BackupControl((stage, entries, count) -> atLastProgress[0] = target.size());
        IOException changed =
            assertThrows(
                IOException.class,
                () ->
                    PortableBackupEnvelope.write(
                        copyFs,
                        source,
                        sourceClose ? sha256(payload) : "0".repeat(64),
                        target,
                        control));
        assertEquals(sourceClose ? "SOURCE_CLOSE" : "VERIFIED_COPY_CHANGED", changed.getMessage());
        assertEquals(atLastProgress[0], target.size());
        assertEquals(1, copyFs.opens);
        assertEquals(1, copyFs.closes);
        assertEquals(1, target.closes);
      }
      releaseNativeSessions(holdNativeSessions());
    }
  }

  @Test
  public void envelopeCancellationBeforeDuringAndAfterCloseIsNotSuccessAndDoesNotReplay()
      throws Exception {
    byte[] payload = v5Payload();
    File source = saveEnvelopeBytes(payload);
    byte[] packed = envelope(payload, false);
    for (boolean rust : new boolean[] {false, true}) {
      RustBackends.setEnabled(rust);
      BackupControl before = new BackupControl(null);
      before.cancel();
      CountingTarget untouched = new CountingTarget();
      CountingFs fs = new CountingFs();
      assertThrows(
          InterruptedIOException.class,
          () -> PortableBackupEnvelope.write(fs, source, sha256(payload), untouched, before));
      assertEquals(0, untouched.size());
      assertEquals(0, untouched.closes);
      assertEquals(0, fs.opens);

      CountingTarget target = new CountingTarget();
      BackupControl[] current = {null};
      int[] atCancel = {0};
      current[0] =
          new BackupControl(
              (stage, entries, count) -> {
                atCancel[0] = target.size();
                current[0].cancel();
              });
      assertThrows(
          InterruptedIOException.class,
          () -> PortableBackupEnvelope.write(fs, source, sha256(payload), target, current[0]));
      assertEquals(atCancel[0], target.size());
      assertEquals(1, target.closes);

      BackupControl end = new BackupControl(null);
      CountingTarget closed = new CountingTarget();
      closed.cancelOnClose = end;
      assertThrows(
          InterruptedIOException.class,
          () -> PortableBackupEnvelope.write(fs, source, sha256(payload), closed, end));
      assertEquals(1, closed.closes);

      // 取消发生在 gzip 构造首次喂入：读后立即检查，未创建 payload，不重开源。
      CountingFs inputFs = new CountingFs();
      BackupControl reading = new BackupControl(null);
      inputFs.cancelDuringRead = reading;
      File input = saveEnvelopeBytes(packed),
          output = new File(envelopeTemp.newFolder(), "cancelled");
      assertThrows(
          InterruptedIOException.class,
          () -> PortableBackupEnvelope.unwrapIfPresent(inputFs, input, output, reading));
      assertEquals(1, inputFs.opens);
      assertEquals(1, inputFs.closes);
      assertFalse(output.exists());
      BackupControl readEnd = new BackupControl(null);
      CountingFs endFs = new CountingFs();
      endFs.cancelAfterSourceClose = readEnd;
      File late = new File(envelopeTemp.newFolder(), "late-cancel");
      assertThrows(
          InterruptedIOException.class,
          () -> PortableBackupEnvelope.unwrapIfPresent(endFs, input, late, readEnd));
      assertEquals(1, endFs.opens);
      assertEquals(1, endFs.closes);
      releaseNativeSessions(holdNativeSessions());
    }
  }

  @Test
  public void archiveFactoriesBorrowStreamsAndKeepUnknownOrOversizedInputOnJvm() throws Exception {
    byte[] compressed = javaGzip(patterned(31));
    RustBackends.setEnabled(true);
    for (long length : new long[] {-1, RustArchiveCodec.MAX_TOTAL_BYTES + 1, Long.MAX_VALUE}) {
      long[] handles = holdNativeSessions();
      try {
        try (InputStream input =
            StrictGzipInputStream.open(new ByteArrayInputStream(compressed), length)) {
          assertTrue(input instanceof StrictGzipInputStream);
          assertArrayEquals(patterned(31), input.readAllBytes());
        }
        CountingTarget output = new CountingTarget();
        try (OutputStream packed = RustArchiveCodec.packStream(output, length)) {
          assertTrue(packed instanceof GZIPOutputStream);
          packed.write(patterned(31));
        }
        assertEquals(0, output.closes);
      } finally {
        releaseNativeSessions(handles);
      }
    }
    for (boolean rust : new boolean[] {false, true}) {
      RustBackends.setEnabled(rust);
      int[] closes = {0};
      InputStream source =
          new ByteArrayInputStream(compressed) {
            @Override
            public void close() {
              closes[0]++;
            }
          };
      try (InputStream selected = StrictGzipInputStream.open(source, compressed.length)) {
        assertEquals(rust, selected instanceof RustGzipInputStream);
        assertArrayEquals(patterned(31), selected.readAllBytes());
      }
      assertEquals(0, closes[0]);
      source.close();
      assertEquals(1, closes[0]);
    }
  }

  @Test
  public void envelopeConstructorFailureClosesTheOwnerAndNeverReopensTheSource() throws Exception {
    // 空成员不会先产出明文，坏尾必在 native 构造泵暴露；有明文的小成员可能延迟到 read。
    byte[] packed = javaGzip(new byte[0]);
    packed[packed.length - 8] ^= 1;
    File source = saveEnvelopeBytes(packed),
        target = new File(envelopeTemp.newFolder(), "untrusted");
    CountingFs fs = new CountingFs();
    RustBackends.setEnabled(true);
    assertEquals(
        "GZIP_TRAILER",
        assertThrows(
                IOException.class,
                () -> StrictGzipInputStream.open(new ByteArrayInputStream(packed), packed.length))
            .getMessage());
    assertEquals(
        "GZIP_TRAILER",
        assertThrows(
                IOException.class,
                () -> PortableBackupEnvelope.unwrapIfPresent(fs, source, target, ENVELOPE_CONTROL))
            .getMessage());
    assertFalse("原生构造在产出首块之前拒绝坏尾", target.exists());
    assertEquals(1, fs.opens);
    assertEquals(1, fs.closes);
    releaseNativeSessions(holdNativeSessions());
  }

  private static void tarSize(byte[] tar, int headerAt, long size) {
    Arrays.fill(tar, headerAt + 124, headerAt + 136, (byte) 0);
    tar[headerAt + 124] = (byte) 0x80;
    for (int at = headerAt + 135; at > headerAt + 124; at--) {
      tar[at] = (byte) size;
      size >>>= 8;
    }
    tarChecksum(tar, headerAt);
  }

  private static void tarChecksum(byte[] tar, int headerAt) {
    Arrays.fill(tar, headerAt + 148, headerAt + 156, (byte) ' ');
    long sum = 0;
    for (int at = headerAt; at < headerAt + 512; at++) sum += tar[at] & 255;
    byte[] checksum =
        String.format(java.util.Locale.ROOT, "%06o\0 ", sum)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(checksum, 0, tar, headerAt + 148, checksum.length);
  }

  @Test
  public void envelopeMemberLimitsLegacyRoutingAndTarOverheadKeepTheirOriginalBounds()
      throws Exception {
    byte[] payload = v5Payload();
    byte[] packed = envelope(payload, false), tar = readJvm(packed).bytes;
    byte[] largePayload = tar.clone(), largeReadme = tar.clone(), legacy = tar.clone();
    tarSize(largePayload, 0, RustArchiveCodec.MAX_TOTAL_BYTES + 1);
    int readmeHeader = 512 + (payload.length + 511) / 512 * 512;
    tarSize(largeReadme, readmeHeader, 16385);
    Arrays.fill(legacy, 0, 100, (byte) 0);
    byte[] path = ".dsh/sessions/a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(path, 0, legacy, 0, path.length);
    tarChecksum(legacy, 0);
    for (boolean rust : new boolean[] {false, true}) {
      assertEquals(
          "ARCHIVE_LIMIT",
          assertThrows(IOException.class, () -> unwrapEnvelope(javaGzip(largePayload), rust))
              .getMessage());
      assertEquals(
          "ENVELOPE_README",
          assertThrows(IOException.class, () -> unwrapEnvelope(javaGzip(largeReadme), rust))
              .getMessage());
      RustBackends.setEnabled(rust);
      File old = saveEnvelopeBytes(javaGzip(legacy)),
          target = new File(envelopeTemp.newFolder(), "old");
      assertFalse(
          PortableBackupEnvelope.unwrapIfPresent(
              new JvmBackupFileSystem(), old, target, ENVELOPE_CONTROL));
      assertFalse(target.exists());
      CountingTarget untouched = new CountingTarget();
      File source = saveEnvelopeBytes(payload);
      assertEquals(
          "ENVELOPE_DIAGNOSTICS_SIZE",
          assertThrows(
                  IOException.class,
                  () ->
                      PortableBackupEnvelope.writeUnencrypted(
                          new JvmBackupFileSystem(),
                          source,
                          sha256(payload),
                          untouched,
                          ENVELOPE_CONTROL,
                          new byte[65537]))
              .getMessage());
      assertEquals(0, untouched.size());
    }
    RustBackends.setEnabled(true);
    long[] handles = holdNativeSessions();
    try {
      // 旧入口未知长度，四个原生会话均被占用也仍由原 JVM 路径完整验证。
      LegacyTarReader.read(
          new ByteArrayInputStream(packed), (member, data) -> {}, ENVELOPE_CONTROL);
      LegacyTarReader.readTrustedAssets(
          new ByteArrayInputStream(packed), (member, data) -> {}, ENVELOPE_CONTROL);
      File source = saveEnvelopeBytes(payload);
      CountingFs huge =
          new CountingFs() {
            @Override
            public BackupFileSystem.Node stat(File file) throws IOException {
              BackupFileSystem.Node real = super.stat(file);
              return new BackupFileSystem.Node(
                  real.type,
                  real.key,
                  RustArchiveCodec.MAX_TOTAL_BYTES,
                  real.modified,
                  real.device,
                  real.mode);
            }

            @Override
            public InputStream read(File file, BackupFileSystem.Node expected) throws IOException {
              opens++;
              throw new IOException("SOURCE_READ_REACHED");
            }
          };
      CountingTarget output = new CountingTarget();
      assertEquals(
          "SOURCE_READ_REACHED",
          assertThrows(
                  IOException.class,
                  () -> PortableBackupEnvelope.write(huge, source, "", output, ENVELOPE_CONTROL))
              .getMessage());
      // payload 自身恰好 MAX，但加 tar overhead 已超 ABI；必须在调用 native 前留 JVM。
      assertEquals(255, output.toByteArray()[9] & 255);
      assertEquals(1, huge.opens);
      assertEquals(1, output.closes);
    } finally {
      releaseNativeSessions(handles);
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

  private static byte[] withUnterminatedComment(byte[] gzip, byte[] comment) {
    byte[] header = Arrays.copyOf(gzip, 10);
    header[3] = (byte) (header[3] | 0x10);
    return concat(concat(header, comment), Arrays.copyOfRange(gzip, 10, gzip.length));
  }

  private static int littleEndianInt(byte[] data, int offset) {
    return (data[offset] & 0xff)
        | ((data[offset + 1] & 0xff) << 8)
        | ((data[offset + 2] & 0xff) << 16)
        | ((data[offset + 3] & 0xff) << 24);
  }
}
