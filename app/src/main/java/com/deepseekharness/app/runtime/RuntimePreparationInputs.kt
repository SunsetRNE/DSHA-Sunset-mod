package com.deepseekharness.app.runtime

import android.content.Context
import com.deepseekharness.app.util.MaintenanceGate
import java.io.File

/** 将既有 APK 缓存与固定文件戳投影为本次解析的输入；不保存文件健康或执行权限。 */
internal class RuntimePreparationInputs(private val context: Context, private val root: File) {
    fun observe(): RuntimeDescriptorPreparation.Identity? =
        try {
            if (MaintenanceGate.shared().isOwner) null
            else {
                val settings =
                    RuntimeHostPorts.fromOwner(context.applicationContext).currentInvocation()
                if (settings == null || root.canonicalFile != root.absoluteFile) null
                else {
                    val base = root.parentFile
                    val files =
                        RuntimeAssetStamp.read(
                            root,
                            listOf(
                                File(base, ".runtime-descriptor.json"),
                                File(base, ".offline-extracted"),
                                File(base, ".offline-identity"),
                            ),
                        )
                    val tools = RuntimeTools.preparationIdentity(context, root)
                    if (files == null || tools == null) null
                    else
                        RuntimeDescriptorPreparation.Identity(
                            // get 的同一 Snapshot 仅在既有 APK/split/AssetManager 验证通过后复用；
                            // 不可验证时每次返回新对象，因而 before/after 不可能匹配。
                            RuntimeApkAssets.get(context),
                            context.assets,
                            settings,
                            root.absolutePath + ":" + files,
                            tools,
                        )
                }
            }
        } catch (_: Exception) {
            null
        }
}
