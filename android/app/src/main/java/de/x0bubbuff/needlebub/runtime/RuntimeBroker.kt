package de.x0bubbuff.needlebub.runtime

import android.os.SystemClock
import android.util.Log
import de.x0bubbuff.needlebub.BuildConfig
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

enum class RuntimeAdmission {
    ACCEPTED,
    QUEUE_FULL,
}

/**
 * Adapter around [RuntimeBrokerCore].
 *
 * Threading contract:
 *  - transitions and their effects are serialized: every batch the core stamps is applied
 *    in sequence order on one control thread, so a bind can never be applied after the
 *    unbind of the same generation;
 *  - binder calls (bind, unbind, dispatch, cancel) run on the control or dispatch thread
 *    and never hold a lock;
 *  - terminal callbacks run on their own thread and contain every throwable, so slow
 *    persistence cannot delay deadline recovery, rebinding or the next request.
 */
class RuntimeBroker(
    private val host: RuntimeHost,
    private val scheduler: RuntimeDeadlineScheduler = ThreadedDeadlineScheduler(),
    private val core: RuntimeBrokerCore = RuntimeBrokerCore(elapsedRealtime = SystemClock::elapsedRealtime),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    private val appVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val logLine: (String) -> Unit = { Log.i(TAG, it) },
) {
    private class Accepted(val work: RuntimeWork, val pack: InstalledPack, val request: RuntimeRequest)

    private val controlExecutor: ExecutorService = newExecutor(CONTROL_THREAD_NAME)
    private val dispatchExecutor: ExecutorService = newExecutor(DISPATCH_THREAD_NAME)

    /**
     * Terminal callbacks run on their own small pool: one slow persistence call cannot
     * starve other deliveries, and the pool stays bounded so database contention does too.
     */
    private val callbackExecutor: ExecutorService = Executors.newFixedThreadPool(CALLBACK_THREADS) { runnable ->
        Thread(runnable, CALLBACK_THREAD_NAME).apply { isDaemon = true }
    }

    /** Guards the accepted map only; never held while a callback or binder call runs. */
    private val acceptedLock = Any()
    private val accepted = HashMap<String, Accepted>()

    /**
     * Admission slots: accepted work whose terminal callback has not finished yet.
     *
     * The core queue alone is not enough of a bound, because a settled request still holds
     * a callback in the executor. Reserving a slot until the callback completes keeps the
     * total outstanding work bounded instead of letting blocked callbacks accumulate.
     */
    private val outstanding = AtomicInteger(0)

    /** Guards the reorder buffer only; batches are posted while it is held to keep order. */
    private val bufferLock = Any()
    private val buffer = TreeMap<Long, EffectBatch>()
    private var nextSequence = 1L

    /** Control thread only. */
    private class RegisteredDeadline(val token: Long, val deadline: RuntimeDeadline)

    private val deadlines = HashMap<Pair<String, RuntimeDeadlineKind>, RegisteredDeadline>()
    private var idleDeadline: RuntimeDeadline? = null

    @Volatile
    private var shuttingDown = false

    private val hostListener = object : RuntimeHostListener {
        override fun onConnected(generation: Int) = postEvent { core.onServiceConnected(generation) }

        override fun onServiceLost(generation: Int) = postEvent { core.onServiceLost(generation) }

        override fun onBindFailed(generation: Int) = postEvent { core.onBindFailed(generation) }
    }

    fun infer(
        internalRequestId: String,
        pack: InstalledPack,
        input: String,
        timeoutMs: Long,
        surface: String = "gateway",
        forceReload: Boolean = false,
        callback: (RuntimeResponse) -> Unit,
    ): RuntimeAdmission {
        val work = RuntimeWork(
            internalRequestId = internalRequestId,
            packId = pack.manifest.id,
            packVersion = pack.manifest.version,
            modelSize = pack.manifest.modelSize,
            input = input,
            queryTemplate = pack.manifest.queryTemplate,
            surface = RuntimePolicy.surface(surface),
            timeoutMs = RuntimePolicy.inferenceTimeoutMs(timeoutMs),
            forceReload = forceReload,
            requestedAtEpochMs = wallClock(),
            requestedAtElapsedMs = elapsedRealtime(),
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            modelIdentity = modelIdentity(pack),
            engineAbi = pack.manifest.engineAbi,
            callback = callback,
        )
        // The admission slot is reserved before the core transition and released again if
        // the core refuses, so the bound holds even when callers race each other.
        if (outstanding.incrementAndGet() > RuntimePolicy.MAX_QUEUE) {
            outstanding.decrementAndGet()
            logBusy(work)
            return RuntimeAdmission.QUEUE_FULL
        }
        // The admission is decided under the core lock; the accepted entry is registered
        // before the batch is enqueued, so a dispatch can never outrun its own work.
        val outcome = core.submit(work)
        if (outcome.admission == RuntimeAdmission.ACCEPTED) {
            val request = RuntimeRequest(
                requestId = work.internalRequestId,
                packId = work.packId,
                packVersion = work.packVersion,
                input = work.input,
                queryTemplate = work.queryTemplate,
                modelSize = work.modelSize,
                timeoutMs = work.timeoutMs,
                surface = work.surface,
                forceReload = work.forceReload,
                requestedAtEpochMs = work.requestedAtEpochMs,
                appVersionName = work.appVersionName,
                appVersionCode = work.appVersionCode,
                modelIdentity = work.modelIdentity,
                engineAbi = pack.manifest.engineAbi,
            )
            synchronized(acceptedLock) { accepted[work.internalRequestId] = Accepted(work, pack, request) }
        } else {
            outstanding.decrementAndGet()
            logBusy(work)
        }
        enqueue(outcome.batch)
        return outcome.admission
    }

    fun cancel(internalRequestId: String) {
        enqueue(core.cancel(internalRequestId))
    }

    /** Releases the runtime and stops every executor; the broker is application scoped. */
    fun shutdown() {
        shuttingDown = true
        runCatching { controlExecutor.execute { releaseAll() } }
        scheduler.shutdown()
        controlExecutor.shutdown()
        dispatchExecutor.shutdown()
        callbackExecutor.shutdown()
    }

    private fun newExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    /** Control-thread events: run the transition, then apply the batch it produced. */
    private fun postEvent(event: () -> EffectBatch) {
        if (shuttingDown) return
        try {
            controlExecutor.execute { enqueue(event()) }
        } catch (_: RejectedExecutionException) {
            // Shutting down; the request is settled by the caller's own deadline handling.
        }
    }

    /**
     * Applies batches strictly in the order their transitions happened.
     *
     * A batch from a later transition can arrive before an earlier one when a caller
     * thread and the deadline scheduler race; it waits in the buffer instead of being
     * applied out of order.
     */
    private fun enqueue(batch: EffectBatch) {
        if (batch.isEmpty || shuttingDown) return
        try {
            synchronized(bufferLock) {
                buffer[batch.sequence] = batch
                while (buffer.isNotEmpty()) {
                    val head = buffer.firstKey()
                    if (head != nextSequence) break
                    val ready = buffer.remove(head) ?: break
                    nextSequence += 1
                    // Posted while the buffer lock is held so the control thread always
                    // receives batches in sequence order.
                    controlExecutor.execute { applyBatch(ready) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun applyBatch(batch: EffectBatch) {
        batch.effects.forEach { effect ->
            when (effect) {
                is RuntimeEffect.ScheduleDeadline -> scheduleDeadline(effect)
                is RuntimeEffect.CancelDeadline -> cancelDeadline(effect)
                is RuntimeEffect.ScheduleIdleRelease -> {
                    idleDeadline?.cancel()
                    idleDeadline = scheduler.schedule(effect.delayMs) {
                        postEvent { core.onIdleReleaseDue() }
                    }
                }

                RuntimeEffect.CancelIdleRelease -> {
                    idleDeadline?.cancel()
                    idleDeadline = null
                }

                is RuntimeEffect.BindRuntime -> bindRuntime(effect.generation)
                is RuntimeEffect.UnbindRuntime -> unbindRuntime(effect.generation)
                is RuntimeEffect.DispatchWork -> scheduleDispatch(effect.requestId, effect.generation)
                is RuntimeEffect.CancelRuntimeWork -> cancelRuntimeWork(effect.requestId, effect.generation)
                is RuntimeEffect.DeliverResponse -> deliver(effect)
            }
        }
    }

    private fun scheduleDeadline(effect: RuntimeEffect.ScheduleDeadline) {
        val key = effect.requestId to effect.kind
        deadlines[key]?.deadline?.cancel()
        deadlines[key] = RegisteredDeadline(
            token = effect.token,
            deadline = scheduler.schedule(effect.delayMs) {
                postEvent { core.onDeadline(effect.requestId, effect.kind, effect.token) }
            },
        )
    }

    private fun cancelDeadline(effect: RuntimeEffect.CancelDeadline) {
        val key = effect.requestId to effect.kind
        val registered = deadlines[key] ?: return
        // A token-specific cancel must not remove a newer bind attempt's timer.
        if (effect.token != 0L && registered.token != effect.token) return
        deadlines.remove(key)
        registered.deadline.cancel()
    }

    private fun bindRuntime(generation: Int) {
        val bound = try {
            host.bind(generation, hostListener)
        } catch (_: Exception) {
            false
        }
        if (!bound) postEvent { core.onBindFailed(generation) }
    }

    private fun unbindRuntime(generation: Int) {
        try {
            host.unbind(generation)
        } catch (_: Exception) {
            // Already unbound.
        }
    }

    private fun cancelRuntimeWork(requestId: String, generation: Int) {
        try {
            host.cancel(requestId, generation)
        } catch (_: Exception) {
            // The runtime is already gone; the core has moved to a new generation.
        }
    }

    /**
     * Dispatch is handed to its own thread: the binder call and the model file read must
     * never delay deadline recovery or rebinding on the control thread.
     */
    private fun scheduleDispatch(requestId: String, generation: Int) {
        if (shuttingDown) return
        try {
            dispatchExecutor.execute {
                // Still-current check right before the native call, after the batch order
                // already proved this dispatch was not superseded by a later transition.
                if (!core.isCurrent(requestId, generation)) return@execute
                val pending = synchronized(acceptedLock) { accepted[requestId] } ?: return@execute
                try {
                    host.dispatch(pending.request, pending.pack, generation) { response ->
                        postEvent { core.onResult(response.requestId, generation, response) }
                    }
                } catch (_: Exception) {
                    postEvent { core.onDispatchFailed(requestId, generation) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun deliver(effect: RuntimeEffect.DeliverResponse) {
        logOutcome(effect)
        // The accepted entry and the admission slot live until the caller's callback has
        // finished: dropping the callback is never an option, so instead the next request
        // is refused while the callbacks are saturated.
        val requestId = effect.work.internalRequestId
        try {
            callbackExecutor.execute {
                try {
                    effect.work.callback(effect.response)
                } catch (_: Throwable) {
                    // A failing caller must never take down the broker or the runtime.
                } finally {
                    releaseSlot(requestId)
                }
            }
        } catch (_: RejectedExecutionException) {
            releaseSlot(requestId)
        }
    }

    private fun releaseSlot(requestId: String) {
        synchronized(acceptedLock) { accepted.remove(requestId) }
        outstanding.decrementAndGet()
    }

    private fun logBusy(work: RuntimeWork) {
        logLine(
            "pack=${work.packId}@${work.packVersion} surface=${work.surface} load=unknown " +
                "status=BUSY error=${ErrorCodes.BUSY} durationMs=0 pssKb=0 " +
                "requestedAt=${work.requestedAtEpochMs} app=${work.appVersionName}+${work.appVersionCode}",
        )
    }

    private fun releaseAll() {
        deadlines.values.forEach { it.deadline.cancel() }
        deadlines.clear()
        idleDeadline?.cancel()
        idleDeadline = null
        runCatching { host.unbind(core.snapshot().generation) }
        synchronized(acceptedLock) { accepted.clear() }
    }

    private fun logOutcome(delivery: RuntimeEffect.DeliverResponse) {
        val response = delivery.response
        if (response.termination == RuntimeTermination.RESULT.name) return
        val work = delivery.work
        logLine(
            "pack=${work.packId}@${work.packVersion} surface=${work.surface} " +
                "load=${response.loadState ?: RuntimeLoadState.UNKNOWN} status=${response.status} " +
                "error=${response.errorCode ?: ErrorCodes.RUNTIME_CRASH} reason=${response.termination} " +
                "durationMs=${response.durationMs} pssKb=${response.pssKb} " +
                "queueWaitMs=${response.queueWaitMs ?: -1} bindWaitMs=${response.bindWaitMs ?: -1} " +
                "requestedAt=${response.requestedAtEpochMs ?: work.requestedAtEpochMs} " +
                "app=${work.appVersionName}+${work.appVersionCode} model=${response.modelIdentity ?: work.modelIdentity} " +
                "engine=${response.engineAbi ?: work.engineAbi}",
        )
    }

    /**
     * Model identity for traces: pack, engine ABI, size and the model digest.
     *
     * The digest is what makes the identity unique to the weights: id, version, ABI and size
     * alone could describe two different model files.
     */
    private fun modelIdentity(pack: InstalledPack): String =
        "${pack.manifest.id}@${pack.manifest.version}:${pack.manifest.engineAbi}:" +
            "${pack.manifest.modelSize}:${pack.manifest.modelSha256.take(SHA256_PREFIX_CHARS)}"

    private companion object {
        const val TAG = "NeedleRuntime"
        const val CONTROL_THREAD_NAME = "NeedleRuntimeControl"
        const val DISPATCH_THREAD_NAME = "NeedleRuntimeDispatch"
        const val CALLBACK_THREAD_NAME = "NeedleRuntimeCallbacks"
        const val CALLBACK_THREADS = 2

        /** Enough of the digest to identify the weights without copying the whole hash. */
        const val SHA256_PREFIX_CHARS = 16
    }
}
