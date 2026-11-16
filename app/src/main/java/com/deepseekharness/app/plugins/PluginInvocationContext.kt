package com.deepseekharness.app.plugins

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** S3a 一次调用的值快照；不缓存就绪、不授予写权限，也不解析 guest 软链接。 */
class PluginInvocationContext(
    val hostRoot: String,
    val hostData: String,
    val rootIdentity: String,
    val dataIdentity: String,
    bindings: List<Binding>,
    val guestHome: String,
    val profile: String,
    val startupId: String,
    val operationId: String,
    val generation: Long,
    val cancelRevision: Long,
    val toolsSha256: String,
    budgets: Map<Stage, Long>,
    val taskId: String = "",
) {
    data class Binding(val host: String, val guest: String)

    enum class Stage {
        CAS,
        REGISTER,
        LEGACY,
    }

    val bindings: List<Binding> = Collections.unmodifiableList(ArrayList(bindings))
    val budgets: Map<Stage, Long> = Collections.unmodifiableMap(LinkedHashMap(budgets))
    val guestProfile: String = "$guestHome/profiles/$profile"
    val guestDataLock: String = guestHome.substringBeforeLast('/', "") + "/.dsha-data.lock"
    val guestProfileLock: String = "$guestProfile/package.json.lock"

    init {
        requirePath(hostRoot)
        requirePath(hostData)
        requirePath(guestHome)
        require(guestHome != "/") { "PLUGIN_HOME_ROOT" }
        // 既有三个执行端固定 web；其它 profile 不能套用这份协议。
        require(profile == "web") { "PLUGIN_PROFILE_UNSUPPORTED" }
        require(
            Regex("[0-9]+:[0-9]+").matches(rootIdentity) &&
                Regex("[0-9]+:[0-9]+").matches(dataIdentity)
        ) {
            "PLUGIN_ROOT_IDENTITY"
        }
        require(taskId.isEmpty() || Regex("[a-f0-9]{32}").matches(taskId)) { "PLUGIN_TASK_ID" }
        require(TOKEN.matches(startupId) && TOKEN.matches(operationId)) { "PLUGIN_OPERATION_ID" }
        require(generation >= 0 && cancelRevision >= 0) { "PLUGIN_GENERATION" }
        require(SHA256.matches(toolsSha256)) { "PLUGIN_TOOLS_IDENTITY" }
        this.bindings.forEach {
            requirePath(it.host)
            requirePath(it.guest)
        }
        require(this.budgets.keys == Stage.entries.toSet()) { "PLUGIN_STAGE_BUDGETS" }
        require(this.budgets.values.all { it in 1..3_000_000L }) { "PLUGIN_STAGE_BUDGET" }
        require(resolve(guestHome) == hostData) { "PLUGIN_DATA_BINDING_MISMATCH" }
    }

    /** 按实际 -b 顺序选最具体挂载，等长时后项覆盖；仅返回路径计划，非 inode 证明。 */
    fun resolve(guest: String): String {
        requirePath(guest)
        var selected = Binding(hostRoot, "/")
        for (binding in bindings) {
            if (
                (guest == binding.guest || guest.startsWith(binding.guest.trimEnd('/') + "/")) &&
                    binding.guest.length >= selected.guest.length
            ) {
                selected = binding
            }
        }
        val suffix = if (selected.guest == "/") guest else guest.removePrefix(selected.guest)
        if (suffix.isEmpty() || suffix == "/") return selected.host
        return selected.host.trimEnd('/') + if (suffix.startsWith('/')) suffix else "/$suffix"
    }

    /** 不从 hostData.parent 猜 data lock；stable 的 /root 父目录并没有随 .dsh 一起绑定。 */
    fun hostDataLock(): String = resolve(guestDataLock)

    fun hostProfileLock(): String = resolve(guestProfileLock)

    /** 私有管道的 V1 值协议；路径不应写入公开诊断。每个值单独 base64，避免换行歧义。 */
    fun fields(): Map<String, String> {
        val values =
            linkedMapOf(
                "schema" to "DSHA_PLUGIN_INVOCATION_V1",
                "hostRoot" to hostRoot,
                "hostData" to hostData,
                "rootIdentity" to rootIdentity,
                "dataIdentity" to dataIdentity,
                "guestHome" to guestHome,
                "profile" to profile,
                "startupId" to startupId,
                "operationId" to operationId,
                "generation" to generation.toString(),
                "cancelRevision" to cancelRevision.toString(),
                "toolsSha256" to toolsSha256,
                "taskId" to taskId,
                "bindingCount" to bindings.size.toString(),
            )
        bindings.forEachIndexed { index, binding ->
            values["binding.$index.host"] = binding.host
            values["binding.$index.guest"] = binding.guest
        }
        Stage.entries.forEach { values["budget.${it.name}"] = budgets.getValue(it).toString() }
        return Collections.unmodifiableMap(values)
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields().forEach { (key, value) ->
            digest.update(key.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(value.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_.:-]{1,128}")
        private val SHA256 = Regex("[a-f0-9]{64}")

        private fun requirePath(path: String) {
            require(
                path.startsWith('/') &&
                    (path == "/" || !path.endsWith('/')) &&
                    !path.contains("//") &&
                    path.none { it == '\\' || it.code < 32 || it.code == 127 } &&
                    path.split('/').none { it == "." || it == ".." }
            ) {
                "PLUGIN_PATH_NOT_CANONICAL"
            }
        }
    }
}
