package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * {@code archive_pack} 的宿主桥：把明文流式编码为**单成员** RFC 1952 gzip（方案 §4.4）。
 *
 * <p>纯 Java、零 Android import。输出头固定为 {@code 1f 8b 08 00 00000000 00 00}
 * （FLG=0/MTIME=0/XFL=0/OS=0），尾部是 CRC32 + ISIZE；gzip 字节允许与 JVM
 * {@code GZIPOutputStream} 不同（压缩器实现差异），兼容性由严格读取器与尾部校验证明。
 *
 * <p>R3 不接生产调用点：A3 slice4 接线时用本类替换 {@code PortableBackupEnvelope} 里的
 * JVM 编码器，并保留运行层开关（{@link RustBackends#archiveSelection()}，默认关闭）。
 * 使用方必须 try-with-resources / 显式 {@link #close()}：原生会话数上限 4，泄漏会让后续
 * 创建返回 {@link RustArchiveCodec#SESSION_LIMIT}。
 *
 * <p>失败域（方案 §5.3）：可展开 panic → 类型化错误码；abort/OOM/SIGSEGV 不可捕获，
 * 本类不伪造“已回退”。任何 {@code IOException} 都表示本次编码不可用；调用方若要按
 * §5.4 用 JVM 重做，必须丢弃本次输出目标并从 0 重新编码（本类不重试、不续写）。
 */
public final class RustGzipOutputStream extends OutputStream {
  private final OutputStream target;
  private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK);
  private final ByteBuffer outputBuffer =
      ByteBuffer.allocateDirect(RustArchiveCodec.MAX_PACK_OUTPUT);
  private final byte[] outputHeap = new byte[RustArchiveCodec.MAX_PACK_OUTPUT];
  private final byte[] finishHeap = new byte[RustArchiveCodec.FINISH_BOUND];
  private long session;
  private boolean finished;
  private boolean closed;

  /** 以默认级别 6 创建编码器。 */
  public RustGzipOutputStream(OutputStream target) throws IOException {
    this(target, RustArchiveCodec.DEFAULT_LEVEL);
  }

  /**
   * 创建编码器；{@code level} 必须在 0..=9（0 = store）。
   *
   * @throws IOException 库不可用、级别非法或原生会话创建失败（消息为冻结错误码/类型化状态）
   */
  public RustGzipOutputStream(OutputStream target, int level) throws IOException {
    if (target == null) {
      throw new NullPointerException("target");
    }
    if (!RustArchiveCodec.levelValid(level)) {
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INVALID_ARGUMENT));
    }
    if (!RustNative.ensureLoaded()) {
      throw new IOException(RustArchiveCodec.unavailableMessage());
    }
    long handle = RustNative.archivePackCreate(level);
    if (handle <= 0) {
      throw new IOException(RustArchiveCodec.message((int) handle));
    }
    this.target = target;
    this.session = handle;
  }

  /** 当前后端是否已结束编码（{@link #finish()} 成功后为 true）。 */
  public boolean isFinished() {
    return finished;
  }

  @Override
  public void write(int value) throws IOException {
    byte[] one = {(byte) value};
    write(one, 0, 1);
  }

  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    if (closed || finished) {
      throw new IOException("STREAM_CLOSED");
    }
    if (bytes == null) {
      throw new NullPointerException("bytes");
    }
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException("offset/length");
    }
    if (length == 0) {
      return;
    }
    int position = offset;
    int remaining = length;
    while (remaining > 0) {
      int chunkLength = Math.min(remaining, RustArchiveCodec.CHUNK);
      inputBuffer.clear();
      inputBuffer.put(bytes, position, chunkLength);
      inputBuffer.flip();
      ByteBuffer view = inputBuffer.slice();
      int consumedTotal = 0;
      while (consumedTotal < chunkLength) {
        ByteBuffer rest = view.duplicate();
        rest.position(consumedTotal);
        long result = RustNative.archivePackUpdate(session, rest.slice(), outputBuffer);
        if (result < 0) {
          throw new IOException(RustArchiveCodec.message((int) result));
        }
        int consumed = RustArchiveCodec.consumed(result);
        int produced = RustArchiveCodec.produced(result);
        writeProduced(produced, outputHeap);
        consumedTotal += consumed;
        if (consumed == 0 && produced == 0) {
          throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
        }
      }
      position += chunkLength;
      remaining -= chunkLength;
    }
  }

  /** 刷出最终块与 gzip 尾部并释放原生会话；之后不能再写。 */
  public void finish() throws IOException {
    if (finished) {
      return;
    }
    if (closed) {
      throw new IOException("STREAM_CLOSED");
    }
    long result = RustNative.archivePackFinish(session, outputBuffer);
    if (result < 0) {
      abortQuietly();
      finished = true;
      throw new IOException(RustArchiveCodec.message((int) result));
    }
    int produced = (int) result;
    if (produced > RustArchiveCodec.FINISH_BOUND) {
      abortQuietly();
      finished = true;
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
    }
    writeProduced(produced, finishHeap);
    session = 0;
    finished = true;
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      throw new IOException("STREAM_CLOSED");
    }
    target.flush();
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    IOException failure = null;
    try {
      if (!finished) {
        finish();
      }
    } catch (IOException error) {
      failure = error;
      abortQuietly();
    } finally {
      closed = true;
      try {
        target.close();
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void writeProduced(int produced, byte[] heap) throws IOException {
    if (produced <= 0) {
      return;
    }
    if (produced > heap.length) {
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
    }
    outputBuffer.position(0);
    outputBuffer.limit(produced);
    outputBuffer.get(heap, 0, produced);
    target.write(heap, 0, produced);
  }

  private void abortQuietly() {
    if (session > 0) {
      RustNative.archivePackAbort(session);
      session = 0;
    }
  }
}
