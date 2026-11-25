@file:Suppress("NON_FINAL_MEMBER_IN_FINAL_CLASS", "NON_FINAL_MEMBER_IN_OBJECT")

package com.deepseekharness.app.util

/**
 * Rust 后端的进程内开关。默认关闭，由 App 启动显式启用；加载失败仍留在 JVM。
 *
 * 选择只描述加载/开关回退。已选择 Rust 后，计划校验的失败不允许使用 JVM 结果掩盖； digest 仅允许纯计算 INTERNAL/PANIC 重做，gzip 不重放已消费输入。
 */
class RustBackends private constructor() {
    enum class SelectionReason {
        SWITCH_OFF,
        RUST_ENABLED,
        NOT_LOADED,
        LIBRARY_MISSING,
        LOAD_FAILED,
        ABI_MISMATCH,
    }

    /** 保留对象身份语义，不改为 data class。 */
    class Selection
    private constructor(
        private val useRust: Boolean,
        private val reason: SelectionReason,
    ) {
        open fun useRust(): Boolean = useRust

        open fun reason(): SelectionReason = reason

        open fun reasonCode(): String = reason.name

        companion object {
            // 仅供外层 Kotlin 实现访问原 private 构造器，Java 源码不可见。
            @JvmSynthetic
            internal fun create(useRust: Boolean, reason: SelectionReason): Selection =
                Selection(useRust, reason)
        }
    }

    companion object {
        @Volatile private var enabled = false

        @JvmStatic open fun enabled(): Boolean = enabled

        @JvmStatic
        open fun setEnabled(value: Boolean) {
            enabled = value
        }

        @JvmStatic
        open fun digestSelection(): Selection {
            if (!enabled) return Selection.create(false, SelectionReason.SWITCH_OFF)
            if (!RustNative.ensureLoaded()) {
                return Selection.create(false, nativeReason(RustNative.status()))
            }
            return Selection.create(true, SelectionReason.RUST_ENABLED)
        }

        @JvmStatic
        open fun planSelection(): Selection {
            if (!enabled) return Selection.create(false, SelectionReason.SWITCH_OFF)
            if (!RustNative.ensureLoaded()) {
                return Selection.create(false, nativeReason(RustNative.status()))
            }
            return Selection.create(true, SelectionReason.RUST_ENABLED)
        }

        @JvmStatic
        open fun archiveSelection(): Selection {
            if (!enabled) return Selection.create(false, SelectionReason.SWITCH_OFF)
            if (!RustNative.ensureLoaded()) {
                return Selection.create(false, nativeReason(RustNative.status()))
            }
            return Selection.create(true, SelectionReason.RUST_ENABLED)
        }

        private fun nativeReason(status: RustNative.Status): SelectionReason =
            when (status) {
                RustNative.Status.LIBRARY_MISSING -> SelectionReason.LIBRARY_MISSING
                RustNative.Status.LOAD_FAILED -> SelectionReason.LOAD_FAILED
                RustNative.Status.ABI_MISMATCH -> SelectionReason.ABI_MISMATCH
                else -> SelectionReason.NOT_LOADED
            }
    }
}
