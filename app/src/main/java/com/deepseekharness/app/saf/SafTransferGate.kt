package com.deepseekharness.app.saf

import java.util.concurrent.Semaphore

/** 桥与原生工作台共用两个传输槽位。 */
class SafTransferGate private constructor() {
    companion object {
        @JvmField val TRANSFERS = Semaphore(SafCapabilityLimits.MAX_CONCURRENT_TRANSFERS)
    }
}
