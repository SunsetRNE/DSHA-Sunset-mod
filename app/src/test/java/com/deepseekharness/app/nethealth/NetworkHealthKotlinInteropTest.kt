package com.deepseekharness.app.nethealth

import com.deepseekharness.app.util.NetworkHealthPolicy
import com.deepseekharness.app.util.NetworkHealthPolicy.Event
import com.deepseekharness.app.util.NetworkHealthPolicy.Probe
import com.deepseekharness.app.util.NetworkHealthPolicy.Signals
import com.deepseekharness.app.util.NetworkHealthPolicy.Verdict
import org.junit.Assert.*
import org.junit.Test

/** 与 Java 基线共用的 Kotlin 消费者，保留字段赋值、静态入口与同包构造。 */
class NetworkHealthKotlinInteropTest {
    @Test
    fun kotlinConsumerKeepsFieldsAndStaticCalls() {
        val signals = Signals()
        signals.connected = true
        signals.transport = null
        signals.resolvState = null
        signals.probe = Probe(false, false, 0, 0, "java.net.UnknownHostException: secret")
        assertEquals(Verdict.EGRESS_FAILED, NetworkHealthPolicy.classify(signals))
        assertEquals(
            "web=0 fgs=0 net=other vpn=0 validated=0 blocked=0 resolv=other dns=fail https=fail err=UnknownHostException",
            NetworkHealthPolicy.evidence(signals),
        )
        assertEquals(60_000L, NetworkHealthPolicy.HEARTBEAT_MS)
        assertEquals("api.deepseek.com", NetworkHealthProbe.HOST)
    }

    @Test
    fun nullableFieldsAndIdentitySurviveAcrossLanguages() {
        val report = NetworkHealthMonitor.Report(null, null, null)
        assertNull(report.verdict)
        assertNull(report.evidence)
        assertNull(report.text)
        val first = Event(-1, null, 0, null)
        assertEquals(1, first.count)
        assertNull(first.verdict)
        assertNotEquals(first, Event(-1, null, 0, null))
        assertFalse(Probe(true, false, 1, 2, null).ok())
    }

    @Test
    fun emptyAndMutableCollectionsKeepTheirContracts() {
        val events = NetworkHealthPolicy.append(null, 1, Verdict.DNS_STALE, "first")
        val parsed = NetworkHealthPolicy.parse(NetworkHealthPolicy.serialize(events))
        assertEquals(1, parsed.size)
        assertEquals("first", parsed[0].evidence)
        assertEquals(Verdict.OK, NetworkHealthPolicy.classify(null))
        assertFalse(NetworkHealthPolicy.shouldProbe(null))
        assertEquals("", NetworkHealthPolicy.redactNetReport(null))
    }
}
