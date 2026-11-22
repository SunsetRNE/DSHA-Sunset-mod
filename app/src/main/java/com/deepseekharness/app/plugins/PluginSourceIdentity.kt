package com.deepseekharness.app.plugins

import java.io.File
import java.util.Objects

/** 已观察到的数据根、运行根与 Web 代次；不代表插件内容已完整校验。 */
class PluginSourceIdentity(
    dataRoot: File,
    dataRootKey: String,
    dataRootDevice: Long,
    rootfs: File,
    rootfsKey: String,
    rootfsDevice: Long,
    runtimeMarkerSha256: String,
    webGeneration: Long,
) {
    @JvmField val dataRoot: File = Objects.requireNonNull(dataRoot).absoluteFile
    @JvmField val dataRootKey: String = Objects.requireNonNull(dataRootKey)
    @JvmField val dataRootDevice: Long = dataRootDevice
    @JvmField val rootfs: File = Objects.requireNonNull(rootfs).absoluteFile
    @JvmField val rootfsKey: String = Objects.requireNonNull(rootfsKey)
    @JvmField val rootfsDevice: Long = rootfsDevice
    @JvmField val runtimeMarkerSha256: String = Objects.requireNonNull(runtimeMarkerSha256)
    @JvmField val webGeneration: Long = webGeneration

    /** 仅复制预期的受控停止后身份；不能据此证明停止已完成。 */
    fun withWebGeneration(generation: Long): PluginSourceIdentity =
        PluginSourceIdentity(
            dataRoot,
            dataRootKey,
            dataRootDevice,
            rootfs,
            rootfsKey,
            rootfsDevice,
            runtimeMarkerSha256,
            generation,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PluginSourceIdentity) return false
        return sameDataSource(other) && webGeneration == other.webGeneration
    }

    /** 仅用于保留同一用户目录的陈旧展示；不能用于执行凭据、查询票据或运行证据。 */
    fun sameUserDataRoot(value: PluginSourceIdentity?): Boolean =
        value != null &&
            dataRoot == value.dataRoot &&
            dataRootKey == value.dataRootKey &&
            dataRootDevice == value.dataRootDevice

    /** 只比较持久根；执行票据必须另核对 Registry 代次与精确 Web 观察，不能单用此方法放行。 */
    fun sameDataSource(value: PluginSourceIdentity?): Boolean =
        value != null &&
            dataRoot == value.dataRoot &&
            dataRootKey == value.dataRootKey &&
            dataRootDevice == value.dataRootDevice &&
            rootfs == value.rootfs &&
            rootfsKey == value.rootfsKey &&
            rootfsDevice == value.rootfsDevice &&
            runtimeMarkerSha256 == value.runtimeMarkerSha256

    override fun hashCode(): Int =
        Objects.hash(
            dataRoot,
            dataRootKey,
            dataRootDevice,
            rootfs,
            rootfsKey,
            rootfsDevice,
            runtimeMarkerSha256,
            webGeneration,
        )
}
