package com.deepseekharness.app.capabilities

/** A selected Android window. Implementations can additionally bind pixel or semantic evidence. */
interface WindowTargetEvidence : TargetEvidence {
    fun displayId(): Int

    fun windowId(): Int

    fun packageName(): String
}
