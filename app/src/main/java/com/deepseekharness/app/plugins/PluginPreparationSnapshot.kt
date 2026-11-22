package com.deepseekharness.app.plugins

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * 同一次只读准备的事实。此类型不扫描文件、不启动 guest，也不持有执行权。
 *
 * 调用方须在已有 owner/锁边界内收集完整事实；缺项、读期间变化和未知依赖不能当作空集合。 S3b 仅消费隔离夹具，不接入生产，也不替代 T11 的 InvocationContext
 * 或提交前复核。
 */
class PluginPreparationSnapshot(
    val preparationId: String,
    val profile: String = "web",
    val complete: Boolean = true,
    val manifestText: String? = null,
    builtins: List<Builtin> = emptyList(),
    officialBundles: List<String> = emptyList(),
    files: Map<String, FileFact> = emptyMap(),
    builtinFacts: Map<String, BuiltinFact> = emptyMap(),
    legacyPackages: Map<String, LegacyPackage> = emptyMap(),
    val pluginSource: String = "/root/.dsh/plugin-src",
    val apkMarker: FileFact = FileFact(),
    val managedMarker: FileFact = FileFact(),
) {
    val builtins: List<Builtin> = immutableList(builtins)
    val officialBundles: List<String> = immutableList(officialBundles)
    val files: Map<String, FileFact> = immutableMap(files)
    val builtinFacts: Map<String, BuiltinFact> = immutableMap(builtinFacts)
    val legacyPackages: Map<String, LegacyPackage> = immutableMap(legacyPackages)
    val apkGeneration: String = generation(apkMarker, managedMarker)

    enum class NodeKind {
        MISSING,
        REGULAR,
        SYMLINK,
        DIRECTORY,
        OTHER,
        UNREADABLE,
    }

    /** 六字段与旧 disabled_stat 顺序一致；不得以路径或内容摘要替代 inode/时间身份。 */
    data class FileStat(
        val device: Long,
        val inode: Long,
        val mode: Long,
        val size: Long,
        val modifiedNs: Long,
        val changedNs: Long,
    ) {
        fun values(): List<Long> = listOf(device, inode, mode, size, modifiedNs, changedNs)
    }

    /** bytes 为完整读回；符号链接的跟随文件语义单独保留，不能冒充普通文件。 */
    class FileFact(
        val kind: NodeKind = NodeKind.MISSING,
        bytes: ByteArray? = null,
        val stat: FileStat? = null,
        val stable: Boolean = true,
        val targetRegular: Boolean = false,
    ) {
        private val content: ByteArray? = bytes?.copyOf()
        val exists: Boolean
            get() = kind != NodeKind.MISSING

        val isFile: Boolean
            get() = kind == NodeKind.REGULAR || (kind == NodeKind.SYMLINK && targetRegular)

        val size: Long?
            get() = stat?.size ?: content?.size?.toLong()

        val observed: Boolean
            get() =
                stable &&
                    kind != NodeKind.UNREADABLE &&
                    (!isFile ||
                        (content != null &&
                            (kind != NodeKind.REGULAR || size == content.size.toLong())))

        fun bytes(): ByteArray? = content?.copyOf()

        fun exact(value: String): Boolean =
            content?.contentEquals(value.toByteArray(StandardCharsets.UTF_8)) == true

        fun text(ascii: Boolean = false): String? {
            if (!isFile || !stable || content == null) return null
            return try {
                (if (ascii) StandardCharsets.US_ASCII else StandardCharsets.UTF_8)
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString()
            } catch (_: Exception) {
                null
            }
        }

        // Python 文本 open 的 universal-newline；legacy 的二进制精确匹配不走这里。
        fun pythonText(): String? = text()?.replace("\r\n", "\n")?.replace('\r', '\n')
    }

    data class Builtin(
        val name: String,
        val guestDirectory: String,
        val defaultDisabled: Boolean = false,
    )

    /** aliasCurrent 来自同次实际 canonical 目标核验，packagePresent 不是单凭同名目录。 */
    data class BuiltinFact(
        val entityPresent: Boolean,
        val aliasKind: NodeKind = NodeKind.MISSING,
        val aliasCurrent: Boolean = false,
        val aliasPackagePresent: Boolean = false,
        val aliasAbsolute: Boolean = false,
    )

    data class Dependency(
        val name: String,
        val reference: String,
        val resolvedDirectory: String,
        val sha256: String,
    )

    /** 来自旧 dependencies.current 的真实图，不能从 package.json 的声明推导完整。 */
    class Dependencies(
        val sha256: String,
        missing: List<String> = emptyList(),
        resolved: List<Dependency> = emptyList(),
        val complete: Boolean = true,
        val currentText: String? = null,
    ) {
        val missing: List<String> = immutableList(missing)
        val resolved: List<Dependency> = immutableList(resolved)
    }

    data class PolicyFailure(val code: String, val message: String)

    data class LegacyPackage(
        val directoryKind: NodeKind,
        val metadataName: String?,
        val version: String?,
        val dependencies: Dependencies?,
        val metadataValid: Boolean = true,
        val compatibilityChecked: Boolean = true,
        val policyFailure: PolicyFailure? = null,
    )

    /** 每次返回独立 JSON 副本；计划变换不回写输入。 */
    fun manifest(): JsonObject? {
        if (manifestText == null) return null
        return JsonReader(StringReader(manifestText)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = JsonParser.parseReader(reader)
            require(value.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT) {
                "PROFILE_MANIFEST_FORMAT"
            }
            value.asJsonObject
        }
    }

    fun file(name: String, suffix: String = ".disabled"): FileFact =
        files[name + suffix] ?: FileFact()

    /** 只解析独立来源文件；重复键、浮点/布尔值、越界读、换链或断写均不提供恢复依据。 */
    fun defaultDisabledStat(name: String): List<Long>? {
        val source = file(name, ".default-disabled.json")
        if (
            source.kind != NodeKind.REGULAR ||
                !source.stable ||
                source.stat == null ||
                source.size == null ||
                source.size!! > 1024 ||
                source.bytes()?.size?.toLong() != source.size
        )
            return null
        return try {
            val reader = JsonReader(StringReader(source.text() ?: return null))
            reader.strictness = Strictness.STRICT
            reader.use {
                reader.beginObject()
                val keys = HashSet<String>()
                var version: Long? = null
                var origin: String? = null
                var disabled: List<Long>? = null
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (!keys.add(key)) return null
                    when (key) {
                        "version" -> version = integer(reader) ?: return null
                        "source" -> {
                            if (reader.peek() != JsonToken.STRING) return null
                            origin = reader.nextString()
                        }
                        "disabledStat" -> {
                            reader.beginArray()
                            val values = ArrayList<Long>()
                            while (reader.hasNext()) {
                                if (values.size == 6) return null
                                values.add(integer(reader) ?: return null)
                            }
                            reader.endArray()
                            disabled = values
                        }
                        else -> return null
                    }
                }
                reader.endObject()
                if (
                    reader.peek() != JsonToken.END_DOCUMENT ||
                        keys.size != 3 ||
                        version != 1L ||
                        origin != "builtin-default" ||
                        disabled?.size != 6
                )
                    null
                else immutableList(disabled!!)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val LEGACY_REVIEW_MARKER = "DSHA_REVIEW_REQUIRED\n"
        const val SAFE_MODE_MARKER = "DSHA_SAFE_MODE\n"
        const val WEB_DISABLED_MARKER = "DSHA_WEB_BUILTIN_DISABLED_V1\n"
        const val ENABLED_HEADER = "DSHA_DEFAULT_OFF_ENABLED_V1"
        const val AUTO_REVIEW_ALIAS = "dsh-auto-review"
        const val AUTO_REVIEW_CANONICAL = "@deepseek-ai/dsh-experimental-auto-review"
        private val NAME = Regex("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
        private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")

        @JvmStatic fun validName(name: String): Boolean = name.length <= 214 && NAME.matches(name)

        @JvmStatic
        fun aliases(name: String): List<String> =
            if (name == AUTO_REVIEW_ALIAS || name == AUTO_REVIEW_CANONICAL)
                listOf(AUTO_REVIEW_ALIAS, AUTO_REVIEW_CANONICAL)
            else listOf(name)

        private fun integer(reader: JsonReader): Long? {
            if (reader.peek() != JsonToken.NUMBER) return null
            val value = reader.nextString()
            return if (INTEGER.matches(value)) value.toLongOrNull() else null
        }

        /** 与旧 Python splitlines 的 ASCII 子集一致，末行换行可省略，额外空行不可省略。 */
        private fun lines(file: FileFact): List<String> {
            val text = file.text(ascii = true) ?: return emptyList()
            if (text.isEmpty()) return emptyList()
            val result = text.split(Regex("\\r\\n|[\\n\\r\\u000b\\u000c\\u001c-\\u001e]"))
            return if (result.last().isEmpty()) result.dropLast(1) else result
        }

        @JvmStatic
        fun generation(apk: FileFact, managed: FileFact): String {
            val apkLines = lines(apk)
            if (
                apkLines.size == 2 &&
                    apkLines[0] == "DSHA_APK_GENERATION_V1" &&
                    Regex("[0-9]{1,12}").matches(apkLines[1])
            )
                return "apk:" + apkLines[1]
            val managedLines = lines(managed)
            if (
                managedLines.size == 2 &&
                    managedLines[0] == "DSHA_MANAGED_ASSETS_V2" &&
                    Regex("[0-9a-f]{64}").matches(managedLines[1])
            )
                return "runtime:" + managedLines[1]
            return "unmanaged"
        }

        internal fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
