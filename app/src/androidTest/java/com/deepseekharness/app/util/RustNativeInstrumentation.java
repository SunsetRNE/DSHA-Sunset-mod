package com.deepseekharness.app.util;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 显式选择的 Android Rust 候选：真实加载/FD 摘要、archive 流与 PlanView。
 *
 * <p>先核验 App 启动后的后端默认值（随包收据存在时必须为启用），随后临时
 * {@code setEnabled(false)} 跑原始 native 差分与失败清理探针，finally 恢复并断言恢复成功；
 * 不把探针期间的临时关闭当作新的生产默认值。
 *
 * <p>仅由 -Pdsha.testRunner=com.deepseekharness.app.util.RustNativeInstrumentation 构建；
 * 运行时必须传入 commit/runId/runAttempt/repository，并与目标 APK 内的 Rust 回执逐项绑定。
 * 不输出参数全集、用户文件、私有路径、异常正文或堆栈。静态编译本类不构成 Android 执行证据。
 */
public final class RustNativeInstrumentation extends Instrumentation {
  private static final String LIBRARY = "libdsha_rs.so";
  private static final String APK_LIBRARY = "lib/arm64-v8a/" + LIBRARY;
  private static final String RECEIPT = "assets/rust-native-receipt.json";
  private static final int MAX_RECEIPT_BYTES = 2 * 1024 * 1024;
  private static final long MAX_LIBRARY_BYTES = 16L * 1024 * 1024;
  private static final int SHA256 = 1;
  private static final int ARCHIVE_SESSIONS = 4;
  private static final int PLAN_MAX_REASONS = 8;
  private static final byte PLAN_SENTINEL = 0x5a;
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private Bundle arguments;

  private enum Failure {
    NONE,
    ARGUMENT_BINDING_INVALID,
    ANDROID_PLATFORM_UNSUPPORTED,
    PAGE_SIZE_UNAVAILABLE,
    BACKENDS_NOT_DEFAULT_ON,
    BACKENDS_PROBE_SWITCH_MUTATED,
    BACKENDS_RESTORE_FAILED,
    RECEIPT_MISSING_OR_DUPLICATE,
    RECEIPT_INVALID,
    RECEIPT_BINDING_MISMATCH,
    APK_LIBRARY_MISSING_OR_DUPLICATE,
    APK_LIBRARY_MISMATCH,
    INSTALLED_LIBRARY_MISSING,
    INSTALLED_LIBRARY_MISMATCH,
    MAPPED_LIBRARY_UNVERIFIED,
    NATIVE_LOAD_FAILED,
    NATIVE_STATUS_MISMATCH,
    NATIVE_ABI_MISMATCH,
    TEMP_FILE_INVALID,
    NATIVE_DIGEST_ERROR,
    NATIVE_DIGEST_LENGTH_MISMATCH,
    NATIVE_DIGEST_MISMATCH,
    NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH,
    NATIVE_ARCHIVE_REJECTION_MISSING,
    NATIVE_ARCHIVE_CLOSE_MISMATCH,
    NATIVE_ARCHIVE_CONTRACT_MISMATCH,
    NATIVE_ARCHIVE_CLEANUP_FAILED,
    NATIVE_PLAN_VERDICT_MISMATCH,
    NATIVE_PLAN_REASONS_MISMATCH,
    NATIVE_PLAN_INPUT_MUTATED,
    NATIVE_PLAN_ERROR_MISMATCH,
    NATIVE_PLAN_PARTIAL_OUTPUT,
    OWNED_TEMP_CLEANUP_FAILED,
    NATIVE_LINK_ERROR,
    IO_OR_RUNTIME_ERROR
  }

  private static final class ProbeFailure extends Exception {
    final Failure code;

    ProbeFailure(Failure code) {
      this.code = code;
    }
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    arguments = args == null ? new Bundle() : args;
    start();
  }

  @Override
  public void onStart() {
    // 框架顺序是 instrumentation.onCreate/onStart 先于 Application.onCreate；先同步等主线程
    // 完成 App 初始化（DshaApp.onCreate 在此启用 Rust 后端），再记下默认开关，finally 恢复。
    runOnMainSync(() -> {});
    final boolean initialEnabled = RustBackends.enabled();
    Bundle output = new Bundle();
    output.putString("probe", "dsha-rust-android-jni-v1");
    output.putInt("probeEvidenceRevision", 2);
    output.putString("result", "FAIL");
    output.putString("failure", Failure.IO_OR_RUNTIME_ERROR.name());
    output.putString("stage", "platform");
    output.putString("androidAbis", Arrays.toString(Build.SUPPORTED_ABIS));
    output.putInt("androidSdk", Build.VERSION.SDK_INT);
    output.putString("androidRelease", Build.VERSION.RELEASE);
    output.putString("buildManufacturer", Build.MANUFACTURER);
    output.putString("buildModel", Build.MODEL);
    output.putString("buildDevice", Build.DEVICE);
    output.putString("buildId", Build.ID);
    output.putString("buildFingerprint", Build.FINGERPRINT);
    output.putBoolean("process64Bit", Process.is64Bit());
    output.putBoolean("rustBackendsEnabledBefore", initialEnabled);
    File ownedTemporary = null;
    boolean success = false;
    try {
      require(
          Build.VERSION.SDK_INT >= 30
              && Process.is64Bit()
              && Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"),
          Failure.ANDROID_PLATFORM_UNSUPPORTED);
      long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
      output.putLong("pageSize", pageSize);
      require(pageSize > 0, Failure.PAGE_SIZE_UNAVAILABLE);

      output.putString("stage", "receipt");
      String commit = binding("commit", "[0-9a-f]{40}");
      String runId = binding("runId", "[1-9][0-9]{0,19}");
      String attempt = binding("runAttempt", "[1-9][0-9]{0,9}");
      String repository = binding("repository", "[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
      output.putString("commit", commit);
      output.putString("runId", runId);
      output.putString("runAttempt", attempt);
      output.putString("repository", repository);
      ApplicationInfo application = getTargetContext().getApplicationInfo();
      String expectedHash;
      long expectedSize;
      try (ZipFile apk = new ZipFile(application.sourceDir)) {
        // 新语义：随包 .so 的 prebuilt 构建里 App 启动后后端默认启用。用本探针自己解析的
        // 收据存在性判断期望；没有收据（.so 未随包的 off 构建）只记录、不误报默认值，
        // 随后由 RECEIPT_MISSING_OR_DUPLICATE 明确失败。
        boolean receiptPresent = containsEntry(apk, RECEIPT);
        output.putBoolean("rustNativeReceiptPresent", receiptPresent);
        if (receiptPresent) {
          require(initialEnabled, Failure.BACKENDS_NOT_DEFAULT_ON);
          output.putString("rustBackendsDefaultExpectation", "ENABLED_WITH_RECEIPT");
        } else {
          output.putString("rustBackendsDefaultExpectation", "RECEIPT_ABSENT_RECORD_ONLY");
        }
        ZipEntry entry = uniqueEntry(apk, RECEIPT, Failure.RECEIPT_MISSING_OR_DUPLICATE);
        byte[] receiptBytes;
        try (InputStream input = apk.getInputStream(entry)) {
          receiptBytes = boundedBytes(input, MAX_RECEIPT_BYTES);
        }
        output.putString(
            "embeddedReceiptSha256",
            hex(MessageDigest.getInstance("SHA-256").digest(receiptBytes)));
        JSONObject receipt = new JSONObject(new String(receiptBytes, StandardCharsets.UTF_8));
        require(
            receipt.getInt("schema") == 2
                && "dsha-rust-native".equals(receipt.getString("kind"))
                && "github-actions".equals(receipt.getString("provenance"))
                && "android".equals(receipt.getString("target"))
                && "aarch64-linux-android"
                    .equals(receipt.getJSONObject("toolchain").getString("targetTriple")),
            Failure.RECEIPT_INVALID);
        require(
            commit.equals(receipt.getString("commit"))
                && runId.equals(receipt.getString("runId"))
                && attempt.equals(receipt.getString("runAttempt"))
                && repository.equals(receipt.getString("repository")),
            Failure.RECEIPT_BINDING_MISMATCH);
        JSONObject artifact = receipt.getJSONObject("artifact");
        expectedHash = artifact.getString("sha256");
        expectedSize = artifact.getLong("size");
        require(
            LIBRARY.equals(artifact.getString("artifactFile"))
                && expectedHash.matches("[0-9a-f]{64}")
                && expectedSize > 0
                && expectedSize <= MAX_LIBRARY_BYTES,
            Failure.RECEIPT_INVALID);
        output.putString("librarySha256", expectedHash);
        output.putLong("librarySize", expectedSize);
        output.putString("libraryAbi", "arm64-v8a");
        output.putString("stage", "apk-library");
        ZipEntry library = uniqueEntry(apk, APK_LIBRARY, Failure.APK_LIBRARY_MISSING_OR_DUPLICATE);
        require(library.getSize() == expectedSize, Failure.APK_LIBRARY_MISMATCH);
        try (InputStream input = apk.getInputStream(library)) {
          require(expectedHash.equals(hash(input, expectedSize)), Failure.APK_LIBRARY_MISMATCH);
        }
        output.putBoolean("apkLibraryVerified", true);
      }
      // 收据与 APK 字节核验完成后，临时关闭运行层开关跑原始 native 差分与失败清理探针：
      // 这些探针直接调用 RustNative / 生产桥，不依赖 RustBackends；关闭只为证明探针不
      // 改变 App 的默认开关，finally 会恢复初始状态并断言恢复成功。
      RustBackends.setEnabled(false);
      output.putBoolean("rustBackendsDisabledForProbes", true);

      output.putString("stage", "installed-library");
      // 当前 Standard 使用 useLegacyPackaging=true，Android 从这个只读安装目录加载库。
      // 若以后改为 APK 直接映射，本探针明确失败，不能把未确认的来源记成成功。
      require(application.nativeLibraryDir != null, Failure.INSTALLED_LIBRARY_MISSING);
      File installedLibrary = new File(application.nativeLibraryDir, LIBRARY).getCanonicalFile();
      require(
          installedLibrary.isFile() && installedLibrary.length() == expectedSize,
          Failure.INSTALLED_LIBRARY_MISSING);
      try (InputStream input = new FileInputStream(installedLibrary)) {
        require(expectedHash.equals(hash(input, expectedSize)), Failure.INSTALLED_LIBRARY_MISMATCH);
      }
      output.putString("librarySource", "nativeLibraryDir");
      output.putBoolean("installedLibraryVerified", true);

      output.putString("stage", "native-load");
      output.putString("nativeStatusBefore", RustNative.status().name());
      boolean loaded = RustNative.ensureLoaded();
      output.putBoolean("ensureLoaded", loaded);
      output.putString("nativeStatus", RustNative.status().name());
      output.putString("nativeStatusCode", RustNative.statusCode());
      output.putBoolean("nativeAvailable", RustNative.isAvailable());
      require(loaded, Failure.NATIVE_LOAD_FAILED);
      require(
          RustNative.status() == RustNative.Status.LOADED
              && "LOADED".equals(RustNative.statusCode())
              && RustNative.isAvailable(),
          Failure.NATIVE_STATUS_MISMATCH);
      int abiVersion = RustNative.abiVersion();
      output.putInt("nativeAbiVersion", abiVersion);
      require(abiVersion == 1 && RustNative.ABI_VERSION == 1, Failure.NATIVE_ABI_MISMATCH);
      output.putString("stage", "mapped-library");
      requireMappedLibrary(installedLibrary);
      output.putBoolean("mappedLibraryVerified", true);

      output.putString("stage", "fd-digest");
      File cache = getTargetContext().getCacheDir().getCanonicalFile();
      ownedTemporary = File.createTempFile("rust-native-owned-", ".bin", cache);
      require(
          cache.equals(ownedTemporary.getCanonicalFile().getParentFile()),
          Failure.TEMP_FILE_INVALID);
      byte[] knownBytes = "abc".getBytes(StandardCharsets.US_ASCII);
      try (FileOutputStream file = new FileOutputStream(ownedTemporary)) {
        file.write(knownBytes);
        file.getFD().sync();
      }
      ByteBuffer nativeOutput = ByteBuffer.allocateDirect(32);
      try (ParcelFileDescriptor descriptor =
          ParcelFileDescriptor.open(ownedTemporary, ParcelFileDescriptor.MODE_READ_ONLY)) {
        require(
            descriptor.getFd() >= 0 && descriptor.getStatSize() == knownBytes.length,
            Failure.TEMP_FILE_INVALID);
        long bytesRead =
            RustNative.digest(descriptor.getFd(), 0, knownBytes.length, SHA256, nativeOutput);
        output.putLong("nativeDigestReturn", bytesRead);
        require(bytesRead >= 0, Failure.NATIVE_DIGEST_ERROR);
        require(bytesRead == knownBytes.length, Failure.NATIVE_DIGEST_LENGTH_MISMATCH);
        byte[] actual = new byte[32];
        for (int index = 0; index < actual.length; index++) actual[index] = nativeOutput.get(index);
        require(ABC_SHA256.equals(hex(actual)), Failure.NATIVE_DIGEST_MISMATCH);
        output.putString("knownVectorSha256", hex(actual));
      }
      output.putBoolean("nativeFdDigestVerified", true);
      probeArchive(output);
      probePlanView(output);
      require(!RustBackends.enabled(), Failure.BACKENDS_PROBE_SWITCH_MUTATED);
      success = true;
      output.putString("failure", Failure.NONE.name());
      output.putString("stage", "complete");
    } catch (ProbeFailure error) {
      output.putString("failure", error.code.name());
    } catch (org.json.JSONException error) {
      output.putString("failure", Failure.RECEIPT_INVALID.name());
    } catch (LinkageError error) {
      output.putString("failure", Failure.NATIVE_LINK_ERROR.name());
    } catch (Exception | AssertionError error) {
      output.putString("failure", Failure.IO_OR_RUNTIME_ERROR.name());
    } finally {
      // 只删除本次 createTempFile 返回的一个文件，绝不遍历或清理现有缓存。
      if (ownedTemporary != null && !ownedTemporary.delete()) {
        success = false;
        output.putString("failure", Failure.OWNED_TEMP_CLEANUP_FAILED.name());
      }
      output.putBoolean(
          "ownedTemporaryRemoved", ownedTemporary == null || !ownedTemporary.exists());
      // 恢复 App 启动后的默认开关，并核验恢复本身；恢复失败不能报告 PASS。
      RustBackends.setEnabled(initialEnabled);
      boolean restored = RustBackends.enabled() == initialEnabled;
      output.putBoolean("rustBackendsRestored", restored);
      output.putBoolean("rustBackendsEnabledAfter", RustBackends.enabled());
      if (!restored) {
        success = false;
        output.putString("failure", Failure.BACKENDS_RESTORE_FAILED.name());
      }
      output.putString("result", success ? "PASS" : "FAIL");
      finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }
  }

  private interface IoProbe {
    void run() throws IOException;
  }

  private static IOException expectIOException(IoProbe probe, Failure failure) throws ProbeFailure {
    try {
      probe.run();
    } catch (IOException expected) {
      return expected;
    }
    throw new ProbeFailure(failure);
  }

  /** 只持有探针自己生成的字节；失败用对象身份核对，不读取或输出异常正文。 */
  private static final class ArchiveSource extends InputStream {
    final ByteArrayInputStream bytes;
    final IOException closeFailure = new IOException("PROBE_SOURCE_CLOSE");
    final int chunk;
    boolean failClose;
    int closes;

    ArchiveSource(byte[] bytes, int chunk) {
      this.bytes = new ByteArrayInputStream(bytes);
      this.chunk = chunk;
    }

    @Override
    public int read() {
      return bytes.read();
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      return bytes.read(target, offset, Math.min(length, chunk));
    }

    @Override
    public void close() throws IOException {
      closes++;
      bytes.close();
      if (failClose) throw closeFailure;
    }
  }

  private static final class ArchiveTarget extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final IOException writeFailure = new IOException("PROBE_TARGET_WRITE");
    final IOException closeFailure = new IOException("PROBE_TARGET_CLOSE");
    boolean failWrite;
    boolean failClose;
    int writes;
    int closes;

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
      writes++;
      if (failWrite) throw writeFailure;
      bytes.write(source, offset, length);
    }

    @Override
    public void close() throws IOException {
      closes++;
      bytes.close();
      if (failClose) throw closeFailure;
    }
  }

  private static void probeArchive(Bundle output) throws Exception {
    output.putString("stage", "archive-session-baseline");
    output.putString("archiveEntryPoint", "RustGzipOutputStream/RustGzipInputStream");
    requireArchiveSessionsFree(output);
    output.putString("stage", "archive-roundtrip");
    byte[] plaintext = new byte[RustArchiveCodec.CHUNK + 73];
    new Random(20_261_116L).nextBytes(plaintext);
    ArchiveTarget target = new ArchiveTarget();
    RustGzipOutputStream encoder = new RustGzipOutputStream(target);
    try (RustGzipOutputStream stream = encoder) {
      stream.write(plaintext, 0, plaintext.length);
      stream.finish();
      require(stream.isFinished(), Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
      stream.finish();
    }
    encoder.close();
    require(target.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    expectIOException(() -> encoder.write(1), Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    byte[] gzip = target.bytes.toByteArray();
    require(gzip.length >= 18, Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
    require(
        RustArchiveCodec.HEADER_HEX.equals(hex(Arrays.copyOf(gzip, 10))),
        Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
    CRC32 crc = new CRC32();
    crc.update(plaintext);
    ByteBuffer trailer = ByteBuffer.wrap(gzip).order(ByteOrder.LITTLE_ENDIAN);
    require(
        trailer.getInt(gzip.length - 8) == (int) crc.getValue()
            && trailer.getInt(gzip.length - 4) == plaintext.length,
        Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
    ArchiveSource source = new ArchiveSource(gzip, 4093);
    RustGzipInputStream decoder = new RustGzipInputStream(source);
    try (RustGzipInputStream stream = decoder) {
      requireArchivePayload(stream, plaintext);
      require(stream.isFinished(), Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
    }
    decoder.close();
    require(source.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    expectIOException(() -> decoder.read(), Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    requireArchiveSessionsFree(output);
    output.putInt("archivePlaintextBytes", plaintext.length);
    output.putInt("archiveGzipBytes", gzip.length);
    output.putBoolean("archiveRoundtripVerified", true);
    output.putBoolean("archiveHeaderTrailerVerified", true);
    output.putBoolean("archiveClosedStreamsRejected", true);
    output.putBoolean("archiveRoundtripCleanupVerified", true);
    probeArchiveReadFailure(output, gzip, plaintext);

    output.putString("stage", "archive-native-finish");
    byte[] emptyGzip = probeArchiveNativeFinish(output);
    requireArchiveSessionsFree(output);
    byte[] badHeader = emptyGzip.clone();
    badHeader[0] ^= 1;
    probeMalformedArchive(
        output, "archiveBadHeader", badHeader, RustArchiveCodec.GZIP_HEADER, false);
    byte[] badTrailer = emptyGzip.clone();
    badTrailer[badTrailer.length - 8] ^= 1;
    probeMalformedArchive(
        output, "archiveBadTrailer", badTrailer, RustArchiveCodec.GZIP_TRAILER, false);
    // native 不知道底层是否已 EOF；截断由生产 RustGzipInputStream 桥判定。
    probeMalformedArchive(
        output, "archiveTruncated", Arrays.copyOf(emptyGzip, emptyGzip.length - 1), 0, false);
    probeMalformedArchive(
        output,
        "archiveTrailingData",
        Arrays.copyOf(emptyGzip, emptyGzip.length + 1),
        RustArchiveCodec.GZIP_TRAILING_DATA,
        false);
    byte[] twoMembers = new byte[emptyGzip.length * 2];
    System.arraycopy(emptyGzip, 0, twoMembers, 0, emptyGzip.length);
    System.arraycopy(emptyGzip, 0, twoMembers, emptyGzip.length, emptyGzip.length);
    probeMalformedArchive(
        output, "archiveTwoMembers", twoMembers, RustArchiveCodec.GZIP_TRAILING_DATA, false);
    probeMalformedArchive(
        output, "archiveConstructorCloseFailure", badTrailer, RustArchiveCodec.GZIP_TRAILER, true);
    probeArchiveCloseFailures(output, emptyGzip);
    output.putBoolean("archiveAbortCleanupVerified", true);
  }

  private static void probeArchiveReadFailure(Bundle output, byte[] gzip, byte[] plaintext)
      throws Exception {
    output.putString("stage", "archive-read-failure");
    byte[] damaged = gzip.clone();
    damaged[damaged.length - 8] ^= 1;
    ArchiveSource source = new ArchiveSource(damaged, 4093);
    try (RustGzipInputStream stream = new RustGzipInputStream(source)) {
      require(!stream.isFinished(), Failure.NATIVE_ARCHIVE_REJECTION_MISSING);
      boolean rejected = false;
      try {
        requireArchivePayload(stream, plaintext);
      } catch (IOException expected) {
        rejected = true;
      }
      require(rejected, Failure.NATIVE_ARCHIVE_REJECTION_MISSING);
    }
    require(source.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    requireArchiveSessionsFree(output);
    output.putBoolean("archiveReadFailureRejected", true);
    output.putBoolean("archiveReadFailureCleanupVerified", true);
  }

  private static void requireArchivePayload(InputStream stream, byte[] expected) throws Exception {
    byte[] buffer = new byte[7919];
    int offset = 0;
    int count;
    while ((count = stream.read(buffer, 0, buffer.length)) != -1) {
      require(
          count > 0 && count <= expected.length - offset,
          Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
      for (int index = 0; index < count; index++) {
        require(
            buffer[index] == expected[offset + index], Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
      }
      offset += count;
    }
    require(offset == expected.length, Failure.NATIVE_ARCHIVE_ROUNDTRIP_MISMATCH);
  }

  /** 四槽共享表必须全部可复得；每次 abort 后同时检查操作与重复 abort 的 stale 码。 */
  private static void requireArchiveSessionsFree(Bundle output) throws ProbeFailure {
    long[] handles = new long[ARCHIVE_SESSIONS];
    long extraPack = 0;
    long extraUnpack = 0;
    try {
      for (int index = 0; index < handles.length; index++) {
        handles[index] =
            index < 2
                ? RustNative.archivePackCreate(RustArchiveCodec.DEFAULT_LEVEL)
                : RustNative.archiveUnpackCreate();
        require(handles[index] > 0, Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
      }
      extraPack = RustNative.archivePackCreate(RustArchiveCodec.DEFAULT_LEVEL);
      extraUnpack = RustNative.archiveUnpackCreate();
      require(
          extraPack == RustArchiveCodec.SESSION_LIMIT
              && extraUnpack == RustArchiveCodec.SESSION_LIMIT,
          Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
      // 释放前推进真实编码状态和一个合法 gzip 头前缀，避免只验证空会话 abort。
      ByteBuffer prefix = ByteBuffer.allocateDirect(1);
      prefix.put(0, (byte) 0x1f);
      long update =
          RustNative.archivePackUpdate(
              handles[0], prefix, ByteBuffer.allocateDirect(RustArchiveCodec.packBound(1)));
      require(
          update >= 0 && RustArchiveCodec.consumed(update) == 1,
          Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
      require(
          RustNative.archiveUnpackWrite(handles[2], prefix) == 1,
          Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
      long waiting = RustNative.archiveUnpackRead(handles[2], ByteBuffer.allocateDirect(1));
      require(
          waiting >= 0
              && RustArchiveCodec.unpackProduced(waiting) == 0
              && (RustArchiveCodec.unpackFlags(waiting) & RustArchiveCodec.FLAG_NEED_INPUT) != 0,
          Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
    } finally {
      // 先释放所有已取得的句柄，再裁决清理；单个断言不能截断剩余句柄的释放。
      boolean released = true;
      if (extraPack > 0) released &= RustNative.archivePackAbort(extraPack) == RustArchiveCodec.OK;
      if (extraUnpack > 0)
        released &= RustNative.archiveUnpackAbort(extraUnpack) == RustArchiveCodec.OK;
      for (int index = 0; index < handles.length; index++) {
        if (handles[index] <= 0) continue;
        int code =
            index < 2
                ? RustNative.archivePackAbort(handles[index])
                : RustNative.archiveUnpackAbort(handles[index]);
        released &= code == RustArchiveCodec.OK;
      }
      require(released, Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
    }
    for (int index = 0; index < handles.length; index++) {
      requireArchiveHandleStale(handles[index], index < 2);
    }
    output.putInt(
        "archiveSessionCapacityChecks", output.getInt("archiveSessionCapacityChecks", 0) + 1);
  }

  private static void requireArchiveHandleStale(long handle, boolean pack) throws ProbeFailure {
    ByteBuffer input = ByteBuffer.allocateDirect(1);
    ByteBuffer result = ByteBuffer.allocateDirect(RustArchiveCodec.FINISH_BOUND);
    long operation =
        pack
            ? RustNative.archivePackUpdate(handle, input, result)
            : RustNative.archiveUnpackRead(handle, result);
    int abort = pack ? RustNative.archivePackAbort(handle) : RustNative.archiveUnpackAbort(handle);
    require(
        operation == RustArchiveCodec.STALE_HANDLE && abort == RustArchiveCodec.STALE_HANDLE,
        Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
  }

  private static byte[] probeArchiveNativeFinish(Bundle output) throws ProbeFailure {
    long handle = RustNative.archivePackCreate(RustArchiveCodec.DEFAULT_LEVEL);
    require(handle > 0, Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
    boolean finished = false;
    try {
      ByteBuffer bytes = ByteBuffer.allocateDirect(RustArchiveCodec.FINISH_BOUND);
      long count = RustNative.archivePackFinish(handle, bytes);
      require(count >= 18 && count <= bytes.capacity(), Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
      finished = true;
      byte[] gzip = new byte[(int) count];
      bytes.get(gzip);
      require(
          RustNative.archivePackFinish(handle, bytes) == RustArchiveCodec.STALE_HANDLE,
          Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
      requireArchiveHandleStale(handle, true);
      output.putBoolean("archiveNativeFinishStaleVerified", true);
      return gzip;
    } finally {
      int code = RustNative.archivePackAbort(handle);
      require(
          code == (finished ? RustArchiveCodec.STALE_HANDLE : RustArchiveCodec.OK),
          Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
    }
  }

  private static void probeMalformedArchive(
      Bundle output, String name, byte[] gzip, int nativeError, boolean failClose)
      throws Exception {
    output.putString("stage", name);
    if (nativeError != 0) {
      long handle = RustNative.archiveUnpackCreate();
      require(handle > 0, Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
      try {
        ByteBuffer input = ByteBuffer.allocateDirect(gzip.length);
        input.put(gzip);
        require(
            RustNative.archiveUnpackWrite(handle, input) == gzip.length,
            Failure.NATIVE_ARCHIVE_CONTRACT_MISMATCH);
        long code = RustNative.archiveUnpackRead(handle, ByteBuffer.allocateDirect(32));
        output.putLong(name + "NativeReturn", code);
        require(code == nativeError, Failure.NATIVE_ARCHIVE_REJECTION_MISSING);
      } finally {
        require(
            RustNative.archiveUnpackAbort(handle) == RustArchiveCodec.OK,
            Failure.NATIVE_ARCHIVE_CLEANUP_FAILED);
      }
      requireArchiveHandleStale(handle, false);
    }
    ArchiveSource source = new ArchiveSource(gzip, gzip.length);
    source.failClose = failClose;
    boolean[] constructed = {false};
    // 空成员不产出明文，因此这些损坏都须在生产构造泵内拒绝并关闭 source。
    IOException error =
        expectIOException(
            () -> {
              try (RustGzipInputStream stream = new RustGzipInputStream(source)) {
                constructed[0] = true;
              }
            },
            Failure.NATIVE_ARCHIVE_REJECTION_MISSING);
    require(
        !constructed[0] && source.closes == 1 && error != source.closeFailure,
        Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    if (failClose) {
      require(
          error.getSuppressed().length == 1 && error.getSuppressed()[0] == source.closeFailure,
          Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    }
    requireArchiveSessionsFree(output);
    output.putBoolean(name + "Rejected", true);
    output.putBoolean(name + "CleanupVerified", true);
  }

  private static void probeArchiveCloseFailures(Bundle output, byte[] emptyGzip) throws Exception {
    output.putString("stage", "archive-source-close-failure");
    ArchiveSource source = new ArchiveSource(emptyGzip, emptyGzip.length);
    source.failClose = true;
    RustGzipInputStream decoder = new RustGzipInputStream(source);
    try (RustGzipInputStream stream = decoder) {
      requireArchivePayload(stream, new byte[0]);
      require(
          expectIOException(stream::close, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH)
              == source.closeFailure,
          Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
      stream.close();
      require(source.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    }
    requireArchiveSessionsFree(output);
    output.putBoolean("archiveSourceCloseFailureVerified", true);

    output.putString("stage", "archive-target-close-failure");
    ArchiveTarget closeTarget = new ArchiveTarget();
    closeTarget.failClose = true;
    RustGzipOutputStream closeEncoder = new RustGzipOutputStream(closeTarget);
    try (RustGzipOutputStream stream = closeEncoder) {
      require(
          expectIOException(stream::close, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH)
              == closeTarget.closeFailure,
          Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
      require(
          stream.isFinished() && closeTarget.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    }
    requireArchiveSessionsFree(output);
    output.putBoolean("archiveTargetCloseFailureVerified", true);

    output.putString("stage", "archive-finish-write-failure");
    ArchiveTarget writeTarget = new ArchiveTarget();
    RustGzipOutputStream writeEncoder = new RustGzipOutputStream(writeTarget);
    try (RustGzipOutputStream stream = writeEncoder) {
      stream.write(new byte[] {1, 2, 3});
      writeTarget.failWrite = true;
      writeTarget.failClose = true;
      require(
          expectIOException(stream::close, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH)
              == writeTarget.writeFailure,
          Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
      int attempts = writeTarget.writes;
      require(
          stream.isFinished() && writeTarget.closes == 1, Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
      stream.finish();
      stream.close();
      require(
          writeTarget.writes == attempts && writeTarget.closes == 1,
          Failure.NATIVE_ARCHIVE_CLOSE_MISMATCH);
    }
    requireArchiveSessionsFree(output);
    output.putBoolean("archiveFinishWriteFailureVerified", true);
    output.putBoolean("archiveFailureNoReplayVerified", true);
    output.putBoolean("archiveCloseFailureCleanupVerified", true);
  }

  private static void probePlanView(Bundle output) throws Exception {
    output.putString("planEntryPoint", "RustNative.verifyPlan");
    output.putString("stage", "plan-valid");
    byte[] valid =
        PlanViewCodec.encode(
            PlanViewCodec.View.builder().appVersion("android-native-probe").build());
    probePlanVerdict(output, "planValid", valid, PlanViewValidator.VALID, new int[0]);
    byte[] invalid =
        PlanViewCodec.encode(
            PlanViewCodec.View.builder()
                .appVersion("android-native-probe")
                .policyVersion(2)
                .rootCount(0)
                .hasExpiry(true)
                .generatedAtMs(10)
                .expiresAtMs(20)
                .nowMs(20)
                .build());
    output.putString("stage", "plan-invalid");
    probePlanVerdict(
        output,
        "planInvalid",
        invalid,
        PlanViewValidator.INVALID,
        new int[] {
          PlanViewValidator.REASON_FIELD_RANGE,
          PlanViewValidator.REASON_POLICY_VERSION,
          PlanViewValidator.REASON_EXPIRED
        });
    probePlanVerdict(
        output,
        "planMalformed",
        new byte[PlanViewCodec.HEADER_BYTES - 1],
        PlanViewValidator.INVALID,
        new int[] {PlanViewValidator.REASON_STRUCTURE});
    output.putString("stage", "plan-jni-errors");
    ByteBuffer nativeInvalid = ByteBuffer.allocateDirect(invalid.length);
    nativeInvalid.put(invalid);
    ByteBuffer nativeValid = ByteBuffer.allocateDirect(valid.length);
    nativeValid.put(valid);
    probePlanError(
        output,
        "planOutputBound",
        nativeInvalid,
        invalid.length,
        PLAN_MAX_REASONS,
        1,
        RustArchiveCodec.OUTPUT_BOUND);
    probePlanError(
        output,
        "planHeapInput",
        ByteBuffer.wrap(valid),
        valid.length,
        PLAN_MAX_REASONS,
        PLAN_MAX_REASONS,
        RustArchiveCodec.INVALID_ARGUMENT);
    probePlanError(
        output,
        "planNullInput",
        null,
        0,
        PLAN_MAX_REASONS,
        PLAN_MAX_REASONS,
        RustArchiveCodec.INVALID_ARGUMENT);
    probePlanError(
        output,
        "planLengthError",
        nativeValid,
        valid.length + 1,
        PLAN_MAX_REASONS,
        PLAN_MAX_REASONS,
        RustArchiveCodec.INVALID_ARGUMENT);
    probePlanError(
        output,
        "planSmallReasons",
        nativeValid,
        valid.length,
        0,
        PLAN_MAX_REASONS,
        RustArchiveCodec.INVALID_ARGUMENT);
    probePlanVerdict(output, "planAfterErrors", valid, PlanViewValidator.VALID, new int[0]);
    output.putBoolean("planErrorOutputUntouched", true);
    output.putBoolean("planInputUnchanged", true);
    output.putBoolean("planDeterministicRepeatsVerified", true);
  }

  private static ByteBuffer planReasons(int capacityReasons) {
    ByteBuffer reasons =
        ByteBuffer.allocateDirect(4 + 4 * capacityReasons).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 0; index < reasons.capacity(); index++) reasons.put(index, PLAN_SENTINEL);
    return reasons;
  }

  /** JVM 只作独立对照；固定预期、原生返回值、完整原因集合必须同时吻合。 */
  private static void probePlanVerdict(
      Bundle output, String name, byte[] view, int expectedCode, int[] expectedReasons)
      throws ProbeFailure {
    PlanViewValidator.Result reference = PlanViewValidator.validate(view);
    require(
        reference.valid() == (expectedCode == PlanViewValidator.VALID)
            && Arrays.equals(reference.reasons(), expectedReasons),
        Failure.NATIVE_PLAN_VERDICT_MISMATCH);
    ByteBuffer input = ByteBuffer.allocateDirect(view.length);
    input.put(view);
    int[] actualReasons = new int[0];
    for (int repeat = 0; repeat < 2; repeat++) {
      ByteBuffer reasons = planReasons(PLAN_MAX_REASONS);
      int code = RustNative.verifyPlan(input, view.length, reasons, PLAN_MAX_REASONS);
      output.putInt("nativePlanCalls", output.getInt("nativePlanCalls", 0) + 1);
      output.putInt(name + "Return", code);
      require(code == expectedCode, Failure.NATIVE_PLAN_VERDICT_MISMATCH);
      int count = reasons.getInt(0);
      require(count == expectedReasons.length, Failure.NATIVE_PLAN_REASONS_MISMATCH);
      actualReasons = new int[count];
      for (int index = 0; index < count; index++) {
        actualReasons[index] = reasons.getInt(4 + 4 * index);
      }
      require(Arrays.equals(actualReasons, expectedReasons), Failure.NATIVE_PLAN_REASONS_MISMATCH);
      for (int index = 4 + 4 * count; index < reasons.capacity(); index++) {
        require(reasons.get(index) == PLAN_SENTINEL, Failure.NATIVE_PLAN_PARTIAL_OUTPUT);
      }
      for (int index = 0; index < view.length; index++) {
        require(input.get(index) == view[index], Failure.NATIVE_PLAN_INPUT_MUTATED);
      }
    }
    output.putString(name + "Reasons", Arrays.toString(actualReasons));
    output.putBoolean(name + "Verified", true);
  }

  private static void probePlanError(
      Bundle output,
      String name,
      ByteBuffer input,
      int length,
      int capacityReasons,
      int maxReasons,
      int expectedCode)
      throws ProbeFailure {
    ByteBuffer reasons = planReasons(capacityReasons);
    byte[] snapshot = new byte[input == null ? 0 : input.capacity()];
    for (int index = 0; index < snapshot.length; index++) snapshot[index] = input.get(index);
    int code = RustNative.verifyPlan(input, length, reasons, maxReasons);
    output.putInt("nativePlanCalls", output.getInt("nativePlanCalls", 0) + 1);
    output.putInt(name + "Return", code);
    require(code == expectedCode && code < 0, Failure.NATIVE_PLAN_ERROR_MISMATCH);
    for (int index = 0; index < reasons.capacity(); index++) {
      require(reasons.get(index) == PLAN_SENTINEL, Failure.NATIVE_PLAN_PARTIAL_OUTPUT);
    }
    for (int index = 0; index < snapshot.length; index++) {
      require(input.get(index) == snapshot[index], Failure.NATIVE_PLAN_INPUT_MUTATED);
    }
    output.putBoolean(name + "Verified", true);
  }

  private String binding(String name, String expression) throws ProbeFailure {
    String value = arguments.getString(name);
    require(value != null && value.matches(expression), Failure.ARGUMENT_BINDING_INVALID);
    return value;
  }

  private static void require(boolean condition, Failure failure) throws ProbeFailure {
    if (!condition) throw new ProbeFailure(failure);
  }

  /** 只判断条目是否存在；重复/合法性仍由 {@link #uniqueEntry} 裁决。 */
  private static boolean containsEntry(ZipFile apk, String name) {
    Enumeration<? extends ZipEntry> entries = apk.entries();
    while (entries.hasMoreElements()) {
      if (name.equals(entries.nextElement().getName())) return true;
    }
    return false;
  }

  private static ZipEntry uniqueEntry(ZipFile apk, String name, Failure failure)
      throws ProbeFailure {
    ZipEntry result = null;
    Enumeration<? extends ZipEntry> entries = apk.entries();
    while (entries.hasMoreElements()) {
      ZipEntry next = entries.nextElement();
      if (!name.equals(next.getName())) continue;
      require(result == null && !next.isDirectory(), failure);
      result = next;
    }
    require(result != null, failure);
    return result;
  }

  private static byte[] boundedBytes(InputStream input, int maximum) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int count;
    while ((count = input.read(buffer)) != -1) {
      require(output.size() + count <= maximum, Failure.RECEIPT_INVALID);
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static String hash(InputStream input, long expectedSize) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[65536];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      total += count;
      require(total <= expectedSize, Failure.INSTALLED_LIBRARY_MISMATCH);
      digest.update(buffer, 0, count);
    }
    require(total == expectedSize, Failure.INSTALLED_LIBRARY_MISMATCH);
    return hex(digest.digest());
  }

  private static void requireMappedLibrary(File expected) throws Exception {
    boolean executableMapping = false;
    try (BufferedReader maps = new BufferedReader(new FileReader("/proc/self/maps"))) {
      String line;
      while ((line = maps.readLine()) != null) {
        String[] fields = line.trim().split("\\s+", 6);
        if (fields.length != 6 || !fields[5].contains(LIBRARY)) continue;
        require(expected.getPath().equals(fields[5]), Failure.MAPPED_LIBRARY_UNVERIFIED);
        if (fields[1].contains("x")) executableMapping = true;
      }
    }
    require(executableMapping, Failure.MAPPED_LIBRARY_UNVERIFIED);
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 15, 16));
      result.append(Character.forDigit(value & 15, 16));
    }
    return result.toString();
  }
}
