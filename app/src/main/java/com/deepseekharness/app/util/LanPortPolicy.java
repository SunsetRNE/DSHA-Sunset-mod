package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** The current app leaves 3081 for legacy LAN sharing; a busy preference falls back at bind time. */
public final class LanPortPolicy {
  public static final int LEGACY_PORT = 3081;
  public static final int CURRENT_PORT = 3082;

  private LanPortPolicy() {}

  public static int preferred(String applicationId) {
    return "com.dsh.client".equals(applicationId) ? LEGACY_PORT : CURRENT_PORT;
  }

  public static boolean reserved(int port) {
    return port == LEGACY_PORT || port == CURRENT_PORT;
  }

  /** Return the held listener itself, never a port discovered by opening and closing a probe. */
  public static ServerSocket bind(java.net.InetAddress address, int preferred) throws IOException {
    return bind(javax.net.ServerSocketFactory.getDefault(), address, preferred);
  }

  public static ServerSocket bind(
      javax.net.ServerSocketFactory factory, java.net.InetAddress address, int preferred)
      throws IOException {
    if (address == null || address.isAnyLocalAddress())
      throw new IllegalArgumentException("LAN_BIND_ADDRESS");
    if (preferred < 1 || preferred > 65535) throw new IllegalArgumentException("LAN_PORT_RANGE");
    try {
      return bindOnce(factory, address, preferred);
    } catch (BindException busy) {
      if (!BridgePortPolicy.addressInUse(busy)) throw busy;
      return bindOnce(factory, address, 0);
    }
  }

  private static ServerSocket bindOnce(
      javax.net.ServerSocketFactory factory, java.net.InetAddress address, int port)
      throws IOException {
    ServerSocket socket = factory.createServerSocket();
    try {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(address, port));
      return socket;
    } catch (IOException | RuntimeException error) {
      try {
        socket.close();
      } catch (IOException closing) {
        error.addSuppressed(closing);
      }
      throw error;
    }
  }
}
