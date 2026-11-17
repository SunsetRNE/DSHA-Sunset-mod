package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginLegacyReviewPlan.Action
import com.deepseekharness.app.plugins.PluginLegacyReviewPlan.Attempt
import com.deepseekharness.app.plugins.PluginLegacyReviewPlan.Decision
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Dependencies
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.LegacyPackage
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.PolicyFailure
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PluginLegacyReviewPlanTest {
    private val name = "fixture-plugin"

    private fun pkg() =
        LegacyPackage(NodeKind.DIRECTORY, name, "1.2.3", Dependencies("a".repeat(64)))

    private fun snapshot(
        marker: String = PluginPreparationSnapshot.LEGACY_REVIEW_MARKER,
        kind: NodeKind = NodeKind.REGULAR,
        source: LegacyPackage? = pkg(),
        reference: String = "link:/root/.dsh/plugin-src/" + name,
        complete: Boolean = true,
        profile: String = "web",
    ): PluginPreparationSnapshot {
        val manifest = JsonObject()
        val dependencies = JsonObject()
        dependencies.addProperty(name, reference)
        manifest.add("dependencies", dependencies)
        return PluginPreparationSnapshot(
            "legacy-one",
            profile = profile,
            complete = complete,
            manifestText = manifest.toString(),
            files =
                mapOf(
                    name + ".disabled" to
                        FileFact(
                            kind,
                            marker.toByteArray(),
                            targetRegular = kind == NodeKind.SYMLINK,
                        )
                ),
            legacyPackages = if (source == null) emptyMap() else mapOf(name to source),
        )
    }

    @Test
    fun onlyExactBinaryReviewMarkerCanQueueActivation() {
        val exact = PluginLegacyReviewPlan.create(snapshot())
        assertEquals(
            listOf(Action.QUEUE_ACTIVATION, Action.ENABLE_PLUGIN),
            exact.steps.map { it.action },
        )
        assertEquals("a".repeat(64), exact.steps[0].fingerprint)
        assertEquals("1.2.3", exact.steps[0].version)
        assertEquals("unknown", exact.classify().status)
        val outcome = exact.classify(mapOf(name to Attempt(true, 0)))
        assertEquals("ok", outcome.status)
        assertEquals(listOf(name), outcome.activated)
        for (marker in
            listOf(
                "",
                "DSHA_SAFE_MODE\n",
                "DSHA_REVIEW_REQUIRED",
                "DSHA_REVIEW_REQUIRED\r\n",
                "DSHA_REVIEW_REQUIRED\nX",
                "DSHA_REVIEW_REQUIREX\n",
            )) {
            val plan = PluginLegacyReviewPlan.create(snapshot(marker))
            assertEquals(marker, Decision.IGNORE, plan.rows.single().decision)
            assertTrue(plan.steps.isEmpty())
        }
        for (kind in listOf(NodeKind.SYMLINK, NodeKind.DIRECTORY, NodeKind.MISSING)) assertTrue(
            PluginLegacyReviewPlan.create(snapshot(kind = kind)).steps.isEmpty()
        )
    }

    @Test
    fun sourceReferenceAndActualDirectoryMustBothMatch() {
        for (reference in
            listOf(
                "^1.2.3",
                "link:/other/fixture-plugin",
                "link:/root/.dsh/plugin-src/fixture-plugin/",
                "link:/root/.dsh/plugin-src/../fixture-plugin",
            )) {
            assertEquals(
                Decision.SKIP,
                PluginLegacyReviewPlan.create(snapshot(reference = reference))
                    .rows
                    .single()
                    .decision,
            )
        }
        for (kind in listOf(NodeKind.SYMLINK, NodeKind.MISSING, NodeKind.REGULAR)) assertEquals(
            Decision.SKIP,
            PluginLegacyReviewPlan.create(snapshot(source = pkg().copy(directoryKind = kind)))
                .rows
                .single()
                .decision,
        )
        assertEquals(
            Decision.UNAVAILABLE,
            PluginLegacyReviewPlan.create(snapshot(source = null)).rows.single().decision,
        )
    }

    @Test
    fun actualMissingDependencyAndWrongPackageIdentityKeepOriginalDisabled() {
        for (source in
            listOf(
                pkg().copy(metadataName = "impostor"),
                pkg().copy(metadataValid = false),
                pkg()
                    .copy(
                        dependencies =
                            Dependencies(
                                "b".repeat(64),
                                listOf("missing-real-dependency"),
                                complete = false,
                            )
                    ),
            )) {
            val plan = PluginLegacyReviewPlan.create(snapshot(source = source))
            assertTrue(plan.steps.isEmpty())
            assertEquals(listOf(name), plan.classify().skipped)
            assertEquals("ok", plan.classify().status)
        }
        for (source in
            listOf(
                pkg().copy(directoryKind = NodeKind.UNREADABLE),
                pkg().copy(dependencies = null),
                pkg().copy(compatibilityChecked = false),
                pkg().copy(dependencies = Dependencies("b".repeat(64), complete = false)),
            )) {
            assertEquals(
                "unknown",
                PluginLegacyReviewPlan.create(snapshot(source = source)).classify().status,
            )
        }
    }

    @Test
    fun policyBlockIsPartialButGenericEnableFailureRemainsOkAndSkipped() {
        val failure = PolicyFailure("PLUGIN_INCOMPATIBLE", "版本不兼容")
        val plan =
            PluginLegacyReviewPlan.create(snapshot(source = pkg().copy(policyFailure = failure)))
        assertTrue(plan.steps.isEmpty())
        assertEquals("partial", plan.classify().status)
        assertEquals(1, plan.classify().exitCode)
        assertEquals(listOf(name to failure), plan.classify().blocked)
        assertEquals("failed", plan.classify().application)
        assertEquals("enable", plan.classify().stage)
        val ready = PluginLegacyReviewPlan.create(snapshot())
        for (attempt in listOf(Attempt(false, null), Attempt(true, 1))) {
            assertEquals("ok", ready.classify(mapOf(name to attempt)).status)
            assertEquals(listOf(name), ready.classify(mapOf(name to attempt)).skipped)
        }
        assertEquals("unknown", ready.classify(mapOf(name to Attempt(true, null))).status)
    }

    @Test
    fun resultOrderFollowsManifestRatherThanReceiptMapOrder() {
        val a = "first-plugin"
        val b = "second-plugin"
        val manifest =
            "{\"dependencies\":{\"$a\":\"link:/root/.dsh/plugin-src/$a\",\"$b\":\"link:/root/.dsh/plugin-src/$b\"}}"
        val snapshot =
            PluginPreparationSnapshot(
                "ordered",
                manifestText = manifest,
                files =
                    listOf(a, b).associate {
                        it + ".disabled" to
                            FileFact(
                                NodeKind.REGULAR,
                                PluginPreparationSnapshot.LEGACY_REVIEW_MARKER.toByteArray(),
                            )
                    },
                legacyPackages = listOf(a, b).associateWith { pkg().copy(metadataName = it) },
            )
        val plan = PluginLegacyReviewPlan.create(snapshot)
        assertEquals(listOf(a, a, b, b), plan.steps.map { it.name })
        assertEquals(
            listOf(a, b),
            plan.classify(linkedMapOf(b to Attempt(true, 0), a to Attempt(true, 0))).activated,
        )
    }

    @Test
    fun unreadableOrChangedMarkerCannotMasqueradeAsIgnored() {
        val original = snapshot()
        for (marker in
            listOf(
                FileFact(NodeKind.UNREADABLE),
                FileFact(NodeKind.REGULAR),
                FileFact(NodeKind.REGULAR, "X".toByteArray(), stable = false),
                FileFact(NodeKind.MISSING, stable = false),
            )) {
            val input =
                PluginPreparationSnapshot(
                    "unknown",
                    manifestText = original.manifestText,
                    files = mapOf(name + ".disabled" to marker),
                    legacyPackages = original.legacyPackages,
                )
            val plan = PluginLegacyReviewPlan.create(input)
            assertTrue(plan.steps.isEmpty())
            assertEquals("unknown", plan.classify().status)
        }
    }

    @Test
    fun reservedNamesAndUnobservedPreparationNeverCreateExecutionSteps() {
        val source = snapshot()
        val reserved =
            PluginPreparationSnapshot(
                "reserved",
                manifestText = source.manifestText,
                files = source.files,
                officialBundles = listOf(name),
                legacyPackages = source.legacyPackages,
            )
        assertTrue(PluginLegacyReviewPlan.create(reserved).steps.isEmpty())
        for (input in listOf(snapshot(complete = false), snapshot(profile = "recovery"))) {
            val plan = PluginLegacyReviewPlan.create(input)
            assertTrue(plan.steps.isEmpty())
            assertNull(plan.classify().exitCode)
        }
    }
}
