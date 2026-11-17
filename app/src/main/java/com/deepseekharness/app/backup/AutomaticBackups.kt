package com.deepseekharness.app.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import com.deepseekharness.app.core.HarnessController
import com.deepseekharness.app.core.RuntimeTasks
import com.deepseekharness.app.util.AutomaticBackupPolicy
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** 自动备份只存个人数据，采用已有 v5 加密归档；本机密钥不参与系统迁移。 */
class AutomaticBackups private constructor() {
    companion object {
        const val JOB = 9137
        const val NOW = 9138
        private val FACTORY_RESET = AtomicBoolean()
        private val PRODUCERS = WorkerQuiescence()

        @JvmStatic
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("dsha_automatic_backup", Context.MODE_PRIVATE)

        @JvmStatic
        fun enabled(context: Context): Boolean {
            val saved = prefs(context)
            return !FACTORY_RESET.get() &&
                !saved.getBoolean("factoryResetPending", false) &&
                saved.getBoolean("enabled", true)
        }

        @JvmStatic
        @JvmName("factoryResetSuspended")
        internal fun factoryResetSuspended(): Boolean = FACTORY_RESET.get()

        @JvmStatic
        @JvmName("factoryResetPending")
        internal fun factoryResetPending(context: Context): Boolean =
            FACTORY_RESET.get() || prefs(context).getBoolean("factoryResetPending", false)

        @JvmStatic
        @JvmName("beginProducer")
        internal fun beginProducer(): WorkerQuiescence.Lease? =
            if (FACTORY_RESET.get()) null else PRODUCERS.tryBegin()

        @JvmStatic
        @JvmName("recordError")
        internal fun recordError(context: Context, error: String?) {
            synchronized(AutomaticBackups::class.java) {
                if (!factoryResetPending(context))
                    prefs(context).edit().putString("error", error ?: "").commit()
            }
        }

        @JvmStatic
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (factoryResetPending(context)) {
                scheduler.cancel(JOB)
                scheduler.cancel(NOW)
                return
            }
            val settings = prefs(context)
            if (!settings.contains("anchor"))
                settings.edit().putLong("anchor", System.currentTimeMillis()).commit()
            if (!enabled(context)) {
                scheduler.cancel(JOB)
                scheduler.cancel(NOW)
                return
            }
            if (scheduler.allPendingJobs.none { it.id == JOB }) {
                scheduler.schedule(
                    JobInfo.Builder(JOB, ComponentName(context, AutomaticBackupService::class.java))
                        .setPersisted(true)
                        .setPeriodic(15 * 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .build()
                )
            }
        }

        @JvmStatic
        fun due(context: Context): Boolean {
            val p = prefs(context)
            return enabled(context) &&
                AutomaticBackupPolicy.due(
                    p.getString("mode", "daily"),
                    p.getInt("hours", 24),
                    p.getInt("minute", 180),
                    System.currentTimeMillis(),
                    p.getLong("anchor", System.currentTimeMillis()),
                    p.getLong("last", 0),
                    p.getLong("stopped", 0),
                    ZoneId.systemDefault(),
                )
        }

        @JvmStatic
        fun stopped(context: Context) {
            synchronized(AutomaticBackups::class.java) {
                if (factoryResetPending(context)) return
                prefs(context).edit().putLong("stopped", System.currentTimeMillis()).commit()
            }
            schedule(context)
            if (prefs(context).getString("mode", "daily") == "stop") request(context, false)
        }

        @JvmStatic
        fun request(context: Context, manual: Boolean) {
            if (!enabled(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val extras = PersistableBundle()
            extras.putBoolean("manual", manual)
            scheduler.schedule(
                JobInfo.Builder(NOW, ComponentName(context, AutomaticBackupService::class.java))
                    .setExtras(extras)
                    .setMinimumLatency(1500)
                    .build()
            )
        }

        @JvmStatic
        @Throws(IOException::class)
        fun idle(context: Context): Boolean {
            val c = HarnessController.get(context)
            return c.config().isWelcomed() &&
                !NativeBackupJobs.get(context).state().busy &&
                !c.isStarting() &&
                !c.isStopping() &&
                c.getWebAuthUrl().isEmpty() &&
                !RuntimeTasks.isBusy() &&
                !HostMaintenancePending.blocked(context.filesDir) &&
                c.isWebStoppedForMaintenance()
        }

        @JvmStatic
        @JvmName("idleForOwner")
        @Throws(IOException::class)
        internal fun idleForOwner(context: Context): Boolean {
            val c = HarnessController.get(context)
            return !c.isStarting() &&
                !c.isStopping() &&
                c.getWebAuthUrl().isEmpty() &&
                !RuntimeTasks.hasOtherTasks() &&
                c.isWebStoppedForMaintenance()
        }

        /** 先阻止新生产者，再取消并等现有作业退出，保留原格式化屏障及失败回退顺序。 */
        @JvmStatic
        @Throws(IOException::class)
        fun suspendForFactoryReset(context: Context, control: BackupControl) {
            val first = FACTORY_RESET.compareAndSet(false, true)
            if (first && !prefs(context).edit().putBoolean("factoryResetPending", true).commit()) {
                FACTORY_RESET.set(false)
                throw IOException("FACTORY_RESET_GATE_PERSISTENCE")
            }
            PRODUCERS.closeGate()
            var nativeClosed = false
            try {
                PostUpgradeCleanupService.suspendForFactoryReset(control)
                val scheduler = context.getSystemService(JobScheduler::class.java)
                if (scheduler != null) {
                    // 门禁已持久化；保留 ROM 拒绝 cancel 时仍可进入私有数据维护的行为。
                    try {
                        scheduler.cancel(JOB)
                    } catch (_: SecurityException) {}
                    try {
                        scheduler.cancel(NOW)
                    } catch (_: SecurityException) {}
                }
                val jobs = NativeBackupJobs.get(context)
                jobs.beginFactoryResetQuiescence(control, 30_000)
                nativeClosed = true
                PRODUCERS.awaitIdle(control, 15_000)
                // 必须与原 Java static synchronized password 共用 Class 对象，不能锁 companion。
                synchronized(AutomaticBackups::class.java) {}
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                if (nativeClosed) NativeBackupJobs.get(context).abortFactoryResetQuiescence()
                PostUpgradeCleanupService.resumeAfterFactoryReset()
                if (first) {
                    prefs(context).edit().remove("factoryResetPending").commit()
                    PRODUCERS.reopen()
                    FACTORY_RESET.set(false)
                    schedule(context)
                    PostUpgradeCleanupService.schedule(context)
                }
                throw failure
            }
        }

        @JvmStatic
        fun abortFactoryReset(context: Context) {
            NativeBackupJobs.get(context).abortFactoryResetQuiescence()
            PostUpgradeCleanupService.resumeAfterFactoryReset()
            prefs(context).edit().remove("factoryResetPending").commit()
            FACTORY_RESET.set(false)
            PRODUCERS.reopen()
            schedule(context)
            PostUpgradeCleanupService.schedule(context)
        }

        @JvmStatic
        fun completeFactoryReset(context: Context) {
            NativeBackupJobs.get(context).completeFactoryResetQuiescence()
            PostUpgradeCleanupService.resumeAfterFactoryReset()
            prefs(context).edit().remove("factoryResetPending").commit()
            FACTORY_RESET.set(false)
            PRODUCERS.reopen()
        }

        @JvmStatic
        @Throws(IOException::class)
        fun password(context: Context): CharArray =
            synchronized(AutomaticBackups::class.java) {
                if (factoryResetPending(context)) throw IOException("FACTORY_RESET_QUIESCING")
                val fs = AndroidBackupFileSystem()
                val root = context.noBackupFilesDir.canonicalFile
                val file = File(root, "automatic-backup-key")
                val vault = com.deepseekharness.app.data.KeyVault(context)
                if (fs.stat(file).type == "FILE") {
                    return@synchronized vault
                        .decryptChecked(String(fs.small(file, 8192), StandardCharsets.UTF_8))
                        .toCharArray()
                }
                if (fs.stat(file).type != "MISSING") throw IOException("AUTOMATIC_KEY_UNAVAILABLE")
                for (copy in NativeBackupJobs.get(context).verifiedCopies().valid) {
                    if (true == copy.metadata["automatic"])
                        throw IOException("AUTOMATIC_KEY_MISSING")
                }
                val random = ByteArray(32)
                SecureRandom().nextBytes(random)
                val plain =
                    android.util.Base64.encodeToString(
                        random,
                        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
                    )
                val encrypted = vault.encrypt(plain)
                if (encrypted.isEmpty() || plain != vault.decryptChecked(encrypted))
                    throw IOException("AUTOMATIC_KEY_UNAVAILABLE")
                fs.atomic(root, file.name, encrypted.toByteArray(StandardCharsets.UTF_8))
                vault
                    .decryptChecked(String(fs.small(file, 8192), StandardCharsets.UTF_8))
                    .toCharArray()
            }

        @JvmStatic
        fun completed(context: Context, id: String?) {
            synchronized(AutomaticBackups::class.java) {
                if (!FACTORY_RESET.get())
                    prefs(context)
                        .edit()
                        .putLong("last", System.currentTimeMillis())
                        .putString("lastId", id)
                        .putString("error", "")
                        .commit()
            }
        }

        /** 只轮换明确标记、已验证的自动产物，手动副本及未知记录不删除。 */
        @JvmStatic
        @Throws(IOException::class)
        fun prune(context: Context, control: BackupControl) {
            val fs = AndroidBackupFileSystem()
            AutomaticBackupPruner.prune(
                fs,
                NativeBackupJobs.get(context).verifiedCopies().valid,
                control,
            )
        }
    }
}
