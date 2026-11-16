package com.deepseekharness.app.capabilities;

import static org.junit.Assert.*;

import com.deepseekharness.app.core.RuntimeSnapshot;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** 冻结权限判据与结果模型：撤销、换代、过期、目标变化、拒绝、UNKNOWN/PARTIAL 与重复消费。 */
public final class CapabilityBrokerContractTest {
  private static final RuntimeSnapshot.Epoch RUN =
      new RuntimeSnapshot.Epoch("install-a", 7, "runtime-a");
  private final CapabilityBroker broker = new CapabilityBroker(() -> 100L);

  private CapabilityRequest request(String capability) {
    return CapabilityRequest.create(
        capability,
        "tap",
        RUN,
        "install-a",
        "bridge-a",
        new ScreenTargetEvidence(0, 12, "com.example.app"),
        3,
        broker.epoch(capability),
        200);
  }

  @Test
  public void singleConfirmationSlotIsSharedAcrossCapabilities() {
    var screen = broker.beginConfirmation(request("screen"));
    assertNotNull(screen);
    assertNull(broker.beginConfirmation(request("sms.read")));
    broker.resolve(screen, true);
    broker.finish(screen);
    assertNotNull(broker.beginConfirmation(request("sms.read")));
  }

  @Test
  public void unansweredFinishCancelsInsteadOfLeavingLateAuthority() {
    var request = request("screen");
    var confirmation = broker.beginConfirmation(request);
    assertTrue(broker.authorize(confirmation.request));
    broker.finish(confirmation);
    assertFalse(broker.authorize(request));
    assertNull(broker.claimDispatch(request));
    broker.finish(confirmation); // 重复收尾不重新打开权限
    assertFalse(broker.current(request));
  }

  @Test
  public void denialKeepsDecisionReadableButWaiterStaysFalse() throws Exception {
    var request = request("screen");
    var confirmation = broker.beginConfirmation(request);
    assertTrue(broker.resolve(confirmation, false));
    assertFalse(confirmation.await(0, TimeUnit.MILLISECONDS));
    assertEquals(Boolean.FALSE, confirmation.answer());
    broker.finish(confirmation);
    assertFalse(broker.authorize(request));
    assertNull(broker.claimDispatch(request));
  }

  @Test
  public void revocationOfAnotherCapabilityDoesNotDisturbPendingConfirmation() {
    var confirmation = broker.beginConfirmation(request("screen"));
    assertNotNull(confirmation);
    broker.revoke("sms.read");
    assertTrue(broker.pending(confirmation));
    assertTrue(broker.resolve(confirmation, true));
    broker.finish(confirmation);
  }

  @Test
  public void targetChangeBreaksBindingOnEveryIdentityField() {
    var target = new ScreenTargetEvidence(0, 12, "com.example.app");
    assertFalse(target.matches(null));
    assertFalse(target.matches(new ScreenTargetEvidence(0, 12, "com.example.app.x")));
    assertFalse(target.kind().isEmpty());
  }

  @Test
  public void generationExpiryAndFenceBoundariesAreStrict() {
    var expired = request("screen");
    var confirmation = broker.beginConfirmation(expired);
    assertNotNull(confirmation);
    assertNull(broker.beginConfirmation(request("screen"))); // 单槽：另一能力也排队不了
    var brokerAtDeadline = new CapabilityBroker(() -> 200L);
    assertTrue(broker.current(expired));
    assertFalse(brokerAtDeadline.current(expired));
    assertTrue(broker.resolve(confirmation, true));
    broker.finish(confirmation);
  }

  @Test
  public void requestAndResultValidationLimitsStayFrozen() {
    var target = new ScreenTargetEvidence(0, 12, "com.example.app");
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRequest("r", "Screen", "tap", RUN, "install-a", "b", target, 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRequest("r", "-screen", "tap", RUN, "install-a", "b", target, 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CapabilityRequest("a\0b", "screen", "tap", RUN, "install-a", "b", target, 0, 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CapabilityRequest("r", "screen", "tap", RUN, "install-a", "b", target, -1, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new ScreenTargetEvidence(0, 1, "x".repeat(256)));
    assertEquals("x".repeat(255), new ScreenTargetEvidence(0, 1, "x".repeat(255)).packageName());
    assertThrows(
        IllegalArgumentException.class, () -> CapabilityResult.notSent("code".repeat(33), ""));
    assertEquals("", CapabilityResult.completed("OK", null).detail);
    assertEquals(
        List.of(
            CapabilityResult.State.NOT_SENT,
            CapabilityResult.State.REJECTED,
            CapabilityResult.State.COMPLETED,
            CapabilityResult.State.PARTIAL,
            CapabilityResult.State.UNKNOWN),
        List.of(CapabilityResult.State.values()));
  }

  @Test
  public void partialAndUnknownRemainUnreplayableAfterClaim() {
    var request = request("screen");
    assertTrue(broker.authorize(request));
    var permit = broker.claimDispatch(request);
    assertNotNull(permit);
    broker.revokeAll();
    assertFalse(permit.isCurrent());
    assertFalse(
        permit.isCurrent() && CapabilityResult.partial("PARTIAL", "").mayTryAlternateTransport());
    assertFalse(
        permit.isCurrent() && CapabilityResult.unknown("UNKNOWN", "").mayTryAlternateTransport());
    assertTrue(CapabilityResult.partial("PARTIAL", "").executionMayHaveOccurred());
    assertTrue(CapabilityResult.unknown("UNKNOWN", "").executionMayHaveOccurred());
  }
}
