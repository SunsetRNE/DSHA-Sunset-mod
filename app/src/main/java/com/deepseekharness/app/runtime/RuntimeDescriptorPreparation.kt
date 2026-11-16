package com.deepseekharness.app.runtime

import com.deepseekharness.app.backup.BackupJson
import com.deepseekharness.app.backup.RuntimeDescriptor
import java.io.IOException
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/** 同次启动的描述符解析出口；原始字节每次重读，不保存 ready/latest/pending 或执行许可。 */
internal class RuntimeDescriptorPreparation
@JvmOverloads
constructor(private val identities: Supplier<Identity?> = Supplier { null }) {
    enum class Kind(val token: String) {
        EXPECTED("expected"),
        INSTALLED("installed"),
    }

    fun interface Bytes {
        @Throws(IOException::class) fun read(): ByteArray
    }

    /** 全部来自同次新鲜观察；对象身份绑定 APK/split 验证及 settings invocation。 */
    class Identity(
        val apk: Any,
        val assets: Any,
        val settings: Any,
        val rootAndFiles: String,
        val tools: String,
    ) {
        fun matches(other: Identity): Boolean =
            apk === other.apk &&
                assets === other.assets &&
                settings === other.settings &&
                rootAndFiles == other.rootAndFiles &&
                tools == other.tools
    }

    private class Entry(val identity: Identity, val bytes: ByteArray, val value: RuntimeDescriptor)

    private class Invocation(
        val generation: Long,
        val current: BooleanSupplier,
        val output: Consumer<String>?,
    ) {
        val calls = LongArray(Kind.entries.size)
        val parses = LongArray(Kind.entries.size)
        val hits = LongArray(Kind.entries.size)
        val invalidations = LongArray(Kind.entries.size)
        val entries = arrayOfNulls<Entry>(Kind.entries.size)
        var retired = false

        fun clear() {
            entries.fill(null)
        }

        fun retire() {
            clear()
            retired = true
        }

        fun finish() {
            clear()
            for (kind in Kind.entries) {
                emit(kind, "calls", calls[kind.ordinal])
                emit(kind, "parses", parses[kind.ordinal])
                emit(kind, "hits", hits[kind.ordinal])
                emit(kind, "invalidations", invalidations[kind.ordinal])
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

    /** 维护/修复或缺失观察不可使旧输入复活；本次剩余读取一律完整解析。 */
    fun invalidate() {
        invocation.get()?.retire()
    }

    private fun observe(active: Invocation): Identity? {
        if (active.retired) return null
        val identity =
            try {
                if (active.current.asBoolean && !Thread.currentThread().isInterrupted)
                    identities.get()
                else null
            } catch (_: RuntimeException) {
                null
            }
        if (identity == null) active.retire()
        return identity
    }

    @Throws(IOException::class)
    fun descriptor(kind: Kind, source: Bytes): RuntimeDescriptor {
        val active = invocation.get()
        if (active == null) return decode(source.read())
        val index = kind.ordinal
        active.calls[index]++
        try {
            val previous = active.entries[index]
            val before = observe(active)
            // 每次读取仍走原受控文件/AssetManager 路径；缓存只省 JSON、摘要和冻结解析。
            val bytes = source.read()
            val after = observe(active)
            val stable = before != null && after != null && before.matches(after)
            if (previous != null) {
                if (
                    stable &&
                        previous.identity.matches(after!!) &&
                        previous.bytes.contentEquals(bytes)
                ) {
                    active.hits[index]++
                    return previous.value
                }
                active.clear()
                active.invalidations[index]++
            }
            active.parses[index]++
            val parsed = decode(bytes)
            val finalIdentity = if (stable) observe(active) else null
            if (stable && finalIdentity != null && after!!.matches(finalIdentity)) {
                active.entries[index] = Entry(finalIdentity, bytes.clone(), parsed)
            } else {
                active.clear()
            }
            return parsed
        } catch (error: Throwable) {
            active.retire()
            throw error
        }
    }

    private fun decode(bytes: ByteArray): RuntimeDescriptor =
        RuntimeDescriptor(BackupJson.read(bytes, 2 * 1024 * 1024))
}
