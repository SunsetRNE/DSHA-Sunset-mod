package com.deepseekharness.app;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.HttpProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class LanFiniteResponseTest {
  private static HttpProtocol.Body chunked() throws IOException {
    return HttpProtocol.parse(
            "POST /fixture HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n", true)
        .requestBody();
  }

  @Test
  public void probeStopsAtByteBudgetAndReplaysEveryByteAndTrailer() throws Exception {
    int size = LanFiniteResponse.MAX_BYTES + 200;
    var wire = new ByteArrayOutputStream();
    wire.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    wire.write(new byte[size]);
    wire.write("\r\n0\r\nX-Fixture: done\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    var remaining = new ByteArrayInputStream(wire.toByteArray());
    var prefix = new LanFiniteResponse();
    try (var socket = new Socket()) {
      assertFalse(prefix.complete(chunked(), remaining, socket));
    }
    assertEquals(LanFiniteResponse.MAX_BYTES, prefix.size());
    var out = new ByteArrayOutputStream();
    HttpProtocol.copyBody(chunked(), prefix.replay(remaining), out);
    assertArrayEquals(wire.toByteArray(), out.toByteArray());
  }

  @Test
  public void interruptedProbeCanResumeInTheMiddleOfAChunkSizeOrTrailer() throws Exception {
    byte[] data = "3\r\nabc\r\n0\r\nX-Fixture: kept\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    for (int stop : new int[] {1, 4, 18}) {
      var source =
          new InputStream() {
            int at;
            boolean timed;

            public int read() throws IOException {
              if (at == stop && !timed) {
                timed = true;
                throw new SocketTimeoutException("fixture");
              }
              return at == data.length ? -1 : data[at++] & 255;
            }

            public int read(byte[] b, int off, int len) throws IOException {
              int v = read();
              if (v < 0) return -1;
              b[off] = (byte) v;
              return 1;
            }
          };
      var prefix = new LanFiniteResponse();
      try (var socket = new Socket()) {
        assertFalse(prefix.complete(chunked(), source, socket));
      }
      var out = new ByteArrayOutputStream();
      HttpProtocol.copyBody(chunked(), prefix.replay(source), out);
      assertArrayEquals(data, out.toByteArray());
    }
  }

  @Test
  public void unknownDownloadsAndEventStreamsDoNotGetThePageResourceFallback() throws Exception {
    assertTrue(LanFiniteResponse.eligible(chunked(), "text/javascript; charset=utf-8"));
    assertTrue(LanFiniteResponse.eligible(chunked(), "application/json"));
    assertFalse(LanFiniteResponse.eligible(chunked(), "text/event-stream"));
    assertFalse(LanFiniteResponse.eligible(chunked(), "application/octet-stream"));
    var large =
        HttpProtocol.parse(
                "POST /fixture HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4194304\r\n\r\n",
                true)
            .requestBody();
    assertFalse(LanFiniteResponse.eligible(large, "application/javascript"));
  }
}
