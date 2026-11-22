package com.deepseekharness.app.runtime

import android.content.Context
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.util.ColdInstallPackages
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.HashSet
import java.util.UUID

/** APK launcher overlays live outside retained runtime trees, which remain byte-for-byte intact. */
internal object RuntimeLauncherAssets {
    const val GUEST = "/run/dsha-launcher"

    private val LOCK = Any()

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context): File = prepare(context, RuntimeApkAssets.get(context))

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(context: Context, apk: RuntimeApkAssets.Snapshot): File =
        synchronized(LOCK) {
            val fs = AndroidBackupFileSystem()
            val data =
                context.applicationInfo.dataDir ?: throw IOException("LAUNCHER_ASSET_CONTEXT")
            val host = ColdInstallPackages.bindPrivateFiles(fs, File(data), context.filesDir)
            val parent = fs.child(host.files, "runtime-launcher-assets")
            if (fs.stat(parent).type == "MISSING") fs.directory(parent)
            if (fs.stat(parent).type != "DIRECTORY") throw IOException("LAUNCHER_ASSET_ROOT")
            val bytes = apk.preloads
            val id = apk.preloadId
            var target = fs.child(parent, id)
            if (current(fs, target, bytes)) {
                host.verify(fs)
                return@synchronized target
            }
            if (fs.stat(target).type != "MISSING")
                target = fs.child(parent, id + "-" + UUID.randomUUID())
            fs.directory(target)
            for (entry in bytes.entries) fs.atomic(target, entry.key, entry.value)
            host.verify(fs)
            if (!current(fs, target, bytes)) throw IOException("LAUNCHER_ASSET_VERIFICATION")
            target
        }

    @Throws(IOException::class)
    private fun current(
        fs: BackupFileSystem,
        root: File,
        expected: Map<String, ByteArray>,
    ): Boolean {
        if (fs.stat(root).type != "DIRECTORY") return false
        if (HashSet(fs.list(root)) != expected.keys) return false
        for (entry in expected.entries) {
            val file = fs.child(root, entry.key)
            if (fs.stat(file).type != "FILE") return false
            if (!Arrays.equals(fs.small(file, 512 * 1024), entry.value)) return false
        }
        return true
    }
}
