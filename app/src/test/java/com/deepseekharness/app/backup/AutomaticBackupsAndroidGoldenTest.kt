package com.deepseekharness.app.backup

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 冻结真实偏好和 JobScheduler，不启动归档、运行时或手机作业。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class AutomaticBackupsAndroidGoldenTest {
    private lateinit var context: Context
    private lateinit var scheduler: JobScheduler
    private lateinit var prefs: SharedPreferences

    private fun resetFlag(): AtomicBoolean =
        AutomaticBackups::class.java.getDeclaredField("FACTORY_RESET").let {
            it.isAccessible = true
            it.get(null) as AtomicBoolean
        }

    @Before
    fun reset() {
        context = RuntimeEnvironment.getApplication()
        scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        prefs = context.getSharedPreferences("dsha_automatic_backup", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        resetFlag().set(false)
    }

    @Test
    fun defaultEnabledUsesLegacyPreferencesAndSchedulesOnePersistedPeriodicJob() {
        assertSame(prefs, AutomaticBackups.prefs(context))
        assertTrue(AutomaticBackups.enabled(context))
        val before = System.currentTimeMillis()
        AutomaticBackups.schedule(context)
        val anchor = prefs.getLong("anchor", -1)
        assertTrue(anchor in before..System.currentTimeMillis())
        val job = scheduler.getPendingJob(9137)!!
        assertEquals(ComponentName(context, AutomaticBackupService::class.java), job.service)
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(900_000L, job.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_NONE, job.networkType)
        AutomaticBackups.schedule(context)
        assertEquals(anchor, prefs.getLong("anchor", -1))
        assertEquals(1, scheduler.allPendingJobs.size)
    }

    @Test
    fun existingPeriodicJobIsNotReplaced() {
        scheduler.schedule(
            JobInfo.Builder(9137, ComponentName(context, AutomaticBackupService::class.java))
                .setPeriodic(1_800_000)
                .build()
        )
        AutomaticBackups.schedule(context)
        assertEquals(1_800_000L, scheduler.getPendingJob(9137)!!.intervalMillis)
    }

    @Test
    fun requestKeepsItsJobIdManualFlagAndLatencyAndDisableCancelsBothJobs() {
        AutomaticBackups.schedule(context)
        AutomaticBackups.request(context, true)
        var job = scheduler.getPendingJob(9138)!!
        assertEquals(1_500L, job.minLatencyMillis)
        assertTrue(job.extras.getBoolean("manual"))
        assertFalse(job.isPeriodic)
        AutomaticBackups.request(context, false)
        job = scheduler.getPendingJob(9138)!!
        assertFalse(job.extras.getBoolean("manual"))
        prefs.edit().putBoolean("enabled", false).commit()
        AutomaticBackups.schedule(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        AutomaticBackups.request(context, true)
        assertNull(scheduler.getPendingJob(9138))
    }

    @Test
    fun disabledScheduleStillInitializesAnchorButFactoryResetDoesNot() {
        prefs.edit().putBoolean("enabled", false).commit()
        AutomaticBackups.schedule(context)
        assertTrue(prefs.contains("anchor"))
        prefs.edit().clear().putBoolean("factoryResetPending", true).commit()
        assertFalse(AutomaticBackups.enabled(context))
        AutomaticBackups.schedule(context)
        assertFalse(prefs.contains("anchor"))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun stoppedOnlyRequestsImmediateWorkInStopModeAndCompletionConsumesIt() {
        prefs.edit().putString("mode", "stop").putLong("anchor", 1).commit()
        val before = System.currentTimeMillis()
        AutomaticBackups.stopped(context)
        assertTrue(prefs.getLong("stopped", -1) in before..System.currentTimeMillis())
        assertFalse(scheduler.getPendingJob(9138)!!.extras.getBoolean("manual"))
        assertTrue(AutomaticBackups.due(context))
        prefs.edit().putString("error", "old").commit()
        AutomaticBackups.completed(context, "copy-identity")
        assertEquals("copy-identity", prefs.getString("lastId", null))
        assertEquals("", prefs.getString("error", null))
        assertFalse(AutomaticBackups.due(context))
        scheduler.cancelAll()
        prefs.edit().putString("mode", "daily").commit()
        AutomaticBackups.stopped(context)
        assertNull(scheduler.getPendingJob(9138))
    }

    @Test
    fun errorsAndCompletionKeepTheHistoricalGateDifference() {
        AutomaticBackups.recordError(context, null)
        assertEquals("", prefs.getString("error", null))
        prefs.edit().putBoolean("factoryResetPending", true).putString("error", "retained").commit()
        AutomaticBackups.recordError(context, "late-error")
        assertEquals("retained", prefs.getString("error", null))
        AutomaticBackups.completed(context, "persisted-gate-only")
        assertEquals("persisted-gate-only", prefs.getString("lastId", null))
        resetFlag().set(true)
        try {
            AutomaticBackups.completed(context, "must-not-publish")
            assertEquals("persisted-gate-only", prefs.getString("lastId", null))
            assertNull(AutomaticBackups.beginProducer())
        } finally {
            resetFlag().set(false)
        }
    }

    @Test
    fun passwordErrorAndCompletionUseTheOriginalJavaClassMonitor() {
        var observed = 0
        val checked =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    assertTrue(
                        "must share AutomaticBackups.class with Java callers",
                        Thread.holdsLock(AutomaticBackups::class.java),
                    )
                    observed++
                    return prefs
                }
            }
        AutomaticBackups.recordError(checked, "under-class-lock")
        AutomaticBackups.completed(checked, "under-class-lock")
        prefs.edit().putBoolean("factoryResetPending", true).commit()
        val failure = assertThrows(IOException::class.java) { AutomaticBackups.password(checked) }
        assertEquals("FACTORY_RESET_QUIESCING", failure.message)
        assertTrue(observed >= 3)
    }
}
