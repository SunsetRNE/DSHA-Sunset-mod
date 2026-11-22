package com.deepseekharness.app.runtime

import android.content.Context
import com.deepseekharness.app.util.ColdInstallPlan
import com.deepseekharness.app.util.Compat
import java.io.File
import java.io.IOException
import java.util.Objects

/** One argv/env/process construction path; callers retain lifecycle and exit-proof ownership. */
internal class RuntimeLauncher(
    private val context: Context?,
    private val rootfs: File,
    private val base: File,
    private val lib: File,
    private val tmp: File,
    ports: RuntimeHostPorts,
) {
    private val ports: RuntimeHostPorts = Objects.requireNonNull(ports)

    @Throws(IOException::class)
    fun builder(command: String, spec: LaunchSpec): ProcessBuilder =
        ports.open().use {
            try {
                RuntimeTools.prepareResolver(context, rootfs, ports.settings())
            } catch (error: IOException) {
                ports.record("DNS_PREPARE", error.javaClass.simpleName)
            }
            val argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain)
            val apk = if (context == null) null else RuntimeApkAssets.get(context)
            val preloads =
                if (context == null) null else RuntimeLauncherAssets.prepare(context, apk!!)
            bindPreloads(argv, preloads)
            for (bind in spec.binds) {
                argv.add("-b")
                argv.add(bind.host + ":" + bind.guest)
            }
            argv.add("/bin/bash")
            argv.add("-c")
            argv.add(command)
            val builder = ProcessBuilder(argv).redirectErrorStream(true)
            if (!spec.isolated && !spec.pipedInput) Compat.redirectStdinDevNull(builder)
            environment(builder, spec, preloads, apk)
            if (spec.coldTrace)
                ports.record(
                    "EXEC",
                    "runtime=" +
                        spec.runtime.id() +
                        " isolated=" +
                        spec.isolated +
                        " prootNoSeccomp=" +
                        ("1" == builder.environment()["PROOT_NO_SECCOMP"]) +
                        " staticLoader=" +
                        builder.environment().containsKey("PROROOT_STUB_LOADER") +
                        " argv=" +
                        argv.subList(0, argv.size - 3),
                )
            builder
        }

    @Throws(IOException::class)
    fun environment(builder: ProcessBuilder, spec: LaunchSpec) {
        environment(
            builder,
            spec,
            null,
            if (context == null) null else RuntimeApkAssets.get(context),
        )
    }

    @Throws(IOException::class)
    private fun environment(
        builder: ProcessBuilder,
        spec: LaunchSpec,
        preparedPreloads: File?,
        apk: RuntimeApkAssets.Snapshot?,
    ) {
        spec.runtime.applyEnv(builder, base, lib, tmp)
        if ("proot" == spec.runtime.id() && !spec.hardlinks) {
            val l2s = File(rootfs, ".l2s")
            if (!l2s.isDirectory && !l2s.mkdirs())
                throw IOException("PROOT_L2S_DIRECTORY_UNAVAILABLE")
            builder.environment()["PROOT_L2S_DIR"] = l2s.absolutePath
        }
        builder.environment()["HOME"] = "/root"
        builder.environment()["PATH"] =
            "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        builder.environment()["TMPDIR"] = "/tmp"
        builder.environment()["DEBIAN_FRONTEND"] = "noninteractive"
        RuntimeTools.applyEnvironment(rootfs, builder.environment(), ports.settings())
        if (context != null) {
            // builder() already checked the exact overlay it bound. Independent terminal/recovery
            // callers still prepare here; no mutable filesystem proof survives a builder
            // invocation.
            if (preparedPreloads == null) RuntimeLauncherAssets.prepare(context, apk!!)
            val bridge = "--require=" + RuntimeLauncherAssets.GUEST + "/bridge-token-compat.cjs"
            val previous = builder.environment().getOrDefault("NODE_OPTIONS", "")
            if (!previous.split(Regex("\\s+")).contains(bridge))
                builder.environment()["NODE_OPTIONS"] =
                    bridge + (if (previous.isEmpty()) "" else " " + previous)
        }
        spec.coldMode?.let { ColdInstallPlan.applyEnvironment(builder.environment(), it) }
        builder.environment().putAll(spec.environment)
        // Classification evidence comes from this APK, never a guest-writable JSON
        // or caller-supplied environment. Native backup independently reads the APK.
        builder.environment().remove("DSHA_MANAGED_PACKAGE_PROOFS_SHA256")
        if (context != null)
            builder.environment()["DSHA_MANAGED_PACKAGE_PROOFS_SHA256"] = apk!!.packageProofSha256
    }

    @Throws(IOException::class)
    fun start(command: String, spec: LaunchSpec): Process = start(command, spec, null)

    @Throws(IOException::class)
    fun start(command: String, spec: LaunchSpec, timing: RuntimeCommandTiming?): Process =
        start(command, spec, timing, null)

    @Throws(IOException::class)
    fun start(
        command: String,
        spec: LaunchSpec,
        timing: RuntimeCommandTiming?,
        beforeStart: ProotBootstrap.LaunchCheck?,
    ): Process {
        val builder = builder(command, spec)
        timing?.mark("launcher-prepare")
        beforeStart?.verify()
        val process =
            if (spec.isolated) IsolatedInstallProcess.start(builder, tmp, context, spec.record)
            else builder.start()
        timing?.mark("process-create")
        return process
    }

    @Throws(IOException::class)
    fun bindPreloads(argv: MutableList<String>) {
        bindPreloads(argv, if (context == null) null else RuntimeLauncherAssets.prepare(context))
    }

    private fun bindPreloads(argv: MutableList<String>, host: File?) {
        if (host == null) return
        argv.add("-b")
        argv.add(host.absolutePath + ":" + RuntimeLauncherAssets.GUEST)
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun managedPackageProofAssetSha256(context: Context): String =
            RuntimeApkAssets.get(context).packageProofSha256
    }
}
