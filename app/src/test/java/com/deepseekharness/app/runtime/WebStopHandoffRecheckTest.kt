package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.backup.JvmBackupFileSystem
import com.deepseekharness.app.util.StopCompletion
import com.deepseekharness.app.util.StopOperationId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真实临时目录/节点/字节加注入内核读取；运行生产无信号复核，绝不启动或结束进程。 */
class WebStopHandoffRecheckTest {
    // A8 P1 身份解析只接受已审的 Kotlin 注解形式；JUnit4 方法规则与
    // `@get:Rule` 等价，避免 use-site 注解被当作无法核验的测试声明。
    @Rule fun temporaryFolder(): TemporaryFolder = temporary

    private val temporary = TemporaryFolder()
    private val fs = JvmBackupFileSystem()
    private lateinit var home: File
    private lateinit var trials: File
    private lateinit var trial: File
    private lateinit var payload: File
    private lateinit var marker: File
    private val trialId = "11111111-1111-1111-1111-111111111111"

    private inner class Io : WebProcessManager(null) {
        var boot = "boot"
        var newGuest = false
        var unknownGuest = false
        var scanCalls = 0
        var inputFs: BackupFileSystem = fs

        override fun root() = home

        override fun stopFileSystem() = inputFs

        override fun stopRootIdentity(): String {
            val node = fs.stat(home)
            if (node.type != "DIRECTORY") throw IOException("ROOT_UNKNOWN")
            return "${node.device}:${node.key}"
        }

        override fun recoveryBoot() = boot

        override fun recoveryProcessEntries(): Array<String> {
            scanCalls++
            return arrayOf("42", "77")
        }

        override fun recoveryHostPid() = 1

        override fun recoveryHostUid() = 100

        override fun processOwner(pid: Int): Int = 100

        override fun inspect(pid: Int): ProcessState {
            if (pid == 42 && unknownGuest) throw IOException("KERNEL_DENIED")
            return if (pid == 77 && newGuest) ProcessState(Kind.WEB, null, "node\u0000dsh\u0000web")
            else ProcessState(Kind.GONE, null, "")
        }

        override fun confirmStopped(live: Boolean): Boolean =
            error("must not retire records or write sentinel")

        override fun stopResult(): StopResult = error("must not submit another stop")
    }

    private fun capture(beforeFreeze: () -> Unit = {}): Pair<Io, WebProcessManager.StopRecheck> {
        home = temporary.newFolder("root")
        trials = temporary.newFolder("runtime-trials")
        trial = File(trials, trialId).apply { assertTrue(mkdir()) }
        payload = File(trial, "payload").apply { assertTrue(mkdir()) }
        marker = File(trial, "launched").apply { writeText(trialId) }
        File(home, ".dsha-web.pid.stale").writeText("42\n")
        File(payload, ".dsha-web.pid.stale").writeText("52\n")
        val io = Io()
        val trace = io.beginStopObservation()
        trace.rememberInput(trials, fs.stat(trials), null)
        trace.rememberTrialEntries(trials, fs.list(trials))
        trace.rememberInput(trial, fs.stat(trial), null)
        trace.rememberInput(payload, fs.stat(payload), null)
        trace.rememberInput(marker, fs.stat(marker), marker.readBytes())
        val observation = io.finishStopObservation(trace)
        val completion =
            StopCompletion(
                StopOperationId(Any(), 1, 1, "instance", 2, 0),
                observation.environment(),
                StopCompletion.Scope.values().map { scope ->
                    StopCompletion.Fact(
                        scope,
                        StopCompletion.Outcome.EXITED,
                        1,
                        "final-scope",
                        if (scope == StopCompletion.Scope.GUEST)
                            StopCompletion.ProcessKey("boot", 42, 7)
                        else null,
                    )
                },
                2,
                StopCompletion.Finish.SUCCEEDED,
                true,
                true,
            )
        beforeFreeze()
        val recheck = io.prepareStopRecheck(trace, completion)
        assertNotNull(recheck)
        return io to recheck!!
    }

    @Test
    fun unchangedInputsAreRecheckedOnEveryCallWithoutStopOrSentinel() {
        val (io, check) = capture()
        assertTrue(check.unchanged())
        assertTrue(check.unchanged())
        assertEquals(2, io.scanCalls)
        assertFalse(File(home, ".dsha-stopped").exists())
        assertEquals("42\n", File(home, ".dsha-web.pid.stale").readText())
    }

    @Test
    fun newGuestDiscoveredByFreshGlobalScanBlocksHandoff() {
        val (io, check) = capture()
        io.newGuest = true
        assertFalse(check.unchanged())
        assertEquals(1, io.scanCalls)
    }

    @Test
    fun unreadableOriginalGuestCannotInheritAnEmptyGlobalScan() {
        val (io, check) = capture()
        io.unknownGuest = true
        assertFalse(check.unchanged())
    }

    @Test
    fun bootChangeInvalidatesEvenWhenTheOriginalGuestIsGone() {
        val (io, check) = capture()
        io.boot = "new-boot"
        assertFalse(check.unchanged())
    }

    @Test
    fun samePathNewRootInodeCannotInheritTheProof() {
        val (_, check) = capture()
        Files.move(home.toPath(), File(home.parentFile, "old-root").toPath())
        assertTrue(home.mkdir())
        assertFalse(check.unchanged())
    }

    @Test
    fun newTrialMemberInvalidatesWithoutDirectoryMtimeEvidence() {
        val (_, check) = capture()
        val time = trials.lastModified()
        File(trials, "22222222-2222-2222-2222-222222222222").mkdir()
        Files.setLastModifiedTime(trials.toPath(), FileTime.fromMillis(time))
        assertFalse(check.unchanged())
    }

    @Test
    fun markerSameInodeSameLengthAndTimeStillChecksBytes() {
        val (_, check) = capture()
        val time = marker.lastModified()
        marker.writeText("22222222-2222-2222-2222-222222222222")
        Files.setLastModifiedTime(marker.toPath(), FileTime.fromMillis(time))
        assertFalse(check.unchanged())
    }

    @Test
    fun atomicMarkerReplacementWithSameBytesStillChangesTheNode() {
        val (_, check) = capture()
        val replacement = File(trial, "replacement").apply { writeText(trialId) }
        Files.move(replacement.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertFalse(check.unchanged())
    }

    @Test
    fun newMainPidRecordBlocksWithoutRetiringIt() {
        val (_, check) = capture()
        val pid = File(home, ".dsha-web.pid").apply { writeText("77\n") }
        assertFalse(check.unchanged())
        assertEquals("77\n", pid.readText())
    }

    @Test
    fun newTrialPidRecordBlocksWithoutRetiringIt() {
        val (_, check) = capture()
        File(payload, ".dsha-web.pid").writeText("77\n")
        assertFalse(check.unchanged())
    }

    @Test
    fun postStopStaleRecordBytesCannotChangeUnderTheSameNode() {
        val (_, check) = capture()
        val stale = File(payload, ".dsha-web.pid.stale")
        val time = stale.lastModified()
        stale.writeText("62\n")
        Files.setLastModifiedTime(stale.toPath(), FileTime.fromMillis(time))
        assertFalse(check.unchanged())
    }

    @Test
    fun lastReadFailureIsUnknownNotPermissionToRepeatStop() {
        val (io, check) = capture()
        io.inputFs =
            object : JvmBackupFileSystem() {
                override fun read(file: File, node: BackupFileSystem.Node): java.io.InputStream {
                    throw IOException("DENIED")
                }
            }
        assertFalse(check.unchanged())
    }

    @Test
    fun mainPidIntroducedBetweenCompletionAndFreezeIsNotAdopted() {
        val (_, check) = capture { File(home, ".dsha-web.pid").writeText("77\n") }
        assertFalse(check.unchanged())
    }

    @Test
    fun mainIdentityIntroducedBetweenCompletionAndFreezeIsNotAdopted() {
        val (_, check) = capture { File(home, ".dsha-web.identity").writeText("77 9\n") }
        assertFalse(check.unchanged())
    }

    @Test
    fun trialPidIntroducedBetweenCompletionAndFreezeIsNotAdopted() {
        val (_, check) = capture { File(payload, ".dsha-web.pid").writeText("77\n") }
        assertFalse(check.unchanged())
    }

    @Test
    fun trialIdentityIntroducedBetweenCompletionAndFreezeIsNotAdopted() {
        val (_, check) = capture { File(payload, ".dsha-web.identity").writeText("77 9\n") }
        assertFalse(check.unchanged())
    }

    @Test
    fun oversizedStaleRecordAfterCompletionBlocksInsteadOfMissingProofFallback() {
        val (_, check) = capture { File(home, ".dsha-web.pid.stale").writeText("x".repeat(129)) }
        assertFalse(check.unchanged())
    }

    @Test
    fun launchCheckRunsAfterRealBuilderPreparationAndBeforeProcessCreation() {
        val ports = RuntimeHostPorts()
        ports.install(
            object : RuntimeHostPorts.Provider {
                override fun snapshot() = RuntimeHostPorts.Settings("auto", false, true, false)

                override fun stage(value: String) {}

                override fun record(kind: String, detail: String) {}

                override fun failure(error: Throwable) {}
            }
        )
        val root = temporary.newFolder("launcher-root")
        var prepared = false
        var checked = 0
        val fake = RuntimeLauncherTest.fake()
        val runtime =
            object : ContainerRuntime by fake {
                override fun applyEnv(builder: ProcessBuilder, base: File, lib: File, tmp: File) {
                    fake.applyEnv(builder, base, lib, tmp)
                    prepared = true
                }
            }
        val launcher = RuntimeLauncher(null, root, root, root, root, ports)
        val error =
            assertThrows(IOException::class.java) {
                launcher.start(
                    "fixture-must-not-execute",
                    LaunchSpec.runtime(runtime).pipedInput(true).build(),
                    null,
                    ProotBootstrap.LaunchCheck {
                        assertTrue(prepared)
                        checked++
                        throw IOException("HANDOFF_REVOKED_AT_DISPATCH")
                    },
                )
            }
        assertEquals("HANDOFF_REVOKED_AT_DISPATCH", error.message)
        assertEquals(1, checked)
    }

    @Test
    fun statToReadReplacementCannotReceiveANewExpectedNode() {
        val (io, check) = capture()
        io.inputFs =
            object : JvmBackupFileSystem() {
                override fun read(file: File, node: BackupFileSystem.Node): java.io.InputStream {
                    if (file == marker) {
                        val replacement = File(trial, "swapped").apply { writeText(trialId) }
                        Files.move(
                            replacement.toPath(),
                            file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    return super.read(file, node)
                }
            }
        assertFalse(check.unchanged())
    }
}
