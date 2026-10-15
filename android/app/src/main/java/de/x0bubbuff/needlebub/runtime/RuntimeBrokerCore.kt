package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import java.util.ArrayDeque

/** Every bounded wait a request can be in. */
enum class RuntimeDeadlineKind {
    QUEUE_EXPIRY,
    BIND_EXPIRY,
    INFERENCE_TIMEOUT,
}

/**
 * Why a request stopped waiting. Exactly one termination applies to an accepted request,
 * and the distinct values keep cancellation, queue expiry, bind failure, inference
 * timeout, runtime death and dispatch failure from being reported as one another.
 */
enum class RuntimeTermination(val errorCode: String?) {
    RESULT(null),
    CANCELLED(ErrorCodes.CANCELLED),
    QUEUE_EXPIRED(ErrorCodes.QUEUE_EXPIRED),
    BIND_FAILED(ErrorCodes.BIND_FAILED),
    INFERENCE_TIMEOUT(ErrorCodes.TIMEOUT),
    RUNTIME_DEATH(ErrorCodes.RUNTIME_CRASH),
    DISPATCH_FAILED(ErrorCodes.RUNTIME_CRASH),
}

/**
 * One accepted request. The broker adapter fills the trace metadata; the core owns the
 * bookkeeping fields and is the only writer of [settled].
 */
class RuntimeWork(
    val internalRequestId: String,
    val packId: String,
    val packVersion: String,
    val modelSize: Long,
    val input: String,
    val queryTemplate: String,
    val surface: String,
    val timeoutMs: Long,
    val forceReload: Boolean,
    val requestedAtEpochMs: Long,
    val requestedAtElapsedMs: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val modelIdentity: String,
    val engineAbi: String = "",
    val callback: (RuntimeResponse) -> Unit,
) {
    internal var settled = false
    internal var dispatchedAtElapsedMs = 0L
    internal var bindStartedAtElapsedMs = 0L
    internal var boundAtElapsedMs = 0L
    internal var termination: RuntimeTermination? = null
}

/** Work handed to the adapter; the core never performs I/O or calls a callback itself. */
sealed interface RuntimeEffect {
    data class ScheduleDeadline(
        val requestId: String,
        val kind: RuntimeDeadlineKind,
        val delayMs: Long,
        /** Identifies one bind attempt so a late timer cannot fail a newer bind. */
        val token: Long = 0L,
    ) : RuntimeEffect

    data class CancelDeadline(
        val requestId: String,
        val kind: RuntimeDeadlineKind,
        /** 0 means "whatever timer is registered for this request and kind". */
        val token: Long = 0L,
    ) : RuntimeEffect

    data class ScheduleIdleRelease(val delayMs: Long) : RuntimeEffect

    data object CancelIdleRelease : RuntimeEffect

    /** Bind a fresh connection for [generation]; late callbacks from older ones are stale. */
    data class BindRuntime(val generation: Int) : RuntimeEffect

    data class UnbindRuntime(val generation: Int) : RuntimeEffect

    data class DispatchWork(val requestId: String, val generation: Int) : RuntimeEffect

    data class CancelRuntimeWork(val requestId: String, val generation: Int) : RuntimeEffect

    /** Terminal callback. Always applied without holding a core or adapter lock. */
    data class DeliverResponse(val work: RuntimeWork, val response: RuntimeResponse) : RuntimeEffect
}

data class SubmitOutcome(val admission: RuntimeAdmission, val batch: EffectBatch)

/**
 * Effects stamped with the order their transition happened in.
 *
 * The adapter must apply batches in this order: a bind and the unbind of the same
 * generation can otherwise be applied the wrong way round when two threads and the
 * deadline scheduler race.
 */
data class EffectBatch(val sequence: Long, val effects: List<RuntimeEffect>) {
    val isEmpty: Boolean get() = effects.isEmpty()

    companion object {
        val EMPTY = EffectBatch(0L, emptyList())
    }
}

data class RuntimeBrokerSnapshot(
    val queueSize: Int,
    val currentRequestId: String?,
    val generation: Int,
    val bound: Boolean,
    val binding: Boolean,
    val idleReleaseScheduled: Boolean,
)

/**
 * Serialized state machine for the isolated runtime.
 *
 * Every transition runs under the instance monitor and only produces [RuntimeEffect]s, so
 * no callback, database write or binder call can happen while the state is locked. All
 * service and binder callbacks carry the generation they belong to; a callback from an
 * older generation can never settle, dispatch or invalidate work of a newer one.
 */
class RuntimeBrokerCore(
    private val maxQueue: Int = RuntimePolicy.MAX_QUEUE,
    private val bindTimeoutMs: Long = RuntimePolicy.BIND_TIMEOUT_MS,
    private val elapsedRealtime: () -> Long = { 0L },
) {
    private val queue = ArrayDeque<RuntimeWork>()
    private var current: RuntimeWork? = null
    private var generation = 0
    private var bound = false
    private var binding = false
    private var bindOwnerId: String? = null
    private var bindGeneration = 0
    private var bindToken = 0L
    private var idleReleaseScheduled = false
    private var batchSequence = 0L

    @Synchronized
    fun snapshot(): RuntimeBrokerSnapshot = RuntimeBrokerSnapshot(
        queueSize = queue.size,
        currentRequestId = current?.internalRequestId,
        generation = generation,
        bound = bound,
        binding = binding,
        idleReleaseScheduled = idleReleaseScheduled,
    )

    /** True while [requestId] is still the request in flight for [generation]. */
    @Synchronized
    fun isCurrent(requestId: String, generation: Int): Boolean =
        generation == this.generation && current?.internalRequestId == requestId && current?.settled == false

    @Synchronized
    fun submit(work: RuntimeWork): SubmitOutcome {
        val inFlight = if (current == null) 0 else 1
        if (queue.size + inFlight >= maxQueue) {
            return SubmitOutcome(RuntimeAdmission.QUEUE_FULL, EffectBatch.EMPTY)
        }
        val effects = ArrayList<RuntimeEffect>()
        if (idleReleaseScheduled) {
            idleReleaseScheduled = false
            effects += RuntimeEffect.CancelIdleRelease
        }
        queue.addLast(work)
        effects += RuntimeEffect.ScheduleDeadline(
            work.internalRequestId,
            RuntimeDeadlineKind.QUEUE_EXPIRY,
            RuntimePolicy.queueWaitMs(work.timeoutMs),
        )
        effects += advanceLocked()
        return SubmitOutcome(RuntimeAdmission.ACCEPTED, batch(effects))
    }

    @Synchronized
    fun cancel(requestId: String): EffectBatch {
        val queued = queue.firstOrNull { it.internalRequestId == requestId }
        if (queued != null) {
            queue.remove(queued)
            val effects = ArrayList<RuntimeEffect>()
            effects += settleLocked(queued, RuntimeTermination.CANCELLED)
            effects += advanceLocked()
            return batch(effects)
        }
        val running = current
        if (running != null && running.internalRequestId == requestId) {
            // Cancellation kills the isolated process: invalidate the binder before the
            // caller learns about it, then rebind for whatever is still queued.
            val effects = ArrayList<RuntimeEffect>()
            effects += RuntimeEffect.CancelRuntimeWork(requestId, generation)
            recycleRuntimeLocked(effects)
            effects += settleLocked(running, RuntimeTermination.CANCELLED)
            effects += advanceLocked()
            return batch(effects)
        }
        return EffectBatch.EMPTY
    }

    /** A connection for [generation] came up. Stale generations are released again. */
    @Synchronized
    fun onServiceConnected(generation: Int): EffectBatch {
        if (generation != this.generation || !binding) {
            return batch(listOf(RuntimeEffect.UnbindRuntime(generation)))
        }
        val now = elapsedRealtime()
        binding = false
        bound = true
        val owner = bindOwnerId
        bindOwnerId = null
        val effects = ArrayList<RuntimeEffect>(4)
        if (owner != null) {
            // The bind deadline belongs to this attempt only; it is done.
            effects += RuntimeEffect.CancelDeadline(owner, RuntimeDeadlineKind.BIND_EXPIRY, bindToken)
            // Record the real connection time so bind wait stays separate from queue wait.
            queue.firstOrNull { it.internalRequestId == owner }?.boundAtElapsedMs = now
        }
        if (queue.isEmpty() && current == null) {
            recycleRuntimeLocked(effects)
            return batch(effects)
        }
        effects += advanceLocked()
        return batch(effects)
    }

    /** Connection died or disconnected. Only the current generation may fail work. */
    @Synchronized
    fun onServiceLost(generation: Int): EffectBatch {
        if (generation != this.generation) return EffectBatch.EMPTY
        val effects = ArrayList<RuntimeEffect>()
        val owner = bindOwnerId
        binding = false
        recycleRuntimeLocked(effects)
        val running = current
        if (running != null) {
            effects += settleLocked(running, RuntimeTermination.RUNTIME_DEATH)
        } else if (owner != null) {
            queue.firstOrNull { it.internalRequestId == owner }?.let { waiting ->
                queue.remove(waiting)
                effects += settleLocked(waiting, RuntimeTermination.BIND_FAILED)
            }
        }
        effects += advanceLocked()
        return batch(effects)
    }

    /** `bindService` refused, returned no binder, or the bind deadline expired. */
    @Synchronized
    fun onBindFailed(generation: Int): EffectBatch {
        if (generation != this.generation || !binding) return EffectBatch.EMPTY
        return batch(onBindFailedLocked())
    }

    @Synchronized
    fun onDispatchFailed(requestId: String, generation: Int): EffectBatch {
        val running = current
        if (generation != this.generation || running == null || running.internalRequestId != requestId) {
            return EffectBatch.EMPTY
        }
        val effects = ArrayList<RuntimeEffect>()
        recycleRuntimeLocked(effects)
        effects += settleLocked(running, RuntimeTermination.DISPATCH_FAILED)
        effects += advanceLocked()
        return batch(effects)
    }

    /** Result from the isolated service; ignored unless it matches the in-flight request. */
    @Synchronized
    fun onResult(requestId: String, generation: Int, response: RuntimeResponse): EffectBatch {
        val running = current
        if (generation != this.generation || running == null || running.internalRequestId != requestId) {
            return EffectBatch.EMPTY
        }
        if (running.settled) return EffectBatch.EMPTY
        val effects = ArrayList<RuntimeEffect>()
        effects += settleLocked(running, RuntimeTermination.RESULT, response)
        effects += advanceLocked()
        return batch(effects)
    }

    @Synchronized
    fun onDeadline(requestId: String, kind: RuntimeDeadlineKind, token: Long = 0L): EffectBatch {
        when (kind) {
            RuntimeDeadlineKind.QUEUE_EXPIRY -> {
                val waiting = queue.firstOrNull { it.internalRequestId == requestId } ?: return EffectBatch.EMPTY
                queue.remove(waiting)
                val effects = ArrayList<RuntimeEffect>()
                effects += settleLocked(waiting, RuntimeTermination.QUEUE_EXPIRED)
                effects += advanceLocked()
                return batch(effects)
            }

            RuntimeDeadlineKind.BIND_EXPIRY -> {
                // The timer must belong to the bind attempt that is still in flight: a
                // late timer from a cancelled bind cannot fail a newer one.
                if (!binding || bindToken != token || bindOwnerId != requestId) return EffectBatch.EMPTY
                return batch(onBindFailedLocked())
            }

            RuntimeDeadlineKind.INFERENCE_TIMEOUT -> {
                val running = current ?: return EffectBatch.EMPTY
                if (running.internalRequestId != requestId) return EffectBatch.EMPTY
                val effects = ArrayList<RuntimeEffect>()
                effects += RuntimeEffect.CancelRuntimeWork(requestId, generation)
                recycleRuntimeLocked(effects)
                effects += settleLocked(running, RuntimeTermination.INFERENCE_TIMEOUT)
                effects += advanceLocked()
                return batch(effects)
            }
        }
    }

    @Synchronized
    fun onIdleReleaseDue(): EffectBatch {
        if (!idleReleaseScheduled) return EffectBatch.EMPTY
        idleReleaseScheduled = false
        if (current != null || queue.isNotEmpty()) return EffectBatch.EMPTY
        val effects = ArrayList<RuntimeEffect>()
        recycleRuntimeLocked(effects)
        return batch(effects)
    }

    private fun onBindFailedLocked(): List<RuntimeEffect> {
        val effects = ArrayList<RuntimeEffect>()
        val owner = bindOwnerId
        binding = false
        invalidateLocked(effects)
        if (owner != null) {
            queue.firstOrNull { it.internalRequestId == owner }?.let { waiting ->
                queue.remove(waiting)
                effects += settleLocked(waiting, RuntimeTermination.BIND_FAILED)
            }
        }
        effects += advanceLocked()
        return effects
    }

    /** Dispatch the head of the queue, or start a bind attempt for it. */
    private fun advanceLocked(): List<RuntimeEffect> {
        if (current != null || queue.isEmpty()) {
            if (current == null && queue.isEmpty() && bound && !idleReleaseScheduled) {
                idleReleaseScheduled = true
                return listOf(RuntimeEffect.ScheduleIdleRelease(RuntimePolicy.IDLE_RELEASE_MS))
            }
            return emptyList()
        }
        if (bound) {
            val work = queue.removeFirst()
            current = work
            work.dispatchedAtElapsedMs = elapsedRealtime()
            val effects = ArrayList<RuntimeEffect>(3)
            effects += RuntimeEffect.CancelDeadline(work.internalRequestId, RuntimeDeadlineKind.QUEUE_EXPIRY)
            effects += RuntimeEffect.ScheduleDeadline(
                work.internalRequestId,
                RuntimeDeadlineKind.INFERENCE_TIMEOUT,
                work.timeoutMs,
            )
            effects += RuntimeEffect.DispatchWork(work.internalRequestId, generation)
            return effects
        }
        if (binding) return emptyList()
        binding = true
        bindGeneration = ++generation
        bindToken += 1
        val token = bindToken
        val owner = queue.first()
        bindOwnerId = owner.internalRequestId
        if (owner.bindStartedAtElapsedMs == 0L) {
            owner.bindStartedAtElapsedMs = elapsedRealtime()
        }
        return listOf(
            RuntimeEffect.BindRuntime(generation),
            RuntimeEffect.ScheduleDeadline(
                owner.internalRequestId,
                RuntimeDeadlineKind.BIND_EXPIRY,
                bindTimeoutMs,
                token,
            ),
        )
    }

    private fun settleLocked(
        work: RuntimeWork,
        termination: RuntimeTermination,
        response: RuntimeResponse? = null,
    ): List<RuntimeEffect> {
        if (work.settled) return emptyList()
        work.settled = true
        work.termination = termination
        if (current === work) {
            current = null
        }
        val now = elapsedRealtime()
        val finalResponse = if (termination == RuntimeTermination.RESULT && response != null) {
            response.copy(
                termination = RuntimeTermination.RESULT.name,
                queueWaitMs = queueWaitMs(work, work.dispatchedAtElapsedMs),
                bindWaitMs = bindWaitMs(work),
                requestedAtEpochMs = response.requestedAtEpochMs ?: work.requestedAtEpochMs,
                appVersionName = response.appVersionName ?: work.appVersionName,
                appVersionCode = response.appVersionCode ?: work.appVersionCode,
                modelIdentity = response.modelIdentity ?: work.modelIdentity,
                engineAbi = response.engineAbi ?: work.engineAbi,
                loadState = response.loadState ?: RuntimeLoadState.UNKNOWN,
            )
        } else {
            failureResponse(work, termination, now)
        }
        val effects = ArrayList<RuntimeEffect>(4)
        effects += RuntimeEffect.CancelDeadline(work.internalRequestId, RuntimeDeadlineKind.QUEUE_EXPIRY)
        effects += RuntimeEffect.CancelDeadline(work.internalRequestId, RuntimeDeadlineKind.INFERENCE_TIMEOUT)
        effects += RuntimeEffect.CancelDeadline(work.internalRequestId, RuntimeDeadlineKind.BIND_EXPIRY)
        effects += RuntimeEffect.DeliverResponse(work, finalResponse)
        return effects
    }

    /** Invalidates the current binding without failing the in-flight request. */
    private fun recycleRuntimeLocked(effects: MutableList<RuntimeEffect>) {
        val released = generation
        bound = false
        binding = false
        bindOwnerId = null
        generation += 1
        effects += RuntimeEffect.UnbindRuntime(released)
    }

    private fun invalidateLocked(effects: MutableList<RuntimeEffect>) {
        val released = generation
        generation += 1
        effects += RuntimeEffect.UnbindRuntime(released)
    }

    private fun failureResponse(work: RuntimeWork, termination: RuntimeTermination, now: Long): RuntimeResponse {
        val dispatched = work.dispatchedAtElapsedMs
        return RuntimeResponse(
            requestId = work.internalRequestId,
            status = "ERROR",
            toolName = null,
            resultJson = null,
            errorCode = termination.errorCode,
            durationMs = if (dispatched == 0L) 0L else (now - dispatched).coerceAtLeast(0L),
            coldLoad = null,
            pssKb = 0L,
            loadState = RuntimeLoadState.UNKNOWN,
            termination = termination.name,
            queueWaitMs = queueWaitMs(work, if (dispatched != 0L) dispatched else now),
            bindWaitMs = bindWaitMs(work),
            requestedAtEpochMs = work.requestedAtEpochMs,
            appVersionName = work.appVersionName,
            appVersionCode = work.appVersionCode,
            modelIdentity = work.modelIdentity,
            engineAbi = work.engineAbi,
            outputTruncated = null,
        )
    }

    /**
     * Time spent waiting before a bind attempt. Kept separate from [bindWaitMs] so the two
     * waits add up to the total wait instead of double counting the connection time.
     */
    private fun queueWaitMs(work: RuntimeWork, fallbackEnd: Long): Long? {
        val end = if (work.bindStartedAtElapsedMs != 0L) work.bindStartedAtElapsedMs else fallbackEnd
        if (end == 0L) return null
        return (end - work.requestedAtElapsedMs).coerceAtLeast(0L)
    }

    private fun bindWaitMs(work: RuntimeWork): Long? {
        if (work.bindStartedAtElapsedMs == 0L) return 0L
        val end = if (work.boundAtElapsedMs != 0L) work.boundAtElapsedMs else work.dispatchedAtElapsedMs
        if (end == 0L) return null
        return (end - work.bindStartedAtElapsedMs).coerceAtLeast(0L)
    }

    /** Stamps a batch with its transition order; empty batches consume no sequence. */
    private fun batch(effects: List<RuntimeEffect>): EffectBatch =
        if (effects.isEmpty()) EffectBatch.EMPTY else EffectBatch(++batchSequence, effects)
}
