package com.deepseekharness.app;

import com.deepseekharness.app.util.HttpProtocol;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Locale;

/** A fallback for a complete page resource when all stream workers are busy. Probe only a bounded
 * prefix. On overflow/timeout, replay every byte to the regular streaming parser without committing a response. */
final class LanFiniteResponse extends ByteArrayOutputStream {
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final int PROBE_MS = 2_000;
  static final int TOTAL_MS = 30_000;
  private static final OutputStream DISCARD =
      new OutputStream() {
        @Override
        public void write(int b) {}

        @Override
        public void write(byte[] b, int off, int len) {}
      };

  LanFiniteResponse() {
    super(8192);
  }

  private void makeRoom(int additional) {
    if (count + additional > buf.length)
      buf =
          java.util.Arrays.copyOf(
              buf, Math.min(MAX_BYTES, Math.max(buf.length * 2, count + additional)));
  }

  static boolean eligible(HttpProtocol.Body body, String contentType) {
    if (body.kind == HttpProtocol.Kind.FIXED) return body.length <= MAX_BYTES;
    if (body.kind != HttpProtocol.Kind.CHUNKED) return false;
    String media = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return media.equals("application/json")
        || media.endsWith("+json")
        || media.equals("text/javascript")
        || media.equals("application/javascript")
        || media.equals("text/css")
        || media.equals("text/html");
  }

  boolean complete(HttpProtocol.Body body, InputStream source, Socket socket) throws IOException {
    long deadline = HttpProtocol.deadline(PROBE_MS);
    InputStream observed =
        new InputStream() {
          int previousTimeout;

          void beforeRead() throws IOException {
            int millis = (int) ((deadline - System.nanoTime()) / 1_000_000L);
            if (count >= MAX_BYTES || millis <= 0)
              throw new SocketTimeoutException("LAN_FINITE_BUDGET");
            if (previousTimeout != millis) {
              socket.setSoTimeout(millis);
              previousTimeout = millis;
            }
          }

          @Override
          public int read() throws IOException {
            beforeRead();
            int value = source.read();
            if (value >= 0) {
              makeRoom(1);
              buf[count++] = (byte) value;
            }
            return value;
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            beforeRead();
            int n = source.read(b, off, Math.min(len, MAX_BYTES - count));
            if (n > 0) {
              makeRoom(n);
              System.arraycopy(b, off, buf, count, n);
              count += n;
            }
            return n;
          }
        };
    try {
      HttpProtocol.copyBody(body, observed, DISCARD);
      return true;
    } catch (SocketTimeoutException overflowOrSlow) {
      return false;
    }
  }

  InputStream replay(InputStream remaining) {
    return new SequenceInputStream(new ByteArrayInputStream(buf, 0, count), remaining);
  }
}
