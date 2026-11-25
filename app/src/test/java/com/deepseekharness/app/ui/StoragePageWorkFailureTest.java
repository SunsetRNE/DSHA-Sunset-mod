package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.StorageMaintenance;
import java.io.IOException;
import java.io.InterruptedIOException;
import org.junit.Test;

public final class StoragePageWorkFailureTest {
  private static final String PRIVATE = "/private/workspace/secret.txt";
  private static final String GATE = "util.MaintenanceGate";
  private static final String PORTS = "core.MaintenanceCoordinator$AndroidPorts";

  @Test
  public void gateThrowHasAdmissionHintWithoutAnOwnerOrPrivateMessage() {
    var failure =
        withTrace(
            new IOException(PRIVATE),
            site(GATE, "exclusive", 101),
            site("backup.StorageMaintenance", "clean", 726));
    var classified = StoragePageWork.classifyFailure(failure);
    assertEquals("IO_FAILURE", classified.originalCode());
    assertEquals("IO_FAILURE", classified.code());
    assertEquals(StoragePageWork.FailureStage.ADMISSION, classified.stage());
    assertEquals(R.string.storage_failure_maintenance_admission, classified.hint());
    String diagnostic = classified.diagnostic(StoragePageWork.Kind.CLEANUP, null, 1234, 89);
    assertTrue(diagnostic.contains("operation=CLEANUP stage=ADMISSION atMs=1234 elapsedMs=89"));
    assertTrue(diagnostic.contains("originalCode=IO_FAILURE"));
    assertTrue(diagnostic.contains("exception=IOException"));
    assertTrue(diagnostic.contains("util.MaintenanceGate.exclusive:101"));
    assertTrue(diagnostic.contains("progress=NONE"));
    assertFalse(diagnostic.contains(PRIVATE));
    assertFalse(diagnostic.contains("owner="));
  }

  @Test
  public void knownExitBoundariesChooseDistinctHints() {
    assertExit(
        "stopTerminals",
        StoragePageWork.FailureStage.TERMINAL_STOP,
        R.string.storage_failure_terminal_exit);
    assertExit("stopWeb", StoragePageWork.FailureStage.WEB_STOP, R.string.storage_failure_web_exit);
    assertExit(
        "confirmOtherProcesses",
        StoragePageWork.FailureStage.BACKGROUND_EXIT,
        R.string.storage_failure_background_exit);
    var retained =
        withTrace(
            new IOException("BOUNDED_GUEST_RECORDS_RETAINED:" + PRIVATE),
            site("runtime.BoundedGuestSessions", "reapExited", 205),
            site(PORTS, "confirmOtherProcesses", 212),
            site(GATE, "exclusive", 118));
    var detail = StoragePageWork.classifyFailure(retained);
    assertEquals(StoragePageWork.FailureStage.BACKGROUND_EXIT, detail.stage());
    String event = detail.diagnostic(StoragePageWork.Kind.EXPAND, null, 100, 9);
    assertTrue(event.contains("runtime.BoundedGuestSessions.reapExited:205"));
    assertFalse(event.contains(PRIVATE));
  }

  @Test
  public void gateOnStackDoesNotRelabelAFileFailure() {
    var failure =
        withTrace(
            new IOException(PRIVATE),
            site("backup.AndroidBackupFileSystem", "stat", 92),
            site("backup.StorageMaintenance", "volume", 356),
            site(GATE, "exclusive", 121));
    var detail = StoragePageWork.classifyFailure(failure);
    assertEquals(StoragePageWork.FailureStage.FILE_WORK, detail.stage());
    assertEquals(0, detail.hint());

    var unknown =
        withTrace(
            new IOException(PRIVATE),
            site("runtime.OtherClass", "unknown", 8),
            site(GATE, "exclusive", 121),
            site("ui.StoragePageWork", "run", 199));
    detail = StoragePageWork.classifyFailure(unknown);
    assertEquals(StoragePageWork.FailureStage.OTHER, detail.stage());
    assertEquals(0, detail.hint());
    assertEquals("run", detail.site().getMethodName());
  }

  @Test
  public void preservesStableCodesAndNormalizesAnActualJavaInterrupt() {
    var stable = withTrace(new IOException("PERMISSION_DENIED"), site(PORTS, "stopWeb", 201));
    var detail = StoragePageWork.classifyFailure(stable);
    assertEquals(StoragePageWork.FailureStage.WEB_STOP, detail.stage());
    assertEquals("PERMISSION_DENIED", detail.code());
    assertEquals(0, detail.hint());

    var cancelled = withTrace(new InterruptedIOException("CANCELLED"), site(PORTS, "stopWeb", 201));
    detail = StoragePageWork.classifyFailure(cancelled);
    assertEquals("CANCELLED", detail.originalCode());
    assertEquals("CANCELLED", detail.code());
    assertEquals(0, detail.hint());

    var interrupted = withTrace(new InterruptedException(PRIVATE), site(PORTS, "stopWeb", 201));
    var wrapped =
        withTrace(new IOException(PRIVATE, interrupted), site("ui.StoragePageWork", "run", 200));
    detail = StoragePageWork.classifyFailure(wrapped);
    assertEquals("IO_FAILURE", detail.originalCode());
    assertEquals("CANCELLED", detail.code());
    assertEquals("InterruptedException", detail.type());
    assertEquals(0, detail.hint());
  }

  @Test
  public void logsCountersWithoutBackendStringsOrUnknownUppercaseMessages() {
    var detail = StoragePageWork.classifyFailure(new IOException("SOME_PRIVATE_VALUE_42"));
    var latest =
        new StorageMaintenance.Progress(
            StorageMaintenance.Phase.EXPANDING,
            PRIVATE,
            2,
            50,
            3,
            60,
            4,
            10,
            5,
            75,
            PRIVATE,
            125,
            250,
            StorageMaintenance.Outcome.RUNNING);
    String diagnostic = detail.diagnostic(StoragePageWork.Kind.EXPAND, latest, 2011, 403);
    assertTrue(diagnostic.contains("originalCode=UNRECOGNIZED shownCode=UNRECOGNIZED"));
    assertTrue(diagnostic.contains("progress=EXPANDING visited=2 knownBytes=50"));
    assertTrue(diagnostic.contains("verified=5 verifiedBytes=75 removed=3 removedBytes=60"));
    assertTrue(diagnostic.contains("targets=4/10 archiveBytes=125/250"));
    assertFalse(diagnostic.contains(PRIVATE));
    assertFalse(diagnostic.contains("SOME_PRIVATE_VALUE_42"));
  }

  private static void assertExit(String method, StoragePageWork.FailureStage stage, int hint) {
    var failure =
        withTrace(
            new IOException(PRIVATE),
            site("runtime.ProcessCloser", "close", 72),
            site(PORTS, method, 192),
            site(GATE, "exclusive", 100));
    var detail = StoragePageWork.classifyFailure(failure);
    assertEquals(stage, detail.stage());
    assertEquals(hint, detail.hint());
    assertEquals(method, detail.site().getMethodName());
  }

  private static StackTraceElement site(String type, String method, int line) {
    return new StackTraceElement("com.deepseekharness.app." + type, method, PRIVATE, line);
  }

  private static <T extends Exception> T withTrace(T failure, StackTraceElement... frames) {
    failure.setStackTrace(frames);
    return failure;
  }
}
