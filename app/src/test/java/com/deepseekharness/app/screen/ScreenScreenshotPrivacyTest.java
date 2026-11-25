package com.deepseekharness.app.screen;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import com.deepseekharness.app.util.ScreenCaptureDelivery;
import com.deepseekharness.app.util.ScreenWindowRegion;
import com.deepseekharness.app.util.ScreenWindowRegion.Bounds;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ScreenScreenshotPrivacyTest {
  private static Bitmap filled(int width, int height, int color) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    return bitmap;
  }

  @Test
  public void splitScreenAndKeyboardAreAbsentFromEncodedPixels() throws Exception {
    var window = new Bounds(4, 0, 8, 6);
    var region =
        new ScreenWindowRegion(
            window,
            new Bounds(4, 1, 8, 5),
            false,
            List.of(new ScreenWindowRegion.Exclusion(71, 1, new Bounds(5, 2, 7, 3))));
    Bitmap source = filled(8, 6, Color.RED);
    for (int y = 1; y < 5; y++) for (int x = 4; x < 8; x++) source.setPixel(x, y, Color.GREEN);
    try (var shot = new ScreenScreenshotCapture.Shot(source, 0, region)) {
      var bytes = new ByteArrayOutputStream();
      assertTrue(shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
      byte[] encoded = bytes.toByteArray();
      Bitmap returned = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
      try {
        for (int y = 0; y < 6; y++)
          for (int x = 0; x < 8; x++) {
            boolean target = x >= 4 && y >= 1 && y < 5 && !(y == 2 && (x == 5 || x == 6));
            assertEquals(
                "pixel " + x + "," + y,
                target ? Color.GREEN : Color.BLACK,
                returned.getPixel(x, y));
          }
      } finally {
        returned.recycle();
      }
    }
  }

  @Test
  public void windowSurfaceKeepsPhysicalCoordinatesAndTargetOnlyStillWorks() throws Exception {
    var window = new Bounds(2, 1, 6, 5);
    var region = new ScreenWindowRegion(window, window, false);
    Bitmap input = filled(4, 4, Color.GREEN);
    try {
      Bitmap positioned = ScreenScreenshotCapture.positionWindow(input, region, new Point(8, 6));
      try (var shot = new ScreenScreenshotCapture.Shot(positioned, 0, region)) {
        assertEquals(Color.BLACK, shot.bitmap.getPixel(0, 0));
        assertEquals(Color.GREEN, shot.bitmap.getPixel(2, 1));
        assertEquals(Color.GREEN, shot.bitmap.getPixel(5, 4));
        assertEquals(Color.BLACK, shot.bitmap.getPixel(6, 4));
      }
      assertThrows(
          IllegalStateException.class,
          () ->
              ScreenScreenshotCapture.positionWindow(
                  input, ScreenWindowRegion.full(8, 6), new Point(8, 6)));
      var full = ScreenWindowRegion.full(4, 4);
      assertSame(input, ScreenScreenshotCapture.positionWindow(input, full, new Point(4, 4)));
      try (var shot = new ScreenScreenshotCapture.Shot(input, 0, full)) {
        assertEquals(Color.GREEN, shot.bitmap.getPixel(3, 3));
      }
    } finally {
      if (!input.isRecycled()) input.recycle();
    }
  }

  @Test
  public void missingOrObscuredGeometryProducesNoShot() {
    var full = new Bounds(0, 0, 8, 6);
    Bitmap source = filled(8, 6, Color.RED);
    try {
      assertEquals(
          "SCREEN_WINDOW_GEOMETRY_UNAVAILABLE",
          assertThrows(
                  IllegalStateException.class,
                  () -> new ScreenScreenshotCapture.Shot(source, 0, null))
              .getMessage());
      assertEquals(
          "SCREEN_TARGET_WINDOW_OCCLUDED",
          assertThrows(
                  IllegalStateException.class,
                  () ->
                      new ScreenScreenshotCapture.Shot(
                          source, 0, new ScreenWindowRegion(full, full, true)))
              .getMessage());
    } finally {
      source.recycle();
    }
  }

  @Test
  public void revokeAfterDecodeBeforePickupReleasesBitmap() throws Exception {
    AtomicBoolean allowed = new AtomicBoolean(true);
    var input = filled(4, 4, Color.GREEN);
    var shot = new ScreenScreenshotCapture.Shot(input, 0, ScreenWindowRegion.full(4, 4));
    try (var pending =
        new ScreenCaptureDelivery<>(allowed::get, ScreenScreenshotCapture.Shot::close)) {
      pending.complete(shot);
      allowed.set(false);
      assertNull(pending.await(1000));
      assertTrue(input.isRecycled());
    }
  }
}
