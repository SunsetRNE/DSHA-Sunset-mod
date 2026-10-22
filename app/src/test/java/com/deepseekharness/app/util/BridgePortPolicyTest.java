package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
  public void rangeLabelMatchesCandidates() {
    assertEquals("3090-3099", BridgePortPolicy.rangeLabel());
  }
}
