package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PluginOperationResultTest {
  @Test
  public void nestedExitUnknownCannotBeDowngradedByAnOuterFailure() {
    var inner =
        PluginOperationResult.unknownExit("PLUGIN_COMMAND_EXIT_UNKNOWN", "closed unavailable");
    var outer =
        new PluginOperationResult.Failure(
            PluginOperationResult.Kind.FAILED, "WRAPPER", "completed");
    outer.initCause(inner);
    assertSame(inner, PluginOperationResult.findFailure(outer));
    assertTrue(PluginOperationResult.findFailure(outer).exitUnknown);
    assertNull(PluginOperationResult.findFailure(new IllegalStateException("UNKNOWN text")));
  }

  @Test
  public void explicitEffectUnknownDoesNotClaimUnknownExitWhileLegacyRemainsConservative() {
    String body =
        "{\"status\":\"unknown\",\"message\":\"effect unknown\",\"executionUnknown\":true,\"exitUnknown\":false}";
    assertFalse(failure(0, false, false, body).exitUnknown);
    assertTrue(failure(0, true, false, body).exitUnknown);
    assertTrue(
        failure(0, false, false, "{\"status\":\"unknown\",\"message\":\"legacy\"}").exitUnknown);
    assertTrue(failure(1, false, true, body).exitUnknown);
    assertTrue(failure(1, false, false, "{}").exitUnknown);
  }

  private String result(String json) {
    return "PLUGIN_RESULT: " + json + "\n";
  }

  private PluginOperationResult.Failure failure(
      int exit, boolean timeout, boolean truncated, String json) {
    try {
      PluginOperationResult.requireCompleted(exit, timeout, truncated, result(json));
      throw new AssertionError("expected non-success");
    } catch (PluginOperationResult.Failure failure) {
      return failure;
    }
  }

  @Test
  public void timeoutsUnknownExitsAndTruncationDoNotTrustSuccessLookingOutput() {
    String success = "{\"status\":\"ok\",\"message\":\"saved\"}";
    assertEquals("PLUGIN_COMMAND_TIMEOUT", failure(0, true, false, success).detail.errorCode());
    assertEquals("PLUGIN_OUTPUT_TRUNCATED", failure(0, false, true, success).detail.errorCode());
    assertTrue(failure(0, true, false, success).exitUnknown);
    assertFalse(failure(0, false, true, success).exitUnknown);
    assertEquals(PluginOperationResult.Kind.UNCONFIRMED, failure(-1, false, false, success).kind);
    assertEquals(PluginOperationResult.Kind.UNCONFIRMED, failure(1, false, false, success).kind);
  }

  @Test
  public void classificationUsesFieldsAndRetainsStageNotLocalizedMessage() {
    for (String message : new String[] {"UNKNOWN 只是用户文字", "échec", "something failed"}) {
      String error =
          "{\"status\":\"error\",\"message\":\""
              + message
              + "\",\"errorCode\":\"VALIDATION_FAILED\",\"stage\":\"check\"}";
      var failure = failure(1, false, false, error);
      assertEquals(PluginOperationResult.Kind.FAILED, failure.kind);
      assertEquals("VALIDATION_FAILED", failure.detail.errorCode());
      assertEquals("check", failure.detail.stage());
    }
    assertEquals(
        PluginOperationResult.Kind.UNCONFIRMED,
        failure(
                1,
                false,
                false,
                "{\"status\":\"error\",\"message\":\"普通说明\",\"executionUnknown\":true}")
            .kind);
  }

  @Test
  public void pendingPartialAndUnverifiedCancellationRemainUnconfirmed() {
    for (String status :
        new String[] {"pending", "partial", "unknown", "cancelled", "future-status"})
      assertEquals(
          status,
          PluginOperationResult.Kind.UNCONFIRMED,
          failure(1, false, false, "{\"status\":\"" + status + "\",\"message\":\"kept\"}").kind);
    assertEquals(
        PluginOperationResult.Kind.CANCELLED,
        failure(
                1,
                false,
                false,
                "{\"status\":\"cancelled\",\"message\":\"kept\",\"cleanupVerified\":true}")
            .kind);
    assertEquals(
        PluginOperationResult.Kind.TIMEOUT,
        failure(
                1,
                false,
                false,
                "{\"status\":\"error\",\"message\":\"kept\",\"errorCode\":\"PLUGIN_COMMAND_TIMEOUT_CONFIRMED\",\"cleanupVerified\":true}")
            .kind);
    assertEquals(
        PluginOperationResult.Kind.UNCONFIRMED,
        failure(
                1,
                false,
                false,
                "{\"status\":\"error\",\"message\":\"kept\",\"errorCode\":\"PLUGIN_COMMAND_TIMEOUT_CONFIRMED\"}")
            .kind);
  }

  @Test
  public void duplicateMalformedAndMissingFieldsCannotBecomeSuccess() {
    for (String json :
        new String[] {
          "{}",
          "{\"status\":\"ok\"}",
          "{\"status\":\"error\",\"status\":\"ok\",\"message\":\"bad\"}",
          "{\"status\":\"ok\",\"message\":\"bad\",\"cleanupVerified\":\"true\"}",
          "{\"status\":true,\"message\":\"bad\"}",
          "{\"status\":\"ok\",\"message\":\"bad\"} trailing"
        })
      assertEquals(PluginOperationResult.Kind.UNCONFIRMED, failure(0, false, false, json).kind);
  }

  @Test
  public void targetAndCompatibilityEvidenceChangesInvalidateRatherThanReplayOldPreview() {
    for (String code :
        new String[] {
          "PLUGIN_TARGET_VERSION_CHANGED",
          "PLUGIN_SOURCE_CHANGED",
          "PLUGIN_CANDIDATE_CHANGED",
          "PLUGIN_COMPATIBILITY_CHANGED"
        }) {
      var error =
          failure(
              1,
              false,
              false,
              "{\"status\":\"error\",\"message\":\"localized\",\"errorCode\":\""
                  + code
                  + "\",\"stage\":\"commit\",\"exitUnknown\":false}");
      assertEquals(PluginOperationResult.Kind.INVALIDATED, error.kind);
      assertEquals(code, error.detail.errorCode());
      assertEquals("commit", error.detail.stage());
      assertFalse(error.exitUnknown);
    }
    assertEquals(
        PluginOperationResult.Kind.FAILED,
        failure(
                1,
                false,
                false,
                "{\"status\":\"error\",\"message\":\"localized\",\"errorCode\":\"PLUGIN_INCOMPATIBLE\"}")
            .kind);
  }

  @Test
  public void successfulCommitOnlyCarriesDeclaredApplicationAndRestartEvidence() throws Exception {
    var receipt =
        PluginOperationResult.requireCompleted(
            0,
            false,
            false,
            result(
                "{\"status\":\"ok\",\"message\":\"saved\",\"application\":\"restart-required\",\"restartReason\":\"no-hmr\",\"stage\":\"install\"}"));
    assertEquals("restart-required", receipt.detail().application());
    assertEquals("no-hmr", receipt.detail().restartReason());
    assertEquals("install", receipt.detail().stage());
    assertEquals(
        "",
        PluginOperationResult.parse(result("{\"status\":\"ok\",\"message\":\"legacy\"}"))
            .detail()
            .application());
  }
}
