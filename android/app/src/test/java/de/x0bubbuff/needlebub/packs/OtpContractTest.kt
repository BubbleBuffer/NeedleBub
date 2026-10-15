package de.x0bubbuff.needlebub.packs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the public Needle 3 OTP contract (test resource copied from the verified run). */
class OtpContractTest {
    private val contract = JSONObject(
        checkNotNull(javaClass.getResourceAsStream("/needle3/otp-contract.json")) { "contract resource is missing" }
            .bufferedReader().use { it.readText() },
    )

    @Test
    fun `contract pins the Needle 3 schema version`() {
        assertEquals("otp-needle3-v1", contract.getString("version"))
        assertEquals(3, contract.getInt("generation"))
        assertEquals("5ecaaf42b060df95197c838dacf29f61a894e8ff9db3f362081f4c7c025199c0", contract.getString("schema_sha256"))
    }

    @Test
    fun `contract tool is the only tool, requires code and keeps source optional`() {
        val tools = contract.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("extract_otp", tool.getString("name"))

        val parameters = tool.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(setOf("code", "source"), parameters.getJSONObject("properties").keys().asSequence().toSet())
        assertEquals(listOf("code"), parameters.getJSONArray("required").strings())
    }

    @Test
    fun `contract tools pass the existing pack tools validator`() {
        val toolsJson = JSONObject().put("formatVersion", 1).put("tools", contract.getJSONArray("tools")).toString()

        NbPackArchive.validateTools(toolsJson)
    }

    @Test
    fun `staged OTP pack uses the trained tools and one Message wrapper`() {
        val packTools = javaClass.getResourceAsStream("/tools.json")!!.bufferedReader().use { it.readText() }
        val manifest = javaClass.getResourceAsStream("/manifest.json")!!.bufferedReader().use { PackManifest.parse(it.readText()) }
        NbPackArchive.validateTools(packTools)
        assertTrue(JSONObject(packTools).getJSONArray("tools").similar(contract.getJSONArray("tools")))
        assertEquals(PackManifest.ENGINE_ABI, manifest.engineAbi)
        assertEquals("Message: {{input}}", manifest.queryTemplate)
        assertEquals("Message: Your code is 0042", manifest.queryTemplate.replace("{{input}}", "Your code is 0042"))
    }

    @Test
    fun `Needle 3 query template with one input placeholder is a valid manifest query`() {
        val manifest = PackManifest.parse("""
            {
              "formatVersion": 1,
              "id": "de.x0bubbuff.needlebub.otp",
              "version": "1.0.0",
              "name": "OTP Extractor",
              "author": "BubbleBuffer",
              "description": "Extracts one-time authentication codes.",
              "license": "Apache-2.0",
              "engine": {"abi": "${PackManifest.ENGINE_ABI}"},
              "model": {"path": "model.cact", "size": 12, "sha256": "${"a".repeat(64)}"},
              "queryTemplate": "Message: {{input}}",
              "surfaces": ["external", "notification"],
              "outputs": {"nb_code": {"type": "string", "pointer": "/code"}}
            }
        """.trimIndent())

        assertEquals("Message: {{input}}", manifest.queryTemplate)
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
