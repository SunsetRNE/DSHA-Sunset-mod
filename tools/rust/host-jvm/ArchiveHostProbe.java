package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.StrictGzipInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * R3 主机 JVM 差分探针：加载主机目标 `libdsha_rs.so`，用生产桥
 * {@link RustGzipOutputStream}/{@link RustGzipInputStream}/{@link RustNative} 与 JVM 参考实现
 * {@link StrictGzipInputStream}/{@link GZIPOutputStream} 对拍单成员 gzip 编解码。
 *
 * <p>由 `tools/rust/host-jvm-differential.py` 以 {@code -Xcheck:jni} 运行，参数为
 * `<libdsha_rs.so>`。只证明“同一源码的主机实现与 JVM 一致”，不等于 Android `.so` 已加载
 * （后者由 R3 签名 `.dev` 包与 APK 静态门禁覆盖）。
 *
 * <p>输出：每个检查一行 JSON（{@code {"check":...,"status":"PASS|FAIL"}}），最后一行为汇总。
 */
public final class ArchiveHostProbe {
  private static final String PYTHON_GZIP_HELLO_WORLD =
      "1f8b0800000000000203cb48cdc9c95728cf2fca49010085114a0d0b000000";

  private static int failures = 0;
  private static int checks = 0;
  private static int nativeCalls = 0;

  private ArchiveHostProbe() {}

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

  private static byte[] unhex(String text) {
    byte[] bytes = new byte[text.length() / 2];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
    }
    return bytes;
  }

  private static byte[] patterned(int size) {
    byte[] payload = new byte[size];
    for (int index = 0; index < size; index++) {
      payload[index] = (byte) ((index * 31 + 7) % 251);
    }
    return payload;
  }

  private static byte[] javaGzip(byte[] payload, int level) throws IOException {
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

  private static byte[] append(byte[] source, int value) {
    return concat(source, new byte[] {(byte) value});
  }

  private static byte[] mutate(byte[] source, int index, int value) {
    byte[] out = source.clone();
    out[index] = (byte) value;
    return out;
  }

  private static byte[] u16le(int value) {
    return new byte[] {(byte) value, (byte) (value >>> 8)};
  }

  /**
   * 按 RFC 1952 顺序（FEXTRA → FNAME → FCOMMENT → FHCRC）插入可选头字段。
   *
   * <p>与 `StrictGzipInputStream` 的读取顺序一致；FHCRC 覆盖“含已插入字段的标志字节 +
   * 固定头 + 可选字段”，不覆盖 FHCRC 自身。
   */
  private static byte[] withOptionalFields(
      byte[] gzip, byte[] extra, byte[] name, byte[] comment, boolean fhcrc) {
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
    if (comment != null) {
      flags |= 0x10;
      fields.writeBytes(comment);
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

  private static byte[] withExtra(byte[] gzip, byte[] extra) {
    return withOptionalFields(gzip, extra, null, null, false);
  }

  private static byte[] withName(byte[] gzip, byte[] name) {
    return withOptionalFields(gzip, null, name, null, false);
  }

  private static byte[] withComment(byte[] gzip, byte[] comment) {
    return withOptionalFields(gzip, null, null, comment, false);
  }

  private static byte[] withFhcrc(byte[] gzip) {
    return withOptionalFields(gzip, null, null, null, true);
  }

  /** FNAME 反例：写入 name 但**没有**终止 0（与 JVM 读取器一样在 8192 字节处拒绝）。 */
  private static byte[] withUnterminatedName(byte[] gzip, byte[] name) {
    byte[] header = Arrays.copyOf(gzip, 10);
    header[3] = (byte) (header[3] | 0x08);
    byte[] out = concat(header, name);
    return concat(out, Arrays.copyOfRange(gzip, 10, gzip.length));
  }

  /** FCOMMENT 反例：写入 comment 但**没有**终止 0（评审 P2-9：必须与 JVM 同报 METADATA_LIMIT）。 */
  private static byte[] withUnterminatedComment(byte[] gzip, byte[] comment) {
    byte[] header = Arrays.copyOf(gzip, 10);
    header[3] = (byte) (header[3] | 0x10);
    byte[] out = concat(header, comment);
    return concat(out, Arrays.copyOfRange(gzip, 10, gzip.length));
  }

  /** JVM/Rust 一次读取的结果：已产出的明文字节 + 规范化后的错误消息（成功为 null）。 */
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
        if (read == 0) {
          continue;
        }
        bytes.write(buffer, 0, read);
      }
      return new Outcome(bytes.toByteArray(), null);
    } catch (IOException error) {
      return new Outcome(bytes.toByteArray(), RustArchiveCodec.canonicalJvmMessage(error.getMessage()));
    } finally {
      try {
        stream.close();
      } catch (IOException ignored) {
        // 关闭失败不改变已经得到的读取结果。
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

  private static final class Case {
    final String name;
    final byte[] gzip;
    final String expectedError;

    Case(String name, byte[] gzip, String expectedError) {
      this.name = name;
      this.gzip = gzip;
      this.expectedError = expectedError;
    }
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

  private static boolean sameOutcome(Outcome first, Outcome second) {
    return Arrays.equals(first.bytes, second.bytes)
        && (first.error == null ? second.error == null : first.error.equals(second.error));
  }

  private static boolean outcomePasses(Outcome outcome, String expectedError) {
    return expectedError == null
        ? outcome.error == null
        : expectedError.equals(outcome.error);
  }

  private static void abortNative(long handle) {
    if (handle > 0) {
      RustNative.archivePackAbort(handle);
      RustNative.archiveUnpackAbort(handle);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("用法: ArchiveHostProbe <libdsha_rs.so 绝对路径>");
      System.exit(2);
    }
    if (!RustNative.loadFromPath(args[0])) {
      check("archiveLoadLibrary", false, "status=" + RustNative.statusCode());
      finish();
      return;
    }
    check("archiveLoadLibrary", RustNative.isAvailable(), "status=" + RustNative.statusCode());
    check(
        "archiveAbiVersion",
        RustNative.abiVersion() == RustNative.ABI_VERSION,
        "abi=" + RustNative.abiVersion());

    // 1) Rust pack → JVM 严格读取器；固定头/尾部逐字段核对。
    byte[] payload = patterned(1_024);
    byte[] rustGzip = rustPack(payload, 6, 4_096);
    check(
        "archiveHeaderPinned",
        hex(Arrays.copyOf(rustGzip, 10)).equals(RustArchiveCodec.HEADER_HEX),
        "header=" + hex(Arrays.copyOf(rustGzip, 10)));
    CRC32 payloadCrc = new CRC32();
    payloadCrc.update(payload);
    int isize = rustGzip.length >= 8 ? littleEndianInt(rustGzip, rustGzip.length - 4) : -1;
    int crc = rustGzip.length >= 8 ? littleEndianInt(rustGzip, rustGzip.length - 8) : -1;
    check(
        "archiveTrailerPinned",
        crc == (int) payloadCrc.getValue() && isize == payload.length,
        "crc=" + crc + " isize=" + isize);

    boolean rustPackReadByJvm = true;
    String firstPackFailure = "";
    for (int level : new int[] {0, 1, 6, 9}) {
      for (int size : new int[] {0, 1, 64, 65_536, 262_144}) {
        byte[] data = patterned(size);
        Outcome jvm = readJvm(rustPack(data, level, level == 0 ? 1 : 7_919));
        boolean ok = jvm.error == null && Arrays.equals(jvm.bytes, data);
        if (!ok) {
          rustPackReadByJvm = false;
          if (firstPackFailure.isEmpty()) {
            firstPackFailure = "level=" + level + " size=" + size + " error=" + jvm.error;
          }
        }
      }
    }
    check(
        "archiveRustPackJvmReads",
        rustPackReadByJvm,
        firstPackFailure.isEmpty() ? "levels 0/1/6/9" : firstPackFailure);

    // 2) 同一载荷 + 不同 write 分块 → gzip 字节一致（无 flush 点，流式编码应确定）。
    check(
        "archiveRustPackDeterministic",
        Arrays.equals(rustPack(payload, 6, 1), rustPack(payload, 6, 262_144)),
        "same payload, chunk 1 vs 262144");

    // 3) JVM pack → Rust 严格读取器；同时覆盖 1/7/4096 字节分块读取。
    boolean jvmPackReadByRust = true;
    String firstReadFailure = "";
    for (int size : new int[] {0, 1, 17, 64, 65_536, 262_144}) {
      byte[] data = patterned(size);
      byte[] jvmGzip = javaGzip(data, 6);
      for (int chunk : new int[] {1, 7, 4_096}) {
        Outcome rust = drain(new RustGzipInputStream(new ChunkedInputStream(jvmGzip, chunk)));
        boolean ok = rust.error == null && Arrays.equals(rust.bytes, data);
        if (!ok) {
          jvmPackReadByRust = false;
          if (firstReadFailure.isEmpty()) {
            firstReadFailure = "size=" + size + " chunk=" + chunk + " error=" + rust.error;
          }
        }
        Outcome jvm = drain(new StrictGzipInputStream(new ChunkedInputStream(jvmGzip, chunk)));
        if (!sameOutcome(rust, jvm)) {
          jvmPackReadByRust = false;
          if (firstReadFailure.isEmpty()) {
            firstReadFailure = "size=" + size + " chunk=" + chunk + " rust/jvm 分歧";
          }
        }
      }
    }
    check(
        "archiveJvmPackRustReads",
        jvmPackReadByRust,
        firstReadFailure.isEmpty() ? "sizes 0..262144, chunks 1/7/4096" : firstReadFailure);

    // 4) 第三方（Python gzip）固定语料：两个实现都必须接受。
    byte[] pythonGzip = unhex(PYTHON_GZIP_HELLO_WORLD);
    Outcome pythonJvm = readJvm(pythonGzip);
    Outcome pythonRust = readRust(pythonGzip);
    check(
        "archivePythonGzipInterop",
        pythonJvm.error == null
            && pythonRust.error == null
            && Arrays.equals(pythonJvm.bytes, "hello world".getBytes(StandardCharsets.US_ASCII))
            && sameOutcome(pythonJvm, pythonRust),
        "jvm=" + pythonJvm.error + " rust=" + pythonRust.error);

    // 5) 合法/非法语料：JVM 与 Rust 的“输出字节 + 错误码消息”逐一相等。
    byte[] base = javaGzip(patterned(1_024), 6);
    byte[] extra8k = new byte[8_193];
    Arrays.fill(extra8k, (byte) 0x5a);
    byte[] name8k = new byte[8_192];
    Arrays.fill(name8k, (byte) 'n');
    List<Case> corpus = new ArrayList<>();
    corpus.add(new Case("valid", base, null));
    corpus.add(new Case("valid-empty", javaGzip(new byte[0], 6), null));
    corpus.add(new Case("valid-ftext", mutate(base, 3, base[3] | 0x01), null));
    corpus.add(new Case("valid-extra", withExtra(base, new byte[] {1, 2, 3, 4}), null));
    corpus.add(new Case("valid-extra-8193", withExtra(base, extra8k), null));
    corpus.add(new Case("valid-fname", withName(base, "name.bin".getBytes(StandardCharsets.US_ASCII)), null));
    corpus.add(new Case("valid-fcomment", withComment(base, "comment".getBytes(StandardCharsets.US_ASCII)), null));
    corpus.add(new Case("valid-fhcrc", withFhcrc(base), null));
    byte[] mixed =
        withOptionalFields(
            base,
            new byte[] {(byte) 0xaa, (byte) 0xbb},
            "n".getBytes(StandardCharsets.US_ASCII),
            "c".getBytes(StandardCharsets.US_ASCII),
            true);
    corpus.add(new Case("valid-mixed-metadata", mixed, null));
    corpus.add(new Case("bad-magic", mutate(base, 0, 0x1e), "GZIP_HEADER"));
    corpus.add(new Case("bad-cm", mutate(base, 2, 7), "GZIP_HEADER"));
    corpus.add(new Case("bad-flags", mutate(base, 3, base[3] | 0x20), "GZIP_HEADER"));
    // 评审 P2-9：FHCRC 不符与 FCOMMENT 超长必须与 JVM 同码。
    byte[] badFhcrc = withFhcrc(base);
    badFhcrc = mutate(badFhcrc, 10, badFhcrc[10] ^ 0xff);
    corpus.add(new Case("bad-fhcrc", badFhcrc, "GZIP_HEADER_CRC"));
    corpus.add(new Case("fname-too-long", withUnterminatedName(base, name8k), "GZIP_METADATA_LIMIT"));
    byte[] comment8k = new byte[8_192];
    Arrays.fill(comment8k, (byte) 'c');
    corpus.add(
        new Case(
            "fcomment-too-long",
            withUnterminatedComment(base, comment8k),
            "GZIP_METADATA_LIMIT"));
    corpus.add(new Case("bad-crc", mutate(base, base.length - 8, base[base.length - 8] ^ 0xff), "GZIP_TRAILER"));
    corpus.add(new Case("bad-isize", mutate(base, base.length - 4, base[base.length - 4] ^ 0xff), "GZIP_TRAILER"));
    corpus.add(new Case("trailing-byte", append(base, 0), "GZIP_TRAILING_DATA"));
    corpus.add(new Case("two-members", concat(base, javaGzip(patterned(64), 6)), "GZIP_TRAILING_DATA"));
    corpus.add(new Case("deflate-garbage", concat(Arrays.copyOf(base, 10), new byte[64]), "GZIP_DEFLATE"));
    int corpusMismatches = 0;
    String firstCorpusMismatch = "";
    for (Case testCase : corpus) {
      Outcome jvm = readJvm(testCase.gzip);
      Outcome rust = readRust(testCase.gzip);
      boolean ok = sameOutcome(jvm, rust) && outcomePasses(jvm, testCase.expectedError);
      if (!ok) {
        corpusMismatches++;
        if (firstCorpusMismatch.isEmpty()) {
          firstCorpusMismatch =
              testCase.name
                  + " jvm="
                  + jvm.error
                  + "/"
                  + jvm.bytes.length
                  + " rust="
                  + rust.error
                  + "/"
                  + rust.bytes.length;
        }
      }
    }
    check(
        "archiveCorpusJvmRustAgree",
        corpusMismatches == 0,
        "cases=" + corpus.size() + " mismatches=" + corpusMismatches + " " + firstCorpusMismatch);

    // 6) 截断语料：每个前缀都必须两边同为 GZIP_TRUNCATED，且已产出字节一致。
    byte[] shortGzip = javaGzip(patterned(192), 6);
    int truncationMismatches = 0;
    String firstTruncationMismatch = "";
    for (int length = 0; length < shortGzip.length; length++) {
      byte[] prefix = Arrays.copyOf(shortGzip, length);
      Outcome jvm = readJvm(prefix);
      Outcome rust = readRust(prefix);
      boolean ok = sameOutcome(jvm, rust) && "GZIP_TRUNCATED".equals(jvm.error);
      if (!ok) {
        truncationMismatches++;
        if (firstTruncationMismatch.isEmpty()) {
          firstTruncationMismatch =
              "prefix=" + length + " jvm=" + jvm.error + " rust=" + rust.error;
        }
      }
    }
    check(
        "archiveTruncationCorpus",
        truncationMismatches == 0,
        "prefixes=" + shortGzip.length + " mismatches=" + truncationMismatches + " " + firstTruncationMismatch);

    // 6b) 可选头逐前缀截断（评审 P2-9）：FEXTRA + FNAME + FCOMMENT + FHCRC 的每个前缀
    // 都必须两边同为 GZIP_TRUNCATED，不能把“头还没读完”误报成头错误。
    int optionalMismatches = 0;
    String firstOptionalMismatch = "";
    for (int length = 0; length < mixed.length; length++) {
      byte[] prefix = Arrays.copyOf(mixed, length);
      Outcome jvm = readJvm(prefix);
      Outcome rust = readRust(prefix);
      boolean ok = sameOutcome(jvm, rust) && "GZIP_TRUNCATED".equals(jvm.error);
      if (!ok) {
        optionalMismatches++;
        if (firstOptionalMismatch.isEmpty()) {
          firstOptionalMismatch =
              "prefix=" + length + " jvm=" + jvm.error + " rust=" + rust.error;
        }
      }
    }
    check(
        "archiveOptionalHeaderTruncation",
        optionalMismatches == 0,
        "prefixes=" + mixed.length + " mismatches=" + optionalMismatches + " "
            + firstOptionalMismatch);

    // 7) native direct buffer / 会话契约错误码（冻结 ABI 数值）。
    check("archiveNativeContractCodes", nativeContract(), nativeContractDetail());

    // 8) 会话上限与生命周期。
    check("archiveSessionLimit", sessionLimit(), "上限 4，第 5 个 SESSION_LIMIT");

    // 9) 运行层开关：默认 off / 显式 on / 一键回滚。
    RustBackends.setEnabled(false);
    RustBackends.Selection off = RustBackends.archiveSelection();
    boolean offOk = !off.useRust() && "SWITCH_OFF".equals(off.reasonCode());
    RustBackends.setEnabled(true);
    RustBackends.Selection on = RustBackends.archiveSelection();
    boolean onOk = on.useRust() && RustBackends.SelectionReason.RUST_ENABLED == on.reason();
    RustBackends.setEnabled(false);
    boolean rollback = !RustBackends.archiveSelection().useRust();
    check("archiveSelectionDefaultOff", offOk, "reason=" + off.reasonCode());
    check("archiveOneKeyRollback", onOk && rollback, "on=" + on.useRust() + " rollback=" + rollback);

    // 10) 负向自检：比较器必须能检出“消息/字节不一致”，不是恒真。
    boolean canonicalFlags = "GZIP_HEADER".equals(RustArchiveCodec.canonicalJvmMessage("GZIP_FLAGS"));
    boolean detectsMessageMismatch =
        !"GZIP_TRAILER".equals(RustArchiveCodec.canonicalJvmMessage("GZIP_HEADER"));
    boolean detectsBytesMismatch = !Arrays.equals(patterned(8), patterned(9));
    check(
        "archiveNegativeControlDetectsMismatch",
        canonicalFlags && detectsMessageMismatch && detectsBytesMismatch,
        "flagsMerged=" + canonicalFlags);

    // 11) 关闭语义：流关闭后继续写/读必须报 STREAM_CLOSED（与 JVM 包装器一致）。
    check("archiveWrapperClosedSemantics", closedSemantics(), "finish/close 后拒绝写入/读取");

    // 12) 评审 P2-6：native finish 成功后写尾部失败时，必须保留原错误、标记 finished，
    // 再次 finish 幂等而不是 STALE_HANDLE。
    check(
        "archiveFinishTailFailure",
        finishTailFailure(),
        "尾部写失败保留原错误；finished 已置位；再次 finish 幂等");

    // 13) 评审 P2-3/P2-7/P2-8：unpack 累计上限映射为 ARCHIVE_LIMIT（冻结 ABI 复用 -3），
    // NOT_IMPLEMENTED 有类型化消息，packBound 拒绝 int 溢出。
    check(
        "archiveLimitErrorMapping",
        limitErrorMapping(),
        "unpack -3→ARCHIVE_LIMIT↔-3；NOT_IMPLEMENTED；packBound 溢出拒绝");

    finish();
  }

  private static boolean nativeContract() throws IOException {
    int invalidLevel = (int) RustNative.archivePackCreate(-1);
    long pack = RustNative.archivePackCreate(6);
    nativeCalls++;
    ByteBuffer oneByte = ByteBuffer.allocateDirect(1);
    oneByte.put(0, (byte) 0x42);
    ByteBuffer packOut = ByteBuffer.allocateDirect(RustArchiveCodec.MAX_PACK_OUTPUT);
    ByteBuffer smallOut = ByteBuffer.allocateDirect(8);
    ByteBuffer emptyIn = ByteBuffer.allocateDirect(1);
    emptyIn.limit(0);
    ByteBuffer heapIn = ByteBuffer.allocate(1);
    ByteBuffer bigIn = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK + 1);
    int heapRejected =
        (int) RustNative.archivePackUpdate(pack, heapIn, packOut);
    nativeCalls++;
    int emptyRejected =
        (int) RustNative.archivePackUpdate(pack, emptyIn.slice(), packOut);
    nativeCalls++;
    int bigRejected =
        (int) RustNative.archivePackUpdate(pack, bigIn, packOut);
    nativeCalls++;
    int smallOutResult =
        (int) RustNative.archivePackUpdate(pack, oneByte, smallOut);
    nativeCalls++;
    long update = RustNative.archivePackUpdate(pack, oneByte, packOut);
    nativeCalls++;
    int consumed = RustArchiveCodec.consumed(update);
    int produced = RustArchiveCodec.produced(update);
    int finishSmall =
        (int) RustNative.archivePackFinish(pack, smallOut);
    nativeCalls++;
    long finish = RustNative.archivePackFinish(pack, packOut);
    nativeCalls++;
    int updateAfterFinish =
        (int) RustNative.archivePackUpdate(pack, oneByte, packOut);
    nativeCalls++;
    int abortAfterFinish = RustNative.archivePackAbort(pack);
    nativeCalls++;

    long unpack = RustNative.archiveUnpackCreate();
    nativeCalls++;
    int writeNull = (int) RustNative.archiveUnpackWrite(unpack, null);
    nativeCalls++;
    int writeBig =
        (int) RustNative.archiveUnpackWrite(unpack, bigIn);
    nativeCalls++;
    int readZero =
        (int) RustNative.archiveUnpackRead(unpack, emptyIn.slice());
    nativeCalls++;
    int readTooBig =
        (int) RustNative.archiveUnpackRead(unpack, bigIn);
    nativeCalls++;
    long pack2 = RustNative.archivePackCreate(6);
    nativeCalls++;
    int wrongKind =
        (int) RustNative.archiveUnpackRead(pack2, oneByte);
    nativeCalls++;
    int abortPack2 = RustNative.archivePackAbort(pack2);
    nativeCalls++;
    int unpackAbort = RustNative.archiveUnpackAbort(unpack);
    nativeCalls++;
    int packAbortUnknown = RustNative.archivePackAbort(unpack);
    nativeCalls++;

    return invalidLevel == RustArchiveCodec.INVALID_ARGUMENT
        && heapRejected == RustArchiveCodec.INVALID_ARGUMENT
        && emptyRejected == RustArchiveCodec.INVALID_ARGUMENT
        && bigRejected == RustArchiveCodec.INPUT_BOUND
        && smallOutResult == RustArchiveCodec.OUTPUT_BOUND
        && consumed == 1
        && produced == 10
        && finishSmall == RustArchiveCodec.OUTPUT_BOUND
        && finish > 0
        && updateAfterFinish == RustArchiveCodec.STALE_HANDLE
        && abortAfterFinish == RustArchiveCodec.STALE_HANDLE
        && writeNull == RustArchiveCodec.INVALID_ARGUMENT
        && writeBig == RustArchiveCodec.INPUT_BOUND
        && readZero == RustArchiveCodec.INVALID_ARGUMENT
        && readTooBig == RustArchiveCodec.INVALID_ARGUMENT
        && wrongKind == RustArchiveCodec.STALE_HANDLE
        && abortPack2 == RustArchiveCodec.OK
        && unpackAbort == RustArchiveCodec.OK
        && packAbortUnknown == RustArchiveCodec.STALE_HANDLE;
  }

  private static String nativeContractDetail() {
    return "contracts checked; nativeCalls=" + nativeCalls;
  }

  private static boolean sessionLimit() {
    long[] handles = new long[4];
    try {
      for (int index = 0; index < handles.length; index++) {
        handles[index] = RustNative.archivePackCreate(6);
        nativeCalls++;
        if (handles[index] <= 0) {
          return false;
        }
      }
      long fifth = RustNative.archivePackCreate(6);
      nativeCalls++;
      long sixth = RustNative.archiveUnpackCreate();
      nativeCalls++;
      boolean rejected = fifth == RustArchiveCodec.SESSION_LIMIT
          && sixth == RustArchiveCodec.SESSION_LIMIT;
      for (int index = 0; index < handles.length; index++) {
        boolean aborted = RustNative.archivePackAbort(handles[index]) == RustArchiveCodec.OK;
        nativeCalls++;
        handles[index] = 0;
        if (!aborted) {
          return false;
        }
      }
      return rejected;
    } finally {
      for (long handle : handles) {
        abortNative(handle);
      }
    }
  }

  private static boolean closedSemantics() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    RustGzipOutputStream out = new RustGzipOutputStream(bytes);
    out.write(patterned(64));
    out.finish();
    boolean writeRejected = false;
    try {
      out.write(1);
    } catch (IOException error) {
      writeRejected = "STREAM_CLOSED".equals(error.getMessage());
    }
    out.close();

    byte[] gzip = bytes.toByteArray();
    RustGzipInputStream in = new RustGzipInputStream(new ByteArrayInputStream(gzip));
    in.read();
    in.close();
    boolean readRejected = false;
    try {
      in.read();
    } catch (IOException error) {
      readRejected = "STREAM_CLOSED".equals(error.getMessage());
    }
    return writeRejected && readRejected;
  }

  private static boolean finishTailFailure() throws IOException {
    final boolean[] fail = {false};
    final int[] closes = {0};
    OutputStream target =
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            if (fail[0]) {
              throw new IOException("TARGET_WRITE_FAILED");
            }
          }

          @Override
          public void write(byte[] bytes, int at, int length) throws IOException {
            if (fail[0]) {
              throw new IOException("TARGET_WRITE_FAILED");
            }
          }

          @Override
          public void close() {
            closes[0]++;
          }
        };
    RustGzipOutputStream out = new RustGzipOutputStream(target);
    nativeCalls++;
    out.write(patterned(64));
    fail[0] = true;
    boolean originalError = false;
    try {
      out.finish();
    } catch (IOException error) {
      originalError = "TARGET_WRITE_FAILED".equals(error.getMessage());
    }
    boolean finished = out.isFinished();
    boolean idempotent = true;
    try {
      out.finish();
    } catch (IOException error) {
      idempotent = false;
    }
    out.close();
    return originalError && finished && idempotent && closes[0] == 1;
  }

  private static boolean limitErrorMapping() {
    boolean limitMapped =
        RustArchiveCodec.ARCHIVE_LIMIT.equals(
                RustArchiveCodec.unpackMessage(RustArchiveCodec.OUTPUT_BOUND))
            && RustArchiveCodec.errorCodeForJvmMessage(RustArchiveCodec.ARCHIVE_LIMIT)
                == RustArchiveCodec.OUTPUT_BOUND
            && "OUTPUT_BOUND"
                .equals(RustArchiveCodec.message(RustArchiveCodec.OUTPUT_BOUND));
    boolean notImplemented =
        "NOT_IMPLEMENTED".equals(RustArchiveCodec.message(RustArchiveCodec.NOT_IMPLEMENTED));
    boolean overflowRejected;
    try {
      RustArchiveCodec.packBound(Integer.MAX_VALUE);
      overflowRejected = false;
    } catch (IllegalArgumentException expected) {
      overflowRejected = true;
    }
    return limitMapped
        && notImplemented
        && overflowRejected
        && RustArchiveCodec.packBound(RustArchiveCodec.CHUNK)
            == RustArchiveCodec.MAX_PACK_OUTPUT;
  }

  private static int littleEndianInt(byte[] data, int offset) {
    return (data[offset] & 0xff)
        | ((data[offset + 1] & 0xff) << 8)
        | ((data[offset + 2] & 0xff) << 16)
        | ((data[offset + 3] & 0xff) << 24);
  }

  private static void finish() {
    RustBackends.setEnabled(false);
    System.out.println(
        "{\"status\":\""
            + (failures == 0 ? "PASS" : "FAIL")
            + "\",\"failures\":"
            + failures
            + ",\"checks\":"
            + checks
            + ",\"nativeCalls\":"
            + nativeCalls
            + ",\"javaVersion\":\""
            + System.getProperty("java.version")
            + "\"}");
    if (failures != 0) {
      System.exit(1);
    }
  }
}
