package de.x0bubbuff.needlebub

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import de.x0bubbuff.needlebub.notifications.AutomationSettings
import de.x0bubbuff.needlebub.notifications.AutomaticOtpState
import de.x0bubbuff.needlebub.developer.DiagnosticEntry
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.otp.OtpPostprocessor
import de.x0bubbuff.needlebub.otp.OtpOutcome
import de.x0bubbuff.needlebub.packs.PackManifest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

@CapacitorPlugin(
    name = "NeedleBub",
    permissions = [Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class NeedleBubPlugin : Plugin() {
    private val executor = Executors.newSingleThreadExecutor()
    private val app get() = context.applicationContext as NeedleBubApplication
    private val automation get() = AutomationSettings(context)

    override fun handleOnDestroy() {
        app.adbCaptureAccess.revoke()
        executor.shutdown()
        super.handleOnDestroy()
    }

    @PluginMethod
    fun status(call: PluginCall) {
        app.packUpdates.checkIfStale()
        val selected = automation.selectedPackages
        val packInstalled = app.packStore.officialOtp() != null
        val notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        val notificationPermission = getPermissionState("notifications").toString() == "granted"
        val configured = AutomaticOtpState.configured(
            packInstalled,
            notificationAccess,
            notificationPermission,
            automation.allApps || selected.isNotEmpty(),
        )
        call.resolve(JSObject()
            .put("otpPackInstalled", packInstalled)
            .put("notificationAccess", notificationAccess)
            .put("notificationPermission", notificationPermission)
            .put("allApps", automation.allApps)
            .put("selectedAppCount", selected.size)
            .put("automaticOtpConfigured", configured)
            .put("automaticOtpEnabled", automation.enabled)
            .put("macroDroidInstalled", packageInstalled("com.arlosoft.macrodroid")))
    }

    @PluginMethod
    fun setAutomaticOtpEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled is required")
        automation.enabled = enabled
        call.resolve()
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        requestPermissionForAlias("notifications", call, "notificationPermissionCallback")
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().put("state", getPermissionState("notifications").toString()))
    }

    @PluginMethod
    fun openNotificationAccess(call: PluginCall) {
        activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        call.resolve()
    }

    @PluginMethod
    fun openNotificationSettings(call: PluginCall) {
        activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        call.resolve()
    }

    @PluginMethod
    fun openMacroDroid(call: PluginCall) {
        val intent = context.packageManager.getLaunchIntentForPackage(MACRODROID_PACKAGE)
            ?: return call.reject("MacroDroid is not installed")
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun runColdModelCheck(call: PluginCall) {
        val pack = app.packStore.officialOtp()
        if (pack == null) {
            call.resolve(coldCheckResult(false, ErrorCodes.PACK_NOT_FOUND, 0L, true, 0L))
            return
        }
        val query = OtpPostprocessor.formatQuery(CHECK_SENDER, CHECK_MESSAGE)
        val requestId = "check-${UUID.randomUUID()}"
        val accepted = app.runtime.infer(
            requestId,
            pack,
            query,
            COLD_CHECK_TIMEOUT_MS,
            surface = "check",
            forceReload = true,
        ) { response ->
            val postprocessed = if (
                response.status == "OK" &&
                response.toolName == "extract_otp" &&
                response.resultJson != null &&
                response.errorCode == null
            ) {
                val calls = JSONArray().put(JSONObject()
                    .put("name", response.toolName)
                    .put("arguments", JSONObject(response.resultJson)))
                OtpPostprocessor.process(query, calls.toString())
            } else null
            val passed = postprocessed is OtpOutcome.Accepted && postprocessed.result.code == CHECK_CODE
            val errorCode = response.errorCode ?: if (passed) null else ErrorCodes.NO_MATCH
            call.resolve(coldCheckResult(
                passed,
                errorCode,
                response.durationMs,
                response.coldLoad,
                response.pssKb,
            ))
        }
        if (!accepted) call.resolve(coldCheckResult(false, ErrorCodes.BUSY, 0L, true, 0L))
    }

    @PluginMethod
    fun listPacks(call: PluginCall) {
        val packs = JSArray()
        app.packStore.list().forEach { pack ->
            packs.put(JSObject()
                .put("id", pack.manifest.id)
                .put("version", pack.manifest.version)
                .put("name", pack.manifest.name)
                .put("author", pack.manifest.author)
                .put("description", pack.manifest.description)
                .put("license", pack.manifest.license)
                .put("verified", pack.verified)
                .put("active", app.packStore.officialOtp()?.manifest?.version == pack.manifest.version)
                .put("surfaces", JSArray(pack.manifest.surfaces.toList()))
                .put("outputs", JSArray(pack.manifest.outputs.keys.toList())))
        }
        call.resolve(JSObject().put("packs", packs))
    }

    @PluginMethod
    fun removePack(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        val version = call.getString("version") ?: return call.reject("version is required")
        call.resolve(JSObject().put("removed", app.packStore.remove(id, version)))
    }

    @PluginMethod
    fun pickPack(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        }
        startActivityForResult(call, intent, "packPicked")
    }

    @ActivityCallback
    private fun packPicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("No pack selected")
        executor.execute {
            val temporary = File(context.cacheDir, "import-${UUID.randomUUID()}.nbpack")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output -> copyBounded(input, output, MAX_ARCHIVE_BYTES) } }
                    ?: error("Could not open selected pack")
                val pack = app.packStore.install(temporary, verified = false)
                call.resolve(JSObject().put("id", pack.manifest.id).put("version", pack.manifest.version).put("verified", false))
            } catch (error: Exception) {
                call.reject(error.message ?: "Pack import failed")
            } finally {
                temporary.delete()
            }
        }
    }

    @PluginMethod
    fun catalogue(call: PluginCall) {
        executor.execute {
            try {
                val raw = de.x0bubbuff.needlebub.updates.OfficialCatalogue(context).embedded().first
                call.resolve(JSObject(raw.toString()))
            } catch (error: Exception) {
                call.reject(error.message ?: "Embedded catalogue is invalid")
            }
        }
    }

    @PluginMethod
    fun installCataloguePack(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        if (id != de.x0bubbuff.needlebub.updates.OfficialCatalogue.OFFICIAL_OTP_ID) {
            return call.reject("Pack not found in official catalogue")
        }
        app.packUpdates.checkNow(allowMetered = true) {
            val installed = app.packStore.officialOtp()
            if (installed == null) call.reject(app.packUpdates.status().lastError ?: "Pack download failed")
            else call.resolve(JSObject().put("id", installed.manifest.id).put("version", installed.manifest.version).put("verified", true))
        }
    }

    @PluginMethod
    fun getPackUpdateStatus(call: PluginCall) {
        app.packUpdates.checkIfStale()
        call.resolve(packUpdateStatus())
    }

    @PluginMethod
    fun setAutomaticPackUpdates(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled is required")
        app.packUpdates.setEnabled(enabled)
        call.resolve()
    }

    @PluginMethod
    fun setPackUpdateNetworkPolicy(call: PluginCall) {
        val allowMetered = call.getBoolean("allowMetered") ?: return call.reject("allowMetered is required")
        app.packUpdates.setAllowMetered(allowMetered)
        call.resolve()
    }

    @PluginMethod
    fun checkForPackUpdates(call: PluginCall) {
        val allowMetered = call.getBoolean("allowMetered", true) ?: true
        app.packUpdates.checkNow(allowMetered = allowMetered) { call.resolve(packUpdateStatus()) }
    }

    @PluginMethod
    fun listNotificationApps(call: PluginCall) {
        executor.execute {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val selected = automation.selectedPackages
            val apps = JSArray()
            context.packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
                .forEach { info ->
                    apps.put(JSObject()
                        .put("packageName", info.activityInfo.packageName)
                        .put("label", info.loadLabel(context.packageManager).toString())
                        .put("selected", info.activityInfo.packageName in selected))
                }
            call.resolve(JSObject().put("apps", apps))
        }
    }

    @PluginMethod
    fun saveNotificationApps(call: PluginCall) {
        val packages = call.getArray("packages")?.toList<String>()?.toSet().orEmpty()
        automation.selectedPackages = packages.filter { it.matches(Regex("^[A-Za-z0-9_.]+\$")) }.toSet()
        automation.allApps = call.getBoolean("allApps", false) == true
        call.resolve()
    }

    @PluginMethod
    fun developerDataStatus(call: PluginCall) {
        val summary = app.developerDataStore.summary()
        call.resolve(JSObject()
            .put("unlocked", app.developerDataSettings.unlocked)
            .put("labAuthenticated", app.developerDataSettings.labAuthenticated)
            .put("captureEnabled", app.developerDataSettings.captureEnabled)
            .put("recordCount", summary.count)
            .put("storedBytes", summary.storedBytes)
            .put("oldestAt", summary.oldestAt ?: JSONObject.NULL)
            .put("adbPullExpiresAt", app.adbCaptureAccess.expiresAtEpochMs() ?: JSONObject.NULL))
    }

    @PluginMethod
    fun unlockDeveloperData(call: PluginCall) {
        app.developerDataSettings.unlocked = true
        app.developerDataStore.addDiagnostic(DiagnosticEntry(
            id = 0, createdAt = System.currentTimeMillis(), packageName = context.packageName,
            category = null, stage = "developer", pack = null, status = "unlocked",
            errorCode = null, durationMs = null, pssKb = null, coldLoad = null,
        ))
        call.resolve(JSObject().put("unlocked", true))
    }

    @PluginMethod
    fun authenticateDeveloperLab(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        val host = activity as? FragmentActivity ?: return call.reject("Authentication host is unavailable")
        host.runOnUiThread {
            if (host.isFinishing || host.isDestroyed) {
                call.reject("Authentication host is unavailable")
                return@runOnUiThread
            }
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    app.developerDataSettings.labAuthenticated = true
                    call.resolve(JSObject().put("authenticated", true))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    call.reject(if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) "Device authentication was cancelled" else errString.toString())
                }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Open Notification Lab")
                .setSubtitle("View encrypted notification and model traces")
                .setAllowedAuthenticators(authenticators)
                .build())
        }
    }

    @PluginMethod
    fun closeDeveloperLab(call: PluginCall) {
        app.developerDataSettings.labAuthenticated = false
        app.adbCaptureAccess.revoke()
        call.resolve()
    }

    @PluginMethod
    fun grantAdbCapturePull(call: PluginCall) {
        if (!app.developerDataSettings.unlocked || !app.developerDataSettings.labAuthenticated) {
            return call.reject("Notification Lab authentication is required")
        }
        app.adbCaptureAccess.grant()
        call.resolve(JSObject().put(
            "expiresAt",
            app.adbCaptureAccess.expiresAtEpochMs() ?: JSONObject.NULL,
        ))
    }

    @PluginMethod
    fun revokeAdbCapturePull(call: PluginCall) {
        app.adbCaptureAccess.revoke()
        call.resolve()
    }

    @PluginMethod
    fun listNotificationRecords(call: PluginCall) {
        if (!app.developerDataSettings.unlocked || !app.developerDataSettings.labAuthenticated) {
            return call.reject("Notification Lab authentication is required")
        }
        executor.execute {
            val (records, nextCursor) = app.developerDataStore.captureSummaries(
                call.getInt("limit", 30) ?: 30,
                call.getLong("cursor"),
                call.getString("filter"),
            )
            if (!app.developerDataSettings.labAuthenticated) {
                call.reject("Notification Lab authentication expired")
                return@execute
            }
            call.resolve(JSObject()
                .put("records", JSArray(records))
                .put("nextCursor", nextCursor ?: JSONObject.NULL))
        }
    }

    @PluginMethod
    fun getNotificationRecord(call: PluginCall) {
        if (!app.developerDataSettings.unlocked || !app.developerDataSettings.labAuthenticated) {
            return call.reject("Notification Lab authentication is required")
        }
        val id = call.getString("id") ?: return call.reject("id is required")
        executor.execute {
            val record = app.developerDataStore.capture(id)
            if (!app.developerDataSettings.labAuthenticated) {
                call.reject("Notification Lab authentication expired")
            } else if (record == null) {
                call.reject("Notification record was not found")
            } else {
                call.resolve(JSObject(record.toString()))
            }
        }
    }

    @PluginMethod
    fun setNotificationCaptureEnabled(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        val enabled = call.getBoolean("enabled") ?: return call.reject("enabled is required")
        app.developerDataSettings.captureEnabled = enabled
        app.developerDataStore.addDiagnostic(DiagnosticEntry(
            id = 0, createdAt = System.currentTimeMillis(), packageName = context.packageName,
            category = null, stage = "capture", pack = null,
            status = if (enabled) "enabled" else "disabled", errorCode = null,
            durationMs = null, pssKb = null, coldLoad = null,
        ))
        call.resolve()
    }

    @PluginMethod
    fun exportNotificationCapture(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        val passphrase = call.getString("passphrase") ?: return call.reject("passphrase is required")
        if (passphrase.length < 12) return call.reject("Passphrase must contain at least 12 characters")
        requireDeviceAuthentication(call, "Export captured notifications", "Confirm before decrypting the local capture", "captureExportAuthenticated")
    }

    @ActivityCallback
    private fun captureExportAuthenticated(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        if (result.resultCode != Activity.RESULT_OK) return call.reject("Device authentication was cancelled")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "needlebub-${System.currentTimeMillis()}.nbcapture")
        }
        startActivityForResult(call, intent, "captureExportPicked")
    }

    @ActivityCallback
    private fun captureExportPicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("No export location selected")
        val passphrase = call.getString("passphrase")?.toCharArray() ?: return call.reject("passphrase is required")
        val deleteAfterExport = call.getBoolean("deleteAfterExport", true) == true
        executor.execute {
            try {
                val (payload, count) = app.developerDataStore.encryptedExport(passphrase)
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(payload) }
                    ?: error("Could not open the export destination")
                val deleted = if (deleteAfterExport) app.developerDataStore.clearCaptures() else 0
                call.resolve(JSObject().put("exported", count).put("deleted", deleted == count && count > 0))
            } catch (error: Exception) {
                call.reject(error.message ?: "Capture export failed")
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    @PluginMethod
    fun clearNotificationCapture(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        requireDeviceAuthentication(call, "Clear captured notifications", "This permanently removes the encrypted capture", "captureClearAuthenticated")
    }

    @ActivityCallback
    private fun captureClearAuthenticated(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        if (result.resultCode != Activity.RESULT_OK) return call.reject("Device authentication was cancelled")
        call.resolve(JSObject().put("removed", app.developerDataStore.clearCaptures()))
    }

    @PluginMethod
    fun listPersistentDiagnostics(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        val entries = JSArray()
        app.developerDataStore.diagnostics(call.getInt("limit", 100) ?: 100).forEach { entry ->
            entries.put(JSObject()
                .put("id", entry.id)
                .put("createdAt", entry.createdAt)
                .put("packageName", entry.packageName ?: JSONObject.NULL)
                .put("category", entry.category ?: JSONObject.NULL)
                .put("stage", entry.stage)
                .put("pack", entry.pack ?: JSONObject.NULL)
                .put("status", entry.status)
                .put("errorCode", entry.errorCode ?: JSONObject.NULL)
                .put("durationMs", entry.durationMs ?: JSONObject.NULL)
                .put("pssKb", entry.pssKb ?: JSONObject.NULL)
                .put("coldLoad", entry.coldLoad ?: JSONObject.NULL))
        }
        call.resolve(JSObject().put("entries", entries))
    }

    @PluginMethod
    fun clearPersistentDiagnostics(call: PluginCall) {
        if (!app.developerDataSettings.unlocked) return call.reject("Developer data is locked")
        call.resolve(JSObject().put("removed", app.developerDataStore.clearDiagnostics()))
    }

    @PluginMethod
    fun getFeatureActivity(call: PluginCall) {
        val featureId = call.getString("featureId") ?: return call.reject("featureId is required")
        val days = (call.getInt("days", 7) ?: 7).coerceIn(1, 7)
        val summary = try {
            app.featureActivity.summary(featureId, days)
        } catch (error: IllegalArgumentException) {
            return call.reject(error.message ?: "Feature ID is invalid")
        }
        call.resolve(JSObject()
            .put("featureId", summary.featureId)
            .put("days", summary.days)
            .put("todayOtp", summary.todayOtp)
            .put("todayRejected", summary.todayRejected)
            .put("todayErrors", summary.todayErrors)
            .put("todaySuppressed", summary.todaySuppressed)
            .put("todayNotRun", summary.todayNotRun)
            .put("totalOtp", summary.totalOtp)
            .put("totalRejected", summary.totalRejected)
            .put("totalErrors", summary.totalErrors)
            .put("totalSuppressed", summary.totalSuppressed)
            .put("totalNotRun", summary.totalNotRun)
            .put("completedInferenceCount", summary.completedInferenceCount)
            .put("averageDurationMs", summary.averageDurationMs ?: JSONObject.NULL)
            .put("lastActivityAt", summary.lastActivityAt ?: JSONObject.NULL))
    }

    @PluginMethod
    fun resetFeatureActivity(call: PluginCall) {
        val featureId = call.getString("featureId")
        try {
            app.featureActivity.reset(featureId)
        } catch (error: IllegalArgumentException) {
            return call.reject(error.message ?: "Feature ID is invalid")
        }
        call.resolve()
    }

    @PluginMethod
    fun diagnostics(call: PluginCall) {
        val macroVersion = try {
            context.packageManager.getPackageInfo("com.arlosoft.macrodroid", 0).versionName
        } catch (_: Exception) { null }
        call.resolve(JSObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("engineAbi", PackManifest.ENGINE_ABI)
            .put("supportedAbi", Build.SUPPORTED_ABIS.joinToString())
            .put("installedPackCount", app.packStore.list().size)
            .put("macroDroidVersion", macroVersion ?: JSONObject.NULL)
            .put("minSdk", 31)
            .put("targetSdk", 36)
            .put("privacy", if (app.developerDataSettings.captureEnabled) "Developer capture is storing encrypted notification records locally" else "Inputs and results are memory-only"))
    }

    private fun requireDeviceAuthentication(call: PluginCall, title: String, description: String, callback: String) {
        val manager = context.getSystemService(KeyguardManager::class.java)
        if (!manager.isDeviceSecure) return call.reject("A secure device lock is required")
        val intent = manager.createConfirmDeviceCredentialIntent(title, description)
            ?: return call.reject("Device authentication is unavailable")
        startActivityForResult(call, intent, callback)
    }

    private fun coldCheckResult(
        passed: Boolean,
        errorCode: String?,
        durationMs: Long,
        coldLoad: Boolean,
        pssKb: Long,
    ) = JSObject()
        .put("passed", passed)
        .put("errorCode", errorCode ?: JSONObject.NULL)
        .put("durationMs", durationMs)
        .put("coldLoad", coldLoad)
        .put("pssKb", pssKb)

    private fun packUpdateStatus(): JSObject {
        val status = app.packUpdates.status()
        return JSObject()
            .put("enabled", status.enabled)
            .put("networkPolicy", if (status.allowMetered) "any" else "unmetered")
            .put("state", status.state)
            .put("currentVersion", status.currentVersion ?: JSONObject.NULL)
            .put("availableVersion", status.availableVersion ?: JSONObject.NULL)
            .put("lastCheckedAt", status.lastCheckedAt ?: JSONObject.NULL)
            .put("lastUpdatedAt", status.lastUpdatedAt ?: JSONObject.NULL)
            .put("lastError", status.lastError ?: JSONObject.NULL)
    }

    private fun packageInstalled(name: String): Boolean = try {
        context.packageManager.getApplicationInfo(name, 0)
        true
    } catch (_: Exception) { false }

    private fun requireImmutableHttpsUrl(raw: String) {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host !in ALLOWED_ARTIFACT_HOSTS) error("Catalogue URL host is not allowed")
        val immutableHf = Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(uri.path)
        val immutableGithub = "/releases/download/" in uri.path
        if (!immutableHf && !immutableGithub) error("Catalogue URL is not immutable")
    }

    private fun download(rawUrl: String, target: File, expectedSize: Long) {
        if (expectedSize !in 1..MAX_ARCHIVE_BYTES) error("Catalogue pack size is invalid")
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.inputStream.use { input -> target.outputStream().use { output -> copyBounded(input, output, expectedSize) } }
        connection.disconnect()
        if (target.length() != expectedSize) error("Downloaded pack size failed")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) error("Pack archive is larger than allowed")
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MACRODROID_PACKAGE = "com.arlosoft.macrodroid"
        const val COLD_CHECK_TIMEOUT_MS = 5_000L
        const val CHECK_SENDER = "Needle Bank"
        const val CHECK_CODE = "739241"
        const val CHECK_MESSAGE = "Your login code is $CHECK_CODE. It expires in 10 minutes."
        val ALLOWED_ARTIFACT_HOSTS = setOf("github.com", "objects.githubusercontent.com", "huggingface.co", "cdn-lfs.hf.co", "cas-bridge.xethub.hf.co")
        const val MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L
    }
}
