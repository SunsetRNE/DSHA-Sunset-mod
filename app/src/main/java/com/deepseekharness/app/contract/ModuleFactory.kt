package com.deepseekharness.app.contract

/** 编译期模块工厂；保留 Java SAM 和空实例失败路径。 */
@java.lang.FunctionalInterface
fun interface ModuleFactory {
    fun create(): ModuleInstance?
}
