package com.deepseekharness.app.util;

/** Device bridge port candidates: prefer the historical 3090, then a bounded fallback range. */
public final class BridgePortPolicy {
  public static final int PRIMARY_PORT = 3090;
  public static final int ATTEMPTS = 10;

  private BridgePortPolicy() {}

  /** Fixed order makes multi-instance diagnosis deterministic: 3090, 3091, ... 3099. */
  public static int[] candidates() {
    int[] ports = new int[ATTEMPTS];
    for (int index = 0; index < ATTEMPTS; index++) ports[index] = PRIMARY_PORT + index;
    return ports;
  }

  /** A bridge port must be a normal TCP port and must not steal the Web/LAN reserved ports. */
  public static boolean valid(int port) {
    return port >= 1 && port <= 65535 && port != 3080 && port != 3081;
  }

  public static String rangeLabel() {
    return PRIMARY_PORT + "-" + (PRIMARY_PORT + ATTEMPTS - 1);
  }
}
