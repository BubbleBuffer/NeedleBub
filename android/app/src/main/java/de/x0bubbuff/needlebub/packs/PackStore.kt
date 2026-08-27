package de.x0bubbuff.needlebub.packs

import android.content.Context
import de.x0bubbuff.needlebub.updates.SemanticVersion
import java.io.File

class PackStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "packs")
    private val preferences = appContext.getSharedPreferences("pack_sources", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<InstalledPack> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter(File::isDirectory).flatMap { packRoot ->
            packRoot.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { versionRoot ->
                try {
                    val manifest = PackManifest.parse(File(versionRoot, "manifest.json").readText())
                    if (manifest.engineAbi != PackManifest.ENGINE_ABI) null
                    else InstalledPack(manifest, versionRoot, preferences.getBoolean(key(manifest), false))
                } catch (_: Exception) {
                    null
                }
            }
        }.sortedWith(compareBy<InstalledPack> { it.manifest.name }.thenComparator { left, right ->
            SemanticVersion.parse(right.manifest.version).compareTo(SemanticVersion.parse(left.manifest.version))
        })
    }

    @Synchronized
    fun install(archive: File, verified: Boolean): InstalledPack {
        val installed = NbPackArchive.install(archive, root, verified)
        preferences.edit().putBoolean(key(installed.manifest), verified).apply()
        if (verified && installed.manifest.id == OFFICIAL_OTP_ID && preferences.getString(ACTIVE_OTP_VERSION, null) == null) {
            activateOfficial(installed.manifest.version)
        }
        return installed
    }

    fun findExternal(capabilityId: String): InstalledPack? =
        list().firstOrNull { it.manifest.id == capabilityId && "external" in it.manifest.surfaces }

    fun officialOtp(): InstalledPack? {
        val compatible = list().filter {
            it.verified && it.manifest.id == OFFICIAL_OTP_ID && "notification" in it.manifest.surfaces
        }
        if (compatible.isEmpty()) return null
        val active = preferences.getString(ACTIVE_OTP_VERSION, null)
        val selected = compatible.firstOrNull { it.manifest.version == active } ?: compatible.first()
        if (active != selected.manifest.version) activateOfficial(selected.manifest.version)
        return selected
    }

    @Synchronized
    fun activateOfficial(version: String): Boolean {
        val target = list().firstOrNull {
            it.verified && it.manifest.id == OFFICIAL_OTP_ID && it.manifest.version == version &&
                "notification" in it.manifest.surfaces
        } ?: return false
        val previous = preferences.getString(ACTIVE_OTP_VERSION, null)
        if (previous == target.manifest.version) return true
        preferences.edit()
            .putString(ACTIVE_OTP_VERSION, target.manifest.version)
            .apply {
                if (previous == null) remove(PREVIOUS_OTP_VERSION)
                else putString(PREVIOUS_OTP_VERSION, previous)
            }
            .apply()
        return true
    }

    fun previousOfficial(): InstalledPack? {
        val version = preferences.getString(PREVIOUS_OTP_VERSION, null) ?: return null
        return list().firstOrNull {
            it.verified && it.manifest.id == OFFICIAL_OTP_ID && it.manifest.version == version
        }
    }

    @Synchronized
    fun rollbackOfficial(): Boolean {
        val previous = previousOfficial() ?: return false
        val current = preferences.getString(ACTIVE_OTP_VERSION, null)
        preferences.edit()
            .putString(ACTIVE_OTP_VERSION, previous.manifest.version)
            .putString(PREVIOUS_OTP_VERSION, current)
            .apply()
        return true
    }

    @Synchronized
    fun remove(id: String, version: String): Boolean {
        val pack = list().firstOrNull { it.manifest.id == id && it.manifest.version == version } ?: return false
        val removed = pack.directory.deleteRecursively()
        if (removed) {
            val editor = preferences.edit().remove(key(pack.manifest))
            if (preferences.getString(ACTIVE_OTP_VERSION, null) == version) editor.remove(ACTIVE_OTP_VERSION)
            if (preferences.getString(PREVIOUS_OTP_VERSION, null) == version) editor.remove(PREVIOUS_OTP_VERSION)
            editor.apply()
        }
        return removed
    }

    fun toolsJson(pack: InstalledPack): String = File(pack.directory, "tools.json").readText()
    fun modelFile(pack: InstalledPack): File = File(pack.directory, pack.manifest.modelPath)

    private fun key(manifest: PackManifest) = "${manifest.id}@${manifest.version}"

    companion object {
        const val OFFICIAL_OTP_ID = "de.x0bubbuff.needlebub.otp"
        private const val ACTIVE_OTP_VERSION = "active_official_otp_version"
        private const val PREVIOUS_OTP_VERSION = "previous_official_otp_version"
    }
}
