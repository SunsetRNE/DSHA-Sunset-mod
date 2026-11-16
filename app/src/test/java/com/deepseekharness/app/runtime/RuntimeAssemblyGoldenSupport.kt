package com.deepseekharness.app.runtime

import com.deepseekharness.app.util.ColdInstallPlan
import java.io.File
import java.nio.file.Files
import java.util.TreeMap

/**
 * 「命令与挂载组装」层黄金结果的夹具与文本格式。
 *
 * <p>黄金数据先在未迁移的 Java 实现上由 `-Ddsha.runtime.golden.dir=<目录>` 生成并提交，迁移后同一测试
 * 用同一份数据断言字节一致。机器相关的临时根路径统一归一为 `$ROOT`，因此同一份数据在 CI 与本机都成立； 与宿主是否存在 `/system`、`/apex` 有关的系统 bind 由
 * `binds()` 用例单独钉住，argv 用例只比较 运行方式自身追加的参数（自镜像、L2S、数据域、preload、命令尾），避免把宿主差异写进运行方式身份。
 */
internal object RuntimeAssemblyGoldenSupport {
    const val PLAIN_RESOURCE = "runtime-assembly-golden.txt"
    const val ANDROID_RESOURCE = "runtime-assembly-android-golden.txt"
    const val ROOT_TOKEN = "\$ROOT"

    class Section internal constructor(val name: String) {
        internal val lines = ArrayList<String>()
        internal val normalizers = ArrayList<Pair<String, String>>()

        fun argv(values: List<String>) {
            values.forEachIndexed { index, value -> lines.add("ARGV $index $value") }
        }

        /** 只渲染组装层自己写出的键；进程继承来的宿主环境不进入冻结数据。 */
        fun envSelected(values: Map<String, String>, keys: Set<String>) {
            for (key in keys.sorted()) {
                lines.add("ENV $key=${values[key] ?: "<absent>"}")
            }
        }

        /** applyEnv 用例先清空继承环境，因此可以完整渲染该运行方式写出的全部键值。 */
        fun envAll(values: Map<String, String>) {
            TreeMap(values).forEach { (key, value) -> lines.add("ENV $key=$value") }
        }

        fun meta(key: String, value: Any?) {
            lines.add("META $key=${value ?: "<null>"}")
        }

        fun binds(values: List<ContainerRuntime.Bind>) {
            values.forEachIndexed { index, bind ->
                lines.add("BINDS $index ${bind.host()}|${bind.guest()}")
            }
        }

        fun failure(label: String, error: Throwable?) {
            if (error == null) {
                meta("$label.error", "<none>")
                return
            }
            meta("$label.class", error.javaClass.simpleName)
            meta("$label.message", error.message)
            meta("$label.cause.class", error.cause?.javaClass?.simpleName)
            meta("$label.cause.message", error.cause?.message)
        }

        fun normalize(path: File, token: String) {
            normalizers.add(path.path to token)
            try {
                val canonical = path.canonicalPath
                if (canonical != path.path) normalizers.add(canonical to token)
            } catch (_: java.io.IOException) {
                // 路径暂不可解析时只归一传入形态，由用例自身保证可解析。
            }
        }

        internal fun fail(block: () -> Unit): Throwable? =
            try {
                block()
                null
            } catch (error: Throwable) {
                error
            }
    }

    /** 每个用例独立的受控临时树；所有派生路径都在 root 内，渲染时归一到 `$ROOT`。 */
    class Fixture {
        val root: File = Files.createTempDirectory("dsha-runtime-golden-").toFile().canonicalFile
        val native: File = dir("native")
        val data: File = dir("data")
        val files: File = dir("data/files")
        val cache: File = dir("cache")
        val linux: File = dir("data/files/linux")
        val base: File = linux
        val rootfs: File = dir("data/files/linux/ubuntu")
        val lib: File = dir("data/files/linux/lib")
        val tmp: File = dir("data/files/linux/tmp")

        init {
            require(files.parentFile == data) { "GOLDEN_DATA_LAYOUT" }
        }

        fun dir(relative: String): File = File(root, relative).also { it.mkdirs() }

        fun file(relative: String, content: String = "fixture\n"): File =
            File(root, relative).also { file ->
                file.parentFile.mkdirs()
                file.writeText(content, Charsets.UTF_8)
            }

        fun nativeFile(name: String, content: String = "fixture\n"): File =
            File(native, name).also { it.writeText(content, Charsets.UTF_8) }

        fun libFile(name: String, content: String = "fixture\n"): File =
            File(lib, name).also { it.writeText(content, Charsets.UTF_8) }

        fun rootfsFile(relative: String, content: String = "fixture\n"): File =
            File(rootfs, relative).also { file ->
                file.parentFile.mkdirs()
                file.writeText(content, Charsets.UTF_8)
            }

        fun section(name: String, block: Section.() -> Unit): Section {
            val section = Section(name)
            section.normalize(root, ROOT_TOKEN)
            section.block()
            return section
        }

        /** 带空格与两种引号的路径；用来证明 argv 元素不会被二次转义或拼接。 */
        fun weirdDirectory(): File = dir("weird dir 'single' \"double\"")

        fun weirdRootfs(): File = File(weirdDirectory(), "linux/ubuntu").also { it.mkdirs() }

        fun weirdFilesDomain(): File = File(weirdDirectory(), "files").also { it.mkdirs() }
    }

    fun render(sections: List<Section>): String {
        val text = StringBuilder("# DSHA runtime assembly golden v1\n")
        for (section in sections) {
            text.append("CASE ").append(section.name).append('\n')
            for (line in section.lines) {
                var rendered = line
                for ((path, token) in section.normalizers.sortedByDescending { it.first.length }) {
                    if (path.isNotEmpty()) rendered = rendered.replace(path, token)
                }
                require(!rendered.contains('\n') && !rendered.contains('\r')) {
                    "GOLDEN_CONTROL_CHARACTER: ${section.name}"
                }
                text.append(rendered).append('\n')
            }
            text.append("END\n")
        }
        return text.toString()
    }

    fun writeOrAssert(resource: String, actual: String) {
        val directory = System.getProperty("dsha.runtime.golden.dir")
        if (directory != null) {
            val target = File(directory)
            target.mkdirs()
            File(target, resource).writeText(actual, Charsets.UTF_8)
            return
        }
        val expected =
            RuntimeAssemblyGoldenSupport::class.java.classLoader.getResourceAsStream(resource)
                ?: throw AssertionError("GOLDEN_RESOURCE_MISSING: $resource")
        val frozen = expected.use { String(it.readBytes(), Charsets.UTF_8) }
        if (frozen != actual) {
            throw AssertionError("GOLDEN_DIFF\n--- frozen\n$frozen--- actual\n$actual")
        }
    }

    fun settings(
        dnsMode: String = "ipv4",
        proroot: Boolean = false,
        staticLoader: Boolean = false,
        disableProotSeccomp: Boolean = false,
    ): RuntimeHostPorts.Settings =
        RuntimeHostPorts.Settings(dnsMode, proroot, staticLoader, disableProotSeccomp)

    /** 捕获 host ports 的写出口；只用于测试，不进入生产。 */
    class RecordingPorts(settings: RuntimeHostPorts.Settings) {
        val records = ArrayList<String>()
        val ports = RuntimeHostPorts()

        init {
            ports.install(
                object : RuntimeHostPorts.Provider {
                    override fun snapshot(): RuntimeHostPorts.Settings = settings

                    override fun stage(value: String) {}

                    override fun record(kind: String, detail: String) {
                        records.add(kind)
                    }

                    override fun failure(error: Throwable) {}
                }
            )
        }
    }

    /** 组装层唯一的假运行方式：只验证 argv/env 顺序与归属，不声明任何真实运行时能力。 */
    class FakeRuntime : ContainerRuntime {
        override fun id(): String = "fixture"

        override fun displayName(): String = "fixture"

        override fun available(): Boolean = true

        override fun unavailableReason(): String = ""

        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): List<String> =
            throw AssertionError("explicit domain is required")

        override fun baseArgv(
            rootfsDir: File,
            hardlinkSupported: Boolean,
            dataDomain: File?,
        ): List<String> = mutableListOf("fixture-runtime", rootfsDir.path)

        override fun applyEnv(pb: ProcessBuilder, baseDir: File, libDir: File, tmpDir: File) {
            pb.environment()["FIXTURE_RUNTIME"] = "1"
        }

        override fun prepare() {}
    }

    /** 系统 bind 是否存在于宿主取决于系统；argv 用例只保留运行方式自身追加的挂载。 */
    private val systemBindTokens: Set<String> by lazy {
        val tokens = HashSet<String>()
        for (bind in ContainerRuntime.binds(false) + ContainerRuntime.binds(true)) {
            tokens.add(bind.host())
            tokens.add(bind.host() + ":" + bind.guest())
        }
        tokens.add("/storage/emulated/0")
        tokens.add("/storage/emulated/0:/sdcard")
        tokens.add("/storage/emulated/0:/storage/emulated/0")
        tokens
    }

    fun withoutSystemBinds(argv: List<String>): List<String> {
        val filtered = ArrayList<String>(argv.size)
        var index = 0
        while (index < argv.size) {
            if (
                argv[index] == "-b" && index + 1 < argv.size && argv[index + 1] in systemBindTokens
            ) {
                index += 2
                continue
            }
            filtered.add(argv[index])
            index++
        }
        return filtered
    }

    fun stdinRedirect(builder: ProcessBuilder): String {
        val redirect = builder.redirectInput()
        if (redirect == ProcessBuilder.Redirect.PIPE) return "pipe"
        val file = redirect.file()
        return if (file != null) "file:" + file.path else redirect.toString()
    }

    fun modeOf(runtime: ContainerRuntime): String = RuntimeExecution.modeKey(runtime)

    fun coldMode(name: String): ColdInstallPlan.Mode = ColdInstallPlan.Mode.valueOf(name)

    /** 五个 proroot 库加两个兼容层文件；只写最小非空字节，用来驱动可用性/环境分支。 */
    fun installProroot(directory: File) {
        for (name in ContainerRuntime.Proroot.LIBS) File(directory, name).writeText("fixture\n")
        File(directory, "libdsha-proroot-compat.so").writeText("fixture\n")
        File(directory, "libdsha-proroot-linker.so").writeText("fixture\n")
    }
}
