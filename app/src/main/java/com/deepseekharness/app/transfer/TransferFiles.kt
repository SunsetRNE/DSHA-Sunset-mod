package com.deepseekharness.app.transfer

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.deepseekharness.app.backup.AndroidBackupFileSystem
import com.deepseekharness.app.backup.BackupFileSystem
import java.io.File
import java.io.FileDescriptor
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 传输路径的每个组成部分都在目录描述符下以 NOFOLLOW 打开。 */
internal class TransferFiles private constructor() {
    companion object {
        @JvmField internal val FS: BackupFileSystem = AndroidBackupFileSystem()
        private const val O_PATH = 0x200000
        private const val O_CLOEXEC = 0x80000

        @JvmStatic
        @JvmName("directory")
        @Throws(IOException::class)
        internal fun directory(parent: File, name: String): File {
            val value = FS.child(parent, name)
            val node = FS.stat(value)
            if (node.type == "MISSING") FS.directory(value)
            else if (node.type != "DIRECTORY") throw IOException("FILE_DIRECTORY_UNSAFE")
            return value
        }

        @JvmStatic
        @JvmName("size")
        @Throws(IOException::class)
        internal fun size(file: File): Long {
            val node = FS.stat(file)
            if (node.type == "MISSING") return 0
            if (node.type != "FILE") throw IOException("FILE_PARTIAL_UNSAFE")
            return node.size
        }

        @JvmStatic
        @JvmName("read")
        @Throws(IOException::class)
        internal fun read(file: File): InputStream = FS.read(file, FS.stat(file))

        @JvmStatic
        @JvmName("write")
        @Throws(IOException::class)
        internal fun write(file: File, offset: Long): OutputStream {
            val expected = FS.stat(file)
            if (expected.type != "MISSING" && expected.type != "FILE")
                throw IOException("FILE_PARTIAL_UNSAFE")
            if (offset > 0 && (expected.type != "FILE" || expected.size != offset))
                throw IOException("FILE_PARTIAL_CHANGED")
            val absolute = file.absoluteFile
            val components = absolute.path.substring(1).split("/")
            var parent: FileDescriptor? = null
            var opened: FileDescriptor? = null
            try {
                parent = Os.open("/", O_PATH or O_CLOEXEC, 0)
                for (index in components.indices) {
                    val component = components[index]
                    if (component.isEmpty() || component == "." || component == "..")
                        throw IOException("FILE_PATH_UNSAFE")
                    ParcelFileDescriptor.dup(parent).use { duplicate ->
                        val leaf = index == components.size - 1
                        val flags =
                            if (leaf) {
                                OsConstants.O_WRONLY or
                                    OsConstants.O_NONBLOCK or
                                    O_CLOEXEC or
                                    OsConstants.O_NOFOLLOW or
                                    (if (expected.type == "MISSING")
                                        OsConstants.O_CREAT or OsConstants.O_EXCL
                                    else 0)
                            } else O_PATH or O_CLOEXEC or OsConstants.O_NOFOLLOW
                        opened = Os.open("/proc/self/fd/${duplicate.fd}/$component", flags, 0x180)
                        val stat = Os.fstat(opened)
                        if (leaf) {
                            if (
                                !OsConstants.S_ISREG(stat.st_mode) ||
                                    (expected.type == "FILE" &&
                                        expected.key != "${stat.st_dev}:${stat.st_ino}")
                            )
                                throw IOException("FILE_PARTIAL_CHANGED")
                            if (offset == 0L) Os.ftruncate(opened, 0)
                            else Os.lseek(opened, offset, OsConstants.SEEK_SET)
                        } else if (!OsConstants.S_ISDIR(stat.st_mode))
                            throw IOException("FILE_PARENT_UNSAFE")
                    }
                    Os.close(parent)
                    parent = opened
                    opened = null
                }
                val owner = ParcelFileDescriptor.dup(parent)
                val descriptor = owner.fileDescriptor
                return object :
                    FilterOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(owner)) {
                    @Throws(IOException::class)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        out.write(bytes, offset, count)
                    }

                    @Throws(IOException::class)
                    override fun close() {
                        try {
                            flush()
                            Os.fsync(descriptor)
                        } catch (error: ErrnoException) {
                            throw IOException("FILE_SYNC_FAILED", error)
                        } finally {
                            super.close()
                        }
                    }
                }
            } catch (error: ErrnoException) {
                throw IOException("FILE_PRIVATE_IO", error)
            } finally {
                try {
                    if (opened != null) Os.close(opened)
                } catch (ignored: ErrnoException) {}
                try {
                    if (parent != null) Os.close(parent)
                } catch (ignored: ErrnoException) {}
            }
        }
    }
}
