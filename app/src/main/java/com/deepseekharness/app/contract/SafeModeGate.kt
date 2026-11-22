package com.deepseekharness.app.contract

/** 安全模式状态；法定许可和恢复入口始终由宿主持有。 */
class SafeModeGate {
    @Volatile private var forced: Boolean

    constructor() {
        forced = false
    }

    constructor(forced: Boolean) {
        this.forced = forced
    }

    fun forcedSafeMode(): Boolean = forced

    // final 类的 protected JVM 方法保留 Java 同包可访问性，外包不能继承扩大入口。
    protected fun enterSafeMode() {
        forced = true
    }

    protected fun exitSafeMode() {
        forced = false
    }

    @JvmSynthetic
    internal fun enterFromHost() {
        enterSafeMode()
    }

    @JvmSynthetic
    internal fun exitFromHost() {
        exitSafeMode()
    }

    fun recoveryEntryAvailable(): Boolean = true
}
