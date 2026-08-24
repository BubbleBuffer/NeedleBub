package de.x0bubbuff.needlebub.notifications

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

class AuthenticatedCopyActivity : Activity() {
    private var code: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        code = intent.getStringExtra(OtpResultNotification.EXTRA_CODE)?.takeIf { it.matches(Regex("^[A-Za-z0-9]{4,8}\$")) }
        if (code == null) return finish()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            android.widget.Toast.makeText(this, "Set a device screen lock before copying NeedleBub codes.", android.widget.Toast.LENGTH_LONG).show()
            return finish()
        }
        val confirmation = keyguard.createConfirmDeviceCredentialIntent("Copy NeedleBub code", "Authenticate to place the code on your clipboard")
        if (confirmation == null) finish() else startActivityForResult(confirmation, REQUEST_AUTH)
    }

    @Deprecated("Activity result is used for the system credential screen on Android 12+.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTH && resultCode == RESULT_OK) copyAndFinish() else finish()
    }

    private fun copyAndFinish() {
        val value = code ?: return finish()
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("NeedleBub code", value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        getSystemService(android.app.NotificationManager::class.java).cancel(OtpResultNotification.NOTIFICATION_ID)
        Handler(Looper.getMainLooper()).postDelayed({
            val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (current == value) clipboard.clearPrimaryClip()
        }, 60_000L)
        finish()
    }

    private companion object { const val REQUEST_AUTH = 42 }
}
