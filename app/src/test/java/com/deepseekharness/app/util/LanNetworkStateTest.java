package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.net.InetAddress;
import org.junit.Test;

public class LanNetworkStateTest {
  @Test
  public void physicalRouteIncludesOnlyTheConnectedPrivateSubnet() throws Exception {
    var route = new LanNetworkState(12, "eth0", "192.168.1.129", 25);
    assertTrue(route.eligible());
    assertTrue(route.admits(InetAddress.getByName("192.168.1.250")));
    assertFalse(route.admits(InetAddress.getByName("192.168.1.120")));
    assertFalse(route.admits(InetAddress.getByName("::1")));
    assertFalse(new LanNetworkState(12, "eth0", "192.168.1.2", 8).eligible());
    assertFalse(new LanNetworkState(12, "eth0", "127.0.0.1", 8).eligible());
    assertFalse(new LanNetworkState(0, "eth0", "10.0.0.1", 24).eligible());
  }

  @Test
  public void reusedAddressOnANewNetworkRequiresFreshApproval() {
    var first = new LanNetworkState(12, "wlan0", "192.168.1.2", 24);
    assertNotEquals(first, new LanNetworkState(13, "wlan0", "192.168.1.2", 24));
    assertNotEquals(first, new LanNetworkState(12, "wlan0", "192.168.1.2", 25));
    assertNotEquals(first, new LanNetworkState(12, "eth0", "192.168.1.2", 24));
    assertNotEquals(first, new LanNetworkState(12, "wlan0", "192.168.1.3", 24));
  }
}
