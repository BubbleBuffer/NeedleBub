package de.x0bubbuff.needlebub.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackManifestTest {
    private val valid = """
        {
          "formatVersion": 1,
          "id": "de.x0bubbuff.needlebub.otp",
          "version": "1.0.0",
          "name": "OTP Extractor",
          "author": "BubbleBuffer",
          "description": "Extracts one-time authentication codes.",
          "license": "Apache-2.0",
          "engine": {"abi": "needle2-hf-98fbd955b0347e78059be0c253cc1ffa09b87bc7-android-arm64"},
          "model": {"path": "model.cact", "size": 12, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
          "queryTemplate": "{{input}}",
          "surfaces": ["external", "notification"],
          "outputs": {"nb_code": {"type": "string", "pointer": "/code"}}
        }
    """.trimIndent()

    @Test
    fun `parses the bounded v1 manifest`() {
        assertEquals("de.x0bubbuff.needlebub.otp", PackManifest.parse(valid).id)
    }

    @Test
    fun `rejects extra input placeholders unsafe paths and unknown fields`() {
        assertThrows(PackValidationException::class.java) { PackManifest.parse(valid.replace("{{input}}", "{{input}} {{input}}")) }
        assertThrows(PackValidationException::class.java) { PackManifest.parse(valid.replace("model.cact", "../model.cact")) }
        assertThrows(PackValidationException::class.java) { PackManifest.parse(valid.dropLast(1) + ",\"future\":true}") }
    }
}
