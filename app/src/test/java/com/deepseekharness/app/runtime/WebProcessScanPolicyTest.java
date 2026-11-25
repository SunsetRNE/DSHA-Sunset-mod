package com.deepseekharness.app.runtime;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;

import static org.junit.Assert.*;

/** Isolated policy fixture: hidden foreign processes must not impersonate app Web. */
public final class WebProcessScanPolicyTest {
  @Rule public TemporaryFolder directory = new TemporaryFolder();

  @Test
  public void idleCheckLeavesWatchdogSentinelAbsentWithUnknownAndExitedWeb() throws Exception {
    File root = directory.newFolder("guest-root");
    File sentinel = new File(root, ".dsha-stopped");
    boolean[] owned = {true};
    var manager =
        new WebProcessManager(null) {
          @Override
          File root() {
            return root;
          }

          @Override
          boolean observeOwnedWeb() {
            return owned[0];
          }
        };

    assertFalse(manager.observeStopped(false));
    assertFalse(sentinel.exists());
    owned[0] = false;
    assertTrue(manager.observeStopped(false));
    assertFalse(sentinel.exists());
  }

  @Test
  public void unknownDeniedPidIsNotAssumedToBeAppWeb() {
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.DENIED, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.OTHER, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.DENIED, true, 10123, 10042));
  }

  @Test
  public void knownAppUidDenialAndReadableUnknownWebRemainFailClosed() {
    assertTrue(
        WebProcessManager.globalScanUnconfirmed(
            WebProcessManager.Kind.DENIED, false, 10042, 10042));
    assertTrue(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.WEB, false, null, 10042));
    assertFalse(
        WebProcessManager.globalScanUnconfirmed(WebProcessManager.Kind.OTHER, false, 10042, 10042));
  }
}
