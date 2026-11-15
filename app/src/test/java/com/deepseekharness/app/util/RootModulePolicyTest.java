package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.RootModulePolicy.Code;
import com.deepseekharness.app.util.RootModulePolicy.Manager;
import com.deepseekharness.app.util.RootModulePolicy.Probe;
import com.deepseekharness.app.util.RootModulePolicy.ProbeResult;
import com.deepseekharness.app.util.RootModulePolicy.RootAccess;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class RootModulePolicyTest {
  private static final String KSU = "ksud 3.3.1-22-g6d31a613 (uapi: 6)";

  @Test
  public void fixedCandidatesFlagsAndArgvCannotAcceptAnotherPathOrOperation() {
    assertEquals("/data/adb/modules", RootModulePolicy.MODULE_ROOT);
    assertEquals(
        List.of("/system/bin/magisk", "-c"),
        RootModulePolicy.argv(Manager.MAGISK, Probe.LOCAL_VERSION));
    assertEquals(
        List.of("/system/bin/magisk", "-v"),
        RootModulePolicy.argv(Manager.MAGISK, Probe.DAEMON_VERSION));
    assertEquals(
        List.of("/system/bin/magisk", "-V"),
        RootModulePolicy.argv(Manager.MAGISK, Probe.DAEMON_CODE));
    assertEquals(
        List.of("/data/adb/ksud", "-V"), RootModulePolicy.argv(Manager.KERNELSU, Probe.VERSION));
    assertEquals(
        List.of("/data/adb/apd", "-V"), RootModulePolicy.argv(Manager.APATCH, Probe.VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () -> RootModulePolicy.flag(Manager.KERNELSU, Probe.LOCAL_VERSION));
    assertThrows(
        IllegalArgumentException.class,
        () -> RootModulePolicy.argv(Manager.APATCH, Probe.DAEMON_VERSION));
    assertThrows(
        IllegalArgumentException.class, () -> RootModulePolicy.flag(Manager.MAGISK, Probe.VERSION));
    assertThrows(
        UnsupportedOperationException.class,
        () -> RootModulePolicy.argv(Manager.APATCH, Probe.VERSION).add("module"));
    assertThrows(
        UnsupportedOperationException.class, () -> RootModulePolicy.probes(Manager.MAGISK).clear());
  }

  @Test
  public void approvedBudgetsAreLiteralAndIndependentOfVersionOutput() {
    assertEquals(256, RootModulePolicy.MAX_ENTRIES);
    assertEquals(16 * 1024, RootModulePolicy.MAX_PROP_BYTES);
    assertEquals(256 * 1024, RootModulePolicy.MAX_TOTAL_BYTES);
    assertEquals(256, RootModulePolicy.MAX_FIELD_CODEPOINTS);
    assertEquals(4 * 1024, RootModulePolicy.MAX_PROBE_BYTES);
    assertEquals(10_000L, RootModulePolicy.DEADLINE_MS);
  }

  @Test
  public void exactVersionsAcceptOnlyNoTailOneLfOrOneCrLf() {
    for (String tail : List.of("", "\n", "\r\n")) {
      assertTrue(
          RootModulePolicy.probeMatches(
              Manager.KERNELSU, Map.of(Probe.VERSION, result(KSU + tail))));
      assertTrue(
          RootModulePolicy.probeMatches(
              Manager.APATCH, Map.of(Probe.VERSION, result("apd 11282" + tail))));
      var magisk = magisk();
      for (Probe probe : magisk.keySet()) {
        ProbeResult original = magisk.get(probe);
        magisk.put(probe, result(original.output() + tail));
      }
      assertTrue(RootModulePolicy.probeMatches(Manager.MAGISK, magisk));
    }
  }

  @Test
  public void wrongVersionCustomDebugOrAnotherManagerNeverMatches() {
    for (String output :
        List.of(
            "ksud 3.3.1 (uapi: 6)",
            "ksud 3.3.1-22-g6d31a613 (uapi: 5)",
            "ksud 3.3.1-23-g6d31a613 (uapi: 6)",
            "apd 11282",
            "32702"))
      assertFalse(
          RootModulePolicy.probeMatches(Manager.KERNELSU, Map.of(Probe.VERSION, result(output))));
    for (String output : List.of("apd 11281", "11282", "apd 11282-custom", "apd v11282"))
      assertFalse(
          RootModulePolicy.probeMatches(Manager.APATCH, Map.of(Probe.VERSION, result(output))));
    var debug = magisk();
    debug.put(Probe.LOCAL_VERSION, result("af8c2566:MAGISK:D (31000)"));
    assertFalse(RootModulePolicy.probeMatches(Manager.MAGISK, debug));
    var mismatchedDaemon = magisk();
    mismatchedDaemon.put(Probe.DAEMON_VERSION, result("31.0:MAGISK:R"));
    assertFalse(RootModulePolicy.probeMatches(Manager.MAGISK, mismatchedDaemon));
  }

  @Test
  public void everyMagiskProbeIsRequiredAndUnsupportedFieldsAreRejected() {
    for (Probe missing : RootModulePolicy.probes(Manager.MAGISK)) {
      var values = magisk();
      values.remove(missing);
      assertFalse(RootModulePolicy.probeMatches(Manager.MAGISK, values));
    }
    var extra = magisk();
    extra.put(Probe.VERSION, result("af8c2566"));
    assertFalse(RootModulePolicy.probeMatches(Manager.MAGISK, extra));
    assertFalse(RootModulePolicy.probeMatches(Manager.APATCH, null));
    assertFalse(RootModulePolicy.probeMatches(Manager.KERNELSU, Map.of()));
  }

  @Test
  public void completeExitZeroIsRequiredEvenForExactVersionBytes() {
    for (int exit : new int[] {-1, 1, 126, 255}) {
      assertFalse(
          RootModulePolicy.probeMatches(
              Manager.KERNELSU, Map.of(Probe.VERSION, new ProbeResult(exit, true, KSU))));
    }
    assertFalse(
        RootModulePolicy.probeMatches(
            Manager.KERNELSU, Map.of(Probe.VERSION, new ProbeResult(0, false, KSU))));
    assertFalse(
        RootModulePolicy.probeMatches(
            Manager.KERNELSU, Map.of(Probe.VERSION, new ProbeResult(0, true, null))));
  }

  @Test
  public void multilineControlSpacePrefixAndOversizedProbeCannotBeTrimmedIntoSuccess() {
    for (String output :
        List.of(
            KSU + "\n\n",
            KSU + "\r",
            KSU + "\nignored",
            " " + KSU,
            KSU + " ",
            KSU + "\u0000",
            KSU + "\u001b",
            KSU + "\t",
            KSU + "\u0085",
            "\ufeff" + KSU,
            KSU + "\r\n\r\n",
            "x".repeat(RootModulePolicy.MAX_PROBE_BYTES + 1))) {
      assertFalse(
          output,
          RootModulePolicy.probeMatches(Manager.KERNELSU, Map.of(Probe.VERSION, result(output))));
    }
  }

  @Test
  public void rootFailureCodesCannotBeOverriddenByMatchingProbe() {
    var values = Map.of(Manager.APATCH, Map.of(Probe.VERSION, result("apd 11282")));
    RootAccess[] states = {
      RootAccess.DISABLED,
      RootAccess.SU_UNAVAILABLE,
      RootAccess.NOT_GRANTED,
      RootAccess.IDENTITY_UNVERIFIED
    };
    Code[] expected = {
      Code.ROOT_DISABLED, Code.SU_UNAVAILABLE, Code.ROOT_NOT_GRANTED, Code.ROOT_IDENTITY_UNVERIFIED
    };
    for (int i = 0; i < states.length; i++) {
      var selection = RootModulePolicy.select(states[i], values);
      assertEquals(expected[i], selection.code());
      assertFalse(selection.supported());
      assertNull(selection.manager());
    }
    assertEquals(Code.ROOT_IDENTITY_UNVERIFIED, RootModulePolicy.select(null, values).code());
  }

  @Test
  public void exactlyOneMatchedManagerSelectsItsIdentityAndNoMatchIsUnsupported() {
    var selection =
        RootModulePolicy.select(
            RootAccess.ROOT_VERIFIED,
            Map.of(
                Manager.APATCH,
                Map.of(Probe.VERSION, result("apd 11282")),
                Manager.KERNELSU,
                Map.of(Probe.VERSION, result("wrong"))));
    assertTrue(selection.supported());
    assertEquals(Code.MANAGER_MATCHED, selection.code());
    assertEquals(Manager.APATCH, selection.manager());
    assertEquals(
        Code.UNSUPPORTED_MANAGER_VERSION,
        RootModulePolicy.select(RootAccess.ROOT_VERIFIED, Map.of()).code());
    assertEquals(Code.UNKNOWN, RootModulePolicy.select(RootAccess.ROOT_VERIFIED, null).code());
  }

  @Test
  public void multipleMatchingManagersAreAmbiguousRegardlessOfIterationOrder() {
    for (boolean reversed : new boolean[] {false, true}) {
      var values = new java.util.LinkedHashMap<Manager, Map<Probe, ProbeResult>>();
      Manager first = reversed ? Manager.KERNELSU : Manager.APATCH;
      Manager second = reversed ? Manager.APATCH : Manager.KERNELSU;
      values.put(first, Map.of(Probe.VERSION, result(first == Manager.APATCH ? "apd 11282" : KSU)));
      values.put(
          second, Map.of(Probe.VERSION, result(second == Manager.APATCH ? "apd 11282" : KSU)));
      var selection = RootModulePolicy.select(RootAccess.ROOT_VERIFIED, values);
      assertEquals(Code.MANAGER_AMBIGUOUS, selection.code());
      assertNull(selection.manager());
      assertFalse(selection.supported());
    }
  }

  @Test
  public void selectionCannotClaimAManagerForRefusalOrOmitOneForSupport() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModulePolicy.Selection(Code.UNKNOWN, Manager.MAGISK));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootModulePolicy.Selection(Code.MANAGER_MATCHED, null));
  }

  private static ProbeResult result(String value) {
    return new ProbeResult(0, true, value);
  }

  private static EnumMap<Probe, ProbeResult> magisk() {
    var values = new EnumMap<Probe, ProbeResult>(Probe.class);
    values.put(Probe.LOCAL_VERSION, result("af8c2566:MAGISK:R (31000)"));
    values.put(Probe.DAEMON_VERSION, result("af8c2566:MAGISK:R"));
    values.put(Probe.DAEMON_CODE, result("31000"));
    return values;
  }
}
