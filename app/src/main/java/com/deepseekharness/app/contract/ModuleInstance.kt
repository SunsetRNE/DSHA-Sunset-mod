package com.deepseekharness.app.contract

/** 模块自行申请的资源仍须在 close 释放；ModuleHost scope 继续 deferred。 */
interface ModuleInstance {
    enum class CloseReason {
        USER_DISABLED,
        HOST_CLOSE,
        SAFE_MODE,
    }

    fun contributions(): List<Contribution?>?

    /** 保留 Java default：Java 实现者只实现 contributions 即可。 */
    fun close(reason: CloseReason?) {}
}
