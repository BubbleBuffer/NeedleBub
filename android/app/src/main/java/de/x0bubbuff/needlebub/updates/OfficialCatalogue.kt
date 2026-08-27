package de.x0bubbuff.needlebub.updates

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class OfficialCatalogueEntry(
    val id: String,
    val version: String,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val engineAbi: String,
)

class OfficialCatalogue(private val context: Context) {
    fun remote(): Pair<JSONObject, OfficialCatalogueEntry> {
        val catalogue = fetch(CATALOGUE_URL, CatalogueSignature.MAX_CATALOGUE_BYTES)
        val signature = fetch(SIGNATURE_URL, MAX_SIGNATURE_BYTES).decodeToString()
        val publicKey = context.assets.open(PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
        CatalogueSignature.verify(catalogue, signature, publicKey)
        return OfficialCatalogueParser.parse(catalogue)
    }

    fun embedded(): Pair<JSONObject, OfficialCatalogueEntry> {
        val catalogue = context.assets.open(CATALOGUE_ASSET).use { it.readBytes() }
        val signature = context.assets.open(SIGNATURE_ASSET).bufferedReader().use { it.readText() }
        val publicKey = context.assets.open(PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
        CatalogueSignature.verify(catalogue, signature, publicKey)
        return OfficialCatalogueParser.parse(catalogue)
    }

    private fun fetch(rawUrl: String, limit: Int): ByteArray {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (connection.responseCode !in 200..299) error("Catalogue request failed")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) error("Catalogue response is too large")
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val AUTHORITY = "https://github.com/BubbleBuffer/NeedleBub"
        const val OFFICIAL_OTP_ID = "de.x0bubbuff.needlebub.otp"
        const val CATALOGUE_URL = "https://raw.githubusercontent.com/BubbleBuffer/NeedleBub/main/catalogue/catalogue.json"
        const val SIGNATURE_URL = "https://raw.githubusercontent.com/BubbleBuffer/NeedleBub/main/catalogue/catalogue.sig"
        const val CATALOGUE_ASSET = "catalogue.json"
        const val SIGNATURE_ASSET = "catalogue.sig"
        const val PUBLIC_KEY_ASSET = "catalogue-public-key.txt"
        const val MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L
        const val MAX_SIGNATURE_BYTES = 4 * 1024
        val ALLOWED_ARTIFACT_HOSTS = setOf(
            "github.com", "objects.githubusercontent.com", "huggingface.co",
            "cdn-lfs.hf.co", "cas-bridge.xethub.hf.co",
        )

        fun requireImmutableArtifact(raw: String) {
            val uri = java.net.URI(raw)
            if (uri.scheme != "https" || uri.host !in ALLOWED_ARTIFACT_HOSTS) {
                throw SecurityException("Catalogue URL host is not allowed")
            }
            val immutableHf = Regex("/resolve/[a-f0-9]{40}/").containsMatchIn(uri.path)
            val immutableGithub = "/releases/download/" in uri.path
            if (!immutableHf && !immutableGithub) throw SecurityException("Catalogue URL is not immutable")
        }
    }
}
