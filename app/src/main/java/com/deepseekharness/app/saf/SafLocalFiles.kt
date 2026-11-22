package com.deepseekharness.app.saf

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * 容器内路径的「固定身份」读写。
 *
 * `/app/saf/pull`、`/app/saf/push` 的容器路径先经 canonical 与政策判据，但「判完再开」之间容器里的进程可以把路径或祖先目录换成软链接（TOCTOU）。
 * 这里在打开前后比对 `lstat(path)` 与 `fstat(fd)` 的 dev/ino，并用 `readlink /proc/self/fd/<fd>`
 * 核对内核记录的真实路径：祖先目录被换链时真实路径必然不同，直接拒绝（失败关闭）。
 *
 * 目录操作走逐级 `O_PATH|O_NOFOLLOW` 锚定：从 `/` 开始，每级用 `/proc/self/fd/<父 fd>/<名字>` 打开并核对，最后通过同一个目录 fd
 * 创建/发布文件，不再用可被祖先替换重新解释的路径字符串做 `rename`。
 *
 * 描述符生命周期：`/proc/self/fd/<n>` 里的 `n` 必须是仍然打开的编号。任何从 [FileDescriptor] 取编号的地方都不能「dup 后立刻 close
 * 再返回编号」——[AnchoredDirectory] 因此持有 dup 后的 [ParcelFileDescriptor]，在 [AnchoredDirectory.close] 时关闭；单次
 * readlink 校验则在 handle 仍打开时完成。
 *
 * `O_NONBLOCK` 是必须的：目标可能在 lstat 与 open 之间被换成 FIFO；确认是普通文件后再清掉该标志。还拒绝 `st_nlink > 1` 的硬链接与 proot
 * `--link2symlink` 的 `.l2s` 实体。
 */
class SafLocalFiles private constructor() {
    /** 调用方对打开后真实路径的额外复核（例如重新跑 BridgePathPolicy 的 guest 判据）。 */
    fun interface PathCheck {
        fun denied(realPath: String?): Boolean
    }

    /** 一个已锚定的目录句柄；子项创建/发布/清理都在这个仍打开的 fd 下完成。 */
    class AnchoredDirectory private constructor(handle: ParcelFileDescriptor?, path: String?) :
        AutoCloseable {
        /** 仍打开的目录 fd 编号；只在 [close] 之前有效。 */
        @JvmField val fd: Int

        private val path: String?
        private val handle: ParcelFileDescriptor?

        init {
            this.handle = handle
            this.fd = handle!!.fd
            this.path = path
        }

        /** 目录的规范化路径（打开时经 readlink 核对）。 */
        fun path(): String? = path

        override fun close() {
            try {
                handle!!.close()
            } catch (ignored: IOException) {}
        }

        companion object {
            @JvmSynthetic
            internal fun fromHandle(
                handle: ParcelFileDescriptor?,
                path: String?,
            ): AnchoredDirectory = AnchoredDirectory(handle, path)
        }
    }

    internal fun interface PublishGuard {
        @Throws(IOException::class) fun check()
    }

    /** 目录列举 seam：生产默认读 /proc/self/fd/<fd>，测试可注入受控列表。 */
    internal fun interface DirectoryLister {
        fun list(fd: Int): Array<String?>?
    }

    /** 拥有底层 fd 的输入流：close 一定释放 fd，不依赖 Android FileInputStream 的实现细节。 */
    internal class OwningInputStream(
        private val delegate: FileInputStream?,
        private val fd: FileDescriptor?,
    ) : InputStream() {
        private var closed = false

        @Throws(IOException::class) override fun read(): Int = delegate!!.read()

        @Throws(IOException::class)
        override fun read(buffer: ByteArray?, offset: Int, length: Int): Int =
            delegate!!.read(buffer, offset, length)

        @Throws(IOException::class) override fun available(): Int = delegate!!.available()

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate!!.close()
            } finally {
                closeRaw(fd)
            }
        }
    }

    /** 拥有底层 fd 的输出流：close 先 flush，再关闭 fd。 */
    internal class OwningOutputStream(
        private val delegate: FileOutputStream?,
        private val fd: FileDescriptor?,
    ) : OutputStream() {
        private var closed = false

        @JvmSynthetic internal fun descriptorForPublish(): FileDescriptor? = fd

        @JvmSynthetic internal fun closedForPublish(): Boolean = closed

        @Throws(IOException::class)
        override fun write(value: Int) {
            delegate!!.write(value)
        }

        @Throws(IOException::class)
        override fun write(buffer: ByteArray?, offset: Int, length: Int) {
            delegate!!.write(buffer, offset, length)
        }

        @Throws(IOException::class)
        override fun flush() {
            delegate!!.flush()
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate!!.close()
            } finally {
                closeRaw(fd)
            }
        }
    }

    companion object {
        /** proot --link2symlink 把「硬链接」落成指向 .l2s 目录实体的软链接。 */
        private const val LINK2SYMLINK = ".l2s"

        /** Linux UAPI O_PATH：只持有目录，不要求列目录权限。 */
        private const val O_PATH = 0x200000

        @Volatile
        private var directoryLister: DirectoryLister = DirectoryLister { fd ->
            val names = File("/proc/self/fd/$fd").list()
            names ?: emptyArray()
        }

        /** 只读打开并固定身份；文件必须已存在且是普通文件。返回的流负责关闭底层 fd。 */
        @JvmStatic
        @Throws(IOException::class)
        fun openRead(expected: File?): InputStream = openRead(expected, null)

        /** 只读打开并固定身份；返回的流负责关闭底层 fd。 */
        @JvmStatic
        @Throws(IOException::class)
        fun openRead(expected: File?, check: PathCheck?): InputStream {
            val fd = pin(expected, OsConstants.O_RDONLY, 0, true, check)
            try {
                return OwningInputStream(FileInputStream(fd), fd)
            } catch (error: RuntimeException) {
                closeRaw(fd)
                throw error
            }
        }

        /**
         * 创建写打开并固定身份（0600）；目标可以不存在。
         *
         * 校验之前不带 `O_TRUNC`：若打开的不是预期文件，不能先把原文件截断；身份、真实路径都核对通过后才 `ftruncate(fd, 0)`。
         */
        @JvmStatic
        @Throws(IOException::class)
        fun openWrite(expected: File?): OutputStream {
            val fd = pin(expected, OsConstants.O_WRONLY or OsConstants.O_CREAT, 0x180, false, null)
            try {
                truncateAfterVerify(fd)
                return OwningOutputStream(FileOutputStream(fd), fd)
            } catch (error: IOException) {
                closeRaw(fd)
                throw error
            } catch (error: RuntimeException) {
                closeRaw(fd)
                throw error
            }
        }

        /** 逐级 O_PATH|O_NOFOLLOW 打开目录并核对最终真实路径。 */
        @JvmStatic
        @Throws(IOException::class)
        fun openAnchoredDirectory(directory: File?): AnchoredDirectory =
            openAnchoredDirectory(directory, null)

        /** 逐级锚定打开目录，并对最终真实路径再跑一次调用方判据。 */
        @JvmStatic
        @Throws(IOException::class)
        fun openAnchoredDirectory(directory: File?, check: PathCheck?): AnchoredDirectory {
            val current = walkOpen(directory, false)
            try {
                val realPath = readlinkOf(current)
                val expected = normalized(directory!!)
                if (expected != realPath) throw IOException("PARENT_CHANGED")
                if (check != null && check.denied(realPath)) throw IOException("DENIED")
                val handle = dupOf(current)
                try {
                    return AnchoredDirectory.fromHandle(handle, expected)
                } catch (error: RuntimeException) {
                    closeQuietly(handle)
                    throw error
                }
            } finally {
                closeRaw(current)
            }
        }

        /** 确保目录存在并返回锚定句柄；缺失的每一级都在父目录 fd 下创建，不按路径 mkdir。 */
        @JvmStatic
        @Throws(IOException::class)
        fun ensureAnchoredDirectory(directory: File?, check: PathCheck?): AnchoredDirectory {
            val current = walkOpen(directory, true)
            try {
                val realPath = readlinkOf(current)
                val expected = normalized(directory!!)
                if (expected != realPath) throw IOException("PARENT_CHANGED")
                if (check != null && check.denied(realPath)) throw IOException("DENIED")
                val handle = dupOf(current)
                try {
                    return AnchoredDirectory.fromHandle(handle, expected)
                } catch (error: RuntimeException) {
                    closeQuietly(handle)
                    throw error
                }
            } finally {
                closeRaw(current)
            }
        }

        /** 在已锚定的目录下创建/截断文件并核对真实路径。 */
        @JvmStatic
        @Throws(IOException::class)
        fun createInDirectory(directory: AnchoredDirectory?, name: String?): OutputStream =
            createInDirectory(directory, name, null)

        /** 在已锚定的目录下创建/截断文件，并对真实路径再跑一次调用方判据。 */
        @JvmStatic
        @Throws(IOException::class)
        fun createInDirectory(
            directory: AnchoredDirectory?,
            name: String?,
            check: PathCheck?,
        ): OutputStream {
            if (directory == null || !validName(name)) throw IOException("BAD_PATH")
            val path = "/proc/self/fd/${directory.fd}/$name"
            val fd: FileDescriptor
            try {
                fd =
                    Os.open(
                        path,
                        OsConstants.O_WRONLY or
                            OsConstants.O_CREAT or
                            OsConstants.O_NOFOLLOW or
                            OsConstants.O_NONBLOCK or
                            OsConstants.O_CLOEXEC,
                        0x180,
                    )
            } catch (error: ErrnoException) {
                throw IOException(error)
            }
            try {
                val opened = Os.fstat(fd)
                if (!OsConstants.S_ISREG(opened.st_mode)) throw IOException("NOT_REGULAR")
                if (opened.st_nlink > 1) throw IOException("HARDLINK")
                val realPath = readlinkOf(fd)
                if (directory.path()!!.plus("/").plus(name) != realPath)
                    throw IOException("PATH_CHANGED")
                if (check != null && check.denied(realPath)) throw IOException("DENIED")
                truncateAfterVerify(fd)
                clearNonBlock(fd)
                return OwningOutputStream(FileOutputStream(fd), fd)
            } catch (error: IOException) {
                closeRaw(fd)
                throw error
            } catch (error: ErrnoException) {
                closeRaw(fd)
                throw IOException(error)
            }
        }

        /** 工作台发布前保留校验 fd：拒绝被换名的对象，权限只修改已校验的 inode。 */
        @JvmStatic
        @JvmName("publishInDirectory")
        @Throws(IOException::class)
        internal fun publishInDirectory(
            directory: AnchoredDirectory?,
            tempName: String?,
            targetName: String?,
            output: OutputStream?,
            previousMode: Int,
            beforePublish: PublishGuard?,
        ) {
            if (directory == null || !validName(tempName) || !validName(targetName))
                throw IOException("BAD_PATH")
            if (output !is OwningOutputStream) throw IOException("UNOWNED_OUTPUT")
            val owned = output
            if (owned.closedForPublish()) throw IOException("CLOSED_OUTPUT")
            output.flush()
            var verification: FileDescriptor? = null
            try {
                val original = Os.fstat(owned.descriptorForPublish())
                verification =
                    Os.open(
                        "/proc/self/fd/${directory.fd}/$tempName",
                        OsConstants.O_RDONLY or
                            OsConstants.O_NOFOLLOW or
                            OsConstants.O_NONBLOCK or
                            OsConstants.O_CLOEXEC,
                        0,
                    )
                verifyPendingFile(directory, tempName, verification, original)
                // 先收完可能失败的输出 close，再发布；另一个受控 fd 保持原 inode 可校验。
                output.close()
                if (previousMode >= 0) {
                    try {
                        Os.fchmod(verification, previousMode and 0x1ff)
                    } catch (unavailable: ErrnoException) {
                        // 权限复制失败保持创建时的 0600，不按可替换的临时名字重试。
                    }
                }
                // close/权限复制也可能耗时；最终发布前仍要核对授权、代次、取消与总截止。
                if (beforePublish == null) throw IOException("PUBLISH_GUARD_MISSING")
                beforePublish.check()
                verifyPendingFile(directory, tempName, verification, original)
                publishInDirectory(directory, tempName, targetName)
            } catch (missingOrChanged: ErrnoException) {
                throw IOException("PATH_CHANGED", missingOrChanged)
            } finally {
                closeRaw(verification)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        private fun verifyPendingFile(
            directory: AnchoredDirectory?,
            name: String?,
            fd: FileDescriptor?,
            original: StructStat,
        ) {
            if (directory == null || !validName(name)) throw IOException("BAD_PATH")
            try {
                val opened = Os.fstat(fd)
                val named = Os.lstat("/proc/self/fd/${directory.fd}/$name")
                if (
                    !OsConstants.S_ISREG(opened.st_mode) ||
                        !OsConstants.S_ISREG(named.st_mode) ||
                        opened.st_nlink != 1L ||
                        named.st_nlink != 1L ||
                        opened.st_dev != original.st_dev ||
                        opened.st_ino != original.st_ino ||
                        opened.st_dev != named.st_dev ||
                        opened.st_ino != named.st_ino ||
                        directory.path()!!.plus("/").plus(name) != readlinkOf(fd)
                )
                    throw IOException("PATH_CHANGED")
            } catch (missingOrChanged: ErrnoException) {
                throw IOException("PATH_CHANGED", missingOrChanged)
            }
        }

        /** 在同一个目录 fd 内原子发布；祖先目录即使被换链也不会改变 rename 的真实目标。 */
        @JvmStatic
        @Throws(IOException::class)
        fun publishInDirectory(
            directory: AnchoredDirectory?,
            tempName: String?,
            targetName: String?,
        ) {
            if (directory == null || !validName(tempName) || !validName(targetName))
                throw IOException("BAD_PATH")
            val base = "/proc/self/fd/${directory.fd}/"
            try {
                Os.rename(base + tempName, base + targetName)
            } catch (error: ErrnoException) {
                throw IOException("PUBLISH_FAILED", error)
            }
        }

        /** 在已锚定目录内删除一个名字（用于临时文件清理）。 */
        @JvmStatic
        @Throws(IOException::class)
        fun deleteInDirectory(directory: AnchoredDirectory?, name: String?) {
            if (directory == null || !validName(name)) throw IOException("BAD_PATH")
            try {
                Os.remove("/proc/self/fd/${directory.fd}/$name")
            } catch (missing: ErrnoException) {
                if (missing.errno != OsConstants.ENOENT) throw IOException(missing)
            }
        }

        @JvmStatic
        @JvmName("setDirectoryListerForTests")
        internal fun setDirectoryListerForTests(lister: DirectoryLister?) {
            directoryLister = lister ?: DirectoryLister { emptyArray() }
        }

        /** 已锚定目录里的文件名列表；只用于清理本端点自己的临时文件。 */
        @JvmStatic
        fun listInDirectory(directory: AnchoredDirectory?): Array<String?>? {
            if (directory == null) return emptyArray()
            return directoryLister.list(directory.fd)
        }

        /** 路径里是否有 proot --link2symlink 的内部实体目录；命中时一律不读写。 */
        @JvmStatic
        fun link2SymlinkPath(path: String?): Boolean {
            if (path.isNullOrEmpty()) return false
            for (segment in path.split("/")) if (LINK2SYMLINK == segment) return true
            return false
        }

        /** 尽力删除半截目标文件；失败不掩盖原始错误。 */
        @JvmStatic
        fun deleteQuietly(target: File?) {
            if (target == null) return
            try {
                Files.deleteIfExists(target.toPath())
            } catch (ignored: Throwable) {}
        }

        @JvmStatic
        @Throws(IOException::class)
        private fun pin(
            expected: File?,
            flags: Int,
            mode: Int,
            mustExist: Boolean,
            check: PathCheck?,
        ): FileDescriptor {
            if (expected == null) throw IOException("NO_PATH")
            val path = expected.path
            if (link2SymlinkPath(path)) throw IOException("LINK2SYMLINK")
            val before = lstatOrNull(path)
            if (mustExist && before == null) throw IOException("NOT_FOUND")
            if (before != null && !OsConstants.S_ISREG(before.st_mode))
                throw IOException("NOT_REGULAR")
            val fd: FileDescriptor
            try {
                fd =
                    Os.open(
                        path,
                        flags or
                            OsConstants.O_NOFOLLOW or
                            OsConstants.O_NONBLOCK or
                            OsConstants.O_CLOEXEC,
                        mode,
                    )
            } catch (error: ErrnoException) {
                throw IOException(error)
            }
            try {
                val opened = Os.fstat(fd)
                if (!OsConstants.S_ISREG(opened.st_mode)) throw IOException("NOT_REGULAR")
                if (opened.st_nlink > 1) throw IOException("HARDLINK")
                val after = lstatOrNull(path) ?: throw IOException("PATH_CHANGED")
                val pathStable =
                    before != null && before.st_dev == after.st_dev && before.st_ino == after.st_ino
                val openedMatchesPath =
                    opened.st_dev == after.st_dev && opened.st_ino == after.st_ino
                if (!mustExist && before == null && openedMatchesPath) {
                    verifyOpenedPath(fd, path, check)
                    clearNonBlock(fd)
                    return fd // 新创建的文件
                }
                if (!pathStable || !openedMatchesPath) throw IOException("PATH_CHANGED")
                if (before!!.st_nlink > 1) throw IOException("HARDLINK")
                verifyOpenedPath(fd, path, check)
                clearNonBlock(fd)
                return fd
            } catch (error: IOException) {
                closeRaw(fd)
                throw error
            } catch (error: ErrnoException) {
                closeRaw(fd)
                throw IOException(error)
            }
        }

        /** 从 `/` 开始逐级打开；`create` 时缺失层在父目录 fd 下 mkdir。 */
        @JvmStatic
        @Throws(IOException::class)
        private fun walkOpen(directory: File?, create: Boolean): FileDescriptor {
            if (directory == null) throw IOException("NO_PATH")
            val path = directory.path
            if (!path.startsWith("/") || path.contains("/../") || path == "/..")
                throw IOException("BAD_PATH")
            if (link2SymlinkPath(path)) throw IOException("LINK2SYMLINK")
            val normalized = normalized(directory)
            var current: FileDescriptor
            try {
                current = Os.open("/", O_PATH or OsConstants.O_CLOEXEC, 0)
            } catch (error: ErrnoException) {
                throw IOException("PARENT_UNVERIFIED", error)
            }
            if (normalized == "/") return current
            try {
                for (segment in
                    java.util.regex.Pattern.compile("/").split(normalized.substring(1))) {
                    if (!validName(segment)) throw IOException("BAD_PATH")
                    val next =
                        try {
                            openChild(current, segment)
                        } catch (missing: ErrnoException) {
                            if (!create || missing.errno != OsConstants.ENOENT) throw missing
                            val mkdirParent = dupOf(current)
                            try {
                                Os.mkdir("/proc/self/fd/${mkdirParent.fd}/$segment", 0x1c0)
                            } catch (mkdirError: ErrnoException) {
                                if (mkdirError.errno != OsConstants.EEXIST) throw mkdirError
                            } finally {
                                closeQuietly(mkdirParent)
                            }
                            openChild(current, segment)
                        } catch (error: IOException) {
                            throw error
                        }
                    val isDirectory: Boolean
                    try {
                        isDirectory = OsConstants.S_ISDIR(Os.fstat(next).st_mode)
                    } catch (error: ErrnoException) {
                        closeRaw(next)
                        throw IOException("PARENT_UNVERIFIED", error)
                    }
                    if (!isDirectory) {
                        closeRaw(next)
                        throw IOException("PARENT_NOT_DIRECTORY")
                    }
                    closeRaw(current)
                    current = next
                }
                return current
            } catch (error: ErrnoException) {
                closeRaw(current)
                throw IOException("PARENT_CHANGED", error)
            } catch (error: IOException) {
                closeRaw(current)
                throw error
            }
        }

        /** 用仍打开的父目录 fd（dup 后保持到 open 完成）打开子目录，O_NOFOLLOW 拒绝符号链接。 */
        @JvmStatic
        @Throws(ErrnoException::class, IOException::class)
        private fun openChild(parent: FileDescriptor?, name: String): FileDescriptor {
            val handle = dupOf(parent)
            try {
                return Os.open(
                    "/proc/self/fd/${handle.fd}/$name",
                    O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                    0,
                )
            } finally {
                closeQuietly(handle)
            }
        }

        /**
         * 用仍打开的 dup 句柄反查真实路径，封住「祖先目录在判据与 open 之间被换成符号链接」的 TOCTOU。 handle 一直保持打开到 readlink
         * 返回，避免返回已关闭的 fd 编号。
         */
        @JvmStatic
        @Throws(IOException::class)
        private fun verifyOpenedPath(fd: FileDescriptor?, expected: String, check: PathCheck?) {
            val opened = readlinkOf(fd)
            if (opened == null || opened != expected) throw IOException("PATH_CHANGED")
            if (check != null && check.denied(opened)) throw IOException("DENIED")
        }

        @JvmStatic
        @Throws(IOException::class)
        private fun readlinkOf(fd: FileDescriptor?): String? {
            val handle = dupOf(fd)
            try {
                return Os.readlink("/proc/self/fd/${handle.fd}")
            } catch (error: ErrnoException) {
                throw IOException("PATH_UNVERIFIED", error)
            } finally {
                closeQuietly(handle)
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        private fun dupOf(fd: FileDescriptor?): ParcelFileDescriptor {
            try {
                return ParcelFileDescriptor.dup(fd)
            } catch (error: IOException) {
                throw IOException("PATH_UNVERIFIED", error)
            }
        }

        /** 校验通过后才允许截断；openWrite/createInDirectory 共用，失败时由调用方关闭 fd。 */
        @JvmStatic
        @Throws(IOException::class)
        private fun truncateAfterVerify(fd: FileDescriptor?) {
            try {
                Os.ftruncate(fd, 0)
            } catch (error: ErrnoException) {
                throw IOException("TRUNCATE_FAILED", error)
            }
        }

        /** 类型与身份核对通过后清掉 O_NONBLOCK，恢复普通文件的阻塞读写语义。 */
        @JvmStatic
        @Throws(IOException::class)
        private fun clearNonBlock(fd: FileDescriptor?) {
            try {
                val flags = Os.fcntlInt(fd!!, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
            } catch (error: ErrnoException) {
                throw IOException("NONBLOCK_CLEAR", error)
            }
        }

        @JvmStatic
        private fun lstatOrNull(path: String): StructStat? {
            try {
                return Os.lstat(path)
            } catch (missing: ErrnoException) {
                return null
            }
        }

        @JvmStatic
        private fun closeRaw(fd: FileDescriptor?) {
            if (fd == null) return
            try {
                Os.close(fd)
            } catch (ignored: ErrnoException) {}
        }

        @JvmStatic
        private fun closeQuietly(value: AutoCloseable?) {
            if (value == null) return
            try {
                value.close()
            } catch (ignored: Exception) {}
        }

        @JvmStatic
        private fun normalized(directory: File): String {
            val path = directory.path
            if (path == "/") return "/"
            return if (path.endsWith("/")) path.substring(0, path.length - 1) else path
        }

        @JvmStatic
        private fun validName(name: String?): Boolean =
            name != null &&
                name.isNotEmpty() &&
                name != "." &&
                name != ".." &&
                name.indexOf('/') < 0 &&
                name.indexOf('\u0000') < 0
    }
}
