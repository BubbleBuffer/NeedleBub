package de.x0bubbuff.needlebub.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialCatalogueParserTest {
    @Test
    fun `selects the newest official version and rejects unknown fields`() {
        val catalogue = """
            {
              "formatVersion": 1,
              "authority": "https://github.com/BubbleBuffer/NeedleBub",
              "entries": [
                ${entry("1.0.0-alpha.1")},
                ${entry("1.0.0-alpha.2")}
              ]
            }
        """.trimIndent().encodeToByteArray()
        assertEquals("1.0.0-alpha.2", OfficialCatalogueParser.parse(catalogue).second.version)

        val malformed = """
            {
              "formatVersion": 1,
              "authority": "https://github.com/BubbleBuffer/NeedleBub",
              "unexpected": true,
              "entries": [${entry("1.0.0-alpha.2")}]
            }
        """.trimIndent().encodeToByteArray()
        assertThrows(SecurityException::class.java) { OfficialCatalogueParser.parse(malformed) }
    }

    private fun entry(version: String) = """
        {
          "id": "de.x0bubbuff.needlebub.otp",
          "version": "$version",
          "name": "OTP Extractor",
          "description": "OTP",
          "verified": true,
          "url": "https://github.com/BubbleBuffer/NeedleBub/releases/download/otp-v$version/otp.nbpack",
          "size": 100,
          "sha256": "${"a".repeat(64)}",
          "engineAbi": "needle2-hf-98fbd955b0347e78059be0c253cc1ffa09b87bc7-android-arm64"
        }
    """.trimIndent()
}
