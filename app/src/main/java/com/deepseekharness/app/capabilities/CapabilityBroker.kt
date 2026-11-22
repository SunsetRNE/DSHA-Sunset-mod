package com.deepseekharness.app.capabilities

import java.util.Collections
import java.util.Objects
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/**
 * Application-owned authority fences and a single native confirmation slot. There is no queue. No
 * adapter, platform call, UI callback or I/O runs while the broker lock is held.
 */
class CapabilityBroker(monotonicMillis: LongSupplier?) {
    private val monotonicMillis: LongSupplier =
        Objects.requireNonNull<LongSupplier>(monotonicMillis, "clock")
    private val epochs: MutableMap<String, Long> = HashMap()

    // Entries disappear once nobody can submit that request object again; no unbounded run history.
    private val claimed: MutableSet<CapabilityRequest> =
        Collections.newSetFromMap(WeakHashMap<CapabilityRequest, Boolean>())
    private val authorized: MutableSet<CapabilityRequest> =
        Collections.newSetFromMap(WeakHashMap<CapabilityRequest, Boolean>())
    private val canceled: MutableSet<CapabilityRequest> =
        Collections.newSetFromMap(WeakHashMap<CapabilityRequest, Boolean>())
    private var sequence: Long = 0
    private var globalEpoch: Long = 0
    private var pending: Confirmation? = null

    fun nowMillis(): Long = monotonicMillis.asLong

    @Synchronized fun epoch(capability: String): Long = epochs.getOrDefault(capability, globalEpoch)

    /**
     * Runtime/target/system availability must additionally be revalidated by the executing adapter.
     */
    @Synchronized
    fun current(request: CapabilityRequest?): Boolean =
        request != null &&
            !canceled.contains(request) &&
            request.runtime.generation > 0 &&
            request.runtime.instanceId.isNotEmpty() &&
            request.installInstanceId == request.runtime.ownerId &&
            request.authorityEpoch == epoch(request.capability) &&
            nowMillis() < request.deadlineElapsedRealtimeMillis

    @Synchronized
    fun beginConfirmation(request: CapabilityRequest?): Confirmation? {
        val candidate = request ?: return null
        if (pending != null || !current(candidate) || claimed.contains(candidate)) return null
        authorized.remove(candidate)
        val confirmation = Confirmation(candidate)
        pending = confirmation
        return confirmation
    }

    @Synchronized
    fun pending(confirmation: Confirmation?): Boolean =
        confirmation != null &&
            pending === confirmation &&
            current(confirmation.request) &&
            confirmation.waiting()

    @Synchronized
    fun resolve(confirmation: Confirmation?, allow: Boolean): Boolean {
        val decision = confirmation ?: return false
        if (!pending(decision)) return false
        if (allow) authorized.add(decision.request) else cancelAuthority(decision.request)
        decision.settle(allow)
        return true
    }

    @Synchronized
    fun finish(confirmation: Confirmation?) {
        val decision = confirmation ?: return
        if (pending !== decision) return
        if (decision.answer() != true) cancelAuthority(decision.request)
        decision.settle(null)
        pending = null
    }

    @Synchronized
    fun revoke(capability: String) {
        epochs[capability] = nextEpoch()
        val confirmation = pending
        if (confirmation != null && confirmation.request.capability == capability)
            finish(confirmation)
    }

    @Synchronized
    fun revokeAll() {
        globalEpoch = nextEpoch()
        epochs.clear()
        val confirmation = pending
        if (confirmation != null) finish(confirmation)
    }

    private fun nextEpoch(): Long {
        // Exhaustion is impossible in practice, but never wrap into an old valid authority epoch.
        if (sequence == Long.MAX_VALUE) throw IllegalStateException("AUTHORITY_EPOCH_EXHAUSTED")
        return ++sequence
    }

    /**
     * Called only after the native orchestrator has evaluated current local policy/system consent.
     */
    @Synchronized
    fun authorize(request: CapabilityRequest?): Boolean {
        val candidate = request ?: return false
        if (!current(candidate) || claimed.contains(candidate)) return false
        authorized.add(candidate)
        return true
    }

    /**
     * For adapter validation before its one-use claim; after claiming use the permit's live fence.
     */
    @Synchronized
    fun authorized(request: CapabilityRequest?): Boolean =
        request != null &&
            current(request) &&
            authorized.contains(request) &&
            !claimed.contains(request)

    @Synchronized
    fun cancel(request: CapabilityRequest?) {
        if (request == null) return
        cancelAuthority(request)
        val confirmation = pending
        if (confirmation != null && confirmation.request === request) finish(confirmation)
    }

    private fun cancelAuthority(request: CapabilityRequest) {
        authorized.remove(request)
        canceled.add(request)
    }

    /**
     * One-use dispatch claim. Once returned, a side effect may have occurred even if revocation
     * races the subsequent platform send. A caller cannot then report NOT_SENT merely because it
     * lost a reply.
     */
    @Synchronized
    fun claimDispatch(request: CapabilityRequest?): DispatchPermit? {
        val candidate = request ?: return null
        if (!current(candidate) || !authorized.remove(candidate) || !claimed.add(candidate))
            return null
        return DispatchPermit(this, candidate)
    }

    fun isCurrent(permit: DispatchPermit?): Boolean =
        permit != null && permit.owner === this && current(permit.request)

    class DispatchPermit
    internal constructor(
        internal val owner: CapabilityBroker,
        @JvmField val request: CapabilityRequest,
    ) {
        fun isCurrent(): Boolean = owner.isCurrent(this)
    }

    class Confirmation internal constructor(@JvmField val request: CapabilityRequest) {
        private val done = CountDownLatch(1)

        @Volatile private var answerValue: Boolean? = null

        @Throws(InterruptedException::class)
        fun await(timeout: Long, unit: TimeUnit): Boolean =
            done.await(timeout, unit) && answerValue == true

        /** null means canceled/unanswered, never a user rejection. */
        fun answer(): Boolean? = answerValue

        internal fun waiting(): Boolean = done.count != 0L

        internal fun settle(allow: Boolean?) {
            if (allow != null) answerValue = allow
            done.countDown()
        }
    }
}
