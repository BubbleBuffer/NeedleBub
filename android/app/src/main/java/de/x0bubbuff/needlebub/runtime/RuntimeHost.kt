package de.x0bubbuff.needlebub.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackStore

/** Callbacks the platform reports back to the broker, always carrying their generation. */
interface RuntimeHostListener {
    fun onConnected(generation: Int)

    fun onServiceLost(generation: Int)

    fun onBindFailed(generation: Int)
}

/**
 * Everything the broker needs from the platform.
 *
 * Keeping bind, unbind, dispatch and cancel behind this seam means the broker never holds
 * a lock while a binder call is in flight, and the adapter can be tested on the JVM with a
 * host that blocks, delays or never connects.
 */
interface RuntimeHost {
    fun bind(generation: Int, listener: RuntimeHostListener): Boolean

    fun unbind(generation: Int)

    fun dispatch(
        request: RuntimeRequest,
        pack: InstalledPack,
        generation: Int,
        onResult: (RuntimeResponse) -> Unit,
    )

    fun cancel(requestId: String, generation: Int)
}

/** Real host: binds [IsolatedInferenceService] and opens the pack model read only. */
class AndroidRuntimeHost(context: Context) : RuntimeHost {
    private val appContext = context.applicationContext
    private val packStore = PackStore(appContext)
    private val slots = RuntimeHostSlots<IIsolatedRuntime, ServiceConnection>()

    override fun bind(generation: Int, listener: RuntimeHostListener): Boolean {
        if (slots.open(generation) == null) return true
        val connection = connection(generation, listener)
        if (!slots.setConnection(generation, connection)) return true
        val bound = try {
            appContext.bindService(
                Intent(appContext, IsolatedInferenceService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: Exception) {
            false
        }
        if (!bound) {
            release(generation)
            return false
        }
        return true
    }

    override fun unbind(generation: Int) {
        release(generation)
    }

    override fun dispatch(
        request: RuntimeRequest,
        pack: InstalledPack,
        generation: Int,
        onResult: (RuntimeResponse) -> Unit,
    ) {
        val runtime = slots.runtime(generation) ?: error("runtime generation $generation is not bound")
        val callback = object : IRuntimeCallback.Stub() {
            override fun onResult(response: RuntimeResponse) = onResult(response)
        }
        // The descriptor and the binder call stay outside every lock.
        ParcelFileDescriptor.open(packStore.modelFile(pack), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            runtime.infer(request, descriptor, packStore.toolsJson(pack), callback)
        }
    }

    override fun cancel(requestId: String, generation: Int) {
        slots.runtime(generation)?.cancel(requestId)
    }

    /** Test seam: true while the generation still holds a live bind slot. */
    fun slotIsLive(generation: Int): Boolean = slots.isLive(generation)

    /** Test seam: how many bind slots are tracked. */
    fun slotCount(): Int = slots.size()

    private fun connection(generation: Int, listener: RuntimeHostListener) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val runtime = service?.let { IIsolatedRuntime.Stub.asInterface(it) }
            if (runtime == null) {
                release(generation)
                listener.onBindFailed(generation)
                return
            }
            if (!slots.attach(generation, runtime)) {
                // Released while the bind was in flight: never reinsert this binder.
                release(generation)
                return
            }
            val linked = try {
                service.linkToDeath({ listener.onServiceLost(generation) }, 0)
                true
            } catch (_: Exception) {
                false
            }
            if (!linked || !slots.isLive(generation)) {
                release(generation)
                listener.onServiceLost(generation)
                return
            }
            listener.onConnected(generation)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            release(generation)
            listener.onServiceLost(generation)
        }

        override fun onBindingDied(name: ComponentName?) {
            release(generation)
            listener.onServiceLost(generation)
        }

        override fun onNullBinding(name: ComponentName?) {
            release(generation)
            listener.onBindFailed(generation)
        }
    }

    /** Releases the slot under the lock, then unbinds the platform connection outside it. */
    private fun release(generation: Int) {
        val slot = slots.release(generation) ?: return
        val connection = slot.connection ?: return
        try {
            appContext.unbindService(connection)
        } catch (_: Exception) {
            // Already unbound by the system.
        }
    }
}
