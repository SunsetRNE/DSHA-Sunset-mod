package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.NetworkHealthPolicy.Event;
import com.deepseekharness.app.util.NetworkHealthPolicy.Probe;
import com.deepseekharness.app.util.NetworkHealthPolicy.Signals;
import com.deepseekharness.app.util.NetworkHealthPolicy.Verdict;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 在 Java 原实现上冻结 null、异常、可变性、对象身份和坏输入的边界。 */
public final class NetworkHealthPolicyContractTest {
  @Test
  public void javaFieldsAndDefaultsStayDirectlyAccessible() throws Exception {
    Signals s = new Signals();
    assertEquals("none", s.transport);
    assertEquals("unavailable", s.resolvState);
    assertNull(s.probe);
    s.transport = null;
    s.resolvState = null;
    assertEquals(
        "web=0 fgs=0 net=none vpn=0 validated=0 blocked=0 resolv=other",
        NetworkHealthPolicy.evidence(s));
    assertTrue(Modifier.isFinal(Probe.class.getField("dnsOk").getModifiers()));
    assertFalse(Modifier.isFinal(Signals.class.getField("probe").getModifiers()));
    assertEquals(60_000, NetworkHealthPolicy.HEARTBEAT_MS);
    assertEquals(180_000, NetworkHealthPolicy.FROZEN_GAP_MS);
    assertEquals(600_000, NetworkHealthPolicy.DEDUP_MS);
    assertEquals(200, NetworkHealthPolicy.MAX_EVENTS);
    assertFalse(NetworkHealthPolicy.shouldProbe(null));
  }

  @Test
  public void valuesKeepIdentityAndNullableVerdictWithoutDataClassSemantics() {
    Event a = new Event(-7, null, -1, null);
    Event b = new Event(-7, null, -1, null);
    assertEquals(-7, a.at);
    assertNull(a.verdict);
    assertEquals(1, a.count);
    assertEquals("", a.evidence);
    assertNotEquals(a, b);
    assertNotEquals(new Probe(true, true, -1, -2, null), new Probe(true, true, -1, -2, null));
    assertThrows(NullPointerException.class, () -> NetworkHealthPolicy.serialize(List.of(a)));
    assertThrows(NullPointerException.class, () -> NetworkHealthPolicy.evidence(null));
  }

  @Test
  public void listCopiesAreMutableAndRecentIsMutableIndependentView() {
    Event a = new Event(1, Verdict.DNS_STALE, 1, "a");
    Event b = new Event(2, Verdict.SYSTEM_BLOCKED, 2, "b");
    List<Event> input = List.of(a, b);
    List<Event> appended = NetworkHealthPolicy.append(input, 3, null, null);
    assertNotSame(input, appended);
    assertSame(a, appended.get(0));
    appended.clear();
    assertEquals(2, input.size());
    List<Event> recent = NetworkHealthPolicy.recent(input, 1);
    assertSame(b, recent.get(0));
    recent.set(0, a);
    assertSame(b, input.get(1));
    recent.clear();
    List<Event> emptyAppend = NetworkHealthPolicy.append(null, 0, null, null);
    emptyAppend.add(a);
    List<Event> emptyParse = NetworkHealthPolicy.parse(null);
    emptyParse.add(a);
    List<Event> emptyRecent = NetworkHealthPolicy.recent(null, 0);
    emptyRecent.add(a);
    Map<Verdict, Integer> emptyTotals = NetworkHealthPolicy.totals(null);
    emptyTotals.put(null, 1);
    List<Event> nullElement = java.util.Arrays.asList((Event) null);
    assertNull(NetworkHealthPolicy.append(nullElement, 0, Verdict.OK, null).get(0));
    assertNull(NetworkHealthPolicy.recent(nullElement, 1).get(0));
    assertThrows(NullPointerException.class, () -> NetworkHealthPolicy.serialize(nullElement));
    assertTrue(NetworkHealthPolicy.recent(input, -1).isEmpty());
    assertTrue(NetworkHealthPolicy.recent(null, Integer.MAX_VALUE).isEmpty());
  }

  @Test
  public void thresholdsAndClockReversalStayExact() {
    assertEquals(180_000, NetworkHealthPolicy.frozenGap(1, 180_001));
    assertEquals(0, NetworkHealthPolicy.frozenGap(1, 180_000));
    List<Event> original = List.of(new Event(10, Verdict.DNS_STALE, 1, "old"));
    assertEquals(2, NetworkHealthPolicy.append(original, 9, Verdict.DNS_STALE, "backward").size());
    assertEquals(
        2, NetworkHealthPolicy.append(original, 600_010, Verdict.DNS_STALE, "boundary").size());
    assertEquals(
        1, NetworkHealthPolicy.append(original, 600_009, Verdict.DNS_STALE, "inside").size());
  }

  @Test
  public void existingIntegerOverflowIsNotSilentlyChanged() {
    List<Event> list = List.of(new Event(1, Verdict.DNS_STALE, Integer.MAX_VALUE, ""));
    assertEquals(1, NetworkHealthPolicy.append(list, 2, Verdict.DNS_STALE, "").get(0).count);
    assertEquals(
        Integer.valueOf(Integer.MIN_VALUE),
        NetworkHealthPolicy.totals(List.of(list.get(0), new Event(2, Verdict.DNS_STALE, 1, "")))
            .get(Verdict.DNS_STALE));
  }

  @Test
  public void serializationUsesExistingHeaderAndSeparatorRules() {
    assertEquals("DSHA_NET_HEALTH_V1\n", NetworkHealthPolicy.serialize(null));
    assertEquals(
        "DSHA_NET_HEALTH_V1\n-1|DNS_STALE|1|a b c d\n",
        NetworkHealthPolicy.serialize(List.of(new Event(-1, Verdict.DNS_STALE, 0, "a|b\nc\rd"))));
    List<Event> events =
        NetworkHealthPolicy.parse(
            "DSHA_NET_HEALTH_V1suffix\r\n-1|DNS_STALE|0|a|b\u00852|SYSTEM_BLOCKED|3|\u20283|NOPE|1|x\n9223372036854775808|DNS_STALE|1|x\n4|DNS_STALE|2147483648|x\n");
    assertEquals(2, events.size());
    assertEquals("a|b", events.get(0).evidence);
    assertEquals("", events.get(1).evidence);
  }

  @Test
  public void parsingCapsNewestRowsWithoutDedupOrDiscardingOk() {
    StringBuilder text = new StringBuilder("DSHA_NET_HEALTH_V1\n");
    for (int i = 0; i < 205; i++) text.append(i).append("|OK|1|x\n");
    List<Event> result = NetworkHealthPolicy.parse(text.toString());
    assertEquals(200, result.size());
    assertEquals(5, result.get(0).at);
    assertEquals(Verdict.OK, result.get(199).verdict);
    result.clear();
  }

  @Test
  public void resolvAndRedactionKeepJavaLineAndTrimSemantics() {
    assertEquals(
        "empty",
        NetworkHealthPolicy.resolvState(
            "resolv_state=current\rresolv_state=empty\rresolv_state=current\n"));
    assertEquals(
        "_current_", NetworkHealthPolicy.resolvState("resolv_state=\u2003current\u2003\n"));
    assertEquals(
        "private_dns=strict\nnetwork=wifi\n",
        NetworkHealthPolicy.redactNetReport(
            "device_dns=secret\rprivate_dns=strict secret\u0085resolv_dns=secret\u2028network=wifi\n"));
    assertEquals("", NetworkHealthPolicy.redactNetReport(null));
  }

  @Test
  public void samePackageHelpersRetainNamesNullAndTokenRules() {
    assertEquals("other", NetworkHealthPolicy.known(null, null));
    assertThrows(NullPointerException.class, () -> NetworkHealthPolicy.known("wifi", null));
    assertEquals("wifi", NetworkHealthPolicy.known("wifi", List.of("wifi")));
    assertEquals("", NetworkHealthPolicy.errorName(null));
    assertEquals("", NetworkHealthPolicy.errorName(" message"));
    assertEquals(
        "SSLException", NetworkHealthPolicy.errorName("javax.net.ssl.SSLException: secret"));
    assertEquals("_".repeat(64), NetworkHealthPolicy.safeToken("密".repeat(70)));
    assertEquals("a_b._-", NetworkHealthPolicy.safeToken("a b._-"));
  }

  @Test
  public void totalsKeepEncounterOrderNullKeyAndMutableMap() {
    Map<Verdict, Integer> totals =
        NetworkHealthPolicy.totals(
            List.of(
                new Event(1, Verdict.DNS_STALE, 1, ""),
                new Event(2, null, 1, ""),
                new Event(3, Verdict.SYSTEM_BLOCKED, 1, "")));
    assertEquals(
        java.util.Arrays.asList(Verdict.DNS_STALE, null, Verdict.SYSTEM_BLOCKED),
        new ArrayList<>(totals.keySet()));
    totals.clear();
    assertTrue(NetworkHealthPolicy.totals(null).isEmpty());
  }
}
