package com.deepseekharness.app.contract

/** 稳定机器码；保持 Java static final 常量形态。 */
class ContractCodes private constructor() {
    companion object {
        const val OK = "OK"
        const val MODULE_INVALID = "MODULE_INVALID"
        const val MODULE_DUPLICATE_ID = "MODULE_DUPLICATE_ID"
        const val MODULE_UNKNOWN = "MODULE_UNKNOWN"
        const val MODULE_ALWAYS_ON = "MODULE_ALWAYS_ON"
        const val MODULE_INSTALL_FAILED = "MODULE_INSTALL_FAILED"
        const val MODULE_INVALID_CONTRIBUTION = "MODULE_INVALID_CONTRIBUTION"
        const val MODULE_CLOSE_FAILED = "MODULE_CLOSE_FAILED"
        const val MODULE_CALLBACK_FAILED = "MODULE_CALLBACK_FAILED"
        const val MODULE_DISABLED = "MODULE_DISABLED"
        const val SAFE_MODE_RESTRICTED = "SAFE_MODE_RESTRICTED"
        const val SAFE_MODE_ENTERED = "SAFE_MODE_ENTERED"
        const val UNKNOWN_CONTRIBUTION = "UNKNOWN_CONTRIBUTION"
        const val DUPLICATE_CONTRIBUTION = "DUPLICATE_CONTRIBUTION"
        const val INVALID_LAYOUT = "INVALID_LAYOUT"
        const val LAYOUT_LOAD_FAILED = "LAYOUT_LOAD_FAILED"
        const val LAYOUT_SAVE_FAILED = "LAYOUT_SAVE_FAILED"
        const val LAYOUT_CORRUPT = "LAYOUT_CORRUPT"
        const val STALE_GENERATION = "STALE_GENERATION"
        const val INVALID_CALLBACK = "INVALID_CALLBACK"
        const val ROUTE_MISSING = "ROUTE_MISSING"
        const val INVALID_ROUTE = "INVALID_ROUTE"
        const val DUPLICATE_ROUTE = "DUPLICATE_ROUTE"
        const val INVALID_DIAGNOSTIC = "INVALID_DIAGNOSTIC"
    }
}
