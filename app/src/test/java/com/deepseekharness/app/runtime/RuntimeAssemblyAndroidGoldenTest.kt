package com.deepseekharness.app.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.util.ColdInstallPlan
import java.io.File
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements

/**
 * 冻结需要 Android Context / AndroidBackupFileSystem 的组装结果（proroot argv、数据域 bind、stdin 重定向、
 * 运行方式选择与硬链接探测）。Robolectric 4.15.1 + SDK 34 在 CI 与本机是同一套影子实现，因此冻结数据可移植； 唯一机器相关部分是临时根，已归一为 `$ROOT`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
    shadows =
        [
            RuntimeAssemblyStorageAccessShadow::class,
            RuntimeAssemblyAndroidGoldenTest.HostFileSystemShadow::class,
        ],
)
class RuntimeAssemblyAndroidGoldenTest {
    /**
     * Robolectric 的 Os.lstat 对不存在的路径返回 mode=0（SPECIAL）而不是 ENOENT， 会把「缺失」误判为特殊文件。这里沿用仓库既有做法，用真实 JVM
     * 文件系统影子替换 AndroidBackupFileSystem 的平台字节事实；迁移前后的产品代码走同一路径。
     */
    @Implements(value = AndroidBackupFileSystem::class, isInAndroidSdk = false)
    class HostFileSystemShadow : RuntimeToolsRegistryAndroidTest.HostFileSystem()

    @Test
    fun frozenAndroidAssemblyMatches() {
        RuntimeAssemblyGoldenSupport.writeOrAssert(
            RuntimeAssemblyGoldenSupport.ANDROID_RESOURCE,
            RuntimeAssemblyGoldenSupport.render(sections()),
        )
    }

    private fun sections(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        sections += prorootArgv()
        sections += prootArgvWithDomain()
        sections += launchBuilderStdin()
        sections += runtimeSelection()
        sections += hardlinkProbe()
        sections += invalidRecord()
        sections += executionLaunch()
        return sections
    }

    private fun prorootArgv(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val context = contextFor(fixture, ports)
            val static = ContainerRuntime.Proroot(context, fixture.native, true)
            sections +=
                fixture.section("proroot-argv-static") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            static.baseArgv(fixture.rootfs, true, null)
                        )
                    )
                }
            val dynamic = ContainerRuntime.Proroot(context, fixture.native, false)
            sections +=
                fixture.section("proroot-argv-dynamic-datadomain") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            dynamic.baseArgv(fixture.rootfs, false, fixture.files)
                        )
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val context = contextFor(fixture, ports)
            val rootfs = fixture.weirdRootfs()
            val runtime = ContainerRuntime.Proroot(context, fixture.native, true)
            sections +=
                fixture.section("proroot-argv-space-quote") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(rootfs, false, null)
                        )
                    )
                }
        }
        return sections
    }

    private fun prootArgvWithDomain(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val context = contextFor(fixture, ports)
            val runtime =
                ContainerRuntime.Proot(
                    context,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("proot-argv-datadomain") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(fixture.rootfs, true, fixture.files)
                        )
                    )
                    meta(
                        "migrationDirectory",
                        File(fixture.files, "rc1-migration-state").isDirectory,
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val context = contextFor(fixture, ports)
            val files = fixture.weirdFilesDomain()
            val rootfs = File(files, "linux/ubuntu").also { it.mkdirs() }
            val runtime =
                ContainerRuntime.Proot(
                    context,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("proot-argv-space-quote-datadomain") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(rootfs, false, files)
                        )
                    )
                }
        }
        return sections
    }

    private fun launchBuilderStdin(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        val ports =
            RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                .ports
        val launcher =
            RuntimeLauncher(null, fixture.rootfs, fixture.base, fixture.lib, fixture.tmp, ports)
        for (variant in
            listOf(
                Triple("normal", false, false),
                Triple("isolated", true, false),
                Triple("piped", false, true),
            )) {
            val name = variant.first
            val isolated = variant.second
            val piped = variant.third
            sections +=
                fixture.section("launch-builder-stdin-$name") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .hardlinks(true)
                            .isolated(isolated)
                            .pipedInput(piped)
                            .build()
                    meta(
                        "stdin",
                        RuntimeAssemblyGoldenSupport.stdinRedirect(launcher.builder("true", spec)),
                    )
                }
        }
        return sections
    }

    private fun runtimeSelection(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            sections +=
                fixture.section("runtime-selection-proroot-available") {
                    val recording =
                        RuntimeAssemblyGoldenSupport.RecordingPorts(
                            RuntimeAssemblyGoldenSupport.settings(
                                proroot = true,
                                staticLoader = true,
                            )
                        )
                    val execution = execution(fixture, recording, forceProot = false)
                    val selected = execution.runtime()
                    meta("selected", selected.id())
                    meta("records", recording.records)
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            sections +=
                fixture.section("runtime-selection-fallback-proot") {
                    val recording =
                        RuntimeAssemblyGoldenSupport.RecordingPorts(
                            RuntimeAssemblyGoldenSupport.settings(
                                proroot = true,
                                staticLoader = true,
                            )
                        )
                    val execution = execution(fixture, recording, forceProot = false)
                    val selected = execution.runtime()
                    meta("selected", selected.id())
                    meta("records", recording.records)
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            sections +=
                fixture.section("runtime-selection-force-proot") {
                    val recording =
                        RuntimeAssemblyGoldenSupport.RecordingPorts(
                            RuntimeAssemblyGoldenSupport.settings(proroot = true)
                        )
                    val execution = execution(fixture, recording, forceProot = true)
                    meta("selected", execution.runtime().id())
                    meta("records", recording.records)
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            sections +=
                fixture.section("runtime-selection-proroot-disabled") {
                    val recording =
                        RuntimeAssemblyGoldenSupport.RecordingPorts(
                            RuntimeAssemblyGoldenSupport.settings(proroot = false)
                        )
                    val execution = execution(fixture, recording, forceProot = false)
                    meta("selected", execution.runtime().id())
                    meta("records", recording.records)
                }
        }
        return sections
    }

    private fun hardlinkProbe(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        val recording =
            RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
        return listOf(
            fixture.section("runtime-hardlink-probe") {
                val context = contextFor(fixture, recording.ports)
                meta(
                    "supported",
                    RuntimeHardlinks(context, fixture.rootfs, recording.ports).supported(),
                )
                meta("records", recording.records)
            }
        )
    }

    private fun invalidRecord(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        val recording =
            RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
        return listOf(
            fixture.section("launch-spec-invalid-record-without-isolation") {
                val record = BoundedGuestSessions.begin(fixture.files)
                try {
                    failure(
                        "build",
                        fail {
                            LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                                .record(record)
                                .build()
                        },
                    )
                } finally {
                    runCatching { record.completed() }
                }
            }
        )
    }

    private fun executionLaunch(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        fixture.nativeFile("libproot.so")
        val recording =
            RuntimeAssemblyGoldenSupport.RecordingPorts(
                RuntimeAssemblyGoldenSupport.settings(proroot = false)
            )
        return listOf(
            fixture.section("execution-launch-datadomain") {
                val execution = execution(fixture, recording, forceProot = false)
                val selected = execution.runtime()
                val spec = execution.launch(selected).hardlinks(execution.hardlinks()).build()
                meta("runtime", spec.runtime.id())
                meta("dataDomain", spec.dataDomain)
                meta("hardlinks", spec.hardlinks)
            }
        )
    }

    private fun execution(
        fixture: RuntimeAssemblyGoldenSupport.Fixture,
        recording: RuntimeAssemblyGoldenSupport.RecordingPorts,
        forceProot: Boolean,
    ): RuntimeExecution {
        val context = contextFor(fixture, recording.ports)
        val nativeFiles = RuntimeNativeFiles(context, fixture.linux, fixture.lib, fixture.tmp)
        val launcher =
            RuntimeLauncher(
                context,
                fixture.rootfs,
                fixture.linux,
                fixture.lib,
                fixture.tmp,
                recording.ports,
            )
        return RuntimeExecution(
            context,
            fixture.rootfs,
            fixture.linux,
            fixture.tmp,
            forceProot,
            true,
            recording.ports,
            object : RuntimeExecution.Readiness {
                override fun require() {}
            },
            nativeFiles,
            launcher,
        )
    }

    private fun contextFor(
        fixture: RuntimeAssemblyGoldenSupport.Fixture,
        ports: RuntimeHostPorts,
    ): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()), RuntimeHostPorts.Owner {
            override fun getApplicationInfo(): ApplicationInfo {
                val info = ApplicationInfo(super.getApplicationInfo())
                info.dataDir = fixture.data.path
                info.nativeLibraryDir = fixture.native.path
                return info
            }

            override fun getFilesDir(): File = fixture.files

            override fun getCacheDir(): File = fixture.cache

            override fun getApplicationContext(): Context = this

            override fun runtimeHostPorts(): RuntimeHostPorts = ports
        }
}
