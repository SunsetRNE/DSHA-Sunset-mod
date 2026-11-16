package com.deepseekharness.app.capabilities

import java.util.Objects

/**
 * Common execution facts. Transport-specific output/exit/cleanup evidence stays with its adapter.
 */
class CapabilityResult private constructor(state: State?, code: String?, detail: String?) {
    enum class State {
        NOT_SENT,
        REJECTED,
        COMPLETED,
        PARTIAL,
        UNKNOWN,
    }

    @JvmField val state: State
    @JvmField val code: String
    @JvmField val detail: String

    init {
        this.state = Objects.requireNonNull<State>(state, "state")
        if (code == null || code.isEmpty() || code.length > 128) {
            throw IllegalArgumentException("INVALID_CAPABILITY_RESULT_CODE")
        }
        this.code = code
        this.detail = detail ?: ""
    }

    fun executionMayHaveOccurred(): Boolean =
        state == State.COMPLETED || state == State.PARTIAL || state == State.UNKNOWN

    /**
     * Eligibility only; callers still need a fresh authorization and must choose any retry
     * explicitly.
     */
    fun mayTryAlternateTransport(): Boolean = state == State.NOT_SENT

    companion object {
        @JvmStatic
        fun notSent(code: String?, detail: String?): CapabilityResult =
            CapabilityResult(State.NOT_SENT, code, detail)

        @JvmStatic
        fun rejected(code: String?, detail: String?): CapabilityResult =
            CapabilityResult(State.REJECTED, code, detail)

        @JvmStatic
        fun completed(code: String?, detail: String?): CapabilityResult =
            CapabilityResult(State.COMPLETED, code, detail)

        @JvmStatic
        fun partial(code: String?, detail: String?): CapabilityResult =
            CapabilityResult(State.PARTIAL, code, detail)

        @JvmStatic
        fun unknown(code: String?, detail: String?): CapabilityResult =
            CapabilityResult(State.UNKNOWN, code, detail)
    }
}
