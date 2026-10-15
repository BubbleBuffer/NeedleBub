package de.x0bubbuff.needlebub.packs

/**
 * Engine-ABI rules for installed packs, free of Android types so they run on the JVM.
 * Packs built for another ABI never reach the runtime; they only decide whether a request
 * reports ENGINE_INCOMPATIBLE instead of PACK_NOT_FOUND.
 */
object PackCompatibility {
    fun isEngineCompatible(manifest: PackManifest): Boolean = manifest.engineAbi == PackManifest.ENGINE_ABI

    fun isIncompatible(manifest: PackManifest, capabilityId: String, surface: String): Boolean =
        manifest.id == capabilityId && surface in manifest.surfaces && !isEngineCompatible(manifest)
}
