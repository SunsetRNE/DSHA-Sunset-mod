package com.deepseekharness.app.backup

import java.io.IOException

/** 只轮换明确标记、完整且已验证的旧自动副本；其他原件保持。 */
class AutomaticBackupPruner private constructor() {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun prune(fs: BackupFileSystem, copies: List<VerifiedBackupCopy>, control: BackupControl) {
            val automatic = ArrayList<VerifiedBackupCopy>()
            for (copy in copies) {
                if (true == copy.metadata["automatic"] && copy.result(true) in setOf("COMPLETE", "DATA_SAVED_PLUGIN_WARNINGS")) {
                    copy.verify(fs, control)
                    automatic.add(copy)
                }
            }
            automatic.sortWith { a, b -> java.lang.Long.compare(b.created, a.created) }
            for (index in 3 until automatic.size) {
                val copy = automatic[index]
                val directory = copy.artifact.parentFile
                val parent = directory.parentFile
                val record = BackupJson.read(fs.small(fs.child(directory, "operation.json"), 16384), 16384)
                if (true == record["busy"] || "FINISHED" != record["stage"]) continue
                val allowed = setOf("portable.dshbak", "verified.json", "operation.json", "source-checks")
                if (!fs.list(directory).all { it in allowed }) continue
                copy.verify(fs, control)
                fs.removeOwned(parent, copy.id)
            }
        }
    }
}
