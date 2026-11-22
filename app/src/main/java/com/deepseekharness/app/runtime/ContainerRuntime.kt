package com.deepseekharness.app.runtime

import android.content.Context
import com.deepseekharness.app.StorageAccess
import com.deepseekharness.app.util.UiText
import java.io.File
import java.io.IOException

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」独立出来，让 proot 与 proroot 并存、可切换。
 *
 * <p>两条实现：
 * - [Proot] —— Termux 的 proot（APK 内置 libproot.so），默认稳定路径；
 * - [Proroot] —— coderredlab/proroot，LD_PRELOAD + 二进制补丁，零 ptrace 开销。
 *
 * <p>proroot 的 argv 里**没有** `--kill-on-exit`：`Process.destroy()` 只杀启动器， 容器里的 node 会变孤儿继续跑 ——
 * 所以「停止」必须按 pid 精确杀（见 `WebProcSel`）， 不能指望杀启动器传播信号。
 */
interface ContainerRuntime {

    fun id(): String

    fun displayName(): String

    fun available(): Boolean

    fun unavailableReason(): String

    /** 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。 */
    fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): MutableList<String>

    /**
     * Null dataDomain is an explicit independent rescue domain, never inferred from rootfs parents.
     */
    fun baseArgv(
        rootfsDir: File,
        hardlinkSupported: Boolean,
        dataDomain: File?,
    ): MutableList<String>

    /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
    @Throws(IOException::class)
    fun applyEnv(pb: ProcessBuilder, baseDir: File, libDir: File, tmpDir: File)

    /** 首次使用前的准备。抛异常表示失败，调用方应回退。 */
    @Throws(Exception::class) fun prepare()

    // ==================================================================

    /** Termux proot，APK 内置。 */
    class Proot
    @JvmOverloads
    constructor(
        private val context: Context?,
        private val nativeLibProot: File,
        private val explicitSettings: RuntimeHostPorts.Settings? = null,
    ) : ContainerRuntime {

        override fun id(): String = "proot"

        override fun displayName(): String = UiText.text("proot（内置，稳定）")

        override fun available(): Boolean = nativeLibProot.isFile && nativeLibProot.length() > 0

        override fun unavailableReason(): String =
            if (available()) "" else UiText.text("APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）")

        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): MutableList<String> {
            if (context == null) throw IllegalStateException("DATA_DOMAIN_CONTEXT_MISSING")
            return baseArgv(rootfsDir, hardlinkSupported, context.filesDir)
        }

        override fun baseArgv(
            rootfsDir: File,
            hardlinkSupported: Boolean,
            dataDomain: File?,
        ): MutableList<String> {
            val argv = ArrayList<String>()
            argv.add(nativeLibProot.absolutePath)
            // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）。
            // Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
            if (!hardlinkSupported) {
                argv.add("--link2symlink")
                // L2S 链保存宿主绝对路径，容器必须能按同一路径访问，否则 dpkg 的 chown/stat 报 ENOENT。
                val l2s = File(rootfsDir, ".l2s")
                l2s.mkdirs()
                argv.add("-b")
                argv.add(l2s.absolutePath + ":" + l2s.absolutePath)
            }
            argv.add("-L")
            argv.add("--kill-on-exit")
            argv.add("-0")
            argv.add("--rootfs=" + rootfsDir.absolutePath)
            argv.add("--cwd=/root")
            // 运行根自镜像：guest 内按 /proc/self/maps 回读自身文件依赖它，见 rootfsSelfMirror()。
            argv.add("-b")
            argv.add(rootfsSelfMirror(rootfsDir))
            for (bind in binds(StorageAccess.granted(context))) {
                if (!File(bind.host).exists()) {
                    if (context != null)
                        RuntimeHostPorts.fromOwner(context.applicationContext)
                            .record("BIND_SKIPPED", bind.host + ": missing or inaccessible")
                    continue
                }
                argv.add("-b")
                argv.add(if (bind.host == bind.guest) bind.host else bind.host + ":" + bind.guest)
            }
            if (dataDomain != null) UserDataBindings.append(argv, rootfsDir, dataDomain)
            return argv
        }

        /**
         * 运行根自我镜像绑定：guest 内按 `/proc/self/maps` 记录的宿主绝对路径回读自身文件。
         *
         * <p>proot 用宿主路径 exec/mmap，maps 里是内核解析后的 canonical 路径；而运行根通常以 `/data/user/0` 别名传入，maps 却是
         * `/data/data`。不绑定的话，guest 的 open 会被翻译成 `<rootfs>/data/data/...`（通常不存在或只有骨架目录）并报 ENOENT。 历史
         * maintenance proot 也在 [RecoveryToolchain] 里绑定同类自镜像。
         */
        private fun rootfsSelfMirror(rootfsDir: File): String {
            var canonical = rootfsDir
            try {
                canonical = rootfsDir.canonicalFile
            } catch (_: IOException) {
                // 路径暂不可解析时退回传入路径，不阻断启动。
            }
            return canonical.absolutePath + ":" + canonical.absolutePath
        }

        @Throws(IOException::class)
        override fun applyEnv(pb: ProcessBuilder, baseDir: File, libDir: File, tmpDir: File) {
            prepare()
            val directory = nativeLibProot.parentFile
            val loader = "libprootloader.so"
            if (
                !File(libDir, "libtalloc.so.2").isFile ||
                    !File(libDir, "libandroid-shmem.so").isFile
            )
                throw IOException("PROOT_DEPENDENCIES_MISSING")
            if (seccompDisabled()) pb.environment()["PROOT_NO_SECCOMP"] = "1"
            else pb.environment().remove("PROOT_NO_SECCOMP")
            pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
            pb.environment()["PROOT_LOADER"] = File(directory, loader).absolutePath
            val loader32 = File(directory, "libprootloader32.so")
            if (loader32.isFile) pb.environment()["PROOT_LOADER_32"] = loader32.absolutePath
            else pb.environment().remove("PROOT_LOADER_32")
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath + ":" + directory.absolutePath
        }

        /** 与 applyEnv 同一来源：本次启动是否带 PROOT_NO_SECCOMP（无 seccomp 过滤的兼容档）。 */
        fun seccompDisabled(): Boolean {
            val settings =
                explicitSettings
                    ?: RuntimeHostPorts.fromOwner(context!!.applicationContext).settings()
            return settings.disableProotSeccomp
        }

        @Throws(IOException::class)
        override fun prepare() {
            if (!available()) throw IOException("PROOT_EXECUTABLE_MISSING")
            val loader = "libprootloader.so"
            if (!File(nativeLibProot.parentFile, loader).isFile)
                throw IOException("PROOT_LOADER_MISSING")
        }
    }

    /** coderredlab/proroot，LD_PRELOAD 路径翻译，零 ptrace。 */
    class Proroot
    @JvmOverloads
    constructor(
        private val ctx: Context?,
        private val dir: File,
        private val staticLoader: Boolean =
            RuntimeHostPorts.fromOwner(ctx!!.applicationContext).settings().staticLoader,
    ) : ContainerRuntime {

        companion object {
            private const val COMPAT_PROXY = "libdsha-proroot-compat.so"
            private const val COMPAT_ENTRY = "libdsha-proroot-linker.so"

            /** 五个 .so 都得在同一目录，启动器靠 /proc/self/exe 的 dirname 找同伴。 */
            @JvmField
            val LIBS =
                arrayOf(
                    "libproroot.so",
                    "libproroot-runtime.so",
                    "libproroot-linker.so",
                    "libproroot-stub-loader.so",
                    "libproroot-bridge.so",
                )

            /**
             * 存放目录：APK 的 jniLibs 提取目录（nativeLibraryDir）。
             *
             * <p>**不能放 filesDir**：Android 10+ 的 W^X 策略不允许从应用可写目录执行代码。
             */
            @JvmStatic
            fun defaultDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)
        }

        override fun id(): String = "proroot"

        override fun displayName(): String = UiText.text("proroot（实验，零 ptrace 开销）")

        override fun available(): Boolean {
            for (name in LIBS) {
                val file = File(dir, name)
                if (!file.isFile || file.length() == 0L) return false
            }
            val proxy = File(dir, COMPAT_PROXY)
            val entry = File(dir, COMPAT_ENTRY)
            return proxy.isFile && proxy.length() > 0 && entry.isFile && entry.length() > 0
        }

        override fun unavailableReason(): String {
            val missing = ArrayList<String>()
            for (name in LIBS) {
                val file = File(dir, name)
                if (!file.isFile || file.length() == 0L) missing.add(name)
            }
            val proxy = File(dir, COMPAT_PROXY)
            if (!proxy.isFile || proxy.length() == 0L) missing.add(COMPAT_PROXY)
            val entry = File(dir, COMPAT_ENTRY)
            if (!entry.isFile || entry.length() == 0L) missing.add(COMPAT_ENTRY)
            if (missing.isEmpty()) return ""
            return UiText.format("缺 %s 个运行时文件（%s 等）", missing.size, missing[0])
        }

        override fun baseArgv(rootfsDir: File, hardlinkSupported: Boolean): MutableList<String> =
            baseArgv(rootfsDir, hardlinkSupported, ctx!!.filesDir)

        override fun baseArgv(
            rootfsDir: File,
            hardlinkSupported: Boolean,
            dataDomain: File?,
        ): MutableList<String> {
            val argv = ArrayList<String>()
            argv.add(File(dir, "libproroot.so").absolutePath)
            argv.add(if (staticLoader) "--static-loader" else "--no-static-loader")
            argv.add("-r")
            argv.add(rootfsDir.absolutePath)
            argv.add("-0")
            argv.add("-w")
            argv.add("/root")
            // 运行根自镜像不在这里重复绑定：当前 v1.2.8 已把 -r 解析后的 canonical rootfs 绑进 guest
            // （见 util/GhHostRoot 与 build343 真机实测）；重复 -b 的 vendor 行为未验证，升级 proroot 时复核。
            for (bind in binds(StorageAccess.granted(ctx))) {
                if (!File(bind.host).exists()) {
                    RuntimeHostPorts.fromOwner(ctx!!.applicationContext)
                        .record("BIND_SKIPPED", bind.host + ": missing or inaccessible")
                    continue
                }
                argv.add("-b")
                argv.add(bind.host + ":" + bind.guest)
            }
            val shm = shmDir()
            shm.mkdirs()
            argv.add("-b")
            argv.add(shm.absolutePath + ":/dev/shm")
            argv.add("--link2symlink")
            if (dataDomain != null) UserDataBindings.append(argv, rootfsDir, dataDomain)
            return argv
        }

        fun shmDir(): File = File(ctx!!.cacheDir, "shm")

        @Throws(IOException::class)
        override fun applyEnv(pb: ProcessBuilder, baseDir: File, libDir: File, tmpDir: File) {
            val proxy = File(dir, COMPAT_PROXY)
            if (!proxy.isFile || proxy.length() == 0L)
                throw IOException("PROROOT_COMPAT_PROXY_MISSING")
            val entry = File(dir, COMPAT_ENTRY)
            if (!entry.isFile || entry.length() == 0L)
                throw IOException("PROROOT_COMPAT_ENTRY_MISSING")
            pb.environment()["PROROOT_TMP_DIR"] = tmpDir.absolutePath
            // Keep the original runtime a direct preload so its path hooks stay in lookup scope.
            pb.environment()["PROROOT_LIB_PATH"] = File(dir, "libproroot-runtime.so").absolutePath
            pb.environment()["PROROOT_LINKER_PATH"] = entry.absolutePath
            if (staticLoader)
                pb.environment()["PROROOT_STUB_LOADER"] =
                    File(dir, "libproroot-stub-loader.so").absolutePath
            else pb.environment().remove("PROROOT_STUB_LOADER")
        }

        @Throws(Exception::class)
        override fun prepare() {
            for (name in LIBS) {
                if (!File(dir, name).isFile)
                    throw IllegalStateException(UiText.format("proroot 运行时缺 %s", name))
            }
            val proxy = File(dir, COMPAT_PROXY)
            if (!proxy.isFile || proxy.length() == 0L)
                throw IOException("PROROOT_COMPAT_PROXY_MISSING")
            val entry = File(dir, COMPAT_ENTRY)
            if (!entry.isFile || entry.length() == 0L)
                throw IOException("PROROOT_COMPAT_ENTRY_MISSING")
            shmDir().mkdirs()
        }
    }

    /**
     * 两个运行时共用的 bind 列表，写在一处（避免「改了 proot 忘了改 proroot」）。 Bundled Termux Python 要用 Android 的 linker 和
     * APEX 库，所以映射 /system、/apex。
     *
     * <p>注意：**不**映射 /linkerconfig —— app 进程对它无权限（selinux）， proot 每次启动都会打 `can't sanitize binding
     * "/linkerconfig": Permission denied`， 污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。
     */
    @JvmRecord data class Bind(val host: String, val guest: String)

    companion object {
        /** 容器挂载清单。公共存储（`/storage/emulated/0`）只在用户已授权文件访问时挂载； 未授权时容器只能读写自己的运行根与私有目录，不能读手机文件。 */
        @JvmStatic
        fun binds(includePublicStorage: Boolean): List<Bind> {
            val list = ArrayList<Bind>()
            list.add(Bind("/dev", "/dev"))
            list.add(Bind("/dev/urandom", "/dev/random"))
            list.add(Bind("/proc", "/proc"))
            list.add(Bind("/sys", "/sys"))
            list.add(Bind("/system", "/system"))
            list.add(Bind("/apex", "/apex"))
            list.add(Bind("/proc/self/fd", "/dev/fd"))
            if (includePublicStorage) {
                list.add(Bind("/storage/emulated/0", "/sdcard"))
                list.add(Bind("/storage/emulated/0", "/storage/emulated/0"))
            }
            return java.util.List.copyOf(list)
        }
    }
}
