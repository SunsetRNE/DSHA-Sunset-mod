package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileStat
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import org.junit.Assert.*
import org.junit.Test

class PluginPreparationSnapshotTest {
    @Test
    fun copiedCollectionsAndMarkerBytesCannotChangeTheSnapshot() {
        val bytes = PluginPreparationSnapshot.LEGACY_REVIEW_MARKER.toByteArray()
        val fact = FileFact(NodeKind.REGULAR, bytes)
        val files = linkedMapOf("fixture.disabled" to fact)
        val missing = arrayListOf("absent")
        val graph = PluginPreparationSnapshot.Dependencies("a".repeat(64), missing)
        val snapshot = PluginPreparationSnapshot("one", files = files)
        bytes[0] = 0
        fact.bytes()!![0] = 0
        files.clear()
        missing.clear()
        assertTrue(snapshot.file("fixture").exact(PluginPreparationSnapshot.LEGACY_REVIEW_MARKER))
        assertEquals(listOf("absent"), graph.missing)
        try {
            (snapshot.files as MutableMap).clear()
            fail("快照不得可变")
        } catch (_: UnsupportedOperationException) {}
    }

    @Test
    fun manifestProjectionDoesNotMutateOriginalAndRejectsNonJson() {
        val snapshot = PluginPreparationSnapshot("one", manifestText = "{\"custom\":{\"value\":1}}")
        snapshot.manifest()!!.getAsJsonObject("custom").addProperty("value", 2)
        assertEquals(1, snapshot.manifest()!!.getAsJsonObject("custom").get("value").asInt)
        for (text in listOf("[]", "null", "{custom:1}", "{} {}", "{/* comment */}")) {
            try {
                PluginPreparationSnapshot("one", manifestText = text).manifest()
                fail(text)
            } catch (_: Exception) {}
        }
    }

    @Test
    fun apkGenerationKeepsOldAsciiSplitlinesAndFallbackRules() {
        fun file(text: String) = FileFact(NodeKind.REGULAR, text.toByteArray())
        val managed = file("DSHA_MANAGED_ASSETS_V2\n" + "a".repeat(64) + "\n")
        for (ending in listOf("\n", "\r\n", "\r", "\u000b", "\u001c")) {
            assertEquals(
                "apk:369",
                PluginPreparationSnapshot.generation(
                    file("DSHA_APK_GENERATION_V1" + ending + "369" + ending),
                    managed,
                ),
            )
        }
        assertEquals(
            "apk:000369",
            PluginPreparationSnapshot.generation(file("DSHA_APK_GENERATION_V1\n000369"), managed),
        )
        for (bad in
            listOf(
                "",
                "DSHA_APK_GENERATION_V1\n369\n\n",
                "DSHA_APK_GENERATION_V1\n-1",
                "DSHA_APK_GENERATION_V1\n1234567890123",
                "DSHA_APK_GENERATION_V1\n３６９",
            )) {
            assertEquals(
                "runtime:" + "a".repeat(64),
                PluginPreparationSnapshot.generation(file(bad), managed),
            )
        }
        assertEquals(
            "unmanaged",
            PluginPreparationSnapshot.generation(
                FileFact(),
                file("DSHA_MANAGED_ASSETS_V2\n" + "A".repeat(64)),
            ),
        )
    }

    @Test
    fun binaryReviewBytesAndPythonTextNewlinesHaveDifferentContracts() {
        val crlf = FileFact(NodeKind.REGULAR, "DSHA_REVIEW_REQUIRED\r\n".toByteArray())
        assertFalse(crlf.exact(PluginPreparationSnapshot.LEGACY_REVIEW_MARKER))
        assertEquals(PluginPreparationSnapshot.LEGACY_REVIEW_MARKER, crlf.pythonText())
        assertNull(FileFact(NodeKind.REGULAR, byteArrayOf(0xc3.toByte(), 0x28)).text())
        assertNull(FileFact(NodeKind.REGULAR, byteArrayOf(1), stable = false).text())
        assertTrue(FileFact(NodeKind.SYMLINK, targetRegular = true).isFile)
        assertFalse(FileFact(NodeKind.SYMLINK).isFile)
    }

    @Test
    fun defaultDisabledSourceRequiresExactTypedFieldsAndBoundedStableFile() {
        val valid =
            "{\"version\":1,\"source\":\"builtin-default\",\"disabledStat\":[1,2,33152,0,5,6]}"
        fun snapshot(
            text: String,
            kind: NodeKind = NodeKind.REGULAR,
            stable: Boolean = true,
        ): PluginPreparationSnapshot {
            val bytes = text.toByteArray()
            return PluginPreparationSnapshot(
                "one",
                files =
                    mapOf(
                        "fixture.default-disabled.json" to
                            FileFact(
                                kind,
                                bytes,
                                FileStat(1, 20, 33152, bytes.size.toLong(), 5, 6),
                                stable,
                            )
                    ),
            )
        }
        assertEquals(
            listOf(1L, 2L, 33152L, 0L, 5L, 6L),
            snapshot(valid).defaultDisabledStat("fixture"),
        )
        for (bad in
            listOf(
                valid.replace("\"version\":1", "\"version\":true"),
                valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid.replace("builtin-default", "user"),
                valid.replace("5,6]", "5]"),
                valid.replace("5,6]", "5,6,7]"),
                valid.dropLast(1),
                valid + " ".repeat(1025),
            )) {
            assertNull(bad, snapshot(bad).defaultDisabledStat("fixture"))
        }
        assertNull(snapshot(valid, NodeKind.SYMLINK).defaultDisabledStat("fixture"))
        assertNull(snapshot(valid, stable = false).defaultDisabledStat("fixture"))
    }

    @Test
    fun nameAndAliasRulesDoNotFoldUnrelatedPackages() {
        assertTrue(PluginPreparationSnapshot.validName("@fixture/scoped-name"))
        for (bad in listOf("../fixture", "@a/../b", "a\\b", "Upper", "a".repeat(215))) assertFalse(
            PluginPreparationSnapshot.validName(bad)
        )
        assertEquals(
            listOf("dsh-auto-review", "@deepseek-ai/dsh-experimental-auto-review"),
            PluginPreparationSnapshot.aliases("dsh-auto-review"),
        )
        assertEquals(listOf("fixture"), PluginPreparationSnapshot.aliases("fixture"))
    }
}
