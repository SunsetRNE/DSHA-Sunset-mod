package com.deepseekharness.app.transfer

/** 不含 Activity、cookie、运行时地址或私有源路径的不可变 UI 快照。 */
class DownloadTask
internal constructor(
    @JvmField val id: String?,
    @JvmField val artifact: TransferArtifact?,
    @JvmField val destination: String?,
    @JvmField val status: Status?,
    @JvmField val downloaded: Long,
    speed: Long,
    @JvmField val message: String?,
    @JvmField val uri: String?,
    @JvmField val displayName: String?,
    @JvmField val location: String?,
    @JvmField val mime: String?,
    @JvmField val createdAt: Long,
) {
    enum class Status {
        QUEUED,
        DOWNLOADING,
        VERIFYING,
        PUBLISHING,
        WAITING_DESTINATION,
        PAUSED,
        FAILED,
        COMPLETE,
        CANCELLED,
    }

    @JvmField val total: Long = artifact!!.bytes
    @JvmField val speedBytesPerSecond: Long = speed
}
