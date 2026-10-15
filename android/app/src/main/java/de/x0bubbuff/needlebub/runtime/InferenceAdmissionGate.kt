package de.x0bubbuff.needlebub.runtime

/**
 * Atomic handshake between admitting a request and cancelling it.
 *
 * The isolated service used to check its cancelled set and publish the active request id
 * in two separate steps, so a cancel landing between them was recorded but never aborted
 * the native run. Both sides now share one lock, which makes exactly one of two outcomes
 * possible: either the start is suppressed because the cancel was already recorded, or the
 * cancel sees the request as active and asks for the process to be terminated.
 */
class InferenceAdmissionGate {
    private val lock = Any()
    private val cancelled = HashSet<String>()
    private var active: String? = null

    /** True when the request may start running. */
    fun admit(requestId: String): Boolean {
        synchronized(lock) {
            if (cancelled.remove(requestId)) return false
            active = requestId
            return true
        }
    }

    /**
     * True when the caller must terminate the process to abort the run.
     *
     * A false result means the request had not started yet; it will never start now.
     */
    fun cancel(requestId: String): Boolean {
        synchronized(lock) {
            if (active == requestId) {
                active = null
                return true
            }
            cancelled += requestId
            return false
        }
    }

    /**
     * Ends the run and reports whether the result may still be delivered.
     *
     * A cancel that arrived after the run finished suppresses the callback instead of
     * letting a settled request answer twice.
     */
    fun finish(requestId: String): Boolean {
        synchronized(lock) {
            if (active == requestId) active = null
            return !cancelled.remove(requestId)
        }
    }

    /** True while [requestId] is the run in flight. */
    fun isActive(requestId: String): Boolean {
        synchronized(lock) {
            return active == requestId
        }
    }
}
