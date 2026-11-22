package com.deepseekharness.app.contract

import java.util.regex.Pattern

/** fail-closed 的契约操作结果；失败只带稳定机器码，不带自由文本。 */
class ContractResult(accepted: Boolean, code: String?) {
    private val accepted: Boolean
    private val code: String

    init {
        if (code == null || !VALID_CODE.matcher(code).matches()) {
            throw IllegalArgumentException("CONTRACT_RESULT_CODE_INVALID")
        }
        this.accepted = accepted
        this.code = code
    }

    fun accepted(): Boolean = accepted

    fun code(): String = code

    fun rejected(): Boolean = !accepted

    override fun equals(other: Any?): Boolean =
        other is ContractResult && accepted == other.accepted && code == other.code

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + if (accepted) 1231 else 1237
        result = 31 * result + code.hashCode()
        return result
    }

    override fun toString(): String = "ContractResult[accepted=$accepted, code=$code]"

    companion object {
        private val VALID_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}")

        @JvmStatic fun ok(): ContractResult = ContractResult(true, ContractCodes.OK)

        @JvmStatic fun rejected(code: String?): ContractResult = ContractResult(false, code)
    }
}
