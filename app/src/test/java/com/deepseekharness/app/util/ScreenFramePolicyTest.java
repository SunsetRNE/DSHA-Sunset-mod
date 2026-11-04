package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

public final class ScreenFramePolicyTest {
  @Test
  public void noImplicitGameListAndExactPackageOnly() {
    assertEquals(Set.of(), ScreenFramePolicy.packages(""));
    assertFalse(ScreenFramePolicy.animated("", "com.example.game"));
    assertTrue(ScreenFramePolicy.animated("com.example.game", "com.example.game"));
    assertFalse(ScreenFramePolicy.animated("com.example.game", "com.example.game.billing"));
    assertFalse(ScreenFramePolicy.animated("com.example.game", "com.example"));
    assertFalse(ScreenFramePolicy.animated("com.example.game", "com.Example.game"));
  }

  @Test
  public void canonicalConfigurationDeduplicatesButRejectsWildcardsAndMalformedNames() {
    assertEquals(
        "com.example.game\norg.example.puzzle",
        ScreenFramePolicy.normalizePackages(
            " com.example.game, org.example.puzzle;\ncom.example.game "));
    for (String bad : new String[] {"*", "com.example.*", "com.example/game", "single", "a..b"}) {
      assertThrows(IllegalArgumentException.class, () -> ScreenFramePolicy.packages(bad));
      assertFalse(ScreenFramePolicy.animated(bad, "com.example.game"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ScreenFramePolicy.packages(" ".repeat(ScreenFramePolicy.CONFIG_LIMIT + 1) + "a.b"));
    String many =
        java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> "example.app" + i)
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThrows(IllegalArgumentException.class, () -> ScreenFramePolicy.packages(many));
  }

  @Test
  public void realTimeBoundaryRejectsExpiredFutureAndOverflowedEvidence() {
    assertTrue(ScreenFramePolicy.recent(100, 5_100, true));
    assertFalse(ScreenFramePolicy.recent(100, 5_101, true));
    assertTrue(ScreenFramePolicy.recent(100, 30_100, false));
    assertFalse(ScreenFramePolicy.recent(100, 30_101, false));
    assertFalse(ScreenFramePolicy.recent(100, 99, true));
    assertFalse(ScreenFramePolicy.recent(-1, 0, true));
    assertFalse(ScreenFramePolicy.recent(1, Long.MAX_VALUE, true));
  }

  @Test
  public void dynamicPixelsCannotOverrideGeometryOrObservationIdentityAndRevision() {
    var clock = new java.util.concurrent.atomic.AtomicLong(100);
    var ledger = new ScreenObservationLedger(clock::get);
    var target = new ScreenTarget(0, 7, "com.example.game");
    var region = ScreenWindowRegion.full(100, 200);
    var settings = new ScreenFramePolicy.Settings("com.example.game", 1);
    var seen = ledger.publish(target, 4, "run", 100, 200, 0, "old pixels", region, 100, settings);
    assertTrue(ScreenFramePolicy.pixelsAllowed(seen, 100, 200, 0, "animated pixels", region));
    assertFalse(ScreenFramePolicy.pixelsAllowed(seen, 100, 200, 1, "animated pixels", region));
    assertFalse(ScreenFramePolicy.pixelsAllowed(seen, 200, 100, 0, "animated pixels", region));
    assertFalse(
        ScreenFramePolicy.pixelsAllowed(
            seen,
            100,
            200,
            0,
            "animated pixels",
            new ScreenWindowRegion(
                region.window(), new ScreenWindowRegion.Bounds(0, 10, 100, 200), false)));
    assertNull(ScreenFramePolicy.observationRejection(seen, settings, 5_100));
    assertEquals(
        "OBSERVATION_TIMEOUT", ScreenFramePolicy.observationRejection(seen, settings, 5_101));
    assertEquals(
        "OBSERVATION_POLICY_REVOKED",
        ScreenFramePolicy.observationRejection(
            seen, new ScreenFramePolicy.Settings("com.example.game", 3), 200));
    assertNull(ledger.consume(seen.id, new ScreenTarget(0, 8, "com.example.game"), 4, "run"));
    assertNull(ledger.consume(seen.id, target, 4, "run"));
  }

  @Test
  public void encodingDelayDoesNotRenewCaptureTimeAndStrictPixelCheckRemainsExact() {
    var clock = new java.util.concurrent.atomic.AtomicLong(6_100);
    var ledger = new ScreenObservationLedger(clock::get);
    var target = new ScreenTarget(0, 7, "com.example.game");
    var region = ScreenWindowRegion.full(100, 200);
    var dynamic = new ScreenFramePolicy.Settings("com.example.game", 1);
    var old = ledger.publish(target, 4, "run", 100, 200, 0, "a", region, 100, dynamic);
    assertEquals(
        "OBSERVATION_TIMEOUT", ScreenFramePolicy.observationRejection(old, dynamic, clock.get()));
    assertNotNull(ledger.consume(old.id, target, 4, "run"));
    assertNull(ledger.consume(old.id, target, 4, "run"));
    var strict = ledger.publish(target, 4, "run", 100, 200, 0, "a", region);
    assertTrue(ScreenFramePolicy.pixelsAllowed(strict, 100, 200, 0, "a", region));
    assertFalse(ScreenFramePolicy.pixelsAllowed(strict, 100, 200, 0, "b", region));
    assertEquals(
        "OBSERVATION_POLICY_REVOKED",
        ScreenFramePolicy.observationRejection(strict, dynamic, clock.get()));
  }

  @Test
  public void revisionCannotRegressOrChangeMeaningAfterCoreHasObservedIt() {
    var current = new ScreenFramePolicy.Settings("", 2);
    assertEquals(
        "SCREEN_FRAME_POLICY_STALE",
        ScreenFramePolicy.settingsRejection(
            current, new ScreenFramePolicy.Settings("com.example.game", 1)));
    assertEquals(
        "SCREEN_FRAME_POLICY_CONFLICT",
        ScreenFramePolicy.settingsRejection(
            current, new ScreenFramePolicy.Settings("com.example.game", 2)));
    assertEquals(
        "SCREEN_FRAME_POLICY_INVALID",
        ScreenFramePolicy.settingsRejection(current, new ScreenFramePolicy.Settings("", -1)));
    assertNull(ScreenFramePolicy.settingsRejection(current, current));
    assertNull(
        ScreenFramePolicy.settingsRejection(
            current, new ScreenFramePolicy.Settings("com.example.game", 3)));
  }
}
