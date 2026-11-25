package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameRatePolicyTest {
  @Test
  public void selectsConfigured120Hz() {
    assertEquals(
        120f, FrameRatePolicy.select(false, 120f, new float[] {60f, 90f, 120f, 144f}), 0.01f);
  }

  @Test
  public void neverExceedsUserPeak() {
    assertEquals(90f, FrameRatePolicy.select(false, 90f, new float[] {60f, 90f, 120f}), 0.01f);
    assertEquals(0f, FrameRatePolicy.select(false, 60f, new float[] {60f, 90f, 120f}), 0.01f);
  }

  @Test
  public void missingSettingUsesConservative120HzCap() {
    assertEquals(120f, FrameRatePolicy.select(false, 0f, new float[] {60f, 120f, 144f}), 0.01f);
  }

  @Test
  public void powerSaverLeavesPolicyToSystem() {
    assertEquals(0f, FrameRatePolicy.select(true, 120f, new float[] {60f, 120f}), 0.01f);
  }

  @Test
  public void ignoresInvalidAndOtherResolutionInputRates() {
    assertEquals(
        119.88f,
        FrameRatePolicy.select(false, Float.NaN, new float[] {Float.NaN, -1f, 60f, 119.88f}),
        0.01f);
  }

  @Test
  public void invalidPeakSettingsUseTheSameBoundedDefault() {
    for (float peak :
        new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
      assertEquals(
          120f, FrameRatePolicy.select(false, peak, new float[] {60f, 90f, 120f, 165f}), 0.01f);
  }

  @Test
  public void highSystemPeakStillCapsApplicationAt120Hz() {
    assertEquals(
        120f, FrameRatePolicy.select(false, 165f, new float[] {60f, 90f, 120f, 165f}), 0.01f);
  }

  @Test
  public void missingOrUnsupportedRatesLeavePolicyToSystem() {
    assertEquals(0f, FrameRatePolicy.select(false, 120f, null), 0f);
    assertEquals(0f, FrameRatePolicy.select(false, 120f, new float[0]), 0f);
    assertEquals(
        0f,
        FrameRatePolicy.select(
            false,
            120f,
            new float[] {
              Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -90f, 60f
            }),
        0f);
  }

  @Test
  public void capToleranceOnlyAcceptsPanelFloatingPointRounding() {
    assertEquals(
        90.00001f, FrameRatePolicy.select(false, 90f, new float[] {60f, 90.00001f}), 0.001f);
    assertEquals(0f, FrameRatePolicy.select(false, 90f, new float[] {60f, 90.4f}), 0f);
  }
}
