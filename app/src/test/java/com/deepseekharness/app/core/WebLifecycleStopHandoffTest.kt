package com.deepseekharness.app.core

import com.deepseekharness.app.runtime.StoredWebSession
import com.deepseekharness.app.runtime.WebProcessManager
import com.deepseekharness.app.util.EnvironmentTaskGate
import com.deepseekharness.app.util.StopCompletion
import com.deepseekharness.app.util.StopOperationId
import com.deepseekharness.app.util.WebSessionProof
import com.deepseekharness.app.util.WebStartProtection
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 真正运行控制器队列、stop coordinator、原票据和 pipeline；只注入宿主 IO，不启动设备进程。 */
@RunWith(WebLifecycleStartProtectionTest.NoApkRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = WebLifecycleResponsivenessTest.App::class,
    shadows = [WebLifecycleResponsivenessTest.HostMetadata::class],
)
class WebLifecycleStopHandoffTest {
    private lateinit var f: WebLifecycleResponsivenessTest
    private lateinit var manager: Io
    private lateinit var processes: WebProcessSession
    private lateinit var web: WebLifecycleController
    private lateinit var prior: WebLifecycleController.WebRun
    private lateinit var ticket: WebStartProtection.Ticket
    private val service = Any()
    private val serviceCurrent = AtomicBoolean(true)
    private lateinit var pipeline: StartupPipelineTest.Fixture
    private var dispatches = 0
    private var closes = 0
    private var finishes = 0
    private var finishFailure = false
    private var beforeLaunch: () -> Unit = {}
    private var afterStop: () -> Unit = {}
    private var preparationCalls = 0
    private var duringLauncherPrepare: () -> Unit = {}
    private lateinit var newRecord: File
    private val created = mutableListOf<FixtureProcess>()

    private class FixtureProcess : Process() {
        private val exited = java.util.concurrent.CountDownLatch(1)
        private val output =
            object : java.io.InputStream() {
                override fun read(): Int {
                    exited.await()
                    return -1
                }
            }

        override fun getInputStream() = output

        override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())

        override fun getOutputStream() = java.io.ByteArrayOutputStream()

        override fun waitFor(): Int {
            exited.await()
            return 0
        }

        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return 0
        }

        override fun destroy() {
            exited.countDown()
        }
    }

    private inner class Io : WebProcessManager(f.boot) {
        var stops = 0
        var scans = 0
        var fresh = true
        var missing = false
        var unknownTrial = false

        override fun stopResult(trace: StopTrace?): StopResult {
            stops++
            return StopResult(StopStatus.STOPPED, "WEB_GUESTS_STOPPED", "")
        }

        override fun confirmStopped(live: Boolean, trace: StopTrace?): Boolean = !live

        override fun confirmStopped(live: Boolean): Boolean = !live

        override fun recoveredLauncherExited(saved: WebSessionProof.Saved?): Boolean = true

        override fun finishStopObservation(trace: StopTrace?): StopObservation {
            val now = android.os.SystemClock.elapsedRealtime()
            val guest = StopCompletion.ProcessKey("boot", 42, 7)
            return StopObservation(
                StopCompletion.Environment("root-1", "boot", "historical-inputs"),
                listOf(
                    StopCompletion.Fact(
                        StopCompletion.Scope.GUEST,
                        StopCompletion.Outcome.EXITED,
                        now,
                        "guest",
                        guest,
                    ),
                    StopCompletion.Fact(
                        StopCompletion.Scope.TRIALS,
                        if (unknownTrial) StopCompletion.Outcome.UNKNOWN
                        else StopCompletion.Outcome.EXITED,
                        now,
                        "trials",
                    ),
                    StopCompletion.Fact(
                        StopCompletion.Scope.RESIDUALS,
                        StopCompletion.Outcome.EXITED,
                        now,
                        "scan",
                    ),
                ),
                true,
            )
        }

        override fun prepareStopRecheck(
            trace: StopTrace?,
            completion: StopCompletion?,
        ): StopRecheck? =
            if (missing) null
            else
                StopRecheck {
                    scans++
                    fresh
                }
    }

    @Before
    fun create() {
        f = WebLifecycleResponsivenessTest()
        f.create()
        f.controller
            .runtimeHostPorts()
            .install(
                object : com.deepseekharness.app.runtime.RuntimeHostPorts.Provider {
                    override fun snapshot() =
                        com.deepseekharness.app.runtime.RuntimeHostPorts.Settings(
                            "auto",
                            false,
                            true,
                            false,
                        )

                    override fun stage(value: String) {}

                    override fun record(kind: String, detail: String) {}

                    override fun failure(error: Throwable) {}
                }
            )
        newRecord = File(f.app.noBackupFilesDir, "web-session.enc")
        assertTrue(File(f.boot.rootfsDir, "root/.dsh").mkdirs())
        f.boot.ready = true
        val running = f.state.lifecycle.beginStart(false, false)
        f.state.lifecycle.finishStart(running)
        f.state.diagnostics.begin(running, false)
        prior = WebLifecycleController.WebRun(running, "old-instance")
        val saved =
            WebSessionProof.Saved(
                "boot",
                running,
                "old-instance",
                "nonce",
                42,
                7,
                "node",
                43,
                8,
                "launcher",
            )
        prior.recovered = StoredWebSession.Record(saved, "", "app", "root", "descriptor", false, "")
        prior.bridge =
            object : WebProcessSession.BridgeLease {
                override fun ensureStarted() {}

                override fun close() {
                    closes++
                }
            }
        f.state.currentRun = prior
        f.state.runs[running] = prior
        manager = Io()
        processes =
            WebProcessSession(
                f.state,
                manager,
                WebProcessSession.Bridges {
                    object : WebProcessSession.BridgeLease {
                        override fun ensureStarted() {}

                        override fun close() {}
                    }
                },
                WebProcessSession.SessionRecords {
                    if (newRecord.exists()) throw IOException("NEW_OR_UNREADABLE_SESSION_RECORD")
                    null
                },
            )
        pipeline = StartupPipelineTest.Fixture()
        web =
            WebLifecycleController(
                f.controller,
                f.state,
                object : WebLifecycleController.ScreenLifetime {
                    override fun revoke() {
                        assertFalse(Thread.holdsLock(f.state.lifecycle))
                    }

                    override fun stop() {}
                },
                manager,
                processes,
                WebLifecycleController.Preparation { _, g, safe, real ->
                    preparationCalls++
                    val actions =
                        object : StartupPipeline.Actions {
                            override fun current() = real.current()

                            override fun yieldPluginQuery() {}

                            override fun stage(value: String) = real.stage(value)

                            override fun status(value: String) = real.status(value)

                            override fun reusePriorStop() = real.reusePriorStop()

                            override fun releasePrior() = real.releasePrior()

                            override fun awaitPriorExit(deadline: Long) =
                                real.awaitPriorExit(deadline)

                            override fun now() = real.now()

                            override fun recoverData() {
                                pipeline.calls.add("recover")
                            }

                            override fun cleanup() {
                                pipeline.calls.add("cleanup")
                            }

                            override fun beforeLaunch(id: String) {
                                beforeLaunch()
                            }

                            override fun safeProfile() = "safe-fixture"

                            override fun unlockStartup() = real.unlockStartup()

                            override fun checkpoint(id: String) {
                                pipeline.calls.add("checkpoint")
                            }
                        }
                    StartupPipeline(pipeline, pipeline).prepare(g, safe, actions)
                },
                { previous, result ->
                    finishes++
                    if (result.stopped()) processes.releaseBridge(previous)
                    afterStop()
                    if (finishFailure) throw IllegalStateException("LAST_SYNC_CLOSE_FAILED")
                },
                WebLifecycleController.Launcher { _, _, _, check ->
                    duringLauncherPrepare()
                    check?.verify()
                    dispatches++
                    FixtureProcess().also { created.add(it) }
                },
            )
        val field = HarnessController::class.java.getDeclaredField("web")
        field.isAccessible = true
        field.set(f.controller, web)
    }

    @After
    fun close() {
        f.state.startProtection.cancelAll()
        // 失效后只消费已排队的启动worker以释放共享租约，不执行后来显式Stop的Future。
        f.pending.queued
            .toList()
            .filter { it !is java.util.concurrent.Future<*> }
            .forEach { it.run() }
        f.pending.queued.clear()
        f.state.lifecycle.beginStop()
        f.state.lifecycle.finishStop(f.state.lifecycle.generation())
        created.forEach { it.destroy() }
        f.close()
        assertFalse(EnvironmentTaskGate.isBusy())
    }

    private fun recover() {
        ticket = f.state.startProtection.begin()!!
        assertTrue(f.state.startProtection.promote(ticket.id(), service, serviceCurrent::get))
        assertTrue(web.startProtectedWeb(ticket, false, true, null))
        assertEquals(2, f.pending.queued.size)
    }

    private fun stop() {
        f.pending.queued.removeAt(0).run()
        assertTrue(f.state.stopReceipt.completion().isDone)
    }

    private fun continueStart() {
        f.pending.queued.removeAt(0).run()
    }

    private fun worker() {
        f.pending.queued.removeAt(0).run()
    }

    private fun noReplay() {
        assertEquals(1, manager.stops)
        assertEquals(0, dispatches)
        assertTrue(f.pending.queued.isEmpty())
    }

    @Test
    fun protectedRecoveryConsumesOneStopAndRetainsLanReleaseAndGates() {
        recover()
        val gR = prior.generation
        val gS = web.webGeneration
        val receipt = f.state.stopReceipt
        stop()
        assertTrue(receipt.completion().get().complete())
        assertEquals(gR, receipt.operation().stoppedGeneration())
        assertEquals(gS, receipt.operation().stopGeneration())
        continueStart()
        val gN = web.webGeneration
        assertTrue(gR < gS && gS < gN)
        assertNotNull(f.state.currentRun.handoff)
        worker()
        assertEquals(1, manager.stops)
        assertEquals(1, finishes)
        assertEquals(1, closes)
        assertEquals(f.state.diagnostics.snapshot().log, 1, dispatches)
        assertTrue(manager.scans >= 2)
        assertTrue(
            pipeline.calls.containsAll(
                listOf("lan-stop", "tools", "recover", "cleanup", "migration", "checkpoint")
            )
        )
        assertFalse(pipeline.calls.contains("stop"))
        assertFalse(pipeline.calls.contains("wait-prior-exit"))
        assertFalse(File(f.boot.rootfsDir, "root/.dsha-stopped").exists())
        assertFalse(
            f.state.currentRun.handoff
                .admission()
                .consume(
                    f.state,
                    receipt.operation(),
                    gN,
                    f.state.startProtection.cancellationRevision(),
                    f.state.startProtection,
                )
        )
    }

    @Test
    fun repeatStopAtSameGSRevokesContinuation() {
        recover()
        val stopped = web.webGeneration
        web.stopWeb {}
        assertEquals(stopped, web.webGeneration)
        stop()
        continueStart()
        noReplay()
    }

    @Test
    fun operationBDoesNotBorrowOperationACompletionEvenIfDisplaySaysStopped() {
        recover()
        stop()
        val a = f.state.stopReceipt
        f.state.stopOperation =
            StopOperationId(
                f.state,
                99,
                prior.generation,
                "other",
                web.webGeneration,
                f.state.startProtection.cancellationRevision(),
            )
        continueStart()
        noReplay()
        assertSame(a.operation(), a.completion().get().operation())
    }

    @Test
    fun realStopBCompletionCannotBeBorrowedByStopAContinuation() {
        recover()
        stop()
        val a = f.state.stopReceipt
        web.stopWeb {}
        val b = f.state.stopReceipt
        assertNotSame(a.operation(), b.operation())
        f.pending.queued.removeAt(1).run()
        assertTrue(b.completion().get().legacyStopped())
        continueStart()
        assertEquals(2, manager.stops)
        assertEquals(0, dispatches)
        assertTrue(f.pending.queued.isEmpty())
        assertSame(a.operation(), a.completion().get().operation())
    }

    @Test
    fun foreignOwnerCompletionCannotResume() {
        recover()
        stop()
        val a = f.state.stopReceipt
        f.state.stopOperation =
            StopOperationId(
                Any(),
                99,
                prior.generation,
                "old-instance",
                web.webGeneration,
                f.state.startProtection.cancellationRevision(),
            )
        continueStart()
        noReplay()
        assertSame(a.operation(), a.completion().get().operation())
    }

    @Test
    fun gSChangeCannotResumeTheOldTicket() {
        recover()
        stop()
        f.state.lifecycle.beginStop()
        f.state.lifecycle.finishStop(web.webGeneration)
        continueStart()
        noReplay()
    }

    @Test
    fun gNChangeRejectsTheQueuedWorker() {
        recover()
        stop()
        continueStart()
        f.state.lifecycle.beginStop()
        f.state.lifecycle.finishStop(web.webGeneration)
        worker()
        noReplay()
    }

    @Test
    fun oldServiceDestroyedAfterAdmissionCannotDispatch() {
        recover()
        stop()
        continueStart()
        f.state.startProtection.serviceDestroyed(service)
        worker()
        noReplay()
    }

    @Test
    fun protectionLossAfterClaimDoesNotFallBackToStop() {
        recover()
        stop()
        continueStart()
        beforeLaunch = { serviceCurrent.set(false) }
        worker()
        noReplay()
    }

    @Test
    fun newGuestOrTrialAfterSelectionIsNotACacheMiss() {
        recover()
        stop()
        continueStart()
        manager.fresh = false
        worker()
        noReplay()
        assertEquals(0, preparationCalls)
    }

    @Test
    fun lastRecheckAfterPipelineCannotLaunchChangedEnvironment() {
        recover()
        stop()
        continueStart()
        beforeLaunch = { manager.fresh = false }
        worker()
        noReplay()
        assertEquals(1, preparationCalls)
    }

    @Test
    fun replacedRunObjectCannotInheritTheStoppedInstance() {
        recover()
        stop()
        continueStart()
        f.state.runs[prior.generation] =
            WebLifecycleController.WebRun(prior.generation, prior.instanceId)
        worker()
        noReplay()
    }

    @Test
    fun newRunObjectIsNotHiddenByTheEmptyOriginalMap() {
        recover()
        stop()
        continueStart()
        f.state.runs[99] = WebLifecycleController.WebRun(99)
        worker()
        noReplay()
    }

    @Test
    fun finalSynchronousCloseFailureBlocksWithoutReadingDisplayProof() {
        finishFailure = true
        recover()
        stop()
        continueStart()
        noReplay()
        assertEquals(StopCompletion.Finish.FAILED, f.state.stopCompletion.finish())
        assertEquals(1, finishes)
    }

    @Test
    fun onlyMissingProofBeforeSelectionRetainsTheOriginalStopChain() {
        manager.missing = true
        recover()
        stop()
        continueStart()
        assertNull(f.state.currentRun.handoff)
        worker()
        assertTrue(pipeline.calls.contains("stop"))
        assertEquals(f.state.diagnostics.snapshot().log, 1, dispatches)
    }

    @Test
    fun incompleteTrialBeforeSelectionRetainsTheOriginalStopChain() {
        manager.unknownTrial = true
        recover()
        stop()
        continueStart()
        assertNull(f.state.currentRun.handoff)
        worker()
        assertTrue(pipeline.calls.contains("stop"))
    }

    @Test
    fun noTicketRecoveryStaysOnTheOriginalChain() {
        web.recoverWeb(false, null, null)
        stop()
        continueStart()
        worker()
        assertNull(f.state.currentRun.handoff)
        assertTrue(pipeline.calls.contains("stop"))
    }

    @Test
    fun protectedColdStartStaysOnTheOriginalChain() {
        ticket = f.state.startProtection.begin()!!
        assertTrue(f.state.startProtection.promote(ticket.id(), service, serviceCurrent::get))
        assertTrue(web.startProtectedWeb(ticket, false, false, null))
        worker()
        assertNull(f.state.currentRun.handoff)
        assertTrue(pipeline.calls.contains("stop"))
        assertEquals(0, manager.stops)
    }

    @Test
    fun newEncryptedRecordDuringPipelineIsPreservedAndBlocksLaunch() {
        recover()
        stop()
        continueStart()
        beforeLaunch = { newRecord.writeText("new-session-evidence") }
        worker()
        noReplay()
        assertEquals("new-session-evidence", newRecord.readText())
    }

    @Test
    fun lostProtectionInsideLauncherPreparationIsCheckedAtActualDispatch() {
        recover()
        stop()
        continueStart()
        duringLauncherPrepare = { serviceCurrent.set(false) }
        worker()
        noReplay()
        assertEquals(1, preparationCalls)
    }

    @Test
    fun newGuestInsideLauncherPreparationIsNotLaunchedOver() {
        recover()
        stop()
        continueStart()
        duringLauncherPrepare = { manager.fresh = false }
        worker()
        noReplay()
        assertEquals(1, preparationCalls)
    }

    @Test
    fun disablingFaultyPluginDoesNotChooseHandoff() {
        f.state.diagnostics.issue(prior.generation, "broken-user-plugin", "failure")
        web.recoverWeb(false, "broken-user-plugin", null)
        stop()
        continueStart()
        worker()
        assertEquals(1, f.boot.pluginCalls.get())
        assertNull(f.state.currentRun.handoff)
        assertTrue(pipeline.calls.contains("stop"))
    }
}
