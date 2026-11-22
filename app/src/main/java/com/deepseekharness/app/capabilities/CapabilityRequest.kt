package com.deepseekharness.app.capabilities

import com.deepseekharness.app.core.RuntimeSnapshot
import java.util.Objects
import java.util.UUID

/**
 * Explicit request context. It must travel with asynchronous work rather than through ThreadLocal.
 */
class CapabilityRequest(
    requestId: String?,
    capability: String?,
    action: String?,
    runtime: RuntimeSnapshot.Epoch?,
    installInstanceId: String?,
    bridgeRunId: String?,
    target: TargetEvidence?,
    policyRevision: Long,
    authorityEpoch: Long,
    deadlineElapsedRealtimeMillis: Long,
) {
    @JvmField val requestId: String
    @JvmField val capability: String
    @JvmField val action: String
    @JvmField val runtime: RuntimeSnapshot.Epoch
    @JvmField val installInstanceId: String
    @JvmField val bridgeRunId: String
    @JvmField val target: TargetEvidence
    @JvmField val policyRevision: Long
    @JvmField val authorityEpoch: Long
    @JvmField val deadlineElapsedRealtimeMillis: Long

    init {
        this.requestId = text(requestId, 256, "INVALID_REQUEST_ID")
        this.capability = text(capability, 64, "INVALID_CAPABILITY")
        if (!this.capability.matches(CAPABILITY))
            throw IllegalArgumentException("INVALID_CAPABILITY")
        this.action = text(action, 8192, "INVALID_CAPABILITY_ACTION")
        this.runtime = Objects.requireNonNull<RuntimeSnapshot.Epoch>(runtime, "runtime")
        this.installInstanceId = text(installInstanceId, 256, "INVALID_INSTALLATION_ID")
        this.bridgeRunId = text(bridgeRunId, 256, "INVALID_BRIDGE_RUN_ID")
        this.target = Objects.requireNonNull<TargetEvidence>(target, "target")
        if (policyRevision < 0 || authorityEpoch < 0 || deadlineElapsedRealtimeMillis < 0) {
            throw IllegalArgumentException("INVALID_REQUEST_FENCE")
        }
        this.policyRevision = policyRevision
        this.authorityEpoch = authorityEpoch
        this.deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis
    }

    companion object {
        private val CAPABILITY = Regex("[a-z][a-z0-9._-]*")

        @JvmStatic
        fun create(
            capability: String?,
            action: String?,
            runtime: RuntimeSnapshot.Epoch?,
            installInstanceId: String?,
            bridgeRunId: String?,
            target: TargetEvidence?,
            policyRevision: Long,
            authorityEpoch: Long,
            deadlineElapsedRealtimeMillis: Long,
        ): CapabilityRequest =
            CapabilityRequest(
                UUID.randomUUID().toString(),
                capability,
                action,
                runtime,
                installInstanceId,
                bridgeRunId,
                target,
                policyRevision,
                authorityEpoch,
                deadlineElapsedRealtimeMillis,
            )

        private fun text(value: String?, limit: Int, error: String): String {
            if (
                value == null ||
                    value.isEmpty() ||
                    value.length > limit ||
                    value.indexOf('\u0000') >= 0
            ) {
                throw IllegalArgumentException(error)
            }
            return value
        }
    }
}
