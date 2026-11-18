@file:Suppress("NON_FINAL_MEMBER_IN_FINAL_CLASS")

package com.deepseekharness.app.util

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 单成员 gzip 编码宿主桥。会话有界，要求显式 close；失败后不回退或续写原输出。
 *
 * finish 成功消费句柄后，先落本地 finished/session，再写尾部；目标写失败也不得重放。 Rust 核心算法、ABI 错误码、direct buffer
 * 的容量/切片契约保持不变。
 */
class RustGzipOutputStream
@Throws(IOException::class)
constructor(target: OutputStream?, level: Int) : OutputStream() {
    private val target: OutputStream
    private val inputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.CHUNK)
    private val outputBuffer = ByteBuffer.allocateDirect(RustArchiveCodec.MAX_PACK_OUTPUT)
    private val outputHeap = ByteArray(RustArchiveCodec.MAX_PACK_OUTPUT)
    private val finishHeap = ByteArray(RustArchiveCodec.FINISH_BOUND)
    private var session: Long = 0
    private var finished = false
    private var closed = false

    @Throws(IOException::class)
    constructor(target: OutputStream?) : this(target, RustArchiveCodec.DEFAULT_LEVEL)

    init {
        if (target == null) throw NullPointerException("target")
        if (!RustArchiveCodec.levelValid(level)) {
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INVALID_ARGUMENT))
        }
        if (!RustNative.ensureLoaded()) throw IOException(RustArchiveCodec.unavailableMessage())
        val handle = RustNative.archivePackCreate(level)
        if (handle <= 0) throw IOException(RustArchiveCodec.message(handle.toInt()))
        this.target = target
        session = handle
    }

    open fun isFinished(): Boolean = finished

    @Throws(IOException::class)
    override fun write(value: Int) {
        val one = byteArrayOf(value.toByte())
        write(one, 0, 1)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray?, offset: Int, length: Int) {
        if (closed || finished) throw IOException("STREAM_CLOSED")
        if (bytes == null) throw NullPointerException("bytes")
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException("offset/length")
        }
        if (length == 0) return
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val chunkLength = minOf(remaining, RustArchiveCodec.CHUNK)
            inputBuffer.clear()
            inputBuffer.put(bytes, position, chunkLength)
            inputBuffer.flip()
            val view = inputBuffer.slice()
            var consumedTotal = 0
            while (consumedTotal < chunkLength) {
                val rest = view.duplicate()
                rest.position(consumedTotal)
                val result = RustNative.archivePackUpdate(session, rest.slice(), outputBuffer)
                if (result < 0) throw IOException(RustArchiveCodec.message(result.toInt()))
                val consumed = RustArchiveCodec.consumed(result)
                val produced = RustArchiveCodec.produced(result)
                writeProduced(produced, outputHeap)
                consumedTotal += consumed
                if (consumed == 0 && produced == 0) {
                    throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
                }
            }
            position += chunkLength
            remaining -= chunkLength
        }
    }

    /** 原生返回成功即消费句柄，随后目标写入异常不能再次调用原生 finish。 */
    @Throws(IOException::class)
    open fun finish() {
        if (finished) return
        if (closed) throw IOException("STREAM_CLOSED")
        val result = RustNative.archivePackFinish(session, outputBuffer)
        if (result < 0) {
            abortQuietly()
            finished = true
            throw IOException(RustArchiveCodec.message(result.toInt()))
        }
        val produced = result.toInt()
        if (produced > RustArchiveCodec.FINISH_BOUND) {
            abortQuietly()
            finished = true
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
        }
        session = 0
        finished = true
        writeProduced(produced, finishHeap)
    }

    @Throws(IOException::class)
    override fun flush() {
        if (closed) throw IOException("STREAM_CLOSED")
        target.flush()
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        var failure: IOException? = null
        try {
            if (!finished) finish()
        } catch (error: IOException) {
            failure = error
            abortQuietly()
        } finally {
            closed = true
            try {
                target.close()
            } catch (error: IOException) {
                if (failure == null) failure = error
            }
        }
        if (failure != null) throw failure
    }

    @Throws(IOException::class)
    private fun writeProduced(produced: Int, heap: ByteArray) {
        if (produced <= 0) return
        if (produced > heap.size)
            throw IOException(RustArchiveCodec.message(RustArchiveCodec.INTERNAL))
        outputBuffer.position(0)
        outputBuffer.limit(produced)
        outputBuffer.get(heap, 0, produced)
        target.write(heap, 0, produced)
    }

    private fun abortQuietly() {
        if (session > 0) {
            RustNative.archivePackAbort(session)
            session = 0
        }
    }
}
