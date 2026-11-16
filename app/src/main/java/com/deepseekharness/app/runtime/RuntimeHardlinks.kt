package com.deepseekharness.app.runtime

import android.content.Context
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.util.Compat
import java.io.File
import java.io.IOException
import java.util.UUID

/** 在独占的宿主槽位中验证硬链接，不使用可预测的用户文件名。 */
internal class RuntimeHardlinks(
    private val context: Context,
    private val rootfs: File,
    private val ports: RuntimeHostPorts,
) {
    private var supported: Boolean? = null

    @Synchronized
    fun supported(): Boolean {
        supported?.let {
            return it
        }
        val fs = AndroidBackupFileSystem()
        var root: File? = null
        val slot = ".dsha-linkprobe-" + UUID.randomUUID()
        var valid = false
        try {
            root = RuntimeAssetFiles.root(context, rootfs)
            val directory = fs.child(root, slot)
            if (fs.stat(directory).type != "MISSING") throw IOException("LINK_PROBE_COLLISION")
            fs.directory(directory)
            fs.atomic(directory, "source", byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
            val source = fs.child(directory, "source")
            val target = fs.child(directory, "linked")
            Compat.link(source, target)
            val a = fs.stat(source)
            val b = fs.stat(target)
            valid =
                a.type == "FILE" &&
                    b.type == "FILE" &&
                    a.size == 2L &&
                    b.size == 2L &&
                    a.device == b.device &&
                    a.key == b.key
        } catch (error: IOException) {
            ports.record("HARDLINK_PROBE_UNAVAILABLE", error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            ports.record("HARDLINK_PROBE_UNAVAILABLE", error.javaClass.simpleName)
        } finally {
            if (root != null)
                try {
                    fs.removeOwned(root, slot)
                } catch (error: IOException) {
                    ports.record("HARDLINK_PROBE_RETAINED", error.javaClass.simpleName)
                }
        }
        supported = valid
        return valid
    }
}
