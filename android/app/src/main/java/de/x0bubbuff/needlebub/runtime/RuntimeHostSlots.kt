package de.x0bubbuff.needlebub.runtime

/**
 * Thread-safe registry of bind slots.
 *
 * A bind runs on the control thread, the platform reports the connection on the main
 * thread, and dispatch and cancel read the binder on their own threads. Only map access is
 * guarded: bindService, unbindService and every binder call happen outside this class, so
 * a blocked platform call can never hold the slot lock.
 *
 * A slot that was released never accepts a binder again, so a late `onServiceConnected`
 * after an unbind cannot reinsert a stale binder or leak a slot.
 */
class RuntimeHostSlots<T : Any, C : Any> {
    class Slot<T, C>(val generation: Int) {
        @Volatile
        var connection: C? = null

        @Volatile
        var runtime: T? = null

        @Volatile
        var released = false
    }

    private val lock = Any()
    private val slots = HashMap<Int, Slot<T, C>>()

    /** Registers a slot; null when the generation is already tracked. */
    fun open(generation: Int): Slot<T, C>? {
        synchronized(lock) {
            if (slots.containsKey(generation)) return null
            val slot = Slot<T, C>(generation)
            slots[generation] = slot
            return slot
        }
    }

    fun setConnection(generation: Int, connection: C): Boolean {
        synchronized(lock) {
            val slot = slots[generation] ?: return false
            if (slot.released) return false
            slot.connection = connection
            return true
        }
    }

    /** Stores the binder only while the slot is live; false means it was released. */
    fun attach(generation: Int, runtime: T): Boolean {
        synchronized(lock) {
            val slot = slots[generation] ?: return false
            if (slot.released) return false
            slot.runtime = runtime
            return true
        }
    }

    fun isLive(generation: Int): Boolean {
        synchronized(lock) {
            return slots[generation]?.released == false
        }
    }

    fun runtime(generation: Int): T? {
        synchronized(lock) {
            val slot = slots[generation] ?: return null
            return if (slot.released) null else slot.runtime
        }
    }

    /** Marks the slot released and hands it back so the caller can unbind outside the lock. */
    fun release(generation: Int): Slot<T, C>? {
        synchronized(lock) {
            val slot = slots.remove(generation) ?: return null
            slot.released = true
            slot.runtime = null
            return slot
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return slots.size
        }
    }
}
