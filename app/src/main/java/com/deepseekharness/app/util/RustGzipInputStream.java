package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * {@code archive_unpack} 的宿主桥：严格单成员 gzip 读取器（方案 §4.5）。
 *
 * <p>镜像 {@code app/src/main/java/com/deepseekharness/app/backup/StrictGzipInputStream}：
 * 校验 magic/CM/FLG、FEXTRA/FNAME/FCOMMENT/FHCRC、raw deflate、尾部 CRC32/ISIZE，拒绝
 * 尾随字节与多成员；{@code FNAME}/{@code FCOMMENT} 各 ≤ 8192 字节（含终止 0）。
 * 解压输出累计上限 16 GiB + 256 MiB，与现有读取器一致。
 *
 * <p>纯 Java、零 Android import。R3 不接生产调用点（A3 slice4 由 Lead 串行安排）。
 * 构造时会推进头部并尽量取出第一块明文：对小载荷/一次性读块，这一步可能已经解完 body 并
 * 校验尾部，因此**构造期也可能抛出** {@code GZIP_TRAILER}/{@code GZIP_DEFLATE}/
 * {@code GZIP_TRAILING_DATA}（JVM 的 {@code StrictGzipInputStream} 把 body/尾部错误推迟到
 * {@code read()}）。若要求“只校验头、body/尾部错误一律延迟到 read”，需要 ABI 新增“只推头”
 * 标志；ABI v1 不提供该时机，本批不做（A3 接线必须按“构造可失败”处理）。截断（头/体/尾提前
 * EOF）映射为 {@code GZIP_TRUNCATED}。
 *
 * <p>所有权：构造成功即接管 {@code source}，{@link #close()} 会关闭它；**任何**构造失败
 * （库不可用、会话创建失败、头/体/尾部错误）也会关闭 {@code source}（关闭异常作为 suppressed
 * 附在原错误上），调用方不得复用。原生会话数上限 4；即使已读到 EOF 也要 close 才能释放会话。
 */
public final class RustGzipInputStream extends InputStream {
  private final InputStream source;
  private final ByteBuffer outputBuffer =
      ByteBuffer.allocateDirect(RustArchiveCodec.MAX_OUTPUT_CHUNK);
  private final byte[] outputHeap = new byte[RustArchiveCodec.MAX_OUTPUT_CHUNK];
  private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK);
  private final byte[] inputHeap = new byte[RustArchiveCodec.CHUNK];
  private int pendingOffset;
  private int pendingLimit;
  private long session;
  private int outputPosition;
  private int outputLimit;
  private int lastFlags;
  private boolean eof;
  private boolean closed;

  /**
   * 构造并推进 gzip 头（见类注释的时机边界）。
   *
   * @throws IOException 库不可用；头部非法/截断；或因首个读块已含完整成员而在构造期暴露
   *     的 body/尾部错误（{@code GZIP_DEFLATE}/{@code GZIP_TRAILER}/{@code GZIP_TRAILING_DATA}）。
   *     失败时会释放原生会话并关闭 {@code source}。
   */
  public RustGzipInputStream(InputStream source) throws IOException {
    if (source == null) {
      throw new NullPointerException("source");
    }
    if (!RustNative.ensureLoaded()) {
      closeSourceQuietly(source);
      throw new IOException(RustArchiveCodec.unavailableMessage());
    }
    long handle = RustNative.archiveUnpackCreate();
    if (handle <= 0) {
      closeSourceQuietly(source);
      throw new IOException(RustArchiveCodec.message((int) handle));
    }
    this.source = source;
    this.session = handle;
    try {
      pumpHeader();
    } catch (IOException error) {
      abortQuietly();
      closeSourceQuietly(source, error);
      throw error;
    } catch (RuntimeException error) {
      abortQuietly();
      closeSourceQuietly(source);
      throw error;
    }
  }

  /** 是否已读到尾部并完成校验（之后 {@link #read(byte[], int, int)} 返回 -1）。 */
  public boolean isFinished() {
    return eof;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int read = read(one, 0, 1);
    return read < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    if (closed) {
      throw new IOException("STREAM_CLOSED");
    }
    if (bytes == null) {
      throw new NullPointerException("bytes");
    }
    if (offset < 0 || length < 0 || offset > bytes.length - length) {
      throw new IndexOutOfBoundsException("offset/length");
    }
    if (length == 0) {
      return 0;
    }
    if (eof) {
      return -1;
    }
    if (outputPosition < outputLimit) {
      return copyOut(bytes, offset, length);
    }
    boolean retriedOutput = false;
    while (true) {
      if (fillOutput()) {
        return copyOut(bytes, offset, length);
      }
      if ((lastFlags & RustArchiveCodec.FLAG_EOF) != 0) {
        confirmSourceEnd();
        eof = true;
        return -1;
      }
      if ((lastFlags & RustArchiveCodec.FLAG_NEED_INPUT) != 0) {
        if (feedOnce()) {
          return copyOut(bytes, offset, length);
        }
        continue;
      }
      if ((lastFlags & RustArchiveCodec.FLAG_NEED_OUTPUT) != 0) {
        // 输出缓冲固定为 256 KiB（= native 单次输出上限），正常永远不会走到这里；
        // 契约被破坏时只重试一次，随后按 INTERNAL 失败，绝不无限循环（评审 P2-5）。
        if (retriedOutput) {
          throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
        }
        retriedOutput = true;
        continue;
      }
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
    }
  }

  @Override
  public int available() {
    return outputLimit - outputPosition;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    abortQuietly();
    source.close();
  }

  private int copyOut(byte[] bytes, int offset, int length) {
    int available = outputLimit - outputPosition;
    int count = Math.min(available, length);
    System.arraycopy(outputHeap, outputPosition, bytes, offset, count);
    outputPosition += count;
    if (outputPosition == outputLimit) {
      outputPosition = 0;
      outputLimit = 0;
    }
    return count;
  }

  /** 调用一次 native read，把产出的明文字节放进内部缓冲；返回是否产出。 */
  private boolean fillOutput() throws IOException {
    long result = RustNative.archiveUnpackRead(session, outputBuffer);
    if (result < 0) {
      // unpack 语境的 OUTPUT_BOUND = 累计明文硬上限，映射为 JVM 的 ARCHIVE_LIMIT。
      throw new IOException(RustArchiveCodec.unpackMessage((int) result));
    }
    int produced = RustArchiveCodec.unpackProduced(result);
    lastFlags = RustArchiveCodec.unpackFlags(result);
    if (produced <= 0) {
      return false;
    }
    if (produced > outputHeap.length) {
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
    }
    outputBuffer.position(0);
    outputBuffer.limit(produced);
    outputBuffer.get(outputHeap, 0, produced);
    outputPosition = 0;
    outputLimit = produced;
    return true;
  }

  /**
   * 把底层流的一段压缩字节喂给 native；返回是否顺带产出了明文。
   *
   * <p>native 的输入缓冲满时 {@code write} 只消费一部分（甚至 0）：剩余输入留在
   * {@link #inputHeap}，下次继续喂，绝不丢字节。
   */
  private boolean feedOnce() throws IOException {
    while (true) {
      if (pendingOffset >= pendingLimit) {
        int read = source.read(inputHeap, 0, inputHeap.length);
        if (read < 0) {
          // 调用方字节流结束但 native 还需要输入 → 截断（与 StrictGzipInputStream 一致）。
          throw new IOException(RustArchiveCodec.message(RustArchiveCodec.GZIP_TRUNCATED));
        }
        pendingOffset = 0;
        pendingLimit = read;
      }
      int length = Math.min(pendingLimit - pendingOffset, RustArchiveCodec.CHUNK);
      inputBuffer.clear();
      inputBuffer.put(inputHeap, pendingOffset, length);
      inputBuffer.flip();
      long result = RustNative.archiveUnpackWrite(session, inputBuffer.slice());
      if (result < 0) {
        throw new IOException(RustArchiveCodec.message((int) result));
      }
      int consumed = (int) result;
      if (consumed > length) {
        throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
      }
      pendingOffset += consumed;
      if (pendingOffset < pendingLimit) {
        // 输入缓冲满：先把已产出的明文交给读路径，剩余压缩字节留在 inputHeap。
        if (fillOutput()) {
          return true;
        }
        if (consumed == 0) {
          throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
        }
        continue;
      }
      return false;
    }
  }

  /** 构造期推行头：产出第一块明文或到达合法 EOF 即认为头已通过。 */
  private void pumpHeader() throws IOException {
    boolean retriedOutput = false;
    while (true) {
      if (fillOutput()) {
        return;
      }
      if ((lastFlags & RustArchiveCodec.FLAG_EOF) != 0) {
        confirmSourceEnd();
        eof = true;
        return;
      }
      if ((lastFlags & RustArchiveCodec.FLAG_NEED_INPUT) != 0) {
        if (feedOnce()) {
          return;
        }
        continue;
      }
      if ((lastFlags & RustArchiveCodec.FLAG_NEED_OUTPUT) != 0) {
        // 同 read()：只重试一次，避免契约破坏时构造期挂死（评审 P2-5）。
        if (retriedOutput) {
          throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
        }
        retriedOutput = true;
        continue;
      }
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL));
    }
  }

  /** EOF 后仍能从底层读到字节 = 尾随数据/多成员（与 JVM 读取器一致）。 */
  private void confirmSourceEnd() throws IOException {
    // 已从底层读走但还没喂给 native 的字节同样在成员结束之后：不能只用 source.read()
    // 判断，否则预读缓冲里的尾随字节会被漏检。
    if (pendingOffset < pendingLimit || source.read() >= 0) {
      throw new IOException(RustArchiveCodec.message(RustArchiveCodec.GZIP_TRAILING_DATA));
    }
  }

  /** 构造失败时关闭 source：任何失败路径都不把已传入的流泄漏给调用方。 */
  private static void closeSourceQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // 构造失败路径不覆盖原始错误；关闭失败无额外信息可安全上报。
    }
  }

  /** 构造失败时关闭 source，并把关闭异常作为 suppressed 附到原始 IOException 上。 */
  private static void closeSourceQuietly(InputStream stream, IOException primary) {
    try {
      stream.close();
    } catch (IOException error) {
      primary.addSuppressed(error);
    }
  }

  private void abortQuietly() {
    if (session > 0) {
      RustNative.archiveUnpackAbort(session);
      session = 0;
    }
  }
}
