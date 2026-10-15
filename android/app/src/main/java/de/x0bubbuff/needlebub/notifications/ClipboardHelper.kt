package de.x0bubbuff.needlebub.notifications

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.UserManager

/**
 * Single place that writes codes to the clipboard.
 *
 * Copies are marked sensitive so the platform can hide them from clipboard previews, and a
 * failing clipboard never throws into the delivery path.
 *
 * There is deliberately no timed clearing: a background process cannot read the clipboard
 * on modern Android, and comparing text alone would also erase a newer clip that happens
 * to hold the same code. Clearing the user's clipboard is left to the platform.
 */
object ClipboardHelper {
    const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    const val LABEL = "NeedleBub code"

    fun copySensitive(context: Context, value: String, label: String = LABEL): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            false
        } else {
            val clip = ClipData.newPlainText(label, value)
            clip.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE_EXTRA, true) }
            clipboard.setPrimaryClip(clip)
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * True only when the phone is fully unlocked and no keyguard is showing.
     *
     * The user chose unlocked-only copying, which includes the swipe-only lockscreen: a
     * visible keyguard suppresses the copy even though the device is not securely locked.
     * This is a copy gate, not an authentication requirement.
     */
    fun isUnlocked(context: Context): Boolean {
        val userManager = context.getSystemService(UserManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return isUnlockedState(
            userUnlocked = userManager?.isUserUnlocked ?: false,
            keyguardLocked = keyguard?.isKeyguardLocked ?: true,
            deviceLocked = keyguard?.isDeviceLocked ?: true,
        )
    }

    /** Pure boundary of the unlock gate, so every lockscreen state stays testable. */
    fun isUnlockedState(userUnlocked: Boolean, keyguardLocked: Boolean, deviceLocked: Boolean): Boolean =
        userUnlocked && !keyguardLocked && !deviceLocked
}
