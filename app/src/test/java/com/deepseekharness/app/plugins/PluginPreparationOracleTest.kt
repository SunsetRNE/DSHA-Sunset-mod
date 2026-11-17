package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginBuiltinRegistrationPlan.Action
import com.deepseekharness.app.plugins.PluginBuiltinRegistrationPlan.StepResult
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Builtin
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.BuiltinFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Dependencies
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Dependency
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileFact
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.FileStat
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.LegacyPackage
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.PolicyFailure
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 输入均为旧实现独立采样的只读事实，预期来自隔离旧 writer；本测试只执行 Kotlin 纯计划。 */
@RunWith(Parameterized::class)
class PluginPreparationOracleTest(private val id: String, private val fixture: JsonObject) {
    private val input = fixture.getAsJsonObject("input")
    private val expected = fixture.getAsJsonObject("result")

    private fun obj(parent: JsonObject, key: String) = parent.getAsJsonObject(key) ?: JsonObject()

    private fun text(parent: JsonObject, key: String): String? =
        parent.get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun bool(parent: JsonObject, key: String) = parent.get(key)?.asBoolean == true

    private fun kind(value: String?) =
        when (value?.uppercase()) {
            "FILE",
            "REGULAR" -> NodeKind.REGULAR
            "DIRECTORY" -> NodeKind.DIRECTORY
            "SYMLINK" -> NodeKind.SYMLINK
            "MISSING",
            null -> NodeKind.MISSING
            else -> NodeKind.OTHER
        }

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun file(value: JsonObject): FileFact {
        val stat = value.get("stat")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asLong }
        return FileFact(
            kind(text(value, "kind")),
            bytes(text(value, "bytesHex") ?: ""),
            stat?.let { FileStat(it[0], it[1], it[2], it[3], it[4], it[5]) },
            value.get("stable")?.asBoolean != false,
            bool(value, "targetRegular"),
        )
    }

    private fun snapshot(): PluginPreparationSnapshot {
        val registry = obj(input, "registry")
        val builtins =
            registry.getAsJsonArray("plugins").map {
                it.asJsonObject.let { row ->
                    Builtin(
                        row.get("name").asString,
                        row.get("guestDirectory").asString,
                        bool(row, "defaultDisabled"),
                    )
                }
            }
        val aliases = obj(input, "aliases")
        val entities = obj(input, "entities")
        val metadata = obj(input, "metadata")
        val graphs = obj(input, "dependenciesCurrent")
        val legacy = LinkedHashMap<String, LegacyPackage>()
        for ((name, entry) in entities.entrySet()) {
            val entity = entry.asJsonObject
            val meta = obj(metadata, name)
            val graph = obj(graphs, "\$ROOT/root/.dsh/plugin-src/" + name)
            val nodes = obj(graph, "nodes")
            val resolved = ArrayList<Dependency>()
            for ((_, node) in nodes.entrySet()) {
                for ((depName, depValue) in obj(node.asJsonObject, "dependencies").entrySet()) {
                    val dep = depValue.asJsonObject
                    val target = text(dep, "target") ?: ""
                    resolved.add(
                        Dependency(
                            depName,
                            text(dep, "requested") ?: "",
                            target,
                            text(obj(nodes, target), "codeSha256") ?: "",
                        )
                    )
                }
            }
            val deps =
                text(graph, "sha256")?.let {
                    Dependencies(
                        it,
                        graph.getAsJsonArray("missing")?.map { missing -> missing.toString() }
                            ?: emptyList(),
                        resolved,
                        bool(graph, "complete"),
                        currentText = graph.toString(),
                    )
                }
            val compatibility = obj(obj(input, "compatibilityFacts"), name)
            val failure =
                text(compatibility, "errorCode")?.let {
                    PolicyFailure(it, text(compatibility, "message") ?: "")
                }
            legacy[name] =
                LegacyPackage(
                    kind(text(entity, "type")),
                    text(meta, "name"),
                    text(meta, "version"),
                    deps,
                    metadataValid = bool(meta, "valid"),
                    compatibilityChecked = bool(compatibility, "checked"),
                    policyFailure = failure,
                )
        }
        val files =
            obj(input, "markers").entrySet().associate { (name, value) ->
                name to file(obj(value.asJsonObject, "fileFact"))
            }
        return PluginPreparationSnapshot(
            id,
            manifestText = input.get("manifest")?.takeUnless { it.isJsonNull }?.toString(),
            builtins = builtins,
            officialBundles =
                registry.getAsJsonArray("officialBundles").map {
                    it.asJsonObject.get("name").asString
                },
            files = files,
            builtinFacts =
                builtins.associate { spec ->
                    val alias = obj(aliases, spec.name)
                    val entity = obj(entities, spec.name)
                    spec.name to
                        BuiltinFact(
                            obj(entity, "filesHex").has("package.json"),
                            kind(text(alias, "type")),
                            bool(alias, "canonicalCurrent"),
                            bool(alias, "packagePresent"),
                            bool(alias, "absolute"),
                        )
                },
            legacyPackages = legacy,
            apkMarker = file(obj(input, "apkMarker")),
            managedMarker = file(obj(input, "managedMarker")),
        )
    }

    @Test
    fun agreesWithIsolatedLegacyPython() {
        val snapshot = snapshot()
        assertEquals(id, input.get("apkGeneration").asString, snapshot.apkGeneration)
        val originalManifest = snapshot.manifestText
        val originalMarkers = snapshot.files.mapValues { it.value.bytes()?.toList() }
        if (fixture.get("operation").asString == "migrate") compareLegacy(snapshot)
        else compareBuiltin(snapshot)
        assertEquals(originalManifest, snapshot.manifestText)
        assertEquals(originalMarkers, snapshot.files.mapValues { it.value.bytes()?.toList() })
    }

    private fun compareLegacy(snapshot: PluginPreparationSnapshot) {
        val plan = PluginLegacyReviewPlan.create(snapshot)
        assertNull(id, plan.unavailableReason)
        val events = expected.getAsJsonArray("events").map { it.asJsonObject }
        val actualSteps =
            events
                .filter { text(it, "event") in listOf("queue-persisted", "enable-enter") }
                .map {
                    (if (text(it, "event") == "queue-persisted")
                        PluginLegacyReviewPlan.Action.QUEUE_ACTIVATION
                    else PluginLegacyReviewPlan.Action.ENABLE_PLUGIN) to text(it, "name")
                }
        assertEquals(id, actualSteps, plan.steps.map { it.action to it.name })
        val attempts = LinkedHashMap<String, PluginLegacyReviewPlan.Attempt>()
        for (event in events) {
            val name = text(event, "name")!!
            if (text(event, "event") == "enable-enter")
                assertTrue(id, bool(event, "queueAlreadyPersisted"))
            if (text(event, "event") == "enable-return")
                attempts[name] = PluginLegacyReviewPlan.Attempt(true, event.get("returnCode").asInt)
            if (text(event, "event") == "queue-persisted") {
                val step = plan.steps.first { it.name == name }
                assertEquals(text(event, "sha256"), step.fingerprint)
                assertEquals(text(event, "version"), step.version)
                val graph = snapshot.legacyPackages.getValue(name).dependencies!!
                assertEquals(
                    obj(input, "dependenciesCurrent").get("\$ROOT/root/.dsh/plugin-src/" + name),
                    JsonParser.parseString(graph.currentText),
                )
            }
        }
        val outcome = plan.classify(attempts)
        val emitted = expected.getAsJsonArray("emittedResults").last().asJsonObject
        assertEquals(id, text(expected, "classification"), outcome.status)
        assertEquals(id, expected.get("returnCode").asInt, outcome.exitCode)
        assertEquals(emitted.getAsJsonArray("activated").map { it.asString }, outcome.activated)
        assertEquals(emitted.getAsJsonArray("skipped").map { it.asString }, outcome.skipped)
        val blocked =
            emitted.getAsJsonArray("blockedPlugins")?.map {
                it.asJsonObject.let { row -> text(row, "name") to text(row, "errorCode") }
            } ?: emptyList()
        assertEquals(blocked, outcome.blocked.map { it.first to it.second.code })
    }

    private fun compareBuiltin(snapshot: PluginPreparationSnapshot) {
        val plan = PluginBuiltinRegistrationPlan.create(snapshot)
        assertNull(id, plan.unavailableReason)
        val results = plan.steps.indices.associateWith { StepResult.VERIFIED }.toMutableMap()
        var failedAt: Int? = null
        for (injection in fixture.getAsJsonArray("injections")) {
            val row = injection.asJsonObject
            if (text(row, "target") == "builtin.ensure_symlink") {
                val name = row.getAsJsonObject("value").keySet().single()
                failedAt =
                    plan.steps.indexOfFirst { it.action == Action.ENSURE_ALIAS && it.name == name }
            }
            if (text(row, "target") == "os.unlink source-record only")
                failedAt =
                    plan.steps.indexOfFirst { it.action == Action.CLEAR_DEFAULT_DISABLE_SOURCE }
        }
        if (failedAt != null) {
            assertTrue(id, failedAt >= 0)
            results[failedAt] = StepResult.FAILED
            val failed = plan.steps[failedAt]
            if (failed.stage == "present") {
                // 旧注册器累计别名失败，仍继续其余健康项；不写成功注册回执。
                for ((index, step) in plan.steps.withIndex()) {
                    if (step.stage == "receipt" || index > failedAt && step.name == failed.name)
                        results.remove(index)
                }
            } else for (index in failedAt + 1 until plan.steps.size) results.remove(index)
        }
        val outcome = plan.classify(results)
        assertEquals(id, text(expected, "classification"), outcome.classification.name)
        assertEquals(id, expected.get("returnCode").asInt, outcome.exitCode)
        val observed =
            plan.manifestAfter(results)?.let { JsonParser.parseString(it) } ?: JsonNull.INSTANCE
        assertEquals(id, expected.get("manifest"), observed)
        if (failedAt == null)
            assertEquals(
                id,
                expected.get("manifest"),
                plan.projectedManifestText?.let { JsonParser.parseString(it) } ?: JsonNull.INSTANCE,
            )
        val prefix =
            plan.steps
                .withIndex()
                .filter { results[it.index] == StepResult.VERIFIED }
                .map { it.value }
        val projectedAliases = snapshot.builtinFacts.toMutableMap()
        for (step in prefix) {
            val before = projectedAliases[step.name] ?: continue
            when (step.action) {
                Action.QUARANTINE_ALIAS,
                Action.REMOVE_ALIAS ->
                    projectedAliases[step.name] =
                        before.copy(
                            aliasKind = NodeKind.MISSING,
                            aliasCurrent = false,
                            aliasPackagePresent = false,
                            aliasAbsolute = false,
                        )
                Action.ENSURE_ALIAS ->
                    projectedAliases[step.name] =
                        before.copy(
                            aliasKind = NodeKind.SYMLINK,
                            aliasCurrent = true,
                            aliasPackagePresent = true,
                            aliasAbsolute = false,
                        )
                else -> Unit
            }
        }
        for ((name, alias) in projectedAliases) {
            val observedAlias = obj(obj(expected, "aliases"), name)
            assertEquals(id + ":" + name, kind(text(observedAlias, "type")), alias.aliasKind)
            assertEquals(
                id + ":" + name,
                bool(observedAlias, "canonicalCurrent"),
                alias.aliasCurrent,
            )
            assertEquals(
                id + ":" + name,
                bool(observedAlias, "packagePresent"),
                alias.aliasPackagePresent,
            )
            assertEquals(id + ":" + name, bool(observedAlias, "absolute"), alias.aliasAbsolute)
        }
        val profileFiles = obj(expected, "profileFiles")
        if (prefix.any { it.action == Action.ENSURE_PROFILE_FILES }) {
            for (name in listOf("cordis.patch.yml", "pnpm-workspace.yaml")) assertEquals(
                id,
                "file",
                text(obj(profileFiles, name), "type"),
            )
        } else assertEquals(id, input.get("profileFiles"), expected.get("profileFiles"))
        val quarantined = expected.getAsJsonArray("quarantine").map { it.asJsonObject }
        assertEquals(
            id,
            quarantined.map { it.getAsJsonObject("migration").get("name").asString }.sorted(),
            prefix.filter { it.action == Action.QUARANTINE_ALIAS }.map { it.name }.sorted(),
        )
        for (record in quarantined) {
            val name = record.getAsJsonObject("migration").get("name").asString
            assertEquals(
                "shadowed-signed-builtin",
                text(record.getAsJsonObject("migration"), "reason"),
            )
            assertEquals(
                obj(obj(input, "aliases"), name).get("filesHex"),
                record.getAsJsonObject("content").get("filesHex"),
            )
        }
        val afterMarkers = obj(expected, "markers")
        for ((name, marker) in snapshot.files) {
            val removed = prefix.any {
                it.name + ".disabled" == name && it.action == Action.CLEAR_DISABLED_MARKER ||
                    it.name + ".enabled" == name && it.action == Action.CLEAR_ENABLE_INTENT ||
                    it.name + ".default-disabled.json" == name &&
                        it.action == Action.CLEAR_DEFAULT_DISABLE_SOURCE
            }
            if (removed) assertFalse(id + ":" + name, afterMarkers.has(name))
            else {
                assertTrue(id + ":" + name, afterMarkers.has(name))
                assertArrayEquals(
                    id + ":" + name,
                    marker.bytes(),
                    file(obj(afterMarkers.getAsJsonObject(name), "fileFact")).bytes(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val stream =
                PluginPreparationOracleTest::class
                    .java
                    .getResourceAsStream("/plugin-s3b-oracle.json") ?: error("S3B_ORACLE_MISSING")
            val fixture =
                stream.bufferedReader(Charsets.UTF_8).use {
                    JsonParser.parseReader(it).asJsonObject
                }
            return fixture.getAsJsonArray("cases").map {
                arrayOf(it.asJsonObject.get("id").asString, it.asJsonObject)
            }
        }
    }
}
