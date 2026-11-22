package com.deepseekharness.app.contract

/** 布局后端；load 可以返回空值，save 的空值行为由具体后端保持。 */
interface LayoutStore {
    fun load(): LayoutSnapshot?

    fun save(snapshot: LayoutSnapshot?)
}
