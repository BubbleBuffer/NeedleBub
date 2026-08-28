package de.x0bubbuff.needlebub.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCaptureAccessTest {
    @Test
    fun grantIsShortLivedAndRevocable() {
        var now = 1_000L
        val access = AdbCaptureAccess(nowElapsedRealtime = { now })

        access.grant()
        assertTrue(access.isActive())

        now += AdbCaptureAccess.GRANT_DURATION_MS
        assertFalse(access.isActive())

        now = 2_000L
        access.grant()
        access.revoke()
        assertFalse(access.isActive())
    }

    @Test
    fun onlyAndroidShellCanReadAnActiveAuthenticatedGrant() {
        val access = AdbCaptureAccess(nowElapsedRealtime = { 5_000L })
        access.grant()

        assertTrue(access.canRead(
            callerUid = AdbCaptureAccess.SHELL_UID,
            developerUnlocked = true,
            labAuthenticated = true,
        ))
        assertFalse(access.canRead(
            callerUid = 12_345,
            developerUnlocked = true,
            labAuthenticated = true,
        ))
        assertFalse(access.canRead(
            callerUid = AdbCaptureAccess.SHELL_UID,
            developerUnlocked = false,
            labAuthenticated = true,
        ))
        assertFalse(access.canRead(
            callerUid = AdbCaptureAccess.SHELL_UID,
            developerUnlocked = true,
            labAuthenticated = false,
        ))
    }

    @Test
    fun reportsAnAuthoritativeWallClockExpiryOnlyWhileActive() {
        var elapsed = 5_000L
        val access = AdbCaptureAccess(nowElapsedRealtime = { elapsed })

        access.grant()
        assertEquals(1_700_000_600_000L, access.expiresAtEpochMs(1_700_000_000_000L))

        elapsed += AdbCaptureAccess.GRANT_DURATION_MS
        assertNull(access.expiresAtEpochMs(1_700_000_600_000L))
    }
}
