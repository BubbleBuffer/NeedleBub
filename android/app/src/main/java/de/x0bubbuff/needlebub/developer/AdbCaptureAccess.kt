package de.x0bubbuff.needlebub.developer

import android.os.SystemClock

class AdbCaptureAccess(
    private val nowElapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    @Volatile
    private var expiresAtElapsedRealtime: Long = 0

    fun grant(): Long {
        val expiresAt = nowElapsedRealtime() + GRANT_DURATION_MS
        expiresAtElapsedRealtime = expiresAt
        return expiresAt
    }

    fun revoke() {
        expiresAtElapsedRealtime = 0
    }

    fun isActive(): Boolean = expiresAtElapsedRealtime > nowElapsedRealtime()

    fun remainingMs(): Long = (expiresAtElapsedRealtime - nowElapsedRealtime()).coerceAtLeast(0)

    fun expiresAtEpochMs(nowEpochMs: Long = System.currentTimeMillis()): Long? {
        val remaining = remainingMs()
        return if (remaining > 0) nowEpochMs + remaining else null
    }

    fun canRead(
        callerUid: Int,
        developerUnlocked: Boolean,
        labAuthenticated: Boolean,
    ): Boolean = callerUid == SHELL_UID &&
        developerUnlocked &&
        labAuthenticated &&
        isActive()

    companion object {
        const val SHELL_UID = 2_000
        const val GRANT_DURATION_MS = 10L * 60 * 1_000
    }
}
