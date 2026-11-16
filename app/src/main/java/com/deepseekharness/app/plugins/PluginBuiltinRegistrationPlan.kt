package com.deepseekharness.app.plugins

import com.deepseekharness.app.plugins.PluginPreparationSnapshot.Companion.immutableList
import com.deepseekharness.app.plugins.PluginPreparationSnapshot.NodeKind
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** normal-web 内置注册的纯计划。所有文件操作都是描述，生产调用链仍使用旧注册器。 */
class PluginBuiltinRegistrationPlan
private constructor(
    val snapshot: PluginPreparationSnapshot,
    steps: List<Step>,
    val projectedManifestText: String?,
    present: List<String>,
    skipped: List<String>,
    missing: List<String>,
    val unavailableReason: String? = null,
) {
    val steps: List<Step> = immutableList(steps)
    val present: List<String> = immutableList(present)
    val skipped: List<String> = immutableList(skipped)
    val missing: List<String> = immutableList(missing)
    // 每次准备仍要求旧运行时扫描；本计划不提供缓存命中或免扫描许可。
    val requiresRuntimeModuleScan: Boolean = true

    enum class Action {
        CHECK_RUNTIME_MODULES,
        CLEAR_ENABLE_INTENT,
        CREATE_DEFAULT_DISABLE,
        CLEAR_DEFAULT_DISABLE_SOURCE,
        CLEAR_DISABLED_MARKER,
        REMOVE_ALIAS,
        QUARANTINE_ALIAS,
        ENSURE_ALIAS,
        VERIFY_ALIAS,
        ENSURE_PROFILE_FILES,
        WRITE_MANIFEST,
        WRITE_REGISTER_IDENTITY,
    }

    data class Step(
        val action: Action,
        val stage: String,
        val name: String = "",
        val directory: String = "",
        val contents: String? = null,
        val bestEffort: Boolean = false,
    )

    enum class StepResult {
        VERIFIED,
        FAILED,
        UNKNOWN,
    }

    enum class Classification {
        OK,
        PARTIAL,
        FAIL,
        UNKNOWN,
    }

    data class Outcome(val classification: Classification, val exitCode: Int?)

    /** 回执按计划序号对应；缺回执不是成功，写后链接核验失败沿用旧 FAIL/PARTIAL 区别。 */
    fun classify(results: Map<Int, StepResult> = emptyMap()): Outcome {
        if (unavailableReason != null) return Outcome(Classification.UNKNOWN, null)
        var partial = false
        val failedAliases = HashSet<String>()
        for ((index, step) in steps.withIndex()) {
            if (
                step.stage == "receipt" && partial ||
                    step.stage == "present" && step.name in failedAliases
            )
                continue
            when (results[index]) {
                null,
                StepResult.UNKNOWN -> return Outcome(Classification.UNKNOWN, null)
                StepResult.FAILED ->
                    if (!step.bestEffort) {
                        if (
                            step.stage == "present" &&
                                step.action in
                                    listOf(
                                        Action.ENSURE_ALIAS,
                                        Action.VERIFY_ALIAS,
                                        Action.QUARANTINE_ALIAS,
                                    )
                        ) {
                            partial = true
                            failedAliases.add(step.name)
                        } else return Outcome(Classification.FAIL, 1)
                    }
                StepResult.VERIFIED -> Unit
            }
        }
        return Outcome(
            if (partial || missing.isNotEmpty()) Classification.PARTIAL else Classification.OK,
            if (partial || missing.isNotEmpty()) 1 else 0,
        )
    }

    /** 仅投影有 VERIFIED 回执的 manifest 写入前缀；失败后的后续计划不能当作已落盘。 */
    fun manifestAfter(results: Map<Int, StepResult>): String? {
        var manifest = snapshot.manifestText
        for ((index, step) in steps.withIndex()) {
            when (results[index]) {
                StepResult.VERIFIED ->
                    if (step.action == Action.WRITE_MANIFEST) manifest = step.contents
                StepResult.FAILED -> if (!step.bestEffort) return manifest
                else -> return manifest
            }
        }
        return manifest
    }

    companion object {
        @JvmStatic
        fun create(snapshot: PluginPreparationSnapshot): PluginBuiltinRegistrationPlan {
            fun unavailable(reason: String) =
                PluginBuiltinRegistrationPlan(
                    snapshot,
                    emptyList(),
                    null,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    reason,
                )
            if (!snapshot.complete || snapshot.profile != "web" || snapshot.preparationId.isEmpty())
                return unavailable("PREPARATION_UNAVAILABLE")
            if (
                snapshot.builtins.map { it.name }.distinct().size != snapshot.builtins.size ||
                    snapshot.builtins.any {
                        !PluginPreparationSnapshot.validName(it.name) ||
                            !it.guestDirectory.startsWith("/")
                    }
            )
                return unavailable("BUILTIN_REGISTRY_FORMAT")
            if (snapshot.builtins.any { !snapshot.builtinFacts.containsKey(it.name) })
                return unavailable("BUILTIN_FACTS_INCOMPLETE")
            if (!snapshot.apkMarker.observed || !snapshot.managedMarker.observed)
                return unavailable("GENERATION_UNOBSERVED")
            val intentNames =
                snapshot.builtins.flatMap { PluginPreparationSnapshot.aliases(it.name) }
            if (
                intentNames.any { name ->
                    listOf(".disabled", ".enabled", ".default-disabled.json").any { suffix ->
                        !snapshot.file(name, suffix).observed
                    }
                } || snapshot.builtinFacts.values.any { it.aliasKind == NodeKind.UNREADABLE }
            )
                return unavailable("INTENT_FACTS_UNAVAILABLE")
            return try {
                build(snapshot)
            } catch (_: Exception) {
                unavailable("PROFILE_MANIFEST_FORMAT")
            }
        }

        private fun build(snapshot: PluginPreparationSnapshot): PluginBuiltinRegistrationPlan {
            val steps = ArrayList<Step>()
            steps.add(Step(Action.CHECK_RUNTIME_MODULES, "runtime"))
            val markers = snapshot.files.toMutableMap()
            fun disabled(name: String): Boolean =
                PluginPreparationSnapshot.aliases(name).any {
                    markers[it + ".disabled"]?.isFile == true
                }
            fun add(
                action: Action,
                stage: String,
                name: String = "",
                directory: String = "",
                contents: String? = null,
                bestEffort: Boolean = false,
            ) {
                steps.add(Step(action, stage, name, directory, contents, bestEffort))
            }

            // 只撤销独立来源与当前空标记六字段身份完全一致的自动默认禁用。
            for (spec in snapshot.builtins.filter { !it.defaultDisabled }) {
                val proof = snapshot.defaultDisabledStat(spec.name) ?: continue
                val marker = snapshot.file(spec.name)
                if (marker.kind == NodeKind.MISSING) {
                    add(Action.CLEAR_DEFAULT_DISABLE_SOURCE, "defaults", spec.name)
                } else if (
                    marker.kind == NodeKind.REGULAR &&
                        marker.stable &&
                        marker.size == 0L &&
                        marker.stat?.values() == proof &&
                        snapshot.builtinFacts.getValue(spec.name).entityPresent
                ) {
                    add(Action.CLEAR_DISABLED_MARKER, "defaults", spec.name)
                    add(Action.CLEAR_DEFAULT_DISABLE_SOURCE, "defaults", spec.name)
                    markers.remove(spec.name + ".disabled")
                }
            }
            for (spec in snapshot.builtins.filter { it.defaultDisabled }) {
                if (disabled(spec.name)) {
                    add(Action.CLEAR_ENABLE_INTENT, "defaults", spec.name)
                } else if (
                    snapshot.file(spec.name, ".enabled").pythonText() !=
                        PluginPreparationSnapshot.ENABLED_HEADER +
                            "\n" +
                            snapshot.apkGeneration +
                            "\n"
                ) {
                    // O_EXCL 遇已存在的目录/悬空链接时旧实现直接 no-op，不覆盖类型。
                    if (!snapshot.file(spec.name).exists) {
                        // 来源须由执行者在实际创建空 marker 后采集 stat，不能预造 inode。
                        add(Action.CREATE_DEFAULT_DISABLE, "defaults", spec.name, contents = "")
                        markers[spec.name + ".disabled"] =
                            PluginPreparationSnapshot.FileFact(NodeKind.REGULAR, byteArrayOf())
                    }
                    add(Action.CLEAR_ENABLE_INTENT, "defaults", spec.name)
                }
            }

            val present = LinkedHashMap<String, String>()
            val dormant = LinkedHashMap<String, String>()
            val missing = ArrayList<String>()
            val skipped = ArrayList<String>()
            for (spec in snapshot.builtins) {
                // 目录只来自本次签名注册表；profile/plugin-src 同名实体不是 APK 来源。
                if (!snapshot.builtinFacts.getValue(spec.name).entityPresent) missing.add(spec.name)
                else if (disabled(spec.name)) {
                    skipped.add(spec.name)
                    dormant[spec.name] = spec.guestDirectory
                } else present[spec.name] = spec.guestDirectory
            }
            var doc = snapshot.manifest()
            fun write(stage: String) {
                add(Action.WRITE_MANIFEST, stage, contents = doc!!.toString())
            }
            fun removeAlias(name: String, stage: String): Boolean {
                val kind = snapshot.builtinFacts.getValue(name).aliasKind
                if (kind == NodeKind.MISSING) return false
                add(
                    if (kind == NodeKind.SYMLINK) Action.REMOVE_ALIAS else Action.QUARANTINE_ALIAS,
                    stage,
                    name,
                    bestEffort = stage == "missing",
                )
                return true
            }
            fun ensureAlias(name: String, directory: String, stage: String): Boolean {
                val fact = snapshot.builtinFacts.getValue(name)
                if (fact.aliasCurrent && fact.aliasPackagePresent && !fact.aliasAbsolute)
                    return false
                if (
                    fact.aliasKind != NodeKind.MISSING &&
                        fact.aliasKind != NodeKind.SYMLINK &&
                        !fact.aliasCurrent
                )
                    add(Action.QUARANTINE_ALIAS, stage, name)
                add(Action.ENSURE_ALIAS, stage, name, directory)
                return true
            }

            if (missing.isNotEmpty()) {
                for (name in missing) removeAlias(name, "missing")
                if (doc != null) {
                    val profile = objectAt(objectAt(doc, "dsh"), "profile")
                    val bundles = bundles(profile).filter { it !in missing }
                    profile.add("bundles", array(bundles))
                    val dependencies = objectAt(doc, "dependencies")
                    for (name in missing) dependencies.remove(name)
                    write("missing")
                }
            }
            if (dormant.isNotEmpty()) {
                val beforeDormant = doc?.deepCopy()
                val created = doc == null
                if (created) {
                    add(Action.ENSURE_PROFILE_FILES, "disabled")
                    doc = newManifest(snapshot, emptyMap())
                }
                val profile = objectAt(objectAt(doc!!, "dsh"), "profile")
                var bundles = bundles(profile)
                val dependencies = objectAt(doc!!, "dependencies")
                var changed = created
                for ((name, directory) in dormant) {
                    val filtered = bundles.filter { it !in PluginPreparationSnapshot.aliases(name) }
                    if (filtered != bundles) {
                        bundles = filtered
                        changed = true
                    }
                    val expected = "link:" + directory
                    if (dependencies.get(name)?.asString != expected) {
                        dependencies.addProperty(name, expected)
                        changed = true
                    }
                    val marker = markers[name + ".disabled"]
                    val webDisabled =
                        name !in
                            PluginPreparationSnapshot.aliases(
                                PluginPreparationSnapshot.AUTO_REVIEW_ALIAS
                            ) &&
                            marker?.kind != NodeKind.SYMLINK &&
                            marker?.pythonText() == PluginPreparationSnapshot.WEB_DISABLED_MARKER
                    if (webDisabled) {
                        if (ensureAlias(name, directory, "disabled")) changed = true
                        add(Action.VERIFY_ALIAS, "disabled", name, directory)
                    } else if (removeAlias(name, "disabled")) changed = true
                }
                profile.add("bundles", array(bundles))
                profile.addProperty("patchReload", "startup")
                if (changed) write("disabled")
                // 旧实现 changed=false 时不落盘，即使内存里 patchReload 被规范化。
                else doc = beforeDormant
            }
            if (present.isNotEmpty()) {
                if (doc == null) {
                    add(Action.ENSURE_PROFILE_FILES, "present")
                    doc = newManifest(snapshot, present)
                    write("present")
                } else {
                    add(Action.ENSURE_PROFILE_FILES, "present")
                    val merged = doc!!.deepCopy()
                    val profile = objectAt(objectAt(merged, "dsh"), "profile")
                    var bundles = bundles(profile)
                    if (PluginPreparationSnapshot.AUTO_REVIEW_CANONICAL in bundles)
                        bundles = bundles.filter {
                            it != PluginPreparationSnapshot.AUTO_REVIEW_ALIAS
                        }
                    val dependencies = objectAt(merged, "dependencies")
                    for ((name, directory) in present) {
                        if (
                            name !in bundles &&
                                !(name == PluginPreparationSnapshot.AUTO_REVIEW_ALIAS &&
                                    PluginPreparationSnapshot.AUTO_REVIEW_CANONICAL in bundles)
                        )
                            bundles = bundles + name
                        dependencies.addProperty(name, "link:" + directory)
                    }
                    profile.add("bundles", array(bundles))
                    profile.addProperty("patchReload", "startup")
                    if (merged != doc) {
                        doc = merged
                        write("present")
                    }
                }
                for ((name, directory) in present) {
                    ensureAlias(name, directory, "present")
                    add(Action.VERIFY_ALIAS, "present", name, directory)
                }
            }
            // 预算身份回执只有无 missing 且先前步骤全部成功后才允许执行；写失败是 best-effort。
            if (missing.isEmpty()) add(Action.WRITE_REGISTER_IDENTITY, "receipt", bestEffort = true)
            return PluginBuiltinRegistrationPlan(
                snapshot,
                steps,
                doc?.toString(),
                present.keys.toList(),
                skipped,
                missing,
            )
        }

        private fun objectAt(parent: JsonObject, key: String): JsonObject {
            val value = parent.get(key)
            if (value == null || value.isJsonNull) {
                val created = JsonObject()
                parent.add(key, created)
                return created
            }
            require(value.isJsonObject) { "PROFILE_MANIFEST_FORMAT" }
            return value.asJsonObject
        }

        private fun bundles(profile: JsonObject): List<String> {
            val value = profile.get("bundles") ?: return emptyList()
            if (value.isJsonNull) return emptyList()
            require(value.isJsonArray)
            return value.asJsonArray.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString
            }
        }

        private fun array(values: List<String>): JsonArray =
            JsonArray().also { result -> values.forEach { result.add(it) } }

        private fun newManifest(
            snapshot: PluginPreparationSnapshot,
            registered: Map<String, String>,
        ): JsonObject {
            val doc = JsonObject()
            doc.addProperty("name", "dsh-profile-web")
            doc.addProperty("private", true)
            val dependencies = JsonObject()
            registered.forEach { (name, directory) ->
                dependencies.addProperty(name, "link:" + directory)
            }
            doc.add("dependencies", dependencies)
            val profile = objectAt(objectAt(doc, "dsh"), "profile")
            profile.add("bundles", array(snapshot.officialBundles + registered.keys))
            profile.addProperty("patchReload", "startup")
            return doc
        }
    }
}
