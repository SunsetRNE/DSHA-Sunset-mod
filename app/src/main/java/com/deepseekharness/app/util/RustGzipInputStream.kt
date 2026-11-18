@file:Suppress("NON_FINAL_MEMBER_IN_FINAL_CLASS")

package com.deepseekharness.app.util

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 严格单成员 gzip 解码宿主桥：校验头/尾、拒绝尾随数据，并保留 ABI 的累计上限。
 *
 * 构造会推进头并可能读出第一块明文，因而也可能暴露 body/尾部错误。成功后接管 source， 失败清理的关闭次序及 suppressed 异常与 Java 基线一致；读到 EOF 后仍需
 * close 释放会话。
 */
class RustGzipInputStream @Throws(IOException::class) constructor(source: InputStream?) :
    InputStream() {
    private val source: InputStream
    private val outputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.MAX_OUTPUT_CHUNK)
    private val outputHeap = ByteArray(RustArchiveCodec.MAX_OUTPUT_CHUNK)
    private val inputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK)
    private val inputHeap = ByteArray(RustArchiveCodec.CHUNK)
    private var pendingOffset = 0
    private var pendingLimit = 0
    private var session: Long = 0
    private var outputPosition = 0
    private var outputLimit = 0
    private var lastFlags = 0
    private var eof = false
    private var closed = false

    init {
        if (source == null) throw NullPointerException("source")
        if (!RustNative.ensureLoaded()) {
            closeSourceQuietly(source)
            throw IOException(RustArchiveCodec.unavailableMessage())
        }
        val handle = RustNative.archiveUnpackCreate()
        if (handle <= 0) {
            closeSourceQuietly(source)
            throw IOException(RustArchiveCodec.message(handle.toInt()))
        }
        this.source = source
        session = handle
        try {
            pumpHeader()
        } catch (error: IOException) {
            abortQuietly()
            closeSourceQuietly(source, error)
            throw error
        } catch (error: RuntimeException) {
            abortQuietly()
            closeSourceQuietly(source)
            throw error
        }
    }

    open fun isFinished(): Boolean = eof

    @Throws(IOException::class)
    override fun read(): Int {
        val one = ByteArray(1)
        val read = read(one, 0, 1)
        return if (read < 0) -1 else one[0].toInt() and 0xff
    }

    @Throws(IOException::class)
    override fun read(bytes: ByteArray?, offset: Int, length: Int): Int {
        if (closed) throw IOException("STREAM_CLOSED")
        if (bytes == null) throw NullPointerException("bytes")
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException("offset/length")
        }
        if (length == 0) return 0
        if (eof) return -1
        if (outputPosition < outputLimit) return copyOut(bytes, offset, length)
        var retriedOutput = false
        while (true) {
            if (fillOutput()) return copyOut(bytes, offset, length)
            if ((lastFlags and RustArchiveCodec.FLAG_EOF) != 0) {
                confirmSourceEnd()
                eof = true
                return -1
            }
            if ((lastFlags and RustArchiveCodec.FLAG_NEED_INPUT) != 0) {
                if (feedOnce()) return copyOut(bytes, offset, length)
                continue
            }
            if ((lastFlags and RustArchiveCodec.FLAG_NEED_OUTPUT) != 0) {
                // 固定缓冲已达 ABI 单次上限，异常输出请求最多重试一次，不无限循环。
                if (retriedOutput)
                    throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
                retriedOutput = true
                continue
            }
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
        }
    }

    override fun available(): Int = outputLimit - outputPosition

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        abortQuietly()
        source.close()
    }

    private fun copyOut(bytes: ByteArray, offset: Int, length: Int): Int {
        val available = outputLimit - outputPosition
        val count = minOf(available, length)
        System.arraycopy(outputHeap, outputPosition, bytes, offset, count)
        outputPosition += count
        if (outputPosition == outputLimit) {
            outputPosition = 0
            outputLimit = 0
        }
        return count
    }

    @Throws(IOException::class)
    private fun fillOutput(): Boolean {
        val result = RustNative.archiveUnpackRead(session, outputBuffer)
        if (result < 0) throw IOException(RustArchiveCodec.unpackMessage(result.toInt()))
        val produced = RustArchiveCodec.unpackProduced(result)
        lastFlags = RustArchiveCodec.unpackFlags(result)
        if (produced <= 0) return false
        if (produced > outputHeap.size)
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
        outputBuffer.position(0)
        outputBuffer.limit(produced)
        outputBuffer.get(outputHeap, 0, produced)
        outputPosition = 0
        outputLimit = produced
        return true
    }

    /** native 部分消费时保留剩余压缩字节，不能丢弃或重新读取底层输入。 */
    @Throws(IOException::class)
    private fun feedOnce(): Boolean {
        while (true) {
            if (pendingOffset >= pendingLimit) {
                val read = source.read(inputHeap, 0, inputHeap.size)
                if (read < 0)
                    throw IOException(RustArchiveCodec.message(RustArchiveCodec.GZIP_TRUNCATED))
                pendingOffset = 0
                pendingLimit = read
            }
            val length = minOf(pendingLimit - pendingOffset, RustArchiveCodec.CHUNK)
            inputBuffer.clear()
            inputBuffer.put(inputHeap, pendingOffset, length)
            inputBuffer.flip()
            val result = RustNative.archiveUnpackWrite(session, inputBuffer.slice())
            if (result < 0) throw IOException(RustArchiveCodec.message(result.toInt()))
            val consumed = result.toInt()
            if (consumed > length)
                throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
            pendingOffset += consumed
            if (pendingOffset < pendingLimit) {
                if (fillOutput()) return true
                if (consumed == 0)
                    throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
                continue
            }
            return false
        }
    }

    @Throws(IOException::class)
    private fun pumpHeader() {
        var retriedOutput = false
        while (true) {
            if (fillOutput()) return
            if ((lastFlags and RustArchiveCodec.FLAG_EOF) != 0) {
                confirmSourceEnd()
                eof = true
                return
            }
            if ((lastFlags and RustArchiveCodec.FLAG_NEED_INPUT) != 0) {
                if (feedOnce()) return
                continue
            }
            if ((lastFlags and RustArchiveCodec.FLAG_NEED_OUTPUT) != 0) {
                if (retriedOutput)
                    throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
                retriedOutput = true
                continue
            }
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
        }
    }

    @Throws(IOException::class)
    private fun confirmSourceEnd() {
        // 已预读但未喂入的字节也属于尾随数据；保留短路，不能额外读取 source。
        if (pendingOffset < pendingLimit || source.read() >= 0) {
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.GZIP_TRAILING_DATA))
        }
    }

    private fun abortQuietly() {
        if (session > 0) {
            RustNative.archiveUnpackAbort(session)
            session = 0
        }
    }

    companion object {
        @JvmStatic
        private fun closeSourceQuietly(stream: InputStream) {
            try {
                stream.close()
            } catch (ignored: IOException) {
                // 保留原错误；库不可用/会话创建失败时不增加关闭异常。
            }
        }

        @JvmStatic
        private fun closeSourceQuietly(stream: InputStream, primary: IOException) {
            try {
                stream.close()
            } catch (error: IOException) {
                primary.addSuppressed(error)
            }
        }
    }
}
