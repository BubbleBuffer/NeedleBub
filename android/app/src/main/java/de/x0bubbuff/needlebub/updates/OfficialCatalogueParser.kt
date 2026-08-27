package de.x0bubbuff.needlebub.updates

import de.x0bubbuff.needlebub.packs.PackManifest
import org.json.JSONObject

object OfficialCatalogueParser {
    private val rootKeys = setOf("formatVersion", "authority", "entries")
    private val entryKeys = setOf(
        "id", "version", "name", "description", "verified",
        "url", "size", "sha256", "engineAbi",
    )
    private val requiredEntryKeys = setOf("id", "version", "name", "url", "size", "sha256", "engineAbi")

    fun parse(bytes: ByteArray): Pair<JSONObject, OfficialCatalogueEntry> {
        val root = try {
            JSONObject(bytes.decodeToString())
        } catch (error: Exception) {
            throw SecurityException("Catalogue JSON is malformed", error)
        }
        if (root.keysSet() != rootKeys ||
            root.optInt("formatVersion", -1) != 1 ||
            root.optString("authority") != OfficialCatalogue.AUTHORITY
        ) {
            throw SecurityException("Catalogue root is invalid")
        }
        val entries = root.optJSONArray("entries") ?: throw SecurityException("Catalogue entries are missing")
        if (entries.length() !in 1..64) throw SecurityException("Catalogue entry count is invalid")
        val parsed = mutableListOf<OfficialCatalogueEntry>()
        val identities = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: throw SecurityException("Catalogue entry is invalid")
            val keys = entry.keysSet()
            if (!keys.all(entryKeys::contains) || !keys.containsAll(requiredEntryKeys)) {
                throw SecurityException("Catalogue entry fields are invalid")
            }
            val id = entry.optString("id")
            val version = entry.optString("version")
            if (id.isBlank() || version.isBlank() || !identities.add("$id@$version")) {
                throw SecurityException("Catalogue entry identity is invalid")
            }
            SemanticVersion.parse(version)
            val size = entry.optLong("size", -1L)
            val sha256 = entry.optString("sha256")
            val url = entry.optString("url")
            if (size !in 1..OfficialCatalogue.MAX_ARCHIVE_BYTES ||
                !sha256.matches(Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE))
            ) {
                throw SecurityException("Catalogue artifact metadata is invalid")
            }
            OfficialCatalogue.requireImmutableArtifact(url)
            parsed += OfficialCatalogueEntry(
                id = id,
                version = version,
                name = entry.optString("name"),
                url = url,
                size = size,
                sha256 = sha256,
                engineAbi = entry.optString("engineAbi"),
            )
        }
        val official = parsed
            .filter { it.id == OfficialCatalogue.OFFICIAL_OTP_ID }
            .maxWithOrNull(compareBy { SemanticVersion.parse(it.version) })
            ?: throw SecurityException("Official OTP pack is missing")
        if (official.engineAbi != PackManifest.ENGINE_ABI) {
            throw SecurityException("Catalogue engine is incompatible")
        }
        val officialJson = (0 until entries.length())
            .asSequence()
            .map(entries::getJSONObject)
            .first { it.getString("id") == official.id && it.getString("version") == official.version }
        if (!officialJson.optBoolean("verified", false)) {
            throw SecurityException("Official OTP pack is not verified")
        }
        return root to official
    }

    private fun JSONObject.keysSet(): Set<String> {
        val output = mutableSetOf<String>()
        val keys = keys()
        while (keys.hasNext()) output += keys.next()
        return output
    }
}
