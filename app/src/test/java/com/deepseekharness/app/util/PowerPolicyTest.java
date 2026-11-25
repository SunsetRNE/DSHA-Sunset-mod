package com.deepseekharness.app.util;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class PowerPolicyTest {
  private static final String FRAME =
      "{\"generation\":\"7\",\"instance\":\"sample\",\"at\":100000,\"idle\":true,\"plugins\":[{\"name\":\"\"}]}";

  @Test
  public void acceptsFullProducerBudgetAndRejectsOneExtraByte() {
    PowerPolicy.IdleFrame expected = new PowerPolicy.IdleFrame(7, "sample", 100000);
    for (int size : new int[] {2048, 2049, 32 * 1024, 256 * 1024, 256 * 1024 + 1}) {
      byte[] bytes =
          FRAME
              .replace("\"name\":\"\"", "\"name\":\"" + "a".repeat(size - FRAME.length()) + "\"")
              .getBytes(StandardCharsets.UTF_8);
      assertEquals(size, bytes.length);
      assertEquals(
          "bytes=" + size,
          size <= PowerPolicy.MAX_ACTIVITY_BYTES ? expected : null,
          PowerPolicy.readIdleFrame(bytes));
    }
  }

  @Test
  public void onlyAcceptsCompleteIdleEvidenceFromTopLevelFields() {
    for (String json :
        new String[] {
          FRAME.replace("true", "false"),
          FRAME.replace("true", "\"true\""),
          FRAME.replace("true", "true,\"idle\":false"),
          FRAME.replace("\"sample\"", "\"\""),
          FRAME.replace("\"at\":100000,", ""),
          FRAME.replace("100000", "100000.1"),
          FRAME.replace("\"at\":100000", "\"plugins2\":{\"at\":100000}"),
          FRAME + "{}",
          FRAME.substring(0, FRAME.length() - 1),
          FRAME.replace("\"7\"", "\"x\"")
        }) {
      assertNull(PowerPolicy.readIdleFrame(json.getBytes(StandardCharsets.UTF_8)));
    }
    assertEquals(
        new PowerPolicy.IdleFrame(7, "sample", 100000),
        PowerPolicy.readIdleFrame(FRAME.replace("\"7\"", "7").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void graceBelongsToContinuousFreshHeartbeatsOfTheSameInstance() {
    PowerPolicy policy = new PowerPolicy();
    for (int elapsed : new int[] {0, 15000, 30000, 45000, 59999, 60000}) {
      long now = 100000L + elapsed;
      policy.observe(new PowerPolicy.IdleFrame(7, "old", now), now);
      assertEquals(
          elapsed < 60000,
          policy.keepCpu(
              true, true, false, false, false, false, policy.knownIdle(7, "old", now), elapsed));
    }
    assertTrue(policy.knownIdle(7, "old", 190000));
    assertFalse(policy.knownIdle(7, "old", 190001));
    assertFalse(policy.knownIdle(7, "old", 159999));
    assertFalse(policy.knownIdle(8, "old", 160000));
    assertFalse(policy.knownIdle(7, "new", 160000));
    for (PowerPolicy.IdleFrame next :
        new PowerPolicy.IdleFrame[] {
          new PowerPolicy.IdleFrame(7, "old", 191000),
          new PowerPolicy.IdleFrame(8, "old", 191001),
          new PowerPolicy.IdleFrame(8, "new", 191002)
        }) {
      policy.observe(next, next.at());
      assertTrue(
          policy.keepCpu(
              true,
              true,
              false,
              false,
              false,
              false,
              policy.knownIdle(next.generation(), next.instance(), next.at()),
              next.at() - 100000));
    }
    policy.observe(null, 191003);
    assertFalse(policy.knownIdle(8, "new", 191003));
  }

  @Test
  public void lanDoesNotDependOnScreenOrBrowserActivityAndStopsWithWeb() {
    PowerPolicy p = new PowerPolicy();
    for (long now : new long[] {0, 60000, 600000, 3600000}) {
      assertTrue(p.keepCpu(true, true, false, false, true, false, true, now));
    }
    assertFalse(p.keepCpu(true, false, false, false, true, false, true, 3600001));
    assertTrue(p.keepCpu(true, true, false, false, false, false, true, 3600002));
    assertFalse(p.keepCpu(true, true, false, false, false, false, true, 3660002));
  }

  @Test
  public void ecoWaitsForContinuousIdleAndResumesForWork() {
    PowerPolicy p = new PowerPolicy();
    assertTrue(p.keepCpu(true, true, false, false, false, false, true, 0));
    assertFalse(p.keepCpu(true, true, false, false, false, false, true, 60000));
    assertTrue(p.keepCpu(true, true, false, false, false, false, false, 61000));
    assertTrue(p.keepCpu(true, true, false, false, false, false, true, 62000));
    assertTrue(p.keepCpu(true, true, false, false, true, false, true, 180000));
    assertTrue(p.keepCpu(true, true, false, false, false, true, true, 240000));
  }

  @Test
  public void stoppedWebReleasesAndPersistentModeKeepsRunning() {
    PowerPolicy p = new PowerPolicy();
    assertTrue(p.keepCpu(false, true, false, false, false, false, true, 0));
    assertTrue(p.keepCpu(false, true, false, false, false, false, true, 100000));
    assertFalse(p.keepCpu(false, false, false, false, false, false, false, 100001));
    assertTrue(p.keepCpu(true, true, true, false, false, false, true, 110000));
    assertTrue(p.keepCpu(true, true, false, true, false, false, true, 210000));
  }
}
