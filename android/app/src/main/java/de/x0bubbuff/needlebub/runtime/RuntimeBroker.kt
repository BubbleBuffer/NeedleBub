package de.x0bubbuff.needlebub.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackStore
import java.util.ArrayDeque

class RuntimeBroker(context: Context) {
    private data class Work(
        val internalRequestId: String,
        val pack: InstalledPack,
        val input: String,
        val timeoutMs: Long,
        val surface: String,
        val forceReload: Boolean,
        val callback: (RuntimeResponse) -> Unit,
    )

    private val appContext = context.applicationContext
    private val packStore = PackStore(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Work>()
    private var runtime: IIsolatedRuntime? = null
    private var binding = false
    private var current: Work? = null
    private var idleRelease: Runnable? = null
    private var timeout: Runnable? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(this@RuntimeBroker) {
                binding = false
                runtime = IIsolatedRuntime.Stub.asInterface(service)
                try {
                    service?.linkToDeath({ handleRuntimeDeath() }, 0)
                } catch (_: Exception) {
                    handleRuntimeDeath()
                    return
                }
                dispatchLocked()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleRuntimeDeath()
        override fun onBindingDied(name: ComponentName?) = handleRuntimeDeath()
        override fun onNullBinding(name: ComponentName?) = handleRuntimeDeath()
    }

    @Synchronized
    fun infer(
        internalRequestId: String,
        pack: InstalledPack,
        input: String,
        timeoutMs: Long,
        surface: String = "gateway",
        forceReload: Boolean = false,
        callback: (RuntimeResponse) -> Unit,
    ): Boolean {
        if (queue.size + (if (current == null) 0 else 1) >= MAX_QUEUE) return false
        idleRelease?.let(handler::removeCallbacks)
        idleRelease = null
        queue.addLast(Work(
            internalRequestId,
            pack,
            input,
            timeoutMs.coerceIn(250L, 30_000L),
            surface.takeIf { it in SURFACES } ?: "gateway",
            forceReload,
            callback,
        ))
        ensureBoundLocked()
        dispatchLocked()
        return true
    }

    @Synchronized
    fun cancel(internalRequestId: String) {
        val queued = queue.firstOrNull { it.internalRequestId == internalRequestId }
        if (queued != null) {
            queue.remove(queued)
            queued.callback(error(queued, ErrorCodes.TIMEOUT))
            return
        }
        if (current?.internalRequestId == internalRequestId) {
            try { runtime?.cancel(internalRequestId) } catch (_: Exception) { }
            finishLocked(error(current!!, ErrorCodes.TIMEOUT))
        }
    }

    private fun ensureBoundLocked() {
        if (runtime != null || binding) return
        binding = true
        if (!appContext.bindService(Intent(appContext, IsolatedInferenceService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            binding = false
            handleRuntimeDeath()
        }
    }

    private fun dispatchLocked() {
        val service = runtime ?: return
        if (current != null || queue.isEmpty()) return
        val work = queue.removeFirst()
        current = work
        val modelFile = packStore.modelFile(work.pack)
        val request = RuntimeRequest(
            work.internalRequestId,
            work.pack.manifest.id,
            work.pack.manifest.version,
            work.input,
            work.pack.manifest.queryTemplate,
            work.pack.manifest.modelSize,
            work.timeoutMs,
            work.surface,
            work.forceReload,
        )
        val timeoutAction = Runnable {
            synchronized(this@RuntimeBroker) {
                if (current?.internalRequestId == work.internalRequestId) {
                    try { runtime?.cancel(work.internalRequestId) } catch (_: Exception) { }
                    finishLocked(error(work, ErrorCodes.TIMEOUT))
                    releaseRuntimeLocked()
                    ensureBoundLocked()
                }
            }
        }
        timeout = timeoutAction
        handler.postDelayed(timeoutAction, work.timeoutMs)
        try {
            ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                service.infer(request, descriptor, packStore.toolsJson(work.pack), object : IRuntimeCallback.Stub() {
                    override fun onResult(response: RuntimeResponse) {
                        synchronized(this@RuntimeBroker) {
                            if (current?.internalRequestId == response.requestId) finishLocked(response)
                        }
                    }
                })
            }
        } catch (_: Exception) {
            finishLocked(error(work, ErrorCodes.RUNTIME_CRASH))
            releaseRuntimeLocked()
            ensureBoundLocked()
        }
    }

    private fun handleRuntimeDeath() {
        synchronized(this) {
            runtime = null
            binding = false
            current?.let { finishLocked(error(it, ErrorCodes.RUNTIME_CRASH)) }
            if (queue.isNotEmpty()) ensureBoundLocked()
        }
    }

    private fun finishLocked(response: RuntimeResponse) {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val work = current ?: return
        current = null
        work.callback(response)
        if (queue.isNotEmpty()) {
            dispatchLocked()
        } else {
            val release = Runnable { synchronized(this@RuntimeBroker) { if (current == null && queue.isEmpty()) releaseRuntimeLocked() } }
            idleRelease = release
            handler.postDelayed(release, RuntimePolicy.IDLE_RELEASE_MS)
        }
    }

    private fun releaseRuntimeLocked() {
        idleRelease?.let(handler::removeCallbacks)
        idleRelease = null
        runtime = null
        if (binding) binding = false
        try { appContext.unbindService(connection) } catch (_: Exception) { }
    }

    private fun error(work: Work, code: String) = RuntimeResponse(
        work.internalRequestId, "ERROR", null, null, code, 0L, work.forceReload, 0L,
    )

    private companion object {
        const val MAX_QUEUE = 8
        val SURFACES = setOf("notification", "gateway", "macro", "check")
    }
}
