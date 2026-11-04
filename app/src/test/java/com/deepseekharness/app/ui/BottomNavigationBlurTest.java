package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public class BottomNavigationBlurTest {
  @Test
  public void gaussianBlurKeepsDimensionsAndBlendsSharpBoundary() {
    Bitmap source = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
    source.eraseColor(Color.BLACK);
    for (int y = 0; y < 9; y++) for (int x = 5; x < 9; x++) source.setPixel(x, y, Color.WHITE);

    Bitmap blurred = BottomNavigationBlur.gaussianBlur(source);

    assertNotSame(source, blurred);
    assertEquals(9, blurred.getWidth());
    assertEquals(9, blurred.getHeight());
    assertTrue(Color.red(blurred.getPixel(4, 4)) > 0);
    assertTrue(Color.red(blurred.getPixel(4, 4)) < 255);
    source.recycle();
    blurred.recycle();
  }
}
