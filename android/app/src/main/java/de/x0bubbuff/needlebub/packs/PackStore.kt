package de.x0bubbuff.needlebub.packs

import android.content.Context
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
        }.sortedWith(compareBy<InstalledPack> { it.manifest.name }.thenByDescending { it.manifest.version })
    }

    @Synchronized
    fun install(archive: File, verified: Boolean): InstalledPack {
        val installed = NbPackArchive.install(archive, root, verified)
        preferences.edit().putBoolean(key(installed.manifest), verified).apply()
        return installed
    }

    fun findExternal(capabilityId: String): InstalledPack? =
        list().firstOrNull { it.manifest.id == capabilityId && "external" in it.manifest.surfaces }

    fun officialOtp(): InstalledPack? = list().firstOrNull {
        it.verified && it.manifest.id == OFFICIAL_OTP_ID && "notification" in it.manifest.surfaces
    }

    @Synchronized
    fun remove(id: String, version: String): Boolean {
        val pack = list().firstOrNull { it.manifest.id == id && it.manifest.version == version } ?: return false
        val removed = pack.directory.deleteRecursively()
        if (removed) preferences.edit().remove(key(pack.manifest)).apply()
        return removed
    }

    fun toolsJson(pack: InstalledPack): String = File(pack.directory, "tools.json").readText()
    fun modelFile(pack: InstalledPack): File = File(pack.directory, pack.manifest.modelPath)

    private fun key(manifest: PackManifest) = "${manifest.id}@${manifest.version}"

    companion object {
        const val OFFICIAL_OTP_ID = "de.x0bubbuff.needlebub.otp"
    }
}
