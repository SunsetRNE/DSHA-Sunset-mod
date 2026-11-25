package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.backup.BackupJson
import com.deepseekharness.app.backup.RuntimeDescriptor
import com.deepseekharness.app.core.EnvironmentAccess
import com.deepseekharness.app.core.HarnessController
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 真实 ManagedRuntimeAssets 读取/健康链；仅把 Android 文件调用适配到隔离宿主文件。 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = RuntimeToolsRegistryAndroidTest.OwnedApplication::class,
    instrumentedPackages = ["com.deepseekharness.app.backup"],
    shadows = [RuntimeDescriptorReadIntegrationTest.CountingFiles::class],
)
class RuntimeDescriptorReadIntegrationTest {
    companion object {
        var descriptorReads = 0
        var healthReads = 0
    }

    @Implements(value = AndroidBackupFileSystem::class, isInAndroidSdk = false)
    class CountingFiles : RuntimeToolsRegistryAndroidTest.HostFileSystem() {
        @Implementation
        override fun read(file: File, node: BackupFileSystem.Node): InputStream {
            if (file.name == ".runtime-descriptor.json") descriptorReads++
            if (file.parentFile.name == "runtime-health") healthReads++
            return super.read(file, node)
        }
    }

    private lateinit var files: File
    private lateinit var installed: File
    private lateinit var health: File
    private lateinit var assets: ManagedRuntimeAssets
    private lateinit var preparation: RuntimeDescriptorPreparation
    private val lines = ArrayList<String>()
    private var assetReads = 0

    @Before
    fun setup() {
        files = RuntimeEnvironment.getApplication().filesDir.canonicalFile
        val root = File(files, "linux/ubuntu").apply { assertTrue(mkdirs()) }
        installed = File(root.parentFile, ".runtime-descriptor.json")
        installed.writeBytes(RuntimeDescriptorPreparationTest.bytes())
        val descriptor = RuntimeDescriptor(BackupJson.read(installed.readBytes(), 2 * 1024 * 1024))
        health = File(File(files, "runtime-health").apply { mkdirs() }, descriptor.id() + ".json")
        val proof =
            linkedMapOf<String, Any>(
                "runtimeId" to descriptor.id(),
                "nonce" to "1".repeat(32),
                "port" to 12345L,
                "confirmedAt" to 1L,
            )
        for (check in
            listOf(
                "assets",
                "nativeModules",
                "process",
                "authentication",
                "localApi",
                "renderer",
                "dataRead",
                "dataWrite",
                "storageFreshReopened",
                "processExited",
            )) proof[check] = true
        health.writeBytes(BackupJson.write(proof, 512 * 1024))
        val identity =
            RuntimeDescriptorPreparation.Identity(Any(), Any(), Any(), "root/file", "tools/1")
        preparation = RuntimeDescriptorPreparation { identity }
        assets =
            ManagedRuntimeAssets(
                RuntimeEnvironment.getApplication(),
                root,
                { "environment" },
                {
                    assetReads++
                    RuntimeDescriptorPreparationTest.bytes().toString(Charsets.UTF_8)
                },
                { null },
                preparation,
            )
        descriptorReads = 0
        healthReads = 0
    }

    @Test
    fun realReadinessReceiptChainRetainsAllReadsButParsesOncePerKind() {
        assets.openPreparation(7, { true }, lines::add).use {
            repeat(2) {
                val descriptor = assets.installedDescriptor()!!
                assertTrue(descriptor.compatible(assets.expectedDescriptor()))
                assertTrue(RuntimeDescriptor.healthy(assets.health(), descriptor.id()))
            }
        }
        assertEquals(4, descriptorReads)
        assertEquals(2, healthReads)
        assertEquals(2, assetReads)
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-installed calls=4"))
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-installed parses=1"))
        assertTrue(lines.contains("STARTUP_PREP_OBSERVATION: phase=descriptor-expected parses=1"))
    }

    @Test
    fun samePathReplacementWithRestoredTimeCannotReuseOldParsedBytes() {
        assets.openPreparation(7, { true }, lines::add).use {
            val first = assets.installedDescriptor()!!
            val time = Files.getLastModifiedTime(installed.toPath())
            val replacement = File(installed.parentFile, "candidate.json")
            replacement.writeBytes(RuntimeDescriptorPreparationTest.bytes("b"))
            Files.move(
                replacement.toPath(),
                installed.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.setLastModifiedTime(installed.toPath(), time)
            val second = assets.installedDescriptor()!!
            assertNotEquals(first.id(), second.id())
            assertFalse(second.latest(assets.expectedDescriptor()))
            assertTrue(second.compatible(assets.expectedDescriptor()))
            assertFalse(RuntimeDescriptor.healthy(assets.health(), second.id()))
        }
        assertEquals(3, descriptorReads)
    }

    @Test
    fun missingOrUnhealthyReceiptIsNotReplacedByAWarmDescriptor() {
        assets.openPreparation(7, { true }, lines::add).use {
            val first = assets.installedDescriptor()!!
            assertTrue(RuntimeDescriptor.healthy(assets.health(), first.id()))
            health.writeText("{}")
            assertFalse(RuntimeDescriptor.healthy(assets.health(), first.id()))
            assertNotSame(first, assets.installedDescriptor())
            assertTrue(health.delete())
            assertNull(assets.health())
        }
        assertEquals(2, healthReads)
    }

    @Test
    fun limitedEntryBannerTreatsAnAbsentDescriptorAsNotLatest() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = File(files, "native").apply { mkdirs() }.absolutePath
        val controller = HarnessController(app)
        assertTrue(installed.delete())

        assertFalse(EnvironmentAccess.runtimeLatest(controller))
        assertNull(controller.proot().installedRuntimeDescriptor())
        assertFalse(controller.proot().isEnvironmentReady())

        val linux = File(files, "linux")
        assertTrue(File(linux, "ubuntu").delete())
        assertTrue(linux.delete())
        assertFalse(EnvironmentAccess.runtimeLatest(controller))
        assertNull(controller.proot().installedRuntimeDescriptor())
    }

    @Test
    fun runtimeFacadeDistinguishesMissingMalformedAndHealthyRecords() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = File(files, "native").apply { mkdirs() }.absolutePath
        val boot = ProotBootstrap(app)
        val descriptor = boot.installedRuntimeDescriptor() ?: error("Missing test descriptor")
        assertTrue(RuntimeDescriptor.healthy(boot.runtimeHealth(), descriptor.id()))

        assertTrue(health.delete())
        assertNull(boot.runtimeHealth())
        assertEquals(descriptor.id(), boot.installedRuntimeDescriptor()?.id())

        installed.writeBytes(byteArrayOf())
        assertThrows(IOException::class.java) { boot.installedRuntimeDescriptor() }
        installed.writeText("{truncated")
        assertThrows(IOException::class.java) { boot.runtimeHealth() }
        assertFalse(boot.isEnvironmentReady())

        assertTrue(installed.delete())
        assertNull(boot.runtimeHealth())
    }

    @Test
    fun actualPendingFenceAndFinalLauncherCheckRemainAfterAWarmParse() {
        assets.openPreparation(7, { true }, lines::add).use {
            assets.installedDescriptor()
            assertSame(assets.installedDescriptor(), assets.installedDescriptor())
            RuntimeEnvironment.getApplication().applicationInfo.nativeLibraryDir =
                File(files, "native").apply { mkdirs() }.absolutePath
            val boot =
                object : ProotBootstrap(RuntimeEnvironment.getApplication()) {
                    override fun isEnvironmentReady() = true
                }
            // 类型错误的现有 guest 事务根由真实 pending inventory 判为 unknown 并阻止启动。
            File(files, "bounded-guest-active").writeText("unknown record root")
            assertThrows(java.io.IOException::class.java) { boot.requireUserRuntime() }
            var finalChecks = 0
            val launcher =
                RuntimeLauncher(
                    null,
                    files,
                    files,
                    files,
                    files,
                    RuntimeHostPorts().also {
                        it.install(
                            object : RuntimeHostPorts.Provider {
                                override fun snapshot() =
                                    RuntimeHostPorts.Settings("auto", false, true, false)

                                override fun stage(value: String) {}

                                override fun record(kind: String, detail: String) {}

                                override fun failure(error: Throwable) {}
                            }
                        )
                    },
                )
            assertThrows(java.io.IOException::class.java) {
                launcher.start(
                    "never-run",
                    LaunchSpec.runtime(RuntimeLauncherTest.fake()).pipedInput(true).build(),
                    null,
                ) {
                    finalChecks++
                    throw java.io.IOException("FINAL_RECHECK")
                }
            }
            assertEquals(1, finalChecks)
        }
    }
}
