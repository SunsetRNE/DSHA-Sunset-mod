package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import static org.junit.Assert.assertThrows;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

public class BridgePortPolicyTest {
  @Test
  public void candidatesPreferLegacyPortAndStayBounded() {
    int[] ports = BridgePortPolicy.candidates();
    assertEquals(10, ports.length);
    assertEquals(3090, ports[0]);
    assertEquals(3099, ports[9]);
    for (int index = 1; index < ports.length; index++)
      assertEquals(ports[index - 1] + 1, ports[index]);
    assertArrayEquals(
        new int[] {3090, 3091, 3092, 3093, 3094, 3095, 3096, 3097, 3098, 3099}, ports);
  }

  @Test
  public void validRejectsReservedAndOutOfRange() {
    assertTrue(BridgePortPolicy.valid(1));
    assertTrue(BridgePortPolicy.valid(3090));
    assertTrue(BridgePortPolicy.valid(65535));
    assertFalse(BridgePortPolicy.valid(0));
    assertFalse(BridgePortPolicy.valid(-1));
    assertFalse(BridgePortPolicy.valid(65536));
    assertFalse(BridgePortPolicy.valid(3080));
    assertFalse(BridgePortPolicy.valid(3081));
  }

  @Test
  public void occupiedFirstPortFallsBackAndReleasedPrimaryCanBeReused() throws Exception {
    // 仅使用系统分配的临时端口，不触碰本机真实设备桥。
    try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      int primary = first.getLocalPort(), fallback = reservation.getLocalPort();
      reservation.close();
      try (ServerSocket second = BridgePortPolicy.bind(new int[] {primary, fallback})) {
        assertEquals(fallback, second.getLocalPort());
        assertEquals("127.0.0.1", second.getInetAddress().getHostAddress());
        assertThrows(
            BridgePortPolicy.Exhausted.class,
            () -> BridgePortPolicy.bind(new int[] {primary, fallback}));
        assertFalse(first.isClosed());
        assertFalse(second.isClosed());
        first.close();
        try (ServerSocket restarted = BridgePortPolicy.bind(new int[] {primary, fallback})) {
          assertEquals(primary, restarted.getLocalPort());
          assertFalse(second.isClosed());
        }
      }
    }
  }

  @Test
  public void permissionAndUnknownBindFailuresAreNotPortConflicts() {
    assertTrue(BridgePortPolicy.addressInUse(new BindException("bind failed: EADDRINUSE")));
    assertTrue(BridgePortPolicy.addressInUse(new BindException("Address already in use")));
    assertFalse(BridgePortPolicy.addressInUse(new BindException("bind failed: EACCES")));
    assertFalse(BridgePortPolicy.addressInUse(new BindException()));
  }

  @Test
  public void rangeLabelMatchesCandidates() {
    assertEquals("3090-3099", BridgePortPolicy.rangeLabel());
  }
}
