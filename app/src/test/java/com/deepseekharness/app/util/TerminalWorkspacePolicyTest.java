package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TerminalWorkspacePolicyTest {
  @Test
  public void pageBackNeverRepresentsProcessEnd() {
    assertEquals(
        TerminalWorkspacePolicy.Back.LEAVE_PAGE, TerminalWorkspacePolicy.back(false, false, false));
    assertEquals(
        TerminalWorkspacePolicy.Back.HIDE_IME, TerminalWorkspacePolicy.back(false, false, true));
    assertEquals(
        TerminalWorkspacePolicy.Back.LEAVE_SELECTION,
        TerminalWorkspacePolicy.back(false, true, true));
    assertEquals(
        TerminalWorkspacePolicy.Back.DISMISS_PANEL, TerminalWorkspacePolicy.back(true, true, true));
  }

  @Test
  public void inlineNeedsWideWindowAndKeepsTerminalWidth() {
    assertFalse(TerminalWorkspacePolicy.canInline(839));
    assertTrue(TerminalWorkspacePolicy.canInline(840));
    assertTrue(TerminalWorkspacePolicy.canInline(1024));
    assertEquals(240, TerminalWorkspacePolicy.inlineWidth(840));
    assertEquals(256, TerminalWorkspacePolicy.inlineWidth(1024));
    assertEquals(296, TerminalWorkspacePolicy.inlineWidth(1280));
    assertEquals(296, TerminalWorkspacePolicy.inlineWidth(1600));
    assertTrue(840 - TerminalWorkspacePolicy.inlineWidth(840) >= 480);
  }

  @Test
  public void drawerWidthUsesTabletMaxAndScrimMargin() {
    assertEquals(336, TerminalWorkspacePolicy.drawerMaxWidth());
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(1000, 336, 32));
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(368, 336, 32));
    assertEquals(288, TerminalWorkspacePolicy.drawerWidth(320, 336, 32));
    assertEquals(208, TerminalWorkspacePolicy.drawerWidth(240, 336, 32));
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(0, 336, 32));
    assertEquals(336, TerminalWorkspacePolicy.drawerWidth(16, 336, 32));
  }

  @Test
  public void shortWindowsSwitchToCompactAndDenseChrome() {
    assertTrue(TerminalWorkspacePolicy.compactHeight(559));
    assertFalse(TerminalWorkspacePolicy.compactHeight(560));
    assertTrue(TerminalWorkspacePolicy.denseFooter(479));
    assertFalse(TerminalWorkspacePolicy.denseFooter(480));
    assertTrue(TerminalWorkspacePolicy.shortLandscape(1422, 889));
    assertTrue(TerminalWorkspacePolicy.shortLandscape(800, 400));
    assertFalse(TerminalWorkspacePolicy.shortLandscape(800, 889));
    assertFalse(TerminalWorkspacePolicy.shortLandscape(1600, 1200));
  }

  @Test
  public void processEpochPreventsOldIdReclaimingNewSession() {
    assertFalse(TerminalWorkspacePolicy.restoreSelection("old", "new", 1, true));
    assertFalse(TerminalWorkspacePolicy.restoreSelection(null, "new", 1, true));
    assertFalse(TerminalWorkspacePolicy.restoreSelection("new", "new", 1, false));
    assertFalse(TerminalWorkspacePolicy.restoreSelection("new", "new", 0, true));
    assertTrue(TerminalWorkspacePolicy.restoreSelection("new", "new", 1, true));
  }

  @Test
  public void floatingMenuNeverLeavesShortKeyboardWindow() {
    for (int height : new int[] {80, 160, 320, 640}) {
      for (float fraction : new float[] {-1, 0, .6f, 1, 4, Float.NaN}) {
        int y = TerminalWorkspacePolicy.ballY(height, 52, 12, 100, fraction);
        assertTrue(y >= 0);
        assertTrue(y + 52 <= height);
      }
    }
  }

  @Test
  public void ballUsesWholeTouchTargetAndClampsInvalidSavedState() {
    assertEquals(12, TerminalWorkspacePolicy.ballX(360, 52, 12, false));
    assertEquals(296, TerminalWorkspacePolicy.ballX(360, 52, 12, true));
    assertEquals(.6f, TerminalWorkspacePolicy.fraction(Float.NaN), 0f);
    assertEquals(0f, TerminalWorkspacePolicy.fraction(-3), 0f);
    assertEquals(1f, TerminalWorkspacePolicy.fraction(3), 0f);
  }
}
