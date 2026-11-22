package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginBuiltinRegistrationPlan.Action
import com.deepseekharness.app.plugins.PluginBuiltinRegistrationPlan.Classification
import com.deepseekharness.app.plugins.PluginBuiltinRegistrationPlan.StepResult
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Builtin
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.BuiltinFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileStat
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PluginBuiltinRegistrationPlanTest {
    private val mobile = "dsh-web-mobile"
    private val notifier = "dsh-task-notifier"
    private val official = listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app")

    private fun file(text: String) = FileFact(NodeKind.REGULAR, text.toByteArray())

    private fun snapshot(
        manifest: String? = null,
        files: Map<String, FileFact> = emptyMap(),
        facts: Map<String, BuiltinFact> =
            mapOf(mobile to BuiltinFact(true), notifier to BuiltinFact(true)),
        defaults: Boolean = false,
        names: List<String> = listOf(mobile, notifier),
    ) =
        PluginPreparationSnapshot(
            "builtin-one",
            manifestText = manifest,
            builtins =
                names.map {
                    Builtin(it, "/root/dsha-" + it.removePrefix("dsh-"), defaults && it == mobile)
                },
            officialBundles = official,
            files = files,
            builtinFacts = facts,
            apkMarker = file("DSHA_APK_GENERATION_V1\n369\n"),
        )

    private fun success(plan: PluginBuiltinRegistrationPlan) =
        plan.classify(plan.steps.indices.associateWith { StepResult.VERIFIED })

    private fun document(plan: PluginBuiltinRegistrationPlan) =
        JsonParser.parseString(plan.projectedManifestText).asJsonObject

    private fun bundles(plan: PluginBuiltinRegistrationPlan) =
        document(plan)
            .getAsJsonObject("dsh")
            .getAsJsonObject("profile")
            .getAsJsonArray("bundles")
            .map { it.asString }

    @Test
    fun newProfileUsesOfficialSelectionAndCurrentApkDependencies() {
        val plan = PluginBuiltinRegistrationPlan.create(snapshot())
        assertNull(plan.unavailableReason)
        assertEquals(official + listOf(mobile, notifier), bundles(plan))
        assertEquals(
            "link:/root/dsha-web-mobile",
            document(plan).getAsJsonObject("dependencies").get(mobile).asString,
        )
        assertEquals(
            "startup",
            document(plan)
                .getAsJsonObject("dsh")
                .getAsJsonObject("profile")
                .get("patchReload")
                .asString,
        )
        assertEquals(Action.CHECK_RUNTIME_MODULES, plan.steps.first().action)
        assertTrue(plan.requiresRuntimeModuleScan)
        assertEquals(Classification.UNKNOWN, plan.classify().classification)
        assertEquals(Classification.OK, success(plan).classification)
    }

    @Test
    fun mergePreservesUserFieldsAndDoesNotReinsertRemovedOfficialSelection() {
        val plan =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    """{"name":"user-profile","custom":{"keep":true},"dependencies":{"user-plugin":"^1.0.0"},"dsh":{"custom":42,"profile":{"bundles":["user-plugin"],"custom":7,"patchReload":"live"}}}"""
                )
            )
        val doc = document(plan)
        assertEquals(listOf("user-plugin", mobile, notifier), bundles(plan))
        assertEquals("^1.0.0", doc.getAsJsonObject("dependencies").get("user-plugin").asString)
        assertEquals(7, doc.getAsJsonObject("dsh").getAsJsonObject("profile").get("custom").asInt)
        assertTrue(doc.getAsJsonObject("custom").get("keep").asBoolean)
    }

    @Test
    fun userDisableQuarantinesShadowEntityAndRetainsDependencyWithoutSelection() {
        val input =
            snapshot(
                files = mapOf(mobile + ".disabled" to file("")),
                facts =
                    mapOf(
                        mobile to BuiltinFact(true, NodeKind.DIRECTORY),
                        notifier to BuiltinFact(true),
                    ),
            )
        val plan = PluginBuiltinRegistrationPlan.create(input)
        assertFalse(mobile in bundles(plan))
        assertTrue(notifier in bundles(plan))
        assertEquals(listOf(mobile), plan.skipped)
        assertTrue(plan.steps.any { it.action == Action.QUARANTINE_ALIAS && it.name == mobile })
        assertFalse(plan.steps.any { it.action == Action.ENSURE_ALIAS && it.name == mobile })
        assertTrue(input.file(mobile).exact(""))
    }

    @Test
    fun onlyExactWebDisableKeepsDormantAliasAndSymlinkMarkerDoesNotQualify() {
        for (text in
            listOf(
                PluginPreparationSnapshot.WEB_DISABLED_MARKER,
                PluginPreparationSnapshot.WEB_DISABLED_MARKER.replace("\n", "\r\n"),
            )) {
            val plan =
                PluginBuiltinRegistrationPlan.create(
                    snapshot(files = mapOf(mobile + ".disabled" to file(text)))
                )
            assertFalse(mobile in bundles(plan))
            assertTrue(plan.steps.any { it.name == mobile && it.action == Action.VERIFY_ALIAS })
        }
        for (marker in
            listOf(
                file(PluginPreparationSnapshot.WEB_DISABLED_MARKER + "\n"),
                FileFact(
                    NodeKind.SYMLINK,
                    PluginPreparationSnapshot.WEB_DISABLED_MARKER.toByteArray(),
                    targetRegular = true,
                ),
                file(PluginPreparationSnapshot.SAFE_MODE_MARKER),
            )) {
            val plan =
                PluginBuiltinRegistrationPlan.create(
                    snapshot(files = mapOf(mobile + ".disabled" to marker))
                )
            assertFalse(mobile in bundles(plan))
            assertFalse(plan.steps.any { it.name == mobile && it.action == Action.ENSURE_ALIAS })
        }
    }

    @Test
    fun autoReviewAliasesShareDisableIntentAndAvoidDuplicateRows() {
        val alias = PluginPreparationSnapshot.AUTO_REVIEW_ALIAS
        val canonical = PluginPreparationSnapshot.AUTO_REVIEW_CANONICAL
        val manifest = """{"dsh":{"profile":{"bundles":["$alias","$canonical","user-plugin"]}}}"""
        val facts = mapOf(alias to BuiltinFact(true))
        val enabled =
            PluginBuiltinRegistrationPlan.create(
                snapshot(manifest, names = listOf(alias), facts = facts)
            )
        assertEquals(listOf(canonical, "user-plugin"), bundles(enabled))
        val disabled =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    manifest,
                    mapOf(canonical + ".disabled" to file("")),
                    facts,
                    names = listOf(alias),
                )
            )
        assertEquals(listOf("user-plugin"), bundles(disabled))
    }

    @Test
    fun defaultOffEnableIntentIsBoundToExactCurrentApkGenerationAndDisableWins() {
        val enabled = PluginPreparationSnapshot.ENABLED_HEADER + "\napk:369\n"
        val kept =
            PluginBuiltinRegistrationPlan.create(
                snapshot(files = mapOf(mobile + ".enabled" to file(enabled)), defaults = true)
            )
        assertTrue(mobile in bundles(kept))
        for (text in listOf(enabled.replace("369", "368"), enabled + "X", "")) {
            val plan =
                PluginBuiltinRegistrationPlan.create(
                    snapshot(files = mapOf(mobile + ".enabled" to file(text)), defaults = true)
                )
            assertFalse(mobile in bundles(plan))
            assertTrue(
                plan.steps.any { it.action == Action.CREATE_DEFAULT_DISABLE && it.contents == "" }
            )
        }
        val explicit =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    files =
                        mapOf(
                            mobile + ".enabled" to file(enabled),
                            mobile + ".disabled" to file(""),
                        ),
                    defaults = true,
                )
            )
        assertFalse(mobile in bundles(explicit))
        assertTrue(
            explicit.steps.any { it.action == Action.CLEAR_ENABLE_INTENT && it.name == mobile }
        )
    }

    @Test
    fun automaticDefaultDisableRestoresOnlyUnchangedSixFieldIdentity() {
        val markerStat = FileStat(1, 2, 33152, 0, 3, 4)
        val source = """{"version":1,"source":"builtin-default","disabledStat":[1,2,33152,0,3,4]}"""
        val sourceFile =
            FileFact(
                NodeKind.REGULAR,
                source.toByteArray(),
                FileStat(1, 9, 33152, source.length.toLong(), 10, 11),
            )
        fun plan(stat: FileStat) =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    files =
                        mapOf(
                            mobile + ".disabled" to FileFact(NodeKind.REGULAR, byteArrayOf(), stat),
                            mobile + ".default-disabled.json" to sourceFile,
                        )
                )
            )
        val restored = plan(markerStat)
        assertTrue(mobile in bundles(restored))
        assertTrue(
            restored.steps.any { it.name == mobile && it.action == Action.CLEAR_DISABLED_MARKER }
        )
        val replaced = plan(markerStat.copy(inode = 20))
        assertFalse(mobile in bundles(replaced))
        assertFalse(replaced.steps.any { it.action == Action.CLEAR_DISABLED_MARKER })
        val absent =
            PluginBuiltinRegistrationPlan.create(
                snapshot(files = mapOf(mobile + ".default-disabled.json" to sourceFile))
            )
        assertTrue(
            absent.steps.any {
                it.name == mobile && it.action == Action.CLEAR_DEFAULT_DISABLE_SOURCE
            }
        )
        assertFalse(absent.steps.any { it.action == Action.CLEAR_DISABLED_MARKER })
    }

    @Test
    fun missingSignedEntityRemovesShadowSelectionAndDependencyButIsPartial() {
        val plan =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    """{"dependencies":{"$mobile":"link:/old","user":"^1"},"dsh":{"profile":{"bundles":["$mobile","user"]}}}""",
                    facts =
                        mapOf(
                            mobile to BuiltinFact(false, NodeKind.DIRECTORY),
                            notifier to BuiltinFact(true),
                        ),
                )
            )
        assertEquals(listOf(mobile), plan.missing)
        assertFalse(mobile in bundles(plan))
        assertFalse(document(plan).getAsJsonObject("dependencies").has(mobile))
        assertTrue(
            plan.steps.any {
                it.action == Action.QUARANTINE_ALIAS && it.stage == "missing" && it.bestEffort
            }
        )
        assertEquals(Classification.PARTIAL, success(plan).classification)
        assertFalse(plan.steps.any { it.action == Action.WRITE_REGISTER_IDENTITY })
    }

    @Test
    fun enabledAliasFailureIsPartialButDormantAliasFailureIsFail() {
        val enabled = PluginBuiltinRegistrationPlan.create(snapshot())
        val disabled =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    files =
                        mapOf(
                            mobile + ".disabled" to
                                file(PluginPreparationSnapshot.WEB_DISABLED_MARKER)
                        )
                )
            )
        fun failFirstAlias(plan: PluginBuiltinRegistrationPlan): Classification {
            val index = plan.steps.indexOfFirst { it.action == Action.ENSURE_ALIAS }
            return plan
                .classify(
                    plan.steps.indices.associateWith {
                        if (it == index) StepResult.FAILED else StepResult.VERIFIED
                    }
                )
                .classification
        }
        assertEquals(Classification.PARTIAL, failFirstAlias(enabled))
        assertEquals(Classification.FAIL, failFirstAlias(disabled))
        assertNull(enabled.classify(mapOf(0 to StepResult.VERIFIED)).exitCode)
    }

    @Test
    fun unknownDisableFactsCannotCreateAnEnablePlan() {
        for (marker in
            listOf(
                FileFact(NodeKind.UNREADABLE),
                FileFact(NodeKind.REGULAR),
                FileFact(NodeKind.REGULAR, byteArrayOf(), stable = false),
                FileFact(NodeKind.MISSING, stable = false),
            )) {
            val plan =
                PluginBuiltinRegistrationPlan.create(
                    snapshot(files = mapOf(mobile + ".disabled" to marker))
                )
            assertTrue(plan.steps.isEmpty())
            assertEquals(Classification.UNKNOWN, plan.classify().classification)
        }
    }

    @Test
    fun unknownApkGenerationCannotReuseARuntimeEnableIntent() {
        val managed = file("DSHA_MANAGED_ASSETS_V2\n" + "a".repeat(64) + "\n")
        val base =
            snapshot(
                defaults = true,
                files =
                    mapOf(
                        mobile + ".enabled" to
                            file(
                                PluginPreparationSnapshot.ENABLED_HEADER +
                                    "\nruntime:" +
                                    "a".repeat(64) +
                                    "\n"
                            )
                    ),
            )
        for (apk in
            listOf(
                FileFact(NodeKind.UNREADABLE),
                FileFact(
                    NodeKind.REGULAR,
                    "DSHA_APK_GENERATION_V1\n369\n".toByteArray(),
                    stable = false,
                ),
                FileFact(NodeKind.REGULAR, "bad".toByteArray(), FileStat(1, 2, 33152, 1, 3, 4)),
                FileFact(NodeKind.MISSING, stable = false),
            )) {
            val input =
                PluginPreparationSnapshot(
                    "generation-unknown",
                    builtins = base.builtins,
                    officialBundles = base.officialBundles,
                    files = base.files,
                    builtinFacts = base.builtinFacts,
                    apkMarker = apk,
                    managedMarker = managed,
                )
            val plan = PluginBuiltinRegistrationPlan.create(input)
            assertEquals("GENERATION_UNOBSERVED", plan.unavailableReason)
            assertTrue(plan.steps.isEmpty())
            assertEquals(Classification.UNKNOWN, plan.classify().classification)
        }
    }

    @Test
    fun oneFailedAliasStillRequiresReceiptForHealthySibling() {
        val plan = PluginBuiltinRegistrationPlan.create(snapshot())
        val first =
            plan.steps.indexOfFirst { it.name == mobile && it.action == Action.ENSURE_ALIAS }
        val second =
            plan.steps.indexOfFirst { it.name == notifier && it.action == Action.ENSURE_ALIAS }
        val receipts = plan.steps.indices.associateWith { StepResult.VERIFIED }.toMutableMap()
        receipts[first] = StepResult.FAILED
        receipts.remove(second)
        assertEquals(Classification.UNKNOWN, plan.classify(receipts).classification)
        receipts[second] = StepResult.VERIFIED
        assertEquals(Classification.PARTIAL, plan.classify(receipts).classification)
    }

    @Test
    fun unchangedDisabledProfileKeepsPersistedPatchReloadWhenOldWriterDoesNotWrite() {
        val manifest =
            """{"name":"keep","dependencies":{"$mobile":"link:/root/dsha-web-mobile"},"dsh":{"profile":{"bundles":[],"patchReload":"live"}}}"""
        val plan =
            PluginBuiltinRegistrationPlan.create(
                snapshot(
                    manifest,
                    files = mapOf(mobile + ".disabled" to file("")),
                    names = listOf(mobile),
                    facts = mapOf(mobile to BuiltinFact(true)),
                )
            )
        assertFalse(plan.steps.any { it.action == Action.WRITE_MANIFEST })
        assertEquals(
            "live",
            document(plan)
                .getAsJsonObject("dsh")
                .getAsJsonObject("profile")
                .get("patchReload")
                .asString,
        )
    }
}
