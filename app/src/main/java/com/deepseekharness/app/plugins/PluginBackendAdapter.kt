package com.deepseekharness.app.plugins

import android.content.Context
import android.os.Looper
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupFileSystem
import com.deepseekharness.app.backup.UserDataLayout
import com.deepseekharness.app.core.MaintenanceCoordinator
import com.deepseekharness.app.plugins.PluginChangeDetector.GuestLayout
import com.deepseekharness.app.plugins.PluginChangeDetector.Kind
import com.deepseekharness.app.plugins.PluginChangeDetector.Scope
import com.deepseekharness.app.plugins.PluginChangeDetector.WatchPath
import com.deepseekharness.app.util.BuiltinPlugins
import com.deepseekharness.app.util.ColdInstallPackages
import com.deepseekharness.app.util.EnvironmentTaskGate
import com.deepseekharness.app.util.GuestPaths
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.function.LongSupplier
import java.util.regex.Pattern

/** 原生只读来源适配；构造不读盘，解析只在后台，不准备环境或启动 Web。 */
class PluginBackendAdapter
private constructor(
    fs: BackupFileSystem,
    frameworkDataDir: File?,
    filesDir: File,
    rootfs: File,
    webGeneration: LongSupplier,
    nativeBackend: Boolean,
) {
    class ResolvedSource
    internal constructor(
        @JvmField val identity: PluginSourceIdentity,
        observedPaths: List<WatchPath>,
    ) {
        @JvmField
        val observedPaths: List<WatchPath> =
            Collections.unmodifiableList(ArrayList<WatchPath>(observedPaths))
    }

    // RuntimeInstallationState/ManagedRuntimeAssets 的安装记录位于 baseDir，即 rootfs 的父目录。
    private val fs: BackupFileSystem = Objects.requireNonNull(fs)
    private val frameworkDataDir: File? = frameworkDataDir
    private val filesDir: File = Objects.requireNonNull(filesDir).absoluteFile
    private val rootfs: File = Objects.requireNonNull(rootfs).absoluteFile
    private val webGeneration: LongSupplier = Objects.requireNonNull(webGeneration)
    private val nativeBackend: Boolean = nativeBackend

    constructor(
        context: Context,
        webGeneration: LongSupplier,
    ) : this(context, GuestPaths.rootfs(context.applicationContext.filesDir), webGeneration)

    /** rootfs 来自实际运行后端的只读路径，不创建或访问 ProotBootstrap。 */
    constructor(
        context: Context,
        rootfs: File,
        webGeneration: LongSupplier,
    ) : this(
        AndroidBackupFileSystem(),
        File(context.applicationContext.applicationInfo.dataDir),
        context.applicationContext.filesDir,
        rootfs,
        webGeneration,
        true,
    )

    /** 可注入已经可信的宿主根；不规范化其内部链接，调用方负责安排后台执行。 */
    constructor(
        fs: BackupFileSystem,
        filesDir: File,
        rootfs: File,
        webGeneration: LongSupplier,
    ) : this(fs, null, filesDir, rootfs, webGeneration, false)

    /** 测试沿生产的 framework authority 绑定链验证实际文件，不运行 Android 门禁。 */
    internal constructor(
        fs: BackupFileSystem,
        frameworkDataDir: File?,
        filesDir: File,
        rootfs: File,
        webGeneration: LongSupplier,
    ) : this(fs, frameworkDataDir, filesDir, rootfs, webGeneration, false)

    /** 小型门禁同样只在后台检查；已有 Web 运行不构成占用。 */
    @Throws(IOException::class)
    fun environmentBlockMessage(): String {
        requireBackground()
        val host = bindHost()
        val message = environmentBlockMessage(if (host == null) filesDir else host.files)
        if (host != null) host.verify(fs)
        return message
    }

    private fun environmentBlockMessage(files: File): String {
        if (!nativeBackend) return ""
        if (MaintenanceCoordinator.pending(files)) return "环境维护尚未完成，请先恢复维护，再刷新或操作插件"
        if (
            MaintenanceCoordinator.isExclusive() && !MaintenanceCoordinator.isOwner() ||
                EnvironmentTaskGate.isBusy() && !EnvironmentTaskGate.ownsCurrentThread()
        )
            return "已有环境任务启动，请稍后刷新或重试插件操作"
        return ""
    }

    @Throws(IOException::class)
    private fun bindHost(): ColdInstallPackages.HostRoot? =
        if (frameworkDataDir == null) null
        else ColdInstallPackages.bindPrivateFiles(fs, frameworkDataDir, filesDir)

    /** 只读取根身份和小型运行时标记正文；其摘要不是包内容校验证明。 两次正文/根/选择记录/Web 代次核对之间发生换代时拒绝结果，不自动重试。 */
    @Throws(IOException::class)
    fun resolveSourceIdentity(): PluginSourceIdentity = resolve(false).identity

    @Throws(IOException::class) fun resolveSource(): ResolvedSource = resolve(true)

    @Throws(IOException::class)
    private fun resolve(withWatches: Boolean): ResolvedSource {
        requireBackground()
        // 只规范化 framework dataDir 的平台别名；可写 files 自身必须经过 NOFOLLOW 与 inode 绑定。
        val host = bindHost()
        val files = if (host == null) filesDir else host.files
        val filesNode = requireDirectory(files)
        val blocked = environmentBlockMessage(files)
        if (blocked.isNotEmpty()) throw IOException(blocked)
        val relativeRoot = relative(filesDir, rootfs)
        val root = File(files, relativeRoot)
        val rootNode = requireDirectory(checkedPath(files, root))
        val generation = webGeneration.getAsLong()
        val markers = markerPaths(files, root)
        val markerDigest = markerDigest(files, markers)
        val layout = UserDataLayout(fs, files)
        val data = layout.current()
        val dataNode = requireDirectory(checkedPath(files, data))
        val watches = if (withWatches) observedPaths(files, root, data, markers) else emptyList()
        if (
            markerDigest != markerDigest(files, markers) ||
                data != layout.current() ||
                !sameIdentity(filesNode, fs.stat(files)) ||
                !sameIdentity(rootNode, fs.stat(root)) ||
                !sameIdentity(dataNode, fs.stat(data)) ||
                generation != webGeneration.getAsLong()
        )
            throw IOException("PLUGIN_SOURCE_CHANGED")
        if (host != null) host.verify(fs)
        return ResolvedSource(
            PluginSourceIdentity(
                data,
                dataNode.key,
                dataNode.device,
                root,
                rootNode.key,
                rootNode.device,
                markerDigest,
                generation,
            ),
            watches,
        )
    }

    @Throws(IOException::class)
    private fun requireBackground() {
        if (nativeBackend) {
            val main = Looper.getMainLooper()
            if (main != null && Looper.myLooper() === main)
                throw IOException("PLUGIN_SOURCE_REQUIRES_BACKGROUND")
        }
        if (Thread.currentThread().isInterrupted) throw IOException("PLUGIN_SOURCE_INTERRUPTED")
    }

    private fun markerPaths(files: File, root: File): List<File> {
        val result = ArrayList<File>()
        result.add(File(files, UserDataLayout.RECORD))
        result.add(File(files, UserDataLayout.RECORD + ".previous"))
        for (name in BASE_MARKERS) result.add(File(root.parentFile, name))
        for (name in ROOT_MARKERS) result.add(File(root, name))
        return result
    }

    @Throws(IOException::class)
    private fun markerDigest(files: File, markers: List<File>): String {
        val digest =
            try {
                MessageDigest.getInstance("SHA-256")
            } catch (unavailable: NoSuchAlgorithmException) {
                throw IOException("SHA256_UNAVAILABLE", unavailable)
            }
        for (marker in markers) {
            requireBackground()
            val node = checkedPath(files, marker)
            val value =
                when {
                    node.type.equals("MISSING") -> ByteArray(0)
                    node.type.equals("FILE") -> fs.small(marker, MARKER_LIMIT)
                    else -> throw IOException("PLUGIN_SOURCE_MARKER_TYPE")
                }
            digestPart(digest, relative(files, marker).toByteArray(StandardCharsets.UTF_8))
            digestPart(digest, node.type.toByteArray(StandardCharsets.US_ASCII))
            digestPart(digest, value)
        }
        val hex = StringBuilder(64)
        for (value in digest.digest()) {
            hex.append(Character.forDigit((value.toInt() and 0xff) ushr 4, 16))
            hex.append(Character.forDigit(value.toInt() and 15, 16))
        }
        return hex.toString()
    }

    private fun digestPart(digest: MessageDigest, value: ByteArray) {
        val length = value.size
        digest.update((length ushr 24).toByte())
        digest.update((length ushr 16).toByte())
        digest.update((length ushr 8).toByte())
        digest.update(length.toByte())
        digest.update(value)
    }

    @Throws(IOException::class)
    private fun observedPaths(
        files: File,
        root: File,
        data: File,
        markers: List<File>,
    ): List<WatchPath> {
        val result = ArrayList<WatchPath>()
        addWatch(result, files, root, Kind.SOURCE_IDENTITY, false)
        addWatch(result, files, data, Kind.SOURCE_IDENTITY, false)
        for (marker in markers) addWatch(result, files, marker, Kind.SOURCE_IDENTITY, false)
        val layout = GuestLayout(root, data, fs)
        for (directory in arrayOf("plugin-src", "node_modules")) {
            result.add(WatchPath(data, File(data, directory), Kind.CONTENT, Scope.PACKAGES, layout))
        }
        for ((directory, scope) in
            arrayOf(
                "profiles" to Scope.PROFILES,
                "plugin-previews" to Scope.PREVIEWS,
                "plugin-history" to Scope.HISTORY,
                "plugin-install-operations" to Scope.JOURNALS,
            )) {
            result.add(WatchPath(data, File(data, directory), Kind.CONTENT, scope, layout))
        }
        for (file in
            arrayOf(
                "plugin-sources.json",
                "plugin-updates.json",
                "plugin-activations.json",
                "plugin-safe-mode.json",
            )) addWatch(result, data, File(data, file), Kind.CONTENT, false)
        for (file in
            arrayOf(
                "package.json",
                "package.json.lock",
                "cordis.patch.yml",
                "compatibility.json",
            )) {
            result.add(WatchPath(data, File(data, "profiles/web/" + file), Kind.CONTENT, false))
        }
        addWatch(
            result,
            files,
            File(data.parentFile, ".dsha-restore-journal.json"),
            Kind.CONTENT,
            false,
        )
        result.add(WatchPath(root, File(root, "root"), Kind.CONTENT, Scope.HOME_PACKAGES, layout))
        for (directory in
            arrayOf(
                "root/node_modules",
                "usr/local/lib/node_modules",
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
            )) {
            result.add(WatchPath(root, File(root, directory), Kind.CONTENT, Scope.PACKAGES, layout))
        }
        result.add(
            WatchPath(
                root,
                File(root, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json"),
                Kind.CONTENT,
                false,
            )
        )
        addWatch(result, root, File(root, "root/dsha-builtin.txt"), Kind.CONTENT, false)
        for (name in BuiltinPlugins.SIGNED_BUILTINS) {
            val guest = BuiltinPlugins.entityDir(name)
            if (!guest.startsWith("/root/")) throw IOException("PLUGIN_BUILTIN_PATH")
            result.add(
                WatchPath(
                    root,
                    File(root, guest.substring(1)),
                    Kind.CONTENT,
                    Scope.SIGNED_BUILTIN,
                    layout,
                )
            )
        }
        addWatch(
            result,
            root,
            File(root, "root/.dsha-web-activity.json"),
            Kind.RUNTIME_PROJECTION,
            false,
        )
        return result
    }

    @Throws(IOException::class)
    private fun addWatch(
        result: MutableList<WatchPath>,
        trustedRoot: File,
        path: File,
        kind: Kind,
        recursive: Boolean,
    ) {
        checkedPath(trustedRoot, path)
        result.add(WatchPath(trustedRoot, path, kind, recursive))
    }

    /** 从可信根逐段 lstat；缺失的父链仅作为将来的观察目标，绝不创建或跟随链接。 */
    @Throws(IOException::class)
    private fun checkedPath(trustedRoot: File, path: File): BackupFileSystem.Node {
        val suffix = relative(trustedRoot, path)
        var node = requireDirectory(trustedRoot)
        if (suffix.isEmpty()) return node
        var at: File = trustedRoot
        val parts = suffix.split("/")
        for (index in parts.indices) {
            at = File(at, parts[index])
            node = fs.stat(at)
            if (node.type.equals("MISSING")) return node
            if (node.type.equals("LINK") || node.type.equals("SPECIAL"))
                throw IOException("PLUGIN_SOURCE_UNSAFE_PATH")
            if (index < parts.size - 1 && !node.type.equals("DIRECTORY"))
                throw IOException("PLUGIN_SOURCE_PARENT_TYPE")
        }
        return node
    }

    @Throws(IOException::class)
    private fun requireDirectory(path: File): BackupFileSystem.Node =
        requireDirectory(fs.stat(path))

    @Throws(IOException::class)
    private fun requireDirectory(node: BackupFileSystem.Node): BackupFileSystem.Node {
        if (!node.type.equals("DIRECTORY") || node.key == null || node.key.isEmpty())
            throw IOException("PLUGIN_SOURCE_ROOT_UNAVAILABLE")
        return node
    }

    private fun sameIdentity(
        before: BackupFileSystem.Node,
        after: BackupFileSystem.Node,
    ): Boolean =
        before.type.equals(after.type) &&
            before.key.equals(after.key) &&
            before.device == after.device

    @Throws(IOException::class)
    private fun relative(root: File, child: File): String {
        val base = root.absolutePath
        val path = child.absolutePath
        if (path == base) return ""
        if (!path.startsWith(base + File.separator)) throw IOException("PLUGIN_SOURCE_OUTSIDE_ROOT")
        val relative = path.substring(base.length + 1).replace(File.separatorChar, '/')
        for (part in PATH_SEPARATOR.split(relative, -1)) if (
            part.isEmpty() || part == "." || part == ".."
        )
            throw IOException("PLUGIN_SOURCE_UNSAFE_PATH")
        return relative
    }

    companion object {
        private val PATH_SEPARATOR = Pattern.compile("/")
        private const val MARKER_LIMIT = 2 * 1024 * 1024
        private val BASE_MARKERS =
            arrayOf(
                ".offline-extracted",
                ".offline-version",
                ".offline-identity",
                ".runtime-descriptor.json",
            )
        private val ROOT_MARKERS =
            arrayOf(
                "usr/local/share/dsha/dsh-runtime.version",
                "usr/local/share/dsha/managed-assets-v2",
                "root/.dsh/script-version",
                "root/.dsh/builtin-plugins.json",
                "root/.dsh/managed-package-proofs.json",
            )
    }
}
