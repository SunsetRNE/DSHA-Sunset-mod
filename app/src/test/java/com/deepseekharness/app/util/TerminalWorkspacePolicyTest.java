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
  public void minimalModeKeepsAllWindowWidthsForTerminal() {
    assertFalse(TerminalWorkspacePolicy.sidebar(false, 1200));
    assertFalse(TerminalWorkspacePolicy.sidebar(true, 839));
    assertTrue(TerminalWorkspacePolicy.sidebar(true, 840));
  }

  @Test
  public void drawerWidthKeepsScrimMarginAndFallsBackBeforeMeasurement() {
    assertEquals(280, TerminalWorkspacePolicy.drawerWidth(1000, 280, 32));
    assertEquals(280, TerminalWorkspacePolicy.drawerWidth(320, 280, 32));
    assertEquals(208, TerminalWorkspacePolicy.drawerWidth(240, 280, 32));
    assertEquals(280, TerminalWorkspacePolicy.drawerWidth(0, 280, 32));
    assertEquals(280, TerminalWorkspacePolicy.drawerWidth(16, 280, 32));
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
