package com.deepseekharness.app.control;

import static org.junit.Assert.*;
import com.deepseekharness.app.util.MainScreenWindowPolicy;
import com.deepseekharness.app.util.ScreenWindowRegion;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public final class OwnedScreenOverlayTest {
  private final ScreenWindowRegion.Bounds full = new ScreenWindowRegion.Bounds(0, 0, 100, 200);
  private final ScreenWindowRegion.Bounds compact = new ScreenWindowRegion.Bounds(70, 80, 95, 120);

  @After
  public void clear() {
    OwnedScreenOverlay.clear("test");
  }

  @Test
  public void onlyExactNativeWindowPackageAndGeometryCanBeExcluded() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    assertNotNull(OwnedScreenOverlay.match(7, compact, true));
    assertNull(OwnedScreenOverlay.match(8, compact, true));
    assertNull(OwnedScreenOverlay.match(7, compact, false));
    assertNull(OwnedScreenOverlay.match(7, new ScreenWindowRegion.Bounds(71, 80, 96, 120), true));
    OwnedScreenOverlay.publish("test", 7, compact, true);
    assertNull(OwnedScreenOverlay.match(7, compact, true));
    assertTrue(OwnedScreenOverlay.expanded());
  }

  @Test
  public void selfOverlayPermitsOtherPixelsButNoTouchPathThroughIt() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    var own = OwnedScreenOverlay.match(7, compact, true);
    var app =
        new MainScreenWindowPolicy.Window(
            0, 2, 1, MainScreenWindowPolicy.Kind.APPLICATION, true, true, full);
    var overlay =
        new MainScreenWindowPolicy.Window(
            0, 7, 4, MainScreenWindowPolicy.Kind.UNSUPPORTED, false, false, compact, own);
    var region = MainScreenWindowPolicy.region(0, List.of(app, overlay));
    assertFalse(region.obscured());
    assertEquals(1, region.exclusions().size());
    assertTrue(region.containsTrajectory(20, 20, 20, 160, 1));
    assertFalse(region.containsTrajectory(80, 90, 80, 90, 1));
    assertFalse(region.containsTrajectory(40, 100, 99, 100, 1));
    assertFalse(region.containsTrajectory(69, 90, 69, 90, 1));
    var foreign =
        new MainScreenWindowPolicy.Window(
            0, 8, 5, MainScreenWindowPolicy.Kind.UNSUPPORTED, false, false, compact);
    assertTrue(MainScreenWindowPolicy.region(0, List.of(app, overlay, foreign)).obscured());
  }

  @Test
  public void overlayMoveAndRecreationInvalidateOldObservationEvenAtSameBounds() {
    OwnedScreenOverlay.publish("test", 7, compact, false);
    var first = OwnedScreenOverlay.match(7, compact, true);
    OwnedScreenOverlay.clear("test");
    OwnedScreenOverlay.publish("test", 7, compact, false);
    assertNotEquals(first, OwnedScreenOverlay.match(7, compact, true));
  }
}
