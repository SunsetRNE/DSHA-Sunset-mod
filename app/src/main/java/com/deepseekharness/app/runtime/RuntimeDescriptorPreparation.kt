package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.BackupJson
import com.deepseekharness.app.backup.RuntimeDescriptor
import java.io.IOException
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** 同次启动的描述符解析出口；只统计解析，不保存 ready/latest/pending 或执行许可。 */
internal class RuntimeDescriptorPreparation {
    enum class Kind(val token: String) {
        EXPECTED("expected"),
        INSTALLED("installed"),
    }

    fun interface Bytes {
        @Throws(IOException::class) fun read(): ByteArray
    }

    private class Invocation(
        val generation: Long,
        val current: BooleanSupplier,
        val output: Consumer<String>?,
    ) {
        val calls = LongArray(Kind.entries.size)
        val parses = LongArray(Kind.entries.size)

        fun finish() {
            for (kind in Kind.entries) {
                emit(kind, "calls", calls[kind.ordinal])
                emit(kind, "parses", parses[kind.ordinal])
            }
        }

        private fun emit(kind: Kind, key: String, count: Long) {
            try {
                output?.accept(
                    "STARTUP_PREP_OBSERVATION: phase=descriptor-${kind.token} $key=$count"
                )
            } catch (_: RuntimeException) {
                // 诊断失败不能代替原来的文件、解析或启动结论。
            }
        }
    }

    private val invocation = ThreadLocal<Invocation>()

    fun open(
        generation: Long,
        current: BooleanSupplier,
        output: Consumer<String>?,
    ): RuntimeHostPorts.Scope {
        val previous = invocation.get()
        if (previous != null) {
            check(previous.generation == generation) { "RUNTIME_PREPARATION_NESTED_OPERATION" }
            return RuntimeHostPorts.Scope {}
        }
        val value = Invocation(generation, current, output)
        invocation.set(value)
        return RuntimeHostPorts.Scope {
            if (invocation.get() === value) {
                invocation.remove()
                value.finish()
            }
        }
    }

    @Throws(IOException::class)
    fun descriptor(kind: Kind, source: Bytes): RuntimeDescriptor {
        val active = invocation.get()
        active?.let { it.calls[kind.ordinal]++ }
        val bytes = source.read()
        active?.let { it.parses[kind.ordinal]++ }
        return RuntimeDescriptor(BackupJson.read(bytes, 2 * 1024 * 1024))
    }
}
