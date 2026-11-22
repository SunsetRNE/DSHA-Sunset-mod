package com.deepseekharness.app.contract

import java.util.Objects

/** 一次安装/关闭对应的回调租约；代次变化后旧租约立即失效，不补发、不重放。 */
class GenerationLease
protected constructor(owner: ModuleId?, generation: Long, diagnostics: DiagnosticsSink?) {
    private val owner: ModuleId
    private val generation: Long
    private val diagnostics: DiagnosticsSink
    @Volatile private var active = true

    init {
        if (owner == null || generation < 1) throw IllegalArgumentException("LEASE_INVALID")
        this.owner = owner
        this.generation = generation
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    }

    fun owner(): ModuleId = owner

    fun generation(): Long = generation

    fun active(): Boolean = active

    /** 同步检查和执行；RuntimeException 进入诊断，Error 保持向上抛出。 */
    @Synchronized
    fun runIfActive(callback: Runnable?): Boolean {
        if (!active || callback == null) return false
        try {
            callback.run()
            return true
        } catch (error: RuntimeException) {
            diagnostics.report(
                ContractCodes.MODULE_CALLBACK_FAILED,
                "module callback failed: $owner (${error.javaClass.simpleName})",
            )
            return false
        }
    }

    // final 类的 protected JVM 方法保留 Java 同包可访问性。
    @Synchronized
    protected fun invalidate(): Boolean {
        val wasActive = active
        active = false
        return wasActive
    }

    @JvmSynthetic internal fun invalidateFromHost(): Boolean = invalidate()

    companion object {
        @JvmSynthetic
        internal fun create(
            owner: ModuleId?,
            generation: Long,
            diagnostics: DiagnosticsSink?,
        ): GenerationLease = GenerationLease(owner, generation, diagnostics)
    }
}
