package com.deepseekharness.app.capabilities

import java.util.Objects

/** Exact display/window/package selection; never falls back to a different display. */
class ScreenTargetEvidence(displayId: Int, windowId: Int, packageName: String?) :
    WindowTargetEvidence {
    @JvmField val displayId: Int
    @JvmField val windowId: Int
    @JvmField val packageName: String

    init {
        if (
            displayId < 0 ||
                windowId < 0 ||
                packageName == null ||
                packageName.isEmpty() ||
                packageName.length > 255
        ) {
            throw IllegalArgumentException("INVALID_SCREEN_TARGET")
        }
        this.displayId = displayId
        this.windowId = windowId
        this.packageName = packageName
    }

    override fun kind(): String = "screen-window"

    override fun displayId(): Int = displayId

    override fun windowId(): Int = windowId

    override fun packageName(): String = packageName

    fun matches(current: WindowTargetEvidence?): Boolean =
        current != null &&
            displayId == current.displayId() &&
            windowId == current.windowId() &&
            Objects.equals(packageName, current.packageName())
}
