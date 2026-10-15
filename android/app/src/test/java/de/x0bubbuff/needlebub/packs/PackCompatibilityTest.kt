package de.x0bubbuff.needlebub.packs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCompatibilityTest {
    private val oldAbi = "needle2-hf-98fbd955b0347e78059be0c253cc1ffa09b87bc7-android-arm64"
    private val capability = "de.x0bubbuff.needlebub.otp"

    @Test
    fun `exact Needle 2 ABI is incompatible and reported only for its own capability and surface`() {
        val manifest = manifest(abi = oldAbi, surfaces = """["external","notification"]""")

        assertFalse(PackCompatibility.isEngineCompatible(manifest))
        assertTrue(PackCompatibility.isIncompatible(manifest, capability, "external"))
        assertTrue(PackCompatibility.isIncompatible(manifest, capability, "notification"))
        assertFalse(PackCompatibility.isIncompatible(manifest, "de.x0bubbuff.needlebub.other", "external"))
    }

    @Test
    fun `current ABI pack is compatible and never reported as incompatible`() {
        val manifest = manifest(abi = PackManifest.ENGINE_ABI, surfaces = """["external"]""")

        assertTrue(PackCompatibility.isEngineCompatible(manifest))
        assertFalse(PackCompatibility.isIncompatible(manifest, capability, "external"))
    }

    @Test
    fun `incompatible pack is not reported for a surface it does not declare`() {
        val manifest = manifest(abi = oldAbi, surfaces = """["external"]""")

        assertFalse(PackCompatibility.isIncompatible(manifest, capability, "notification"))
    }

    private fun manifest(abi: String, surfaces: String): PackManifest = PackManifest.parse("""
        {
          "formatVersion": 1,
          "id": "$capability",
          "version": "1.0.0",
          "name": "OTP Extractor",
          "author": "BubbleBuffer",
          "description": "Extracts one-time authentication codes.",
          "license": "Apache-2.0",
          "engine": {"abi": "$abi"},
          "model": {"path": "model.cact", "size": 12, "sha256": "${"a".repeat(64)}"},
          "queryTemplate": "Message: {{input}}",
          "surfaces": $surfaces,
          "outputs": {"nb_code": {"type": "string", "pointer": "/code"}}
        }
    """.trimIndent())
}
