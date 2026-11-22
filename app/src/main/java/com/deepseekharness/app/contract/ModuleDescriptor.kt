package com.deepseekharness.app.contract

import java.util.Objects

/**
 * 模块声明：纯数据，不读文件/网络/配置。
 *
 * [capabilities] 只是“会请求”的声明，授权派发仍由宿主能力层判定；本类不提供权限。
 *
 * DEFERRED(A1)：contractVersion 与 describe() 生命周期校验不在本轮；声明字段只覆盖编译期清单所需的最小集合。
 */
class ModuleDescriptor(
    id: ModuleId?,
    version: Int,
    name: String?,
    capabilities: Set<String>?,
    enablement: Enablement?,
) {
    enum class Enablement {
        ALWAYS_ON,
        OPTIONAL_USER,
    }

    private val id: ModuleId
    private val version: Int
    private val name: String
    private val capabilities: Set<String>
    private val enablement: Enablement

    init {
        val checkedId: ModuleId = Objects.requireNonNull(id, "id")!!
        if (version < 1) throw IllegalArgumentException("MODULE_VERSION_INVALID")
        if (
            name == null || name.codePoints().allMatch(Character::isWhitespace) || name.length > 200
        ) {
            throw IllegalArgumentException("MODULE_NAME_INVALID")
        }
        val checkedCapabilities: Set<String> =
            Objects.requireNonNull(capabilities, "capabilities")!!
        for (capability: String? in checkedCapabilities) {
            if (
                capability == null ||
                    capability.codePoints().allMatch(Character::isWhitespace) ||
                    capability.length > 128
            ) {
                throw IllegalArgumentException("MODULE_CAPABILITY_INVALID")
            }
        }
        val capabilitiesCopy = java.util.Set.copyOf(checkedCapabilities)
        val checkedEnablement: Enablement = Objects.requireNonNull(enablement, "enablement")!!
        this.id = checkedId
        this.version = version
        this.name = name
        this.capabilities = capabilitiesCopy
        this.enablement = checkedEnablement
    }

    fun id(): ModuleId = id

    fun version(): Int = version

    fun name(): String = name

    fun capabilities(): Set<String> = capabilities

    fun enablement(): Enablement = enablement

    fun alwaysOn(): Boolean = enablement == Enablement.ALWAYS_ON

    override fun equals(other: Any?): Boolean =
        other is ModuleDescriptor &&
            id == other.id &&
            version == other.version &&
            name == other.name &&
            capabilities == other.capabilities &&
            enablement == other.enablement

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + id.hashCode()
        result = 31 * result + version
        result = 31 * result + name.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + enablement.hashCode()
        return result
    }

    override fun toString(): String =
        "ModuleDescriptor[id=$id, version=$version, name=$name, capabilities=$capabilities, " +
            "enablement=$enablement]"
}
