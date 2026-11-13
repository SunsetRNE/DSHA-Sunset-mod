package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import org.junit.Test;

public class RuntimeToolsPreparationTest {
  @Test
  public void preparationOutcomeKeepsOnlyFixedTokensAndCounts() {
    assertEquals(
        "RUNTIME_TOOLS_PREPARATION: outcome=hit-prepared-proof files=488 miss=none",
        RuntimeTools.preparationLine("hit-prepared-proof", 488, ""));
    assertEquals(
        "RUNTIME_TOOLS_PREPARATION: outcome=failed files=unknown miss=none",
        RuntimeTools.preparationLine("failed", -1, null));
  }

  @Test
  public void cacheMissReasonIsReducedToItsFixedReasonToken() {
    assertEquals(
        "metadata",
        RuntimeTools.cacheMissCode(
            "reason=metadata entry=3 entries=488 asset=usr/local/share/dsha/managed-assets-v2"));
    assertEquals(
        "invalidated", RuntimeTools.cacheMissCode("reason=invalidated source=explicit-repair"));
    assertEquals("unavailable", RuntimeTools.cacheMissCode("reason=unavailable"));
    assertEquals("unknown", RuntimeTools.cacheMissCode("user path /secret? reason="));
    assertEquals("unknown", RuntimeTools.cacheMissCode(null));
  }

  @Test
  public void preparationLineNeverContainsPathsCredentialsOrExceptionText() {
    String line =
        RuntimeTools.preparationLine(
            "prepared",
            12,
            RuntimeTools.cacheMissCode(
                "reason=metadata entry=3 asset=/data/user/0/private token=sk-secret"));
    assertTrue(
        line, line.matches("RUNTIME_TOOLS_PREPARATION: outcome=prepared files=12 miss=[a-z-]+"));
    assertFalse(line.contains("/"));
    assertFalse(line.contains("secret"));
  }
}
