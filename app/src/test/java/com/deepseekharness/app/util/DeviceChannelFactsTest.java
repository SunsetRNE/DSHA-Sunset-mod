package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.DeviceChannelFacts.Channel;
import com.deepseekharness.app.util.DeviceChannelFacts.ConnectionEvidence;
import org.junit.Test;

public final class DeviceChannelFactsTest {
  @Test
  public void rootCandidateAndRequestedRootDoNotProveRootIdentityOrBusinessGrant() {
    DeviceChannelFacts facts =
        new DeviceChannelFacts(
            Channel.ROOT,
            true,
            true,
            false,
            false,
            false,
            ConnectionEvidence.UNVERIFIED,
            true,
            DeviceChannelFacts.UNKNOWN_UID,
            false,
            100,
            4);

    assertTrue(facts.enabled());
    assertTrue(facts.backendVisible());
    assertTrue(facts.requestedRoot());
    assertFalse(facts.channelAuthorized());
    assertFalse(facts.executionUidKnown());
    assertFalse(facts.rootIdentityVerified());
    assertFalse(facts.businessGranted());
  }

  @Test
  public void connectedAndBoundShizukuDoesNotEnableChannelOrAuthorizeBusiness() {
    DeviceChannelFacts facts =
        new DeviceChannelFacts(
            Channel.SHIZUKU,
            false,
            true,
            false,
            true,
            true,
            ConnectionEvidence.UNVERIFIED,
            false,
            DeviceChannelFacts.UNKNOWN_UID,
            false,
            200,
            7);

    assertTrue(facts.backendVisible());
    assertTrue(facts.bound());
    assertTrue(facts.connected());
    assertFalse(facts.enabled());
    assertFalse(facts.channelAuthorized());
    assertFalse(facts.businessGranted());
    assertFalse(facts.connectionAuthenticated());
    assertFalse(facts.executionUidKnown());
  }

  @Test
  public void tcpConnectedAdbDoesNotBecomeAuthenticatedOrAcquireAnExecutionUid() {
    DeviceChannelFacts facts = adb(ConnectionEvidence.TCP_REACHABLE, false, -1, false);

    assertTrue(facts.connected());
    assertEquals(ConnectionEvidence.TCP_REACHABLE, facts.connectionEvidence());
    assertFalse(facts.connectionAuthenticated());
    assertFalse(facts.executionUidKnown());
    assertEquals(DeviceChannelFacts.UNKNOWN_UID, facts.executionUid());
    assertFalse(facts.businessGranted());
  }

  @Test
  public void authenticatedAdbStillNeedsIndependentBusinessAuthorization() {
    DeviceChannelFacts facts = adb(ConnectionEvidence.AUTHENTICATED, false, -1, false);

    assertTrue(facts.enabled());
    assertTrue(facts.channelAuthorized());
    assertTrue(facts.connectionAuthenticated());
    assertFalse(facts.businessGranted());
    assertFalse(facts.executionUidKnown());
  }

  @Test
  public void requestedAdbSuDoesNotUpgradeAuthenticationIntoRootIdentity() {
    DeviceChannelFacts facts = adb(ConnectionEvidence.AUTHENTICATED, true, -1, true);

    assertTrue(facts.requestedRoot());
    assertTrue(facts.connectionAuthenticated());
    assertTrue(facts.businessGranted());
    assertFalse(facts.executionUidKnown());
    assertFalse(facts.rootIdentityVerified());
    assertEquals(-1, facts.executionUid());
  }

  @Test
  public void knownShellUidDoesNotSatisfyRequestedRoot() {
    DeviceChannelFacts facts = adb(ConnectionEvidence.AUTHENTICATED, true, 2000, true);

    assertTrue(facts.requestedRoot());
    assertTrue(facts.executionUidKnown());
    assertEquals(2000, facts.executionUid());
    assertFalse(facts.rootIdentityVerified());
  }

  @Test
  public void actualUidEvidenceDoesNotCreatePermissionOrBusinessGrant() {
    DeviceChannelFacts facts =
        new DeviceChannelFacts(
            Channel.ROOT,
            false,
            true,
            false,
            false,
            false,
            ConnectionEvidence.UNVERIFIED,
            false,
            0,
            false,
            400,
            9);

    assertTrue(facts.executionUidKnown());
    assertTrue(facts.rootIdentityVerified());
    assertFalse(facts.requestedRoot());
    assertFalse(facts.enabled());
    assertFalse(facts.channelAuthorized());
    assertFalse(facts.businessGranted());
    assertFalse(facts.connectionAuthenticated());
  }

  @Test
  public void visibleShizukuBinderIsNotAnImplicitServiceBinding() {
    DeviceChannelFacts facts =
        new DeviceChannelFacts(
            Channel.SHIZUKU,
            true,
            true,
            true,
            false,
            false,
            ConnectionEvidence.UNVERIFIED,
            false,
            -1,
            false,
            500,
            10);

    assertTrue(facts.backendVisible());
    assertTrue(facts.channelAuthorized());
    assertFalse(facts.bound());
    assertFalse(facts.connected());
    assertFalse(facts.businessGranted());
  }

  @Test
  public void unknownUidAndObservationTimeRemainUnknownForEveryChannel() {
    for (Channel channel : Channel.values()) {
      DeviceChannelFacts facts =
          new DeviceChannelFacts(
              channel,
              true,
              true,
              true,
              true,
              true,
              ConnectionEvidence.AUTHENTICATED,
              true,
              DeviceChannelFacts.UNKNOWN_UID,
              true,
              DeviceChannelFacts.UNKNOWN_OBSERVED_AT_MILLIS,
              12);

      assertEquals(channel, facts.channel());
      assertEquals(-1, facts.executionUid());
      assertFalse(facts.executionUidKnown());
      assertFalse(facts.rootIdentityVerified());
      assertEquals(-1L, facts.observedAtMillis());
      assertEquals(12L, facts.authorizationRevision());
    }
  }

  @Test
  public void invalidUidTimeRevisionAndMissingEvidenceAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> facts(Channel.ADB, ConnectionEvidence.UNVERIFIED, -2, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> facts(Channel.ADB, ConnectionEvidence.UNVERIFIED, -1, -2, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> facts(Channel.ADB, ConnectionEvidence.UNVERIFIED, -1, 0, -1));
    assertThrows(
        NullPointerException.class, () -> facts(null, ConnectionEvidence.UNVERIFIED, -1, 0, 0));
    assertThrows(NullPointerException.class, () -> facts(Channel.ADB, null, -1, 0, 0));
  }

  @Test
  public void oldOwnerCannotPublishDisconnectOrRetireReplacementTcpEvidence() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(ledger.activate("A", 3));
    assertTrue(ledger.publish("A", 3, ConnectionEvidence.AUTHENTICATED, 100));
    var old = ledger.observe("A", 3);

    assertTrue(ledger.activate("B", 3));
    assertTrue(ledger.publish("B", 3, ConnectionEvidence.TCP_REACHABLE, 200));
    var replacement = ledger.observe("B", 3);
    assertFalse(ledger.publish("A", 3, ConnectionEvidence.AUTHENTICATED, 300));
    ledger.disconnect("A");
    ledger.retire("A");

    assertFalse(ledger.current(old));
    assertTrue(ledger.current(replacement));
    assertSame(replacement, ledger.observe("B", 3));
    assertEquals(ConnectionEvidence.TCP_REACHABLE, replacement.evidence());
    assertEquals(200L, replacement.observedAtMillis());
    assertTrue(replacement.connected());
    assertUnknown(ledger.observe("A", 3));
  }

  @Test
  public void invalidateRejectsPreviousEpochAndDoesNotRefreshProofTime() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(ledger.activate("A", 4));
    assertTrue(ledger.publish("A", 4, ConnectionEvidence.AUTHENTICATED, 100));
    var old = ledger.observe("A", 4);

    assertTrue(ledger.invalidate(5));
    assertFalse(ledger.current(old));
    assertFalse(ledger.publish("A", 4, ConnectionEvidence.AUTHENTICATED, 900));
    assertUnknown(ledger.observe("A", 4));
    var invalidated = ledger.observe("A", 5);
    assertUnknown(invalidated);
    assertTrue(ledger.current(invalidated));
    assertTrue(ledger.publish("A", 5, ConnectionEvidence.TCP_REACHABLE, 950));
    assertFalse(ledger.current(invalidated));
    assertEquals(ConnectionEvidence.TCP_REACHABLE, ledger.observe("A", 5).evidence());
  }

  @Test
  public void staleActivationOrInvalidationCannotRewindReplacementEpoch() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(ledger.activate("B", 20));
    assertTrue(ledger.publish("B", 20, ConnectionEvidence.TCP_REACHABLE, 200));
    var replacement = ledger.observe("B", 20);

    assertFalse(ledger.activate("A", 19));
    assertFalse(ledger.invalidate(19));
    assertFalse(ledger.invalidate(20));
    assertTrue(ledger.current(replacement));
    assertSame(replacement, ledger.observe("B", 20));
  }

  @Test
  public void tcpPublicationDoesNotCarryForwardEarlierAuthentication() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(ledger.activate("A", 6));
    assertTrue(ledger.publish("A", 6, ConnectionEvidence.AUTHENTICATED, 100));
    var authenticated = ledger.observe("A", 6);
    assertTrue(ledger.publish("A", 6, ConnectionEvidence.TCP_REACHABLE, 200));
    var tcp = ledger.observe("A", 6);

    assertFalse(ledger.current(authenticated));
    assertTrue(ledger.current(tcp));
    assertEquals(ConnectionEvidence.TCP_REACHABLE, tcp.evidence());
    assertEquals(200L, tcp.observedAtMillis());
    assertTrue(tcp.connected());
  }

  @Test
  public void unknownReadsAndExplicitUnverifiedPublicationNeverBorrowPreviousTime() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertUnknown(ledger.observe("A", 0));
    assertTrue(ledger.activate("A", 7));
    assertTrue(ledger.publish("A", 7, ConnectionEvidence.AUTHENTICATED, 100));
    var authenticated = ledger.observe("A", 7);
    var wrongOwner = ledger.observe("B", 7);
    var wrongEpoch = ledger.observe("A", 8);
    assertUnknown(wrongOwner);
    assertUnknown(wrongEpoch);
    assertFalse(ledger.current(wrongOwner));
    assertFalse(ledger.current(wrongEpoch));
    assertSame(authenticated, ledger.observe("A", 7));

    assertTrue(ledger.publish("A", 7, ConnectionEvidence.UNVERIFIED, 999));
    assertUnknown(ledger.observe("A", 7));
    assertFalse(ledger.current(authenticated));
  }

  @Test
  public void disconnectAndRetireOnlyInvalidateTheirOwnObservation() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(ledger.activate("A", 8));
    assertTrue(ledger.publish("A", 8, ConnectionEvidence.AUTHENTICATED, 100));
    var connected = ledger.observe("A", 8);
    ledger.disconnect("A");
    assertFalse(ledger.current(connected));
    var disconnected = ledger.observe("A", 8);
    assertUnknown(disconnected);
    assertTrue(ledger.current(disconnected));
    ledger.retire("A");
    assertFalse(ledger.current(disconnected));
    assertFalse(ledger.publish("A", 8, ConnectionEvidence.AUTHENTICATED, 200));
    assertUnknown(ledger.observe("A", 8));
    assertFalse(ledger.activate("B", 7));
    assertTrue(ledger.activate("B", 8));
    assertUnknown(ledger.observe("B", 8));
  }

  @Test
  public void observationIdentityCannotBeRebuiltFromMatchingOwnerAndEpoch() {
    var first = new DeviceChannelFacts.ConnectionLedger();
    var second = new DeviceChannelFacts.ConnectionLedger();
    assertTrue(first.activate("same-owner", 9));
    assertTrue(second.activate("same-owner", 9));
    assertTrue(first.publish("same-owner", 9, ConnectionEvidence.AUTHENTICATED, 100));
    assertTrue(second.publish("same-owner", 9, ConnectionEvidence.AUTHENTICATED, 100));
    var observed = first.observe("same-owner", 9);
    assertFalse(second.current(observed));
    assertFalse(first.current(null));
    assertTrue(first.activate("same-owner", 9));
    assertFalse(first.current(observed));
    assertUnknown(first.observe("same-owner", 9));
  }

  @Test
  public void invalidOrOlderPublicationCannotChangeCurrentEvidence() {
    var ledger = new DeviceChannelFacts.ConnectionLedger();
    assertFalse(ledger.activate(null, 1));
    assertFalse(ledger.activate("", 1));
    assertFalse(ledger.activate("A", -1));
    assertTrue(ledger.activate("A", 10));
    assertTrue(ledger.publish("A", 10, ConnectionEvidence.TCP_REACHABLE, 200));
    var tcp = ledger.observe("A", 10);
    assertFalse(ledger.publish("A", 10, ConnectionEvidence.AUTHENTICATED, 199));
    assertFalse(ledger.publish("A", 10, ConnectionEvidence.AUTHENTICATED, -1));
    assertFalse(ledger.publish("A", 10, ConnectionEvidence.UNVERIFIED, -2));
    assertFalse(ledger.publish("A", 10, null, 300));
    assertSame(tcp, ledger.observe("A", 10));
    assertTrue(ledger.current(tcp));
    assertEquals(ConnectionEvidence.TCP_REACHABLE, tcp.evidence());
  }

  private static void assertUnknown(DeviceChannelFacts.ConnectionLedger.Observation observation) {
    assertEquals(ConnectionEvidence.UNVERIFIED, observation.evidence());
    assertEquals(-1L, observation.observedAtMillis());
    assertFalse(observation.connected());
  }

  private static DeviceChannelFacts adb(
      ConnectionEvidence evidence, boolean requestedRoot, int uid, boolean businessGranted) {
    return new DeviceChannelFacts(
        Channel.ADB,
        true,
        true,
        true,
        false,
        true,
        evidence,
        requestedRoot,
        uid,
        businessGranted,
        300,
        8);
  }

  private static DeviceChannelFacts facts(
      Channel channel, ConnectionEvidence evidence, int uid, long time, long revision) {
    return new DeviceChannelFacts(
        channel, false, false, false, false, false, evidence, false, uid, false, time, revision);
  }
}
