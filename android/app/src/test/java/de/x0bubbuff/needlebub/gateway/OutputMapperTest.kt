package de.x0bubbuff.needlebub.gateway

import de.x0bubbuff.needlebub.packs.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputMapperTest {
    @Test
    fun `returns stable generic and typed declared outputs`() {
        val manifest = PackManifest.parse("""
            {"formatVersion":1,"id":"de.x0bubbuff.needlebub.test","version":"1.0.0","name":"Test","author":"BubbleBuffer","description":"Test pack","license":"MIT","engine":{"abi":"${PackManifest.ENGINE_ABI}"},"model":{"path":"model.cact","size":1,"sha256":"${"a".repeat(64)}"},"queryTemplate":"{{input}}","surfaces":["external"],"outputs":{"nb_code":{"type":"string","pointer":"/code"},"nb_count":{"type":"number","pointer":"/nested/count"}}}
        """.trimIndent())

        val values = OutputMapper.map(manifest, true, "extract", """{"code":"123456","nested":{"count":2}}""", null)
        assertEquals("true", values["nb_matched"])
        assertEquals("extract", values["nb_tool"])
        assertEquals("", values["nb_error_code"])
        assertEquals("123456", values["nb_code"])
        assertEquals("2", values["nb_count"])
    }

    @Test
    fun `omits a declared output whose optional source is missing from the call`() {
        val manifest = PackManifest.parse("""
            {"formatVersion":1,"id":"de.x0bubbuff.needlebub.test","version":"1.0.0","name":"Test","author":"BubbleBuffer","description":"Test pack","license":"MIT","engine":{"abi":"${PackManifest.ENGINE_ABI}"},"model":{"path":"model.cact","size":1,"sha256":"${"a".repeat(64)}"},"queryTemplate":"Message: {{input}}","surfaces":["external"],"outputs":{"nb_code":{"type":"string","pointer":"/code"},"nb_source":{"type":"string","pointer":"/source","optional":true}}}
        """.trimIndent())

        val values = OutputMapper.map(manifest, true, "extract_otp", """{"code":"0042"}""", null)
        assertEquals("true", values["nb_matched"])
        assertEquals("0042", values["nb_code"])
        assertEquals(false, values.containsKey("nb_source"))
    }
}
