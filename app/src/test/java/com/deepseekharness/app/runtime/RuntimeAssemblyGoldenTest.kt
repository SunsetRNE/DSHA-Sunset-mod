package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.JvmBackupFileSystem
import com.deepseekharness.app.backup.PrivateRoots
import com.deepseekharness.app.backup.UserDataLayout
import com.deepseekharness.app.util.ColdInstallPlan
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冻结「命令与挂载组装」层的实际 argv/env/BINDS 结果。
 *
 * <p>先在本迁移之前的 Java 实现上以 `-Ddsha.runtime.golden.dir=<目录>` 生成并提交
 * `app/src/test/resources/runtime-assembly-golden.txt`，迁移后用同一份冻结数据断言字节一致。 需要 Android
 * Context/AndroidBackupFileSystem 的用例在 [RuntimeAssemblyAndroidGoldenTest]。
 */
class RuntimeAssemblyGoldenTest {
    @Test
    fun frozenPlainAssemblyMatches() {
        RuntimeAssemblyGoldenSupport.writeOrAssert(
            RuntimeAssemblyGoldenSupport.PLAIN_RESOURCE,
            RuntimeAssemblyGoldenSupport.render(sections()),
        )
    }

    private fun sections(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        sections += binds()
        sections += prootArgv()
        sections += prootEnvironment()
        sections += prorootEnvironment()
        sections += launchSpec()
        sections += launchBuilder()
        sections += startOrder()
        sections += runtimeMode()
        sections += userDataBindings()
        return sections
    }

    private fun binds(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        return listOf(
            fixture.section("binds-storage-off") { binds(ContainerRuntime.binds(false)) },
            fixture.section("binds-storage-on") { binds(ContainerRuntime.binds(true)) },
        )
    }

    private fun prootArgv(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("proot-argv-hardlinks-on") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(fixture.rootfs, true, null)
                        )
                    )
                }
            sections +=
                fixture.section("proot-argv-hardlinks-off") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(fixture.rootfs, false, null)
                        )
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            val rootfs = File(fixture.weirdDirectory(), "linux/ubuntu").also { it.mkdirs() }
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("proot-argv-space-quote") {
                    argv(
                        RuntimeAssemblyGoldenSupport.withoutSystemBinds(
                            runtime.baseArgv(rootfs, false, null)
                        )
                    )
                }
        }
        return sections
    }

    private fun prootEnvironment(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            fixture.nativeFile("libprootloader.so")
            fixture.nativeFile("libprootloader32.so")
            fixture.libFile("libtalloc.so.2")
            fixture.libFile("libandroid-shmem.so")
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("proot-env-seccomp-on") { applyEnvironment(runtime, fixture) }
            File(fixture.native, "libprootloader32.so").delete()
            sections +=
                fixture.section("proot-env-seccomp-off") { applyEnvironment(runtime, fixture) }
            File(fixture.lib, "libtalloc.so.2").delete()
            sections +=
                fixture.section("proot-env-missing-talloc") {
                    failure("applyEnv", fail { applyEnvironment(runtime, fixture) })
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libprootloader.so")
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(),
                )
            sections +=
                fixture.section("proot-env-missing-proot") {
                    failure("applyEnv", fail { applyEnvironment(runtime, fixture) })
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            fixture.libFile("libtalloc.so.2")
            fixture.libFile("libandroid-shmem.so")
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(),
                )
            sections +=
                fixture.section("proot-env-missing-loader") {
                    failure("applyEnv", fail { applyEnvironment(runtime, fixture) })
                }
        }
        return sections
    }

    private fun RuntimeAssemblyGoldenSupport.Section.applyEnvironment(
        runtime: ContainerRuntime,
        fixture: RuntimeAssemblyGoldenSupport.Fixture,
    ) {
        val builder = ProcessBuilder("fixture")
        // 清空 JVM 继承环境，只冻结运行方式自身写出的键；否则黄金数据会随宿主环境漂移。
        builder.environment().clear()
        runtime.applyEnv(builder, fixture.base, fixture.lib, fixture.tmp)
        envAll(builder.environment())
    }

    private fun prorootEnvironment(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            sections +=
                fixture.section("proroot-env-static") {
                    val builder = ProcessBuilder("fixture")
                    builder.environment().clear()
                    ContainerRuntime.Proroot(null, fixture.native, true)
                        .applyEnv(builder, fixture.base, fixture.lib, fixture.tmp)
                    envAll(builder.environment())
                }
            sections +=
                fixture.section("proroot-env-dynamic") {
                    val builder = ProcessBuilder("fixture")
                    builder.environment().clear()
                    ContainerRuntime.Proroot(null, fixture.native, false)
                        .applyEnv(builder, fixture.base, fixture.lib, fixture.tmp)
                    envAll(builder.environment())
                }
            sections +=
                fixture.section("proroot-prepare-missing-lib") {
                    File(fixture.native, "libproroot-bridge.so").delete()
                    val error = fail {
                        ContainerRuntime.Proroot(null, fixture.native, true).prepare()
                    }
                    meta("prepare.class", error?.javaClass?.simpleName)
                    meta(
                        "namesMissingLib",
                        error?.message?.contains("libproroot-bridge.so") == true,
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            File(fixture.native, "libdsha-proroot-compat.so").delete()
            sections +=
                fixture.section("proroot-env-missing-proxy") {
                    val builder = ProcessBuilder("fixture")
                    failure(
                        "applyEnv",
                        fail {
                            ContainerRuntime.Proroot(null, fixture.native, true)
                                .applyEnv(builder, fixture.base, fixture.lib, fixture.tmp)
                        },
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            RuntimeAssemblyGoldenSupport.installProroot(fixture.native)
            File(fixture.native, "libdsha-proroot-linker.so").delete()
            sections +=
                fixture.section("proroot-env-missing-entry") {
                    val builder = ProcessBuilder("fixture")
                    failure(
                        "applyEnv",
                        fail {
                            ContainerRuntime.Proroot(null, fixture.native, true)
                                .applyEnv(builder, fixture.base, fixture.lib, fixture.tmp)
                        },
                    )
                }
        }
        return sections
    }

    private fun launchSpec(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        sections +=
            fixture.section("launch-spec-state") {
                val spec =
                    LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                        .hardlinks(true)
                        .isolated(true)
                        .coldTrace(true)
                        .pipedInput(true)
                        .dataDomain(File(fixture.root, "domain"))
                        .coldMode(ColdInstallPlan.Mode.PROOT_COMPAT)
                        .bind("/private/payload", "/root/trial")
                        .environment(mapOf("DEEPSEEK_API_KEY" to "private-launch-key"))
                        .build()
                meta("runtime", spec.runtime.id())
                meta("hardlinks", spec.hardlinks)
                meta("isolated", spec.isolated)
                meta("coldTrace", spec.coldTrace)
                meta("pipedInput", spec.pipedInput)
                meta("dataDomain", spec.dataDomain)
                meta("record", spec.record)
                meta("coldMode", spec.coldMode)
                meta("environmentKeys", spec.environment.keys.sorted())
                meta("binds", spec.binds.map { it.host() + ":" + it.guest() })
                meta("toString", spec.toString())
            }
        sections +=
            fixture.section("launch-spec-invalid-runtime-missing") {
                failure("build", fail { LaunchSpec.runtime(null).build() })
            }
        sections +=
            fixture.section("launch-spec-invalid-bind-guest-relative") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .bind("/host", "relative")
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-invalid-bind-dotdot") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .bind("/host", "/root/../escape")
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-invalid-bind-trailing-dotdot") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .bind("/host", "/root/..")
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-invalid-bind-host-nul") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .bind("/host\u0000", "/root/x")
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-invalid-environment-key") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .environment(mapOf("bad-key" to "value"))
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-invalid-environment-value") {
                failure(
                    "build",
                    fail {
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .environment(mapOf("KEY" to "bad\u0000value"))
                            .build()
                    },
                )
            }
        sections +=
            fixture.section("launch-spec-binds-immutable") {
                val spec =
                    LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                        .bind("/private/payload", "/root/trial")
                        .build()
                failure("clear", fail { (spec.binds as MutableList<*>).clear() })
            }
        sections +=
            fixture.section("launch-spec-environment-immutable") {
                val spec =
                    LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                        .environment(mapOf("KEY" to "value"))
                        .build()
                failure("clear", fail { (spec.environment as MutableMap<String, String>).clear() })
            }
        return sections
    }

    private fun launchBuilder(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val launcher =
                RuntimeLauncher(null, fixture.rootfs, fixture.base, fixture.lib, fixture.tmp, ports)
            sections +=
                fixture.section("launch-builder-argv") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .hardlinks(true)
                            .pipedInput(true)
                            .bind("/private/payload one", "/root/trial 'two'")
                            .environment(mapOf("DEEPSEEK_API_KEY" to "secret value with 'quote'"))
                            .build()
                    val builder = launcher.builder("printf '%s' \"a b\"; echo done", spec)
                    argv(builder.command())
                    meta("redirectErrorStream", builder.redirectErrorStream())
                    meta("stdin", RuntimeAssemblyGoldenSupport.stdinRedirect(builder))
                }
            sections +=
                fixture.section("launch-builder-environment") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .hardlinks(true)
                            .pipedInput(true)
                            .coldMode(ColdInstallPlan.Mode.PROOT_COMPAT)
                            .environment(
                                mapOf(
                                    "DEEPSEEK_API_KEY" to "secret value with 'quote'",
                                    "NARB_DISABLE_NATIVE_CACHE" to "0",
                                    "HOME" to "/custom-home",
                                    "PROOT_NO_SECCOMP" to "caller",
                                    "DSHA_MANAGED_PACKAGE_PROOFS_SHA256" to "a".repeat(64),
                                )
                            )
                            .build()
                    envSelected(
                        launcher.builder("true", spec).environment(),
                        setOf(
                            "HOME",
                            "PATH",
                            "TMPDIR",
                            "DEBIAN_FRONTEND",
                            "DEEPSEEK_API_KEY",
                            "NARB_DISABLE_NATIVE_CACHE",
                            "PROOT_NO_SECCOMP",
                            "DSHA_MANAGED_PACKAGE_PROOFS_SHA256",
                        ),
                    )
                }
            sections +=
                fixture.section("launch-builder-proof-absent") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .pipedInput(true)
                            .environment(
                                mapOf("DSHA_MANAGED_PACKAGE_PROOFS_SHA256" to "a".repeat(64))
                            )
                            .build()
                    val environment = launcher.builder("true", spec).environment()
                    meta(
                        "proofPresent",
                        environment.containsKey("DSHA_MANAGED_PACKAGE_PROOFS_SHA256"),
                    )
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            fixture.nativeFile("libproot.so")
            fixture.nativeFile("libprootloader.so")
            fixture.libFile("libtalloc.so.2")
            fixture.libFile("libandroid-shmem.so")
            fixture.rootfsFile("usr/local/share/dsha/net-epoch.cjs")
            fixture.rootfsFile("usr/local/share/dsha/dns-compat.cjs")
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val launcher =
                RuntimeLauncher(null, fixture.rootfs, fixture.base, fixture.lib, fixture.tmp, ports)
            val runtime =
                ContainerRuntime.Proot(
                    null,
                    File(fixture.native, "libproot.so"),
                    RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
                )
            sections +=
                fixture.section("launch-builder-proot-l2s") {
                    val spec =
                        LaunchSpec.runtime(runtime)
                            .hardlinks(false)
                            .pipedInput(true)
                            .environment(mapOf("KEY_A" to "value a"))
                            .build()
                    val builder = launcher.builder("true", spec)
                    argv(RuntimeAssemblyGoldenSupport.withoutSystemBinds(builder.command()))
                    envSelected(
                        builder.environment(),
                        setOf(
                            "HOME",
                            "PATH",
                            "TMPDIR",
                            "DEBIAN_FRONTEND",
                            "PROOT_NO_SECCOMP",
                            "PROOT_LOADER",
                            "PROOT_LOADER_32",
                            "PROOT_TMP_DIR",
                            "PROOT_L2S_DIR",
                            "LD_LIBRARY_PATH",
                            "KEY_A",
                        ),
                    )
                    meta("l2sDirectory", File(fixture.rootfs, ".l2s").isDirectory)
                }
        }
        return sections
    }

    private fun startOrder(): List<RuntimeAssemblyGoldenSupport.Section> {
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val checks = ArrayList<String>()
            val phases = ArrayList<String>()
            val timing = RuntimeCommandTiming { line ->
                phases.add(line.substringAfter("phase=").substringBefore(" "))
            }
            sections +=
                fixture.section("start-order-verify-before-process") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .pipedInput(true)
                            .build()
                    val error = fail {
                        RuntimeLauncher(
                                null,
                                fixture.rootfs,
                                fixture.base,
                                fixture.lib,
                                fixture.tmp,
                                ports,
                            )
                            .start(
                                "true",
                                spec,
                                timing,
                                object : ProotBootstrap.LaunchCheck {
                                    override fun verify() {
                                        checks.add("verify")
                                        throw IOException("GOLDEN_VERIFY")
                                    }
                                },
                            )
                    }
                    failure("start", error)
                    meta("checks", checks)
                    meta("phases", phases)
                }
        }
        run {
            val fixture = RuntimeAssemblyGoldenSupport.Fixture()
            val ports =
                RuntimeAssemblyGoldenSupport.RecordingPorts(RuntimeAssemblyGoldenSupport.settings())
                    .ports
            val checks = ArrayList<String>()
            sections +=
                fixture.section("start-order-without-timing-still-checks") {
                    val spec =
                        LaunchSpec.runtime(RuntimeAssemblyGoldenSupport.FakeRuntime())
                            .pipedInput(true)
                            .build()
                    val error = fail {
                        RuntimeLauncher(
                                null,
                                fixture.rootfs,
                                fixture.base,
                                fixture.lib,
                                fixture.tmp,
                                ports,
                            )
                            .start(
                                "true",
                                spec,
                                null,
                                object : ProotBootstrap.LaunchCheck {
                                    override fun verify() {
                                        checks.add("verify")
                                    }
                                },
                            )
                    }
                    meta("checks", checks)
                    meta("error.class", error?.javaClass?.simpleName)
                }
        }
        return sections
    }

    private fun runtimeMode(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        fixture.nativeFile("libproot.so")
        val prootNoSeccomp =
            ContainerRuntime.Proot(
                null,
                File(fixture.native, "libproot.so"),
                RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = true),
            )
        val prootSeccomp =
            ContainerRuntime.Proot(
                null,
                File(fixture.native, "libproot.so"),
                RuntimeAssemblyGoldenSupport.settings(disableProotSeccomp = false),
            )
        return listOf(
            fixture.section("runtime-mode-keys") {
                meta("prootNoSeccomp.key", RuntimeExecution.modeKey(prootNoSeccomp))
                meta("prootNoSeccomp.seccompOff", RuntimeExecution.seccompOff(prootNoSeccomp))
                meta("prootSeccomp.key", RuntimeExecution.modeKey(prootSeccomp))
                meta("prootSeccomp.seccompOff", RuntimeExecution.seccompOff(prootSeccomp))
                meta(
                    "fake.key",
                    RuntimeExecution.modeKey(RuntimeAssemblyGoldenSupport.FakeRuntime()),
                )
                meta(
                    "fake.seccompOff",
                    RuntimeExecution.seccompOff(RuntimeAssemblyGoldenSupport.FakeRuntime()),
                )
            }
        )
    }

    private fun userDataBindings(): List<RuntimeAssemblyGoldenSupport.Section> {
        val fixture = RuntimeAssemblyGoldenSupport.Fixture()
        val sections = ArrayList<RuntimeAssemblyGoldenSupport.Section>()
        val fs = JvmBackupFileSystem()
        val files = fixture.files
        val rootfs = File(files, "linux/ubuntu").also { it.mkdirs() }

        sections +=
            fixture.section("user-data-bindings-legacy") {
                val argv = ArrayList<String>()
                UserDataBindings.append(argv, rootfs, files, fs)
                argv(argv)
                meta("migrationDirectory", File(files, "rc1-migration-state").isDirectory)
            }
        sections +=
            fixture.section("user-data-bindings-files-not-directory") {
                val other = File(fixture.root, "not-a-directory").also { it.writeText("x") }
                val argv = ArrayList<String>()
                failure("append", fail { UserDataBindings.append(argv, rootfs, other, fs) })
                meta("argv", argv)
            }
        sections +=
            fixture.section("user-data-bindings-rootfs-mismatch") {
                val other = File(fixture.root, "other-rootfs").also { it.mkdirs() }
                val argv = ArrayList<String>()
                failure("append", fail { UserDataBindings.append(argv, other, files, fs) })
                meta("argv", argv)
            }
        run {
            val stateFixture = RuntimeAssemblyGoldenSupport.Fixture()
            val stateFiles = stateFixture.files
            val stateRootfs = File(stateFiles, "linux/ubuntu").also { it.mkdirs() }
            stateFixture.file("data/files/rc1-migration-state", "not-a-directory")
            sections +=
                stateFixture.section("user-data-bindings-migration-state-file") {
                    val argv = ArrayList<String>()
                    failure(
                        "append",
                        fail { UserDataBindings.append(argv, stateRootfs, stateFiles, fs) },
                    )
                    meta("argv", argv)
                }
        }
        run {
            val stableFixture = RuntimeAssemblyGoldenSupport.Fixture()
            val stableFiles = stableFixture.files
            val stableRootfs = File(stableFiles, "linux/ubuntu").also { it.mkdirs() }
            File(stableFiles, UserDataLayout.STABLE).mkdirs()
            File(stableFiles, PrivateRoots.DATA_LOCATION)
                .writeBytes(UserDataLayout.record(UserDataLayout.Home.STABLE))
            sections +=
                stableFixture.section("user-data-bindings-stable") {
                    val argv = ArrayList<String>()
                    UserDataBindings.append(argv, stableRootfs, stableFiles, fs)
                    argv(argv)
                }
        }
        run {
            val missingFixture = RuntimeAssemblyGoldenSupport.Fixture()
            val missingFiles = missingFixture.files
            val missingRootfs = File(missingFiles, "linux/ubuntu").also { it.mkdirs() }
            File(missingFiles, PrivateRoots.DATA_LOCATION)
                .writeBytes(UserDataLayout.record(UserDataLayout.Home.STABLE))
            sections +=
                missingFixture.section("user-data-bindings-stable-root-missing") {
                    val argv = ArrayList<String>()
                    failure(
                        "append",
                        fail { UserDataBindings.append(argv, missingRootfs, missingFiles, fs) },
                    )
                    meta("argv", argv)
                }
        }
        return sections
    }
}
