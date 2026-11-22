package com.deepseekharness.app.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同一套 Kotlin 消费代码同时编译到迁移前 Java 和迁移后 Kotlin 实现。 */
class SafKotlinInteropTest {
    @Test
    fun nullAndWhitespaceKeepJavaSemanticsAtTheKotlinBoundary() {
        assertEquals("", SafPaths.normalize(null))
        assertEquals("\u00a0name\u00a0", SafPaths.normalize("\u00a0name\u00a0"))
        assertNull(SafCapabilityLimits.uriProblem("\u00a0"))
        assertEquals(SafCapabilityLimits.URI_MISSING, SafCapabilityLimits.uriProblem("\u2000"))
        assertFalse(SafPaths.looksText(null))
    }

    @Test
    fun recordConstructorKeepsNormalizationAndJavaRecordIdentity() {
        val uri = "content://kotlin.documents/tree/root"
        val record =
            SafGrantRecord(
                SafGrantRecord.idOf(uri),
                uri,
                " app ",
                null,
                true,
                false,
                false,
                1,
                SafGrantRecord.State.ACTIVE,
                1,
                0,
                0,
                0,
                null,
            )
        val javaRecord: java.lang.Record = record
        assertTrue(javaRecord.javaClass.isRecord)
        assertEquals("app · root", record.summary())
        assertTrue(record.canRead())
        assertFalse(record.usableWrite())
    }

    @Test
    fun epochRevocationAndOneTimeConsumptionWorkForKotlinConsumers() {
        val uri = "content://kotlin.documents/tree/root"
        val id = SafGrantRecord.idOf(uri)
        val pending = SafGrantLedger.empty().beginTake(uri, "", "", 0, 3, 0).ledger
        val active = pending.commitTake(uri, 3, 3, true, 0).ledger
        val handles = SafCapabilityHandles { "kotlin-ticket" }
        val issued = handles.issue(active, id, SafCapabilityHandles.Operation.DELETE, "file", 0)
        assertTrue(issued.ok())
        assertEquals(
            SafCapabilityHandles.Result.REVOKED,
            handles.check(
                active.forget(uri).ledger,
                issued.token,
                SafCapabilityHandles.Operation.DELETE,
                "file",
                1,
            ),
        )
        assertEquals(
            SafCapabilityHandles.Result.OK,
            handles.consume(active, issued.token, SafCapabilityHandles.Operation.DELETE, "file", 1),
        )
        assertEquals(
            SafCapabilityHandles.Result.UNKNOWN,
            handles.consume(active, issued.token, SafCapabilityHandles.Operation.DELETE, "file", 1),
        )
    }
}
