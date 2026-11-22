package com.deepseekharness.app.transfer

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** 同包 Kotlin 与原 Java/迁移 Kotlin 的双向调用，不通过反射或替身消费公开接口。 */
class TransferKotlinInteropTest {
    @Test
    fun fieldsSnapshotsAndNestedStatusRemainCallable() {
        val artifact =
            TransferArtifact("kotlin-id", "报告.txt", "TEXT/PLAIN", 9, "AB".repeat(32), "v1")
        assertEquals("text/plain", artifact.mime)
        assertEquals("ab".repeat(32), artifact.sha256)
        val task =
            DownloadTask(
                null,
                artifact,
                null,
                DownloadTask.Status.PAUSED,
                4,
                12,
                null,
                null,
                null,
                null,
                null,
                37,
            )
        assertSame(artifact, task.artifact)
        assertEquals(9L, task.total)
        assertEquals(12L, task.speedBytesPerSecond)
        assertNull(task.id)
        assertEquals(DownloadTask.Status.PAUSED, task.status)
        val tasks = arrayListOf(task)
        val state = DownloadEngine.State(tasks, false, null)
        assertSame(task, state.tasks[0])
        assertThrows(UnsupportedOperationException::class.java) {
            (state.tasks as MutableList<DownloadTask>).clear()
        }
    }

    @Test
    fun staticProtocolAndDomainNullChecksRetainTheirContracts() {
        assertTrue(TransferArtifact.safeName("\u00a0"))
        assertFalse(TransferArtifact.safeName(null))
        assertEquals(
            "FILE_ARTIFACT_ID",
            assertThrows(IOException::class.java) {
                    TransferArtifact(null, null, null, -1, null, null)
                }
                .message,
        )
        val hash = "a".repeat(64)
        assertEquals(0L, TransferProtocol.responseOffset(200, 7, 10, null, "10", "\"$hash\"", hash))
        assertEquals(16L * 1024 * 1024 + 13, TransferProtocol.requiredSpace(10, 7, true))
    }

    @Test
    fun explicitActionIsConsumedOnceAcrossJavaAndKotlin() {
        val action = PendingFileAction("task-1", "share")
        assertEquals("", action.consume(null, "share", null, true))
        assertEquals("share", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true))
        assertEquals("", action.consume("task-1", "share", DownloadTask.Status.COMPLETE, true))
        assertEquals("", action.id())
    }
}
