package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/** 迁移前后同一真实 Rust .so；缺少库直接失败，不使用 assumption 伪装通过。 */
public class RustBridgeNativeGoldenTest {
  @BeforeClass
  public static void requireNativeLibrary() {
    String library = System.getProperty("dsha.rust.hostLib", "").trim();
    assertFalse("RUST_HOST_LIB_REQUIRED", library.isEmpty());
    assertTrue(
        "RUST_HOST_LIB_UNUSABLE: " + RustNative.statusCode(), RustNative.loadFromPath(library));
    assertEquals(1, RustNative.abiVersion());
  }

  @After
  public void resetSwitch() {
    RustBackends.setEnabled(false);
  }

  @Test
  public void finishRecordsConsumedHandleBeforeTargetFailureAndNeverReplays() throws Exception {
    FailingTarget target = new FailingTarget();
    RustGzipOutputStream output = new RustGzipOutputStream(target);
    output.write(new byte[] {1, 2, 3});
    target.failWrite = true;
    IOException error = assertThrows(IOException.class, output::finish);
    assertSame(target.writeFailure, error);
    assertTrue(output.isFinished());
    int writes = target.writes;
    output.finish();
    assertEquals(writes, target.writes);
    output.close();
    output.close();
    assertEquals(1, target.closes);
    assertEquals(writes, target.writes);
    assertEquals(
        "STREAM_CLOSED",
        assertThrows(IOException.class, () -> output.write(null, -1, -1)).getMessage());
    assertEquals("STREAM_CLOSED", assertThrows(IOException.class, output::flush).getMessage());
    // 已结束的 finish 在 close 之后仍幂等；不能变成 STALE_HANDLE。
    output.finish();
  }

  @Test
  public void closeKeepsFirstIoFailureAndClosesTargetExactlyOnce() throws Exception {
    FailingTarget target = new FailingTarget();
    RustGzipOutputStream output = new RustGzipOutputStream(target);
    target.failWrite = true;
    target.failClose = true;
    IOException error = assertThrows(IOException.class, output::close);
    assertSame(target.writeFailure, error);
    assertEquals(0, error.getSuppressed().length);
    assertEquals(1, target.closes);
    int writes = target.writes;
    output.close();
    output.finish();
    assertEquals(writes, target.writes);
    assertEquals(1, target.closes);
  }

  @Test
  public void malformedConstructorClosesSourceAndKeepsSuppressedCloseFailure() throws Exception {
    CountingSource input = new CountingSource(new byte[] {0, 1, 2, 3});
    input.failClose = true;
    IOException error = assertThrows(IOException.class, () -> new RustGzipInputStream(input));
    assertEquals("GZIP_HEADER", error.getMessage());
    assertArrayEquals(new Throwable[] {input.closeFailure}, error.getSuppressed());
    assertEquals(1, input.closes);
    // 构造失败释放句柄；随后四个并发会话仍全部可用。
    RustGzipOutputStream[] outputs = new RustGzipOutputStream[4];
    try {
      for (int i = 0; i < outputs.length; i++)
        outputs[i] = new RustGzipOutputStream(new ByteArrayOutputStream());
    } finally {
      for (RustGzipOutputStream output : outputs) if (output != null) output.close();
    }
  }

  @Test
  public void streamValidationAndClosedPriorityStayInJavaOrder() throws Exception {
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    RustGzipOutputStream output = new RustGzipOutputStream(target);
    assertEquals(
        "bytes",
        assertThrows(NullPointerException.class, () -> output.write(null, -1, -1)).getMessage());
    assertEquals(
        "offset/length",
        assertThrows(
                IndexOutOfBoundsException.class,
                () -> output.write(new byte[1], Integer.MAX_VALUE, 1))
            .getMessage());
    output.write(new byte[0], 0, 0);
    output.finish();
    assertEquals(
        "STREAM_CLOSED",
        assertThrows(IOException.class, () -> output.write(null, 0, 0)).getMessage());
    output.close();
    CountingSource source = new CountingSource(target.toByteArray());
    RustGzipInputStream input = new RustGzipInputStream(source);
    assertTrue(input.isFinished());
    assertEquals(
        "bytes",
        assertThrows(NullPointerException.class, () -> input.read(null, -1, -1)).getMessage());
    assertEquals(0, input.read(new byte[0], 0, 0));
    assertEquals(-1, input.read());
    input.close();
    input.close();
    assertEquals(1, source.closes);
    assertEquals(
        "STREAM_CLOSED",
        assertThrows(IOException.class, () -> input.read(null, -1, -1)).getMessage());
  }

  @Test
  public void closeFailureConsumesInputOwnershipWithoutSecondClose() throws Exception {
    CountingSource source = new CountingSource(gzip(new byte[] {4, 5, 6}));
    RustGzipInputStream input = new RustGzipInputStream(source);
    source.failClose = true;
    assertSame(source.closeFailure, assertThrows(IOException.class, input::close));
    input.close();
    assertEquals(1, source.closes);
    assertEquals("STREAM_CLOSED", assertThrows(IOException.class, input::read).getMessage());
  }

  @Test
  public void factoryBorrowsAndChunkedStreamsRoundTripWithNativeBackend() throws Exception {
    RustBackends.setEnabled(true);
    FailingTarget target = new FailingTarget();
    byte[] payload = new byte[RustArchiveCodec.CHUNK + 19];
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
    try (OutputStream output = RustArchiveCodec.packStream(target, payload.length)) {
      assertTrue(output instanceof RustGzipOutputStream);
      output.write(payload, 0, 1);
      output.write(payload, 1, payload.length - 1);
    }
    assertEquals(0, target.closes);
    try (GZIPInputStream reference =
        new GZIPInputStream(new ByteArrayInputStream(target.bytes.toByteArray()))) {
      assertArrayEquals(payload, reference.readAllBytes());
    }
    CountingSource source = new CountingSource(target.bytes.toByteArray());
    try (RustGzipInputStream input = new RustGzipInputStream(source)) {
      ByteArrayOutputStream decoded = new ByteArrayOutputStream();
      byte[] chunk = new byte[17];
      int n;
      while ((n = input.read(chunk, 0, chunk.length)) >= 0) decoded.write(chunk, 0, n);
      assertArrayEquals(payload, decoded.toByteArray());
      assertTrue(input.isFinished());
    }
    assertEquals(1, source.closes);
  }

  private static byte[] gzip(byte[] payload) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
      output.write(payload);
    }
    return bytes.toByteArray();
  }

  private static final class FailingTarget extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final IOException writeFailure = new IOException("TARGET_WRITE");
    final IOException closeFailure = new IOException("TARGET_CLOSE");
    boolean failWrite;
    boolean failClose;
    int writes;
    int closes;

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] values, int offset, int length) throws IOException {
      writes++;
      if (failWrite) throw writeFailure;
      bytes.write(values, offset, length);
    }

    @Override
    public void close() throws IOException {
      closes++;
      if (failClose) throw closeFailure;
    }
  }

  private static final class CountingSource extends ByteArrayInputStream {
    final IOException closeFailure = new IOException("SOURCE_CLOSE");
    boolean failClose;
    int closes;

    CountingSource(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closes++;
      if (failClose) throw closeFailure;
      super.close();
    }
  }
}
