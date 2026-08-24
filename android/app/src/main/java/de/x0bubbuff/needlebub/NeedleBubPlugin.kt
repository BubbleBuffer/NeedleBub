package de.x0bubbuff.needlebub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.app.NotificationManagerCompat
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
import de.x0bubbuff.needlebub.packs.PackManifest
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
        executor.shutdown()
        super.handleOnDestroy()
    }

    @PluginMethod
    fun status(call: PluginCall) {
        val selected = automation.selectedPackages
        call.resolve(JSObject()
            .put("otpPackInstalled", app.packStore.officialOtp() != null)
            .put("notificationAccess", context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context))
            .put("notificationPermission", getPermissionState("notifications").toString() == "granted")
            .put("allApps", automation.allApps)
            .put("selectedAppCount", selected.size)
            .put("automaticOtpReady", app.packStore.officialOtp() != null && context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context) && getPermissionState("notifications").toString() == "granted" && (automation.allApps || selected.isNotEmpty()))
            .put("macroDroidInstalled", packageInstalled("com.arlosoft.macrodroid")))
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
        val raw = context.assets.open("catalogue.json").bufferedReader().use { it.readText() }
        call.resolve(JSObject(raw))
    }

    @PluginMethod
    fun installCataloguePack(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id is required")
        executor.execute {
            var temporary: File? = null
            try {
                val catalogue = JSONObject(context.assets.open("catalogue.json").bufferedReader().use { it.readText() })
                val entries = catalogue.getJSONArray("entries")
                val entry = (0 until entries.length()).asSequence().map(entries::getJSONObject).firstOrNull { it.getString("id") == id }
                    ?: error("Pack not found in official catalogue")
                if (entry.getString("engineAbi") != PackManifest.ENGINE_ABI) error("Pack needs a different Needle engine")
                val url = entry.getString("url")
                requireImmutableHttpsUrl(url)
                val expectedSize = entry.getLong("size")
                val expectedDigest = entry.getString("sha256")
                temporary = File(context.cacheDir, "download-${UUID.randomUUID()}.nbpack")
                download(url, temporary, expectedSize)
                if (!temporary.sha256().equals(expectedDigest, ignoreCase = true)) error("Downloaded pack checksum failed")
                val pack = app.packStore.install(temporary, verified = true)
                call.resolve(JSObject().put("id", pack.manifest.id).put("version", pack.manifest.version).put("verified", true))
            } catch (error: Exception) {
                call.reject(error.message ?: "Pack download failed")
            } finally {
                temporary?.delete()
            }
        }
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
    fun diagnostics(call: PluginCall) {
        val macroVersion = try {
            context.packageManager.getPackageInfo("com.arlosoft.macrodroid", 0).versionName
        } catch (_: Exception) { null }
        call.resolve(JSObject()
            .put("engineAbi", PackManifest.ENGINE_ABI)
            .put("supportedAbi", Build.SUPPORTED_ABIS.joinToString())
            .put("installedPackCount", app.packStore.list().size)
            .put("macroDroidVersion", macroVersion ?: JSONObject.NULL)
            .put("minSdk", 31)
            .put("targetSdk", 36)
            .put("privacy", "Inputs and results are memory-only"))
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
        val ALLOWED_ARTIFACT_HOSTS = setOf("github.com", "objects.githubusercontent.com", "huggingface.co", "cdn-lfs.hf.co", "cas-bridge.xethub.hf.co")
        const val MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L
    }
}
