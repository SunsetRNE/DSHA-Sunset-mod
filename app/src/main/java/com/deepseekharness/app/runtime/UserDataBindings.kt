package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.backup.UserDataLayout
import java.io.File
import java.io.IOException

/** guest 数据绑定和受管脚本覆盖使用同一清单；不改变历史 L2S 的宿主绝对路径绑定。 */
internal object UserDataBindings {

    @JvmStatic
    fun append(argv: MutableList<String>, rootfs: File, filesDomain: File) {
        append(argv, rootfs, filesDomain, AndroidBackupFileSystem())
    }

    @JvmStatic
    fun append(
        argv: MutableList<String>,
        rootfs: File,
        filesDomain: File,
        fs: BackupFileSystem,
    ) {
        try {
            if (fs.stat(filesDomain).type != "DIRECTORY")
                throw IOException("DATA_DOMAIN_FILES_TYPE")
            val files = filesDomain.canonicalFile
            val expected = fs.child(files, "linux/ubuntu")
            if (
                fs.stat(expected).type != "DIRECTORY" ||
                    rootfs.canonicalFile != expected.canonicalFile
            )
                throw IOException("DATA_DOMAIN_ROOTFS_MISMATCH")
            for (bind in UserDataLayout(fs, files).binds(rootfs)) {
                argv.add("-b")
                argv.add(bind[0] + ":" + bind[1])
            }
            // 本机回执与独立快照位于 rootfs 外，不接受用户归档携带的状态。
            val migration = fs.child(files, "rc1-migration-state")
            val node = fs.stat(migration)
            if (node.type == "MISSING") fs.directory(migration)
            else if (node.type != "DIRECTORY") throw IOException("MIGRATION_STATE_UNAVAILABLE")
            argv.add("-b")
            argv.add(migration.absolutePath + ":/run/dsha-rc1-state")
        } catch (error: IOException) {
            throw IllegalStateException("DATA_LOCATION_UNREADABLE", error)
        }
    }
}
