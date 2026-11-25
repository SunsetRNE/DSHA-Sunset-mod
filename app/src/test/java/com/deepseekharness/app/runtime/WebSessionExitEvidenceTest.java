package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.WebSessionProof;
import com.deepseekharness.app.util.WebStopDiagnostic;
import com.deepseekharness.app.util.WebStopException;
import java.io.IOException;
import org.junit.Test;

/** A reused PID proves the saved process exited even when the replacement hides its environment. */
public final class WebSessionExitEvidenceTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final String WEB = "node /usr/local/bin/dsh web";
  private static final WebSessionProof.Saved SAVED =
      new WebSessionProof.Saved(BOOT, 7, BOOT, "ab".repeat(32), 42, 123, WEB, 41, 122, "launcher");

  private static final class Kernel extends WebProcessManager {
    long webBirth = 123, launcherBirth = 122;
    int inspections;
    boolean environmentDenied = true, kernelDenied, gone, verified = true;
    boolean launcherEnvironmentDenied, launcherCommandDenied;
    Integer uid = 10001;
    Kind kind = Kind.WEB;
    String boot = BOOT;

    Kernel() {
      super(null);
    }

    @Override
    String recoveryBoot() {
      return boot;
    }

    @Override
    int recoveryHostUid() {
      return 10001;
    }

    @Override
    int recoveryHostPid() {
      return 100;
    }

    @Override
    String[] recoveryProcessEntries() {
      return new String[] {"42"};
    }

    @Override
    Integer processOwner(int pid) {
      return uid;
    }

    @Override
    ProcessState inspect(int pid) throws IOException {
      inspections++;
      if (kernelDenied || pid == 41 && launcherCommandDenied) throw new IOException("PROC_DENIED");
      if (gone) return new ProcessState(Kind.GONE, null, "");
      if (kind == Kind.DENIED) return new ProcessState(kind, null, "");
      long born = pid == 42 ? webBirth : launcherBirth;
      var identity = WebPidIdentity.parse(stat(pid, born), pid);
      return new ProcessState(kind, identity, pid == 42 ? WEB : "launcher-replacement");
    }

    @Override
    String readProcessFile(int pid, String name) throws IOException {
      if (name.equals("stat")) {
        if (kernelDenied) throw new IOException("STAT_DENIED");
        return stat(pid, pid == 42 ? webBirth : launcherBirth);
      }
      assertEquals("environ", name);
      if (environmentDenied || pid == 41 && launcherEnvironmentDenied)
        throw new IOException("ENVIRON_DENIED");
      return "DSHA_WEB_GENERATION=7\0DSH_TASK_INSTANCE="
          + BOOT
          + "\0DSH_TASK_TOKEN="
          + "ab".repeat(32)
          + "\0";
    }

    @Override
    public boolean verifySession(WebSessionProof.Saved saved) {
      return verified;
    }
  }

  private static String stat(int pid, long born) {
    return pid + " (node) S 1 1 1 " + "0 ".repeat(15) + born + " 0 0";
  }

  @Test
  public void reusedWebBirthDoesNotNeedReplacementEnvironment() {
    Kernel kernel = new Kernel();
    kernel.webBirth++;
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
  }

  @Test
  public void reusedLauncherBirthDoesNotKeepMaintenanceBlocked() throws Exception {
    Kernel kernel = new Kernel();
    kernel.launcherBirth++;
    assertTrue(kernel.recoveredLauncherExited(SAVED));
  }

  @Test
  public void survivingWebCanReconnectAfterSameUidLauncherPidReuse() {
    Kernel kernel = new Kernel();
    kernel.verified =
        false; // Old PID/identity files unavailable, only the encrypted record remains.
    kernel.environmentDenied = false;
    kernel.launcherBirth++;
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeRecoveredSession(SAVED));
    kernel.launcherEnvironmentDenied = true;
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeRecoveredSession(SAVED));
    kernel.launcherCommandDenied = true; // Only the new birth is visible, cmdline cannot be read.
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeRecoveredSession(SAVED));
    kernel.launcherBirth--;
    assertEquals(
        WebProcessManager.SessionObservation.UNKNOWN, kernel.observeRecoveredSession(SAVED));
  }

  @Test
  public void sameBirthWithUnreadableEnvironmentRemainsUnknown() {
    Kernel kernel = new Kernel();
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(SAVED));
    assertThrows(IOException.class, () -> kernel.recoveredLauncherExited(SAVED));
  }

  @Test
  public void unreadableKernelDoesNotTurnAStaleClaimIntoExit() {
    Kernel kernel = new Kernel();
    kernel.webBirth++;
    kernel.launcherBirth++;
    kernel.kernelDenied = true;
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(SAVED));
    assertThrows(IOException.class, () -> kernel.recoveredLauncherExited(SAVED));
  }

  @Test
  public void liveSessionStillRequiresCompleteVerification() throws Exception {
    Kernel kernel = new Kernel();
    kernel.environmentDenied = false;
    assertEquals(WebProcessManager.SessionObservation.LIVE, kernel.observeSession(SAVED));
    assertFalse(kernel.recoveredLauncherExited(SAVED));
    kernel.verified = false;
    assertEquals(WebProcessManager.SessionObservation.UNKNOWN, kernel.observeSession(SAVED));
  }

  @Test
  public void knownExitAndBootChangeRetireWithoutEnvironmentAccess() throws Exception {
    Kernel kernel = new Kernel();
    kernel.gone = true;
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
    kernel.gone = false;
    kernel.kernelDenied = true;
    kernel.boot = "87654321-1234-1234-1234-123456789abc";
    assertEquals(WebProcessManager.SessionObservation.EXITED, kernel.observeSession(SAVED));
    assertTrue(kernel.recoveredLauncherExited(SAVED));
  }

  @Test
  public void deniedOwnedScanIsUnknownInsteadOfClaimingALiveWeb() {
    Kernel kernel = new Kernel();
    kernel.kind = WebProcessManager.Kind.DENIED;
    WebStopException failure = assertThrows(WebStopException.class, kernel::hasOwnedWeb);
    assertEquals(WebStopDiagnostic.Code.OWNED_WEB_SCAN_UNCONFIRMED, failure.diagnostic().code());
    assertEquals(1, kernel.inspections);
  }

  @Test
  public void readableOwnedWebAndUnrelatedProcessRemainDistinct() throws Exception {
    Kernel kernel = new Kernel();
    assertTrue(kernel.hasOwnedWeb());
    kernel.kind = WebProcessManager.Kind.OTHER;
    assertFalse(kernel.hasOwnedWeb());
    kernel.kind = WebProcessManager.Kind.DENIED;
    kernel.uid = 10002;
    assertFalse(kernel.hasOwnedWeb());
  }
}
