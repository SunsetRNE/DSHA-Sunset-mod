package com.deepseekharness.app.device;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RootModulePolicy;
import com.deepseekharness.app.util.RootModulePolicy.Manager;
import com.deepseekharness.app.util.RootModulePolicy.Probe;
import com.deepseekharness.app.util.RootModulePolicy.ProbeResult;
import com.deepseekharness.app.util.SelfPackageIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 实际 Core 的包内事实端口夹具；不调用 UID0 生产入口、真实进程或设备文件。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class RootModuleReadCoreTest {
  private static final String APK =
      "/data/app/~~fixture/io.github.sunsetrne.dsha.test-fixture/base.apk";

  @After
  public void clearOnlyFixtureInterrupt() {
    Thread.interrupted();
  }

  @Test
  public void missingVerifiedIdentityRefusesBeforeAnyPortObservationOrDispatch() throws Exception {
    Port port = new Port(Manager.APATCH);
    JSONObject result = new JSONObject(RootModuleReadCore.read(null, port));
    assertUnavailable(result, "ROOT_IDENTITY_UNVERIFIED");
    assertEquals(0, port.clockCalls);
    assertEquals(0, port.probes.size());
    assertEquals(0, port.scanCalls);
  }

  @Test
  public void everyFiniteManagerCanProduceOnlyItsVerifiedMetadata() throws Exception {
    for (Manager manager : Manager.values()) {
      Port port = new Port(manager);
      port.result =
          complete(
              List.of(
                  entry(
                      "mod",
                      "id=mod\nname=Module\nversion=1.2\nversionCode=12\n",
                      true,
                      false,
                      true)));
      JSONObject result = read(port);
      assertEquals("LIST_COMPLETE", result.getString("code"));
      assertTrue(result.getBoolean("ok"));
      assertTrue(result.getBoolean("readOnly"));
      assertTrue(result.getBoolean("complete"));
      assertEquals(manager.name(), result.getString("manager"));
      assertEquals(0, result.getInt("executionUid"));
      assertEquals(1, result.getJSONArray("modules").length());
      JSONObject module = result.getJSONArray("modules").getJSONObject(0);
      assertEquals("mod", module.getString("id"));
      assertEquals("Module", module.getString("name"));
      assertEquals("1.2", module.getString("version"));
      assertEquals("12", module.getString("versionCode"));
      assertTrue(module.getBoolean("disabled"));
      assertFalse(module.getBoolean("removing"));
      assertTrue(module.getBoolean("updatePending"));
      assertEquals(1234L, module.getLong("modifiedMillis"));
      assertEquals(5, port.probes.size());
      assertEquals(1, port.scanCalls);
      assertEquals(10_100L, port.scanDeadline);
      assertFixedProbeOrder(port);
    }
  }

  @Test
  public void completeEmptyPartialEmptyAndUnsupportedRemainDifferentResults() throws Exception {
    Port emptyPort = new Port(Manager.APATCH);
    JSONObject empty = read(emptyPort);
    assertEquals("LIST_COMPLETE", empty.getString("code"));
    assertTrue(empty.getBoolean("complete"));
    assertEquals(0, empty.getJSONArray("modules").length());
    assertEquals(1, emptyPort.scanCalls);

    Port partialPort = new Port(Manager.APATCH);
    partialPort.result = partial(List.of());
    JSONObject partial = read(partialPort);
    assertEquals("LIST_PARTIAL", partial.getString("code"));
    assertFalse(partial.getBoolean("complete"));
    assertEquals(0, partial.getJSONArray("modules").length());
    assertEquals(1, partialPort.scanCalls);

    Port unsupportedPort = new Port();
    JSONObject unsupported = read(unsupportedPort);
    assertUnavailable(unsupported, "UNSUPPORTED_MANAGER_VERSION");
    assertEquals(5, unsupportedPort.probes.size());
    assertEquals(0, unsupportedPort.scanCalls);
  }

  @Test
  public void partialScanKeepsItsVerifiedPrefixWithoutBeingUpgradedToComplete() throws Exception {
    Port port = new Port(Manager.KERNELSU);
    port.result = partial(List.of(entry("first", "id=first\n")));
    JSONObject result = read(port);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(1, result.getJSONArray("modules").length());
    assertEquals("first", result.getJSONArray("modules").getJSONObject(0).getString("id"));
    assertEquals(1, port.scanCalls);
    assertEquals(5, port.probes.size());
  }

  @Test
  public void multipleMatchedManagersNeverChooseFirstOrOpenModuleRoot() throws Exception {
    Port all = new Port(Manager.MAGISK, Manager.KERNELSU, Manager.APATCH);
    JSONObject result = read(all);
    assertUnavailable(result, "MANAGER_AMBIGUOUS");
    assertEquals(4, all.probes.size());
    assertEquals(0, all.scanCalls);
    assertEquals(Manager.KERNELSU, all.probes.get(3).manager());

    Port latterPair = new Port(Manager.KERNELSU, Manager.APATCH);
    assertUnavailable(read(latterPair), "MANAGER_AMBIGUOUS");
    assertEquals(5, latterPair.probes.size());
    assertEquals(0, latterPair.scanCalls);
  }

  @Test
  public void probeIOExceptionIsUnknownWithOneAttemptAndNoScan() throws Exception {
    Port port = new Port(Manager.APATCH);
    port.throwProbeIoAt = 1;
    JSONObject result = read(port);
    assertUnavailable(result, "UNKNOWN");
    assertEquals(1, port.probes.size());
    assertEquals(0, port.scanCalls);
    assertEquals(Manager.MAGISK, port.probes.get(0).manager());
    assertEquals(Probe.LOCAL_VERSION, port.probes.get(0).probe());
  }

  @Test
  public void probeInterruptedExceptionPreservesInterruptionAndNeverRetries() throws Exception {
    Port port = new Port(Manager.APATCH);
    port.throwProbeInterruptedAt = 1;
    JSONObject result = read(port);
    assertUnavailable(result, "UNKNOWN");
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals(1, port.probes.size());
    assertEquals(0, port.scanCalls);
  }

  @Test
  public void rootDirectoryIOExceptionIsUnreadableRatherThanEmptyComplete() throws Exception {
    Port port = new Port(Manager.APATCH);
    port.scanFailure = new IOException("CONTROLLED_EACCES_DO_NOT_EXPOSE_PATH");
    JSONObject result = read(port);
    assertUnavailable(result, "MODULE_ROOT_UNREADABLE");
    assertEquals(5, port.probes.size());
    assertEquals(1, port.scanCalls);
    assertFalse(result.toString().contains("CONTROLLED_EACCES"));
  }

  @Test
  public void scanDeadlineSourceChangeAndInterruptAreNotFoldedIntoUnreadable() throws Exception {
    Port deadline = new Port(Manager.APATCH);
    deadline.scanFailure = new IOException("MODULE_DEADLINE_EXCEEDED");
    assertUnavailable(read(deadline), "MODULE_DEADLINE_EXCEEDED");
    assertEquals(1, deadline.scanCalls);

    Port changed = new Port(Manager.APATCH);
    changed.scanFailure = new IOException("MODULE_SOURCE_CHANGED");
    assertUnavailable(read(changed), "MODULE_SOURCE_CHANGED");
    assertEquals(1, changed.scanCalls);

    Port interrupted = new Port(Manager.APATCH);
    interrupted.scanFailure = new java.io.InterruptedIOException("ROOT_MODULE_SCAN_CANCELLED");
    try {
      assertUnavailable(read(interrupted), "UNKNOWN");
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, interrupted.scanCalls);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void partialScanReasonIsPassedThroughInsteadOfBecomingUnknown() throws Exception {
    Port port = new Port(Manager.KERNELSU);
    port.result =
        new RootModuleFiles.Scan(List.of(), false, RootModulePolicy.Code.MODULE_SOURCE_CHANGED);
    JSONObject result = read(port);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals("MODULE_SOURCE_CHANGED", result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(0, result.getJSONArray("modules").length());
    assertEquals(1, port.scanCalls);
  }

  @Test
  public void allProbeTimeoutsAndScanUseOneShrinkingDeadline() throws Exception {
    Port port = new Port(Manager.APATCH);
    port.timeAfterEachProbe = 1_100L;
    JSONObject result = read(port);
    assertTrue(result.getBoolean("complete"));
    assertEquals(
        List.of(10_000L, 8_900L, 7_800L, 6_700L, 5_600L),
        port.probes.stream().map(Call::timeoutMs).toList());
    assertEquals(10_100L, port.scanDeadline);
    assertEquals(1, port.scanCalls);
  }

  @Test
  public void deadlineBeforeFirstProbeAndBetweenProbesStopsFurtherPortCalls() throws Exception {
    Port beforeFirst = new Port(Manager.APATCH);
    beforeFirst.clockValues.add(100L);
    beforeFirst.clockValues.add(10_100L);
    assertUnavailable(read(beforeFirst), "UNKNOWN");
    assertEquals(0, beforeFirst.probes.size());
    assertEquals(0, beforeFirst.scanCalls);

    Port between = new Port(Manager.APATCH);
    between.timeAfterEachProbe = 4_000L;
    assertUnavailable(read(between), "UNKNOWN");
    assertEquals(3, between.probes.size());
    assertEquals(
        List.of(10_000L, 6_000L, 2_000L), between.probes.stream().map(Call::timeoutMs).toList());
    assertEquals(0, between.scanCalls);
  }

  @Test
  public void deadlineDuringEntryParsingKeepsPrefixAndDoesNotRescan() throws Exception {
    Port port = new Port(Manager.KERNELSU);
    port.result = complete(List.of(entry("first", "id=first\n"), entry("second", "id=second\n")));
    port.expireAfterScanEntryChecks = 1;
    JSONObject result = read(port);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals("MODULE_DEADLINE_EXCEEDED", result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(1, result.getJSONArray("modules").length());
    assertEquals("first", result.getJSONArray("modules").getJSONObject(0).getString("id"));
    assertEquals(1, port.scanCalls);
    assertEquals(5, port.probes.size());
  }

  @Test
  public void identityMismatchOrMalformedPropertyIsPartialAndNeverRetained() throws Exception {
    for (String invalid : List.of("id=other\n", "id=bad\nname=$(id)\n", "id=bad\nid=bad\n")) {
      Port port = new Port(Manager.APATCH);
      port.result = complete(List.of(entry("first", "id=first\n"), entry("bad", invalid)));
      JSONObject result = read(port);
      assertEquals("LIST_PARTIAL", result.getString("code"));
      assertFalse(result.getBoolean("complete"));
      assertEquals(1, result.getJSONArray("modules").length());
      assertEquals("first", result.getJSONArray("modules").getJSONObject(0).getString("id"));
      assertFalse(result.toString().contains("$(id)"));
      assertEquals(5, port.probes.size());
      assertEquals(1, port.scanCalls);
    }
  }

  @Test
  public void invalidUtf8OrTraversalInputCannotBecomeModuleOutput() throws Exception {
    Port invalidUtf8 = new Port(Manager.APATCH);
    invalidUtf8.result =
        complete(
            List.of(
                new RootModuleFiles.Entry(
                    "bad", new byte[] {(byte) 0xc0, (byte) 0xaf}, false, false, false, 0)));
    JSONObject utf8 = read(invalidUtf8);
    assertPartialEmpty(utf8, "MODULE_PROP_UTF8");
    assertEquals(1, invalidUtf8.scanCalls);

    Port traversal = new Port(Manager.APATCH);
    traversal.result = complete(List.of(entry("../bad", "id=../bad\n")));
    JSONObject unsafe = read(traversal);
    assertPartialEmpty(unsafe, "MODULE_ID_INVALID");
    assertEquals(1, traversal.scanCalls);
    assertFalse(unsafe.toString().contains("../bad"));
  }

  @Test
  public void duplicateModuleIdStopsAtOneEntryAndNeverReplaysProbeOrScan() throws Exception {
    Port port = new Port(Manager.APATCH);
    port.result =
        complete(
            List.of(
                entry("same", "id=same\n"),
                entry("same", "id=same\n"),
                entry("after", "id=after\n")));
    JSONObject result = read(port);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals("MODULE_DUPLICATE_ID", result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(1, result.getJSONArray("modules").length());
    assertEquals(5, port.probes.size());
    assertEquals(1, port.scanCalls);
  }

  @Test
  public void oversizedPropertyAndTotalMetadataBudgetCannotReportComplete() throws Exception {
    Port oneTooLarge = new Port(Manager.APATCH);
    oneTooLarge.result =
        complete(
            List.of(
                new RootModuleFiles.Entry(
                    "large",
                    padded("large", RootModulePolicy.MAX_PROP_BYTES + 1),
                    false,
                    false,
                    false,
                    0)));
    assertPartialEmpty(read(oneTooLarge), "MODULE_PROP_LIMIT");
    assertEquals(1, oneTooLarge.scanCalls);

    Port total = new Port(Manager.MAGISK);
    List<RootModuleFiles.Entry> entries = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      String id = "m" + i;
      entries.add(
          new RootModuleFiles.Entry(
              id, padded(id, RootModulePolicy.MAX_PROP_BYTES), false, false, false, 0));
    }
    entries.add(entry("extra", "id=extra\n"));
    total.result = complete(entries);
    JSONObject result = read(total);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals("MODULE_TOTAL_LIMIT", result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(16, result.getJSONArray("modules").length());
    assertEquals(5, total.probes.size());
    assertEquals(1, total.scanCalls);
  }

  @Test
  public void entryBudgetBoundsOutputEvenIfInjectedScannerOverproduces() throws Exception {
    Port port = new Port(Manager.KERNELSU);
    List<RootModuleFiles.Entry> entries = new ArrayList<>();
    for (int i = 0; i <= RootModulePolicy.MAX_ENTRIES; i++)
      entries.add(entry("m" + i, "id=m" + i + "\n"));
    port.result = complete(entries);
    JSONObject result = read(port);
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals("MODULE_ENTRY_LIMIT", result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(RootModulePolicy.MAX_ENTRIES, result.getJSONArray("modules").length());
    assertEquals(5, port.probes.size());
    assertEquals(1, port.scanCalls);
  }

  @Test
  public void nonzeroTruncatedOrWrongVersionDoesNotReachScan() throws Exception {
    for (ProbeResult result :
        List.of(
            new ProbeResult(126, true, "apd 11282"),
            new ProbeResult(0, false, "apd 11282"),
            new ProbeResult(0, true, "apd 11283"),
            new ProbeResult(0, true, "apd 11282\n\n"))) {
      Port port = new Port(Manager.APATCH);
      port.apatchOverride = result;
      assertUnavailable(read(port), "UNSUPPORTED_MANAGER_VERSION");
      assertEquals(5, port.probes.size());
      assertEquals(0, port.scanCalls);
    }
  }

  private static SelfPackageIdentity identity() throws IOException {
    return SelfPackageIdentity.launch("io.github.sunsetrne.dsha.test", 12345, APK, APK);
  }

  private static JSONObject read(Port port) throws Exception {
    SelfPackageIdentity identity = identity();
    port.expectedIdentity = identity;
    return new JSONObject(RootModuleReadCore.read(identity, port));
  }

  private static void assertUnavailable(JSONObject result, String code) throws Exception {
    assertFalse(result.getBoolean("ok"));
    assertTrue(result.getBoolean("readOnly"));
    assertEquals(code, result.getString("code"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(0, result.getJSONArray("modules").length());
    assertFalse(result.has("executionUid"));
  }

  private static void assertPartialEmpty(JSONObject result, String reason) throws Exception {
    assertEquals("LIST_PARTIAL", result.getString("code"));
    assertEquals(reason, result.getString("reason"));
    assertFalse(result.getBoolean("complete"));
    assertEquals(0, result.getJSONArray("modules").length());
  }

  private static RootModuleFiles.Entry entry(String id, String property) {
    return entry(id, property, false, false, false);
  }

  private static RootModuleFiles.Entry entry(
      String id, String property, boolean disabled, boolean removing, boolean updatePending) {
    return new RootModuleFiles.Entry(
        id, property.getBytes(StandardCharsets.UTF_8), disabled, removing, updatePending, 1234);
  }

  private static RootModuleFiles.Scan complete(List<RootModuleFiles.Entry> entries) {
    return new RootModuleFiles.Scan(entries, true, null);
  }

  private static RootModuleFiles.Scan partial(List<RootModuleFiles.Entry> entries) {
    return new RootModuleFiles.Scan(entries, false, RootModulePolicy.Code.MODULE_ROOT_UNREADABLE);
  }

  private static byte[] padded(String id, int length) {
    String prefix = "id=" + id + "\nignored=";
    return (prefix + "x".repeat(length - prefix.length())).getBytes(StandardCharsets.UTF_8);
  }

  private static void assertFixedProbeOrder(Port port) {
    int cursor = 0;
    for (Manager manager : Manager.values()) {
      for (Probe probe : RootModulePolicy.probes(manager)) {
        assertEquals(manager, port.probes.get(cursor).manager());
        assertEquals(probe, port.probes.get(cursor).probe());
        cursor++;
      }
    }
    assertEquals(cursor, port.probes.size());
  }

  private record Call(Manager manager, Probe probe, long timeoutMs) {}

  private static final class Port implements RootModuleReadCore.ReadPort {
    final EnumSet<Manager> supported = EnumSet.noneOf(Manager.class);
    final List<Call> probes = new ArrayList<>();
    final ArrayDeque<Long> clockValues = new ArrayDeque<>();
    RootModuleFiles.Scan result = complete(List.of());
    SelfPackageIdentity expectedIdentity;
    ProbeResult apatchOverride;
    IOException scanFailure;
    long time = 100, timeAfterEachProbe, scanDeadline;
    int clockCalls, scanCalls, scanEntryChecks;
    int throwProbeIoAt = -1, throwProbeInterruptedAt = -1, expireAfterScanEntryChecks = -1;
    boolean scanReturned;

    Port(Manager... managers) {
      supported.addAll(List.of(managers));
    }

    @Override
    public long now() {
      clockCalls++;
      if (!clockValues.isEmpty()) return clockValues.removeFirst();
      if (scanReturned
          && expireAfterScanEntryChecks >= 0
          && ++scanEntryChecks > expireAfterScanEntryChecks) return scanDeadline;
      return time;
    }

    @Override
    public ProbeResult probe(
        SelfPackageIdentity identity, Manager manager, Probe probe, long timeoutMs)
        throws IOException, InterruptedException {
      assertSame(expectedIdentity, identity);
      assertTrue(RootModulePolicy.probes(manager).contains(probe));
      assertTrue(timeoutMs > 0 && timeoutMs <= RootModulePolicy.DEADLINE_MS);
      probes.add(new Call(manager, probe, timeoutMs));
      if (probes.size() == throwProbeIoAt) throw new IOException("CONTROLLED_PROBE_FAILURE");
      if (probes.size() == throwProbeInterruptedAt)
        throw new InterruptedException("CONTROLLED_PROBE_CANCEL");
      time += timeAfterEachProbe;
      if (manager == Manager.APATCH && apatchOverride != null) return apatchOverride;
      if (!supported.contains(manager)) return new ProbeResult(1, true, "not present");
      String output =
          switch (manager) {
            case APATCH -> "apd 11282";
            case KERNELSU -> "ksud 3.3.1-22-g6d31a613 (uapi: 6)";
            case MAGISK ->
                switch (probe) {
                  case LOCAL_VERSION -> "af8c2566:MAGISK:R (31000)";
                  case DAEMON_VERSION -> "af8c2566:MAGISK:R";
                  case DAEMON_CODE -> "31000";
                  default -> throw new AssertionError("unexpected Magisk probe");
                };
          };
      return new ProbeResult(0, true, output + "\n");
    }

    @Override
    public RootModuleFiles.Scan scan(long deadline) throws IOException {
      scanCalls++;
      scanDeadline = deadline;
      if (scanFailure != null) throw scanFailure;
      scanReturned = true;
      return result;
    }
  }
}
