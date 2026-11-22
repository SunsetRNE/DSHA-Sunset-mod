package com.deepseekharness.app.contract

import java.util.regex.Pattern

/** 编译期模块身份；只增不复用，不从文件或网络读取。 */
class ModuleId(value: String?) {
    private val value: String

    init {
        if (value == null || !VALID.matcher(value).matches()) {
            throw IllegalArgumentException("MODULE_ID_INVALID")
        }
        this.value = value
    }

    fun value(): String = value

    override fun equals(other: Any?): Boolean = other is ModuleId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private val VALID = Pattern.compile("[a-z][a-z0-9._-]{0,63}")

        @JvmStatic fun of(value: String?): ModuleId = ModuleId(value)
    }
}
