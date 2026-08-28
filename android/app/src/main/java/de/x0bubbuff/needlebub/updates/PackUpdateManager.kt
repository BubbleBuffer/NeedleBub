package de.x0bubbuff.needlebub.updates

import android.content.Context
import android.net.ConnectivityManager
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackStore
import de.x0bubbuff.needlebub.otp.OtpOutcome
import de.x0bubbuff.needlebub.otp.OtpPostprocessor
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PackUpdateManager(context: Context, private val packStore: PackStore, private val runtime: RuntimeBroker) {
    private val appContext = context.applicationContext
    private val state = PackUpdateState(appContext)
    private val catalogue = OfficialCatalogue(appContext)
    private val executor = Executors.newSingleThreadExecutor()

    fun status(): PackUpdateStatus = state.read(packStore.officialOtp()?.manifest?.version)

    fun setEnabled(enabled: Boolean) {
        state.enabled = enabled
        PackUpdateScheduler.sync(appContext, enabled, state.allowMetered)
        if (!enabled) state.record("idle")
    }

    fun setAllowMetered(allowMetered: Boolean) {
        state.allowMetered = allowMetered
        PackUpdateScheduler.sync(appContext, state.enabled, allowMetered)
    }

    fun schedule() = PackUpdateScheduler.sync(appContext, state.enabled, state.allowMetered)

    fun checkIfStale() {
        val status = status()
        if (state.enabled && (status.lastCheckedAt == null || System.currentTimeMillis() - status.lastCheckedAt >= FOREGROUND_STALE_MS)) {
            checkNow()
        }
    }

    fun checkNow(allowMetered: Boolean? = null, onComplete: (() -> Unit)? = null) {
        executor.execute {
            try {
                performUpdate(allowMetered ?: state.allowMetered)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun performUpdate(allowMetered: Boolean) {
        state.record("checking")
        val entry = try {
            catalogue.remote().second
        } catch (_: Exception) {
            try {
                catalogue.embedded().second
            } catch (_: Exception) {
                state.record("failed", error = "CATALOGUE_SIGNATURE_INVALID", checked = true)
                return
            }
        }
        val current = packStore.officialOtp()
        when (PackUpdatePolicy.decide(
            current?.manifest?.version,
            entry.version,
            appContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered,
            allowMetered,
        )) {
            UpdateAction.UP_TO_DATE -> {
                state.record("up_to_date", checked = true)
                return
            }
            UpdateAction.WAIT_FOR_WIFI -> {
                state.record("waiting_for_wifi", availableVersion = entry.version, checked = true)
                return
            }
            UpdateAction.DOWNLOAD -> state.record("available", availableVersion = entry.version, checked = true)
        }
        val existing = packStore.list().firstOrNull {
            it.verified && it.manifest.id == entry.id && it.manifest.version == entry.version
        }
        val target = existing ?: downloadAndInstall(entry) ?: return
        state.record("health_check", availableVersion = entry.version)
        if (!healthCheck(target)) {
            if (existing == null) packStore.remove(target.manifest.id, target.manifest.version)
            state.record("failed", availableVersion = entry.version, error = "HEALTH_CHECK_FAILED")
            return
        }
        if (!packStore.activateOfficial(target.manifest.version)) {
            state.record("failed", availableVersion = entry.version, error = "ACTIVATION_FAILED")
            return
        }
        state.record("up_to_date", updated = true)
    }

    private fun downloadAndInstall(entry: OfficialCatalogueEntry): InstalledPack? {
        state.record("downloading", availableVersion = entry.version)
        val temporary = File(appContext.cacheDir, "update-${UUID.randomUUID()}.nbpack")
        return try {
            download(entry.url, temporary, entry.size)
            if (!temporary.sha256().equals(entry.sha256, ignoreCase = true)) {
                state.record("failed", availableVersion = entry.version, error = "ARTIFACT_MISMATCH")
                null
            } else {
                state.record("verifying", availableVersion = entry.version)
                packStore.install(temporary, verified = true)
            }
        } catch (_: Exception) {
            state.record("failed", availableVersion = entry.version, error = "DOWNLOAD_FAILED")
            null
        } finally {
            temporary.delete()
        }
    }

    private fun healthCheck(pack: InstalledPack): Boolean {
        val positiveQuery = OtpPostprocessor.formatQuery(CHECK_SENDER, CHECK_MESSAGE)
        val positive = infer(pack, positiveQuery, forceReload = true) ?: return false
        if (positive.status != "OK" || positive.toolName != "extract_otp" || positive.resultJson == null) return false
        val calls = JSONArray().put(JSONObject().put("name", positive.toolName).put("arguments", JSONObject(positive.resultJson)))
        val accepted = OtpPostprocessor.process(positiveQuery, calls.toString())
        if (accepted !is OtpOutcome.Accepted || accepted.result.code != CHECK_CODE) return false
        val negative = infer(pack, OtpPostprocessor.formatQuery("YouTube", "Avoiding Bot Detection by User0332"), forceReload = false)
            ?: return false
        return negative.status == "NO_MATCH"
    }

    private fun infer(pack: InstalledPack, query: String, forceReload: Boolean): de.x0bubbuff.needlebub.runtime.RuntimeResponse? {
        val latch = CountDownLatch(1)
        var response: de.x0bubbuff.needlebub.runtime.RuntimeResponse? = null
        val accepted = runtime.infer(
            "update-check-${UUID.randomUUID()}", pack, query, HEALTH_TIMEOUT_MS,
            surface = "check", forceReload = forceReload,
        ) {
            response = it
            latch.countDown()
        }
        if (!accepted || !latch.await(HEALTH_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)) return null
        return response
    }

    private fun download(rawUrl: String, target: File, expectedSize: Long) {
        OfficialCatalogue.requireImmutableArtifact(rawUrl)
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) error("Pack request failed")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > expectedSize) error("Pack is larger than declared")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.length() != expectedSize) error("Pack size does not match")
        } finally {
            connection.disconnect()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FOREGROUND_STALE_MS = 6L * 60L * 60L * 1_000L
        const val HEALTH_TIMEOUT_MS = 8_000L
        const val CHECK_SENDER = "Needle Bank"
        const val CHECK_CODE = "739241"
        const val CHECK_MESSAGE = "Your login code is $CHECK_CODE. It expires in 10 minutes."
    }
}
