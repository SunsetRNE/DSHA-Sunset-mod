package com.deepseekharness.app.remote.ui;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WorkspaceEntryTest {
  @Test public void launcherOffersRemoteBeforeAnyLocalInstallationAndExplicitLocalKeepsGate() {
    assertTrue(WorkspaceEntry.choose(false, true, true));
    assertFalse(WorkspaceEntry.choose(true, true, true));
    assertFalse(WorkspaceEntry.choose(false, true, false));
    assertFalse(WorkspaceEntry.choose(false, false, true));
    assertFalse(WorkspaceEntry.choose(false, false, false));
  }
}
