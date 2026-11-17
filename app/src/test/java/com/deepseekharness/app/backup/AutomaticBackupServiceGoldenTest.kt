package com.deepseekharness.app.backup

import android.app.Application
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Looper
import com.deepseekharness.app.util.ScheduledBackupOwner
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

/** 用可控队列驱动真实 Service；只替换归档/宿主事实，不启动归档或接触真实密钥。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class,
    instrumentedPackages = ["com.deepseekharness.app.backup"],
    shadows = [AutomaticBackupServiceGoldenTest.Backups::class, AutomaticBackupServiceGoldenTest.Jobs::class, AutomaticBackupServiceGoldenTest.Callbacks::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticBackupServiceGoldenTest {
    @Implements(value = AutomaticBackups::class, isInAndroidSdk = false)
    class Backups {
        companion object {
            var enabled = true
            var due = true
            var idle = true
            var passwordCalls = 0
            val errors = ArrayList<String>()
            @Implementation @JvmStatic fun enabled(context: Context): Boolean = enabled
            @Implementation @JvmStatic fun due(context: Context): Boolean = due
            @Implementation @JvmStatic fun idle(context: Context): Boolean = idle
            @Implementation @JvmStatic fun password(context: Context): CharArray {
                passwordCalls++
                return "synthetic-secret".toCharArray()
            }
            @Implementation @JvmStatic fun recordError(context: Context, error: String?) { errors += error ?: "" }
        }
    }

    @Implements(value = NativeBackupJobs::class, isInAndroidSdk = false)
    class Jobs {
        companion object {
            lateinit var instance: NativeBackupJobs
            var current = NativeBackupJobs.State("", "IDLE", "", "", "", 0, 0, false)
            var exports = 0
            var cancellations = 0
            @Implementation @JvmStatic fun get(context: Context): NativeBackupJobs = instance
        }
        @Implementation fun state(): NativeBackupJobs.State = current
        @Implementation fun exportAutomatic(secret: CharArray): Boolean {
            assertTrue(Thread.holdsLock(instance))
            exports++
            current = NativeBackupJobs.State("owned-native-id", "RUNNING", "", "", "", 0, 0, true)
            return true
        }
        @Implementation fun cancel() {
            assertTrue(Thread.holdsLock(instance))
            cancellations++
        }
    }

    @Implements(JobService::class)
    class Callbacks {
        companion object { val finished = ArrayList<Pair<JobParameters, Boolean>>() }
        @Implementation fun jobFinished(params: JobParameters, retry: Boolean) { finished += params to retry }
    }

    private lateinit var service: AutomaticBackupService
    private lateinit var owner: ScheduledBackupOwner<JobParameters>

    @Before
    fun reset() {
        Backups.enabled = true
        Backups.due = true
        Backups.idle = true
        Backups.passwordCalls = 0
        Backups.errors.clear()
        Callbacks.finished.clear()
        Jobs.instance = Shadow.newInstanceOf(NativeBackupJobs::class.java)
        Jobs.current = NativeBackupJobs.State("", "IDLE", "", "", "", 0, 0, false)
        Jobs.exports = 0
        Jobs.cancellations = 0
        service = Robolectric.buildService(AutomaticBackupService::class.java).create().get()
        val field = AutomaticBackupService::class.java.getDeclaredField("owner")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        owner = field.get(service) as ScheduledBackupOwner<JobParameters>
    }

    private fun params(): JobParameters = Shadow.newInstanceOf(JobParameters::class.java)

    private fun prepare(ticket: ScheduledBackupOwner.Ticket<JobParameters>, manual: Boolean, producer: WorkerQuiescence.Lease) {
        val method = AutomaticBackupService::class.java.getDeclaredMethod("prepare", ScheduledBackupOwner.Ticket::class.java, Boolean::class.javaPrimitiveType, WorkerQuiescence.Lease::class.java)
        method.isAccessible = true
        method.invoke(service, ticket, manual, producer)
    }

    private fun complete(ticket: ScheduledBackupOwner.Ticket<JobParameters>, secret: CharArray?, retry: Boolean = false, error: String = "") {
        val producers = WorkerQuiescence()
        val lease = producers.tryBegin()
        val method = AutomaticBackupService::class.java.getDeclaredMethod("completePreparation", ScheduledBackupOwner.Ticket::class.java, CharArray::class.java, Boolean::class.javaPrimitiveType, String::class.java, WorkerQuiescence.Lease::class.java)
        method.isAccessible = true
        method.invoke(service, ticket, secret, retry, error, lease)
        assertEquals("lease released by finally", 0, producers.active())
        if (secret != null) assertTrue("secret erased by finally", secret.all { it == '\u0000' })
    }

    @Test
    fun busyPreparationDefersWithoutStoppingUserWorkAndOnlyManualRequestsRetry() {
        Backups.idle = false
        for (manual in listOf(false, true)) {
            val p = params()
            val ticket = owner.begin(p)
            val producers = WorkerQuiescence()
            prepare(ticket, manual, producers.tryBegin())
            assertEquals(1, producers.active())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, producers.active())
            assertEquals(p to manual, Callbacks.finished.last())
        }
        assertEquals(0, Backups.passwordCalls)
        assertEquals(0, Jobs.exports)
        assertEquals(0, Jobs.cancellations)
    }

    @Test
    fun cancellationBeforeQueuedPreparationCannotExportOrFinishANewerRequest() {
        val oldParams = params()
        val old = owner.begin(oldParams)
        val producers = WorkerQuiescence()
        prepare(old, false, producers.tryBegin())
        assertEquals(1, Backups.passwordCalls)
        assertFalse(service.onStopJob(oldParams))
        val next = owner.begin(params())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(owner.current(next))
        assertEquals(0, producers.active())
        assertEquals(0, Jobs.exports)
        assertTrue(Callbacks.finished.isEmpty())
    }

    @Test
    fun staleCompletionErasesItsSecretAndCannotPublishErrors() {
        val p = params()
        val old = owner.begin(p)
        assertFalse(service.onStopJob(p))
        val next = owner.begin(params())
        complete(old, "late-synthetic-secret".toCharArray(), error = "late-error")
        assertTrue(owner.current(next))
        assertTrue(Backups.errors.isEmpty())
        assertTrue(Callbacks.finished.isEmpty())
        assertEquals(0, Jobs.exports)
    }

    @Test
    fun disableBeforeCompletionReleasesSecretAndHonorsRetry() {
        val p = params()
        val ticket = owner.begin(p)
        Backups.enabled = false
        complete(ticket, "synthetic-secret".toCharArray(), retry = true)
        assertEquals(listOf(p to true), Callbacks.finished)
        assertFalse(owner.current(ticket))
        assertEquals(0, Jobs.exports)
    }

    @Test
    fun ownedCancellationNeverTouchesAnUnrelatedNativeJob() {
        val p = params()
        val ticket = owner.begin(p)
        complete(ticket, "synthetic-secret".toCharArray())
        assertEquals("owned-native-id", owner.jobId(ticket))
        assertEquals(1, Jobs.exports)
        Jobs.current = NativeBackupJobs.State("unrelated-user-id", "RUNNING", "", "", "", 0, 0, true)
        assertFalse(service.onStopJob(p))
        assertEquals(0, Jobs.cancellations)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Callbacks.finished.isEmpty())
    }

    @Test
    fun destructionCancelsOnlyItsOwnedJobAndLateCallbacksStillReleaseTheirLease() {
        val p = params()
        val ticket = owner.begin(p)
        complete(ticket, "synthetic-secret".toCharArray())
        service.onDestroy()
        assertEquals(1, Jobs.cancellations)
        complete(ticket, "late-synthetic-secret".toCharArray())
        assertEquals(1, Jobs.exports)
        assertTrue(Callbacks.finished.isEmpty())
    }

    @Test
    fun preparationErrorFinishesExactlyOnceWithoutNativeSubmission() {
        val p = params()
        val ticket = owner.begin(p)
        complete(ticket, null, error = "PREPARATION_FAILED")
        complete(ticket, null, error = "LATE_FAILURE")
        assertEquals(listOf("PREPARATION_FAILED"), Backups.errors)
        assertEquals(listOf(p to false), Callbacks.finished)
        assertEquals(0, Jobs.exports)
    }
}
