package com.deepseekharness.app.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 轮换 golden 使用独立临时副本；不把来源自述或手动/未知记录当自动删除证明。 */
class AutomaticBackupPrunerGoldenTest {
    @get:Rule val temp = TemporaryFolder()
    private val control = BackupControl(null)
    private val fs = JvmBackupFileSystem()

    private inner class Fixture {
        val operations = HostOperationArchive.root(temp.newFolder())
        val history = File(operations, HostOperationArchive.COMPLETED)
        init { fs.directory(operations); fs.directory(history) }

        fun copy(created: Long, marker: Any? = true, integrity: String = "QUIESCENT", busy: Boolean = false, stage: String = "FINISHED", warnings: Boolean = false): VerifiedBackupCopy {
            val id = UUID.randomUUID().toString()
            val directory = File(history, id)
            fs.directory(directory)
            val artifact = File(directory, "portable.dshbak")
            fs.create(artifact).use { PortableBackupCrypto.encrypt(ByteArrayInputStream("synthetic-$id".toByteArray()), it, "synthetic-password".toCharArray(), control) }
            val sha = fs.read(artifact, fs.stat(artifact)).use { BackupArchive.digest(it, control) }
            val metadata = linkedMapOf<String, Any>("encryptedSha256" to sha, "encryptedBytes" to artifact.length(), "entries" to 1L, "createdAt" to created, "integrity" to integrity, "requestedScope" to "application")
            if (marker != null) metadata["automatic"] = marker
            if (warnings) metadata["plugins"] = mapOf("complete" to false)
            fs.atomic(directory, "verified.json", BackupJson.write(metadata, BackupLimits.MANIFEST))
            fs.atomic(directory, "operation.json", BackupJson.write(mapOf("version" to 1L, "id" to id, "stage" to stage, "busy" to busy, "updatedAt" to created, "result" to "COMPLETE", "error" to "", "artifact" to "portable.dshbak"), 16384))
            return VerifiedBackupCopy.inspect(fs, operations, id)
        }
    }

    private fun remains(copy: VerifiedBackupCopy): Boolean = fs.stat(copy.artifact.parentFile).type == "DIRECTORY"
    private fun changeArtifact(copy: VerifiedBackupCopy) {
        val bytes = copy.artifact.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        copy.artifact.writeBytes(bytes)
    }

    @Test
    fun onlyExplicitBooleanAutomaticAndCompleteCopiesCountTowardThree() {
        val fixture = Fixture()
        val older = fixture.copy(1, warnings = true)
        val newest = (2L..4L).map { fixture.copy(it) }
        val retained = listOf(fixture.copy(0, marker = false), fixture.copy(0, marker = null), fixture.copy(0, marker = "true"), fixture.copy(0, integrity = "PARTIAL"), fixture.copy(0, integrity = "BEST_EFFORT"))
        AutomaticBackupPruner.prune(fs, retained + older + newest, control)
        assertFalse(remains(older))
        for (copy in retained + newest) assertTrue("retained ${copy.id}", remains(copy))
    }

    @Test
    fun tiesAreStableAndUnknownOrUnfinishedOriginalsDoNotBecomeDeletionCandidates() {
        val fixture = Fixture()
        val oldestBusy = fixture.copy(0, busy = true)
        val oldestOpen = fixture.copy(0, stage = "COMMITTING")
        val oldestUnknown = fixture.copy(0)
        fs.atomic(oldestUnknown.artifact.parentFile, "user-note", "retain".toByteArray())
        val tied = (1..4).map { fixture.copy(10) }
        AutomaticBackupPruner.prune(fs, listOf(oldestBusy, oldestOpen, oldestUnknown) + tied, control)
        for (copy in tied.take(3) + listOf(oldestBusy, oldestOpen, oldestUnknown)) assertTrue(remains(copy))
        assertFalse(remains(tied.last()))
    }

    @Test
    fun invalidEligibleCopyAbortsBeforeAnyDeletion() {
        val fixture = Fixture()
        val copies = (1L..5L).map { fixture.copy(it) }
        changeArtifact(copies.last())
        val failure = assertThrows(IOException::class.java) { AutomaticBackupPruner.prune(fs, copies, control) }
        assertEquals("VERIFIED_COPY_CHANGED", failure.message)
        for (copy in copies) assertTrue(remains(copy))
    }

    @Test
    fun candidateIsVerifiedAgainAfterReadingItsDirectory() {
        val fixture = Fixture()
        val copies = (1L..4L).map { fixture.copy(it) }
        var changed = false
        val checking = object : JvmBackupFileSystem() {
            override fun list(directory: File): List<String> {
                val names = super.list(directory)
                if (directory == copies.first().artifact.parentFile) {
                    changed = true
                    changeArtifact(copies.first())
                }
                return names
            }
        }
        val failure = assertThrows(IOException::class.java) { AutomaticBackupPruner.prune(checking, copies, control) }
        assertTrue(changed)
        assertEquals("VERIFIED_COPY_CHANGED", failure.message)
        for (copy in copies) assertTrue(remains(copy))
    }
}
