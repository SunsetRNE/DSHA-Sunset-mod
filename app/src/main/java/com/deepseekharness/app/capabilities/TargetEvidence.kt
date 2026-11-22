package com.deepseekharness.app.capabilities

/** Immutable evidence captured by an adapter. Evidence is never itself an execution permission. */
interface TargetEvidence {
    fun kind(): String
}
