package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.LanNetworkState;
import com.deepseekharness.app.util.LanPortPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** 实际 listener 和真实 TCP 请求；仅端口绑定/地址观察定向到随机 loopback。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {LanPasswordSocketTest.Port.class, LanPasswordSocketTest.Network.class})
public class LanPasswordSocketTest {
  @Implements(value = LanPortPolicy.class, isInAndroidSdk = false)
  public static class Port {
    @Implementation
    protected static ServerSocket bind(int ignored) throws IOException {
      return new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    }
  }

  @Implements(value = LanNetworkState.class, isInAndroidSdk = false)
  public static class Network {
    @Implementation
    protected boolean poll() {
      return false;
    }
  }

  @After
  public void stop() {
    LanProxyService.stop();
  }

  @Test
  public void passwordBootstrapSessionRotationAndStopUseActualListener() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    ConfigStore config = new ConfigStore(context);
    config.setLanPassword("sample-password-one");
    assertTrue(LanProxyService.setDshAuthCookie("dsh-auth-socket=valid", 741));
    LanProxyService.start(null, context, 49123, 741);
    int port = awaitPort(741);
    assertTrue(request(port, "", "").startsWith("HTTP/1.1 401"));
    assertTrue(request(port, basic("wrong-password"), "").startsWith("HTTP/1.1 401"));
    String authenticated = request(port, basic("sample-password-one"), "");
    assertTrue(authenticated, authenticated.startsWith("HTTP/1.1 303"));
    String cookie =
        authenticated
            .lines()
            .filter(line -> line.startsWith("Set-Cookie: dsha_lan="))
            .findFirst()
            .orElseThrow()
            .substring("Set-Cookie: ".length())
            .split(";", 2)[0];
    config.setLanPassword("sample-password-two");
    assertFalse(LanProxyService.isBound());
    LanProxyService.start(null, context, 49123, 741);
    port = awaitPort(741);
    assertTrue(request(port, "", cookie).startsWith("HTTP/1.1 401"));
    assertTrue(request(port, basic("sample-password-one"), "").startsWith("HTTP/1.1 401"));
    assertTrue(request(port, basic("sample-password-two"), "").startsWith("HTTP/1.1 303"));
    LanProxyService.stopLanListener();
    assertEquals(0, LanProxyService.port(741));
    assertTrue(LanProxyService.hasDshAuth(741));
  }

  private static String basic(String password) {
    return "Basic "
        + Base64.getEncoder().encodeToString(("dsha:" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static int awaitPort(long generation) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      int port = LanProxyService.port(generation);
      if (port > 0) return port;
      Thread.sleep(10);
    }
    throw new AssertionError("LAN listener failed to bind");
  }

  private static String request(int port, String authorization, String cookie) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      String head =
          "GET / HTTP/1.1\r\nHost: 127.0.0.1:"
              + port
              + (authorization.isEmpty() ? "" : "\r\nAuthorization: " + authorization)
              + (cookie.isEmpty() ? "" : "\r\nCookie: " + cookie)
              + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] bytes = new byte[4096];
      for (int read; (read = socket.getInputStream().read(bytes)) >= 0; ) {
        output.write(bytes, 0, read);
        if (output.size() > 65536) throw new IOException("response too large");
      }
      return output.toString(StandardCharsets.UTF_8);
    }
  }
}
