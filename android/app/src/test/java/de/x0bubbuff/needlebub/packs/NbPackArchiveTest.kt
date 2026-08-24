package de.x0bubbuff.needlebub.packs

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class NbPackArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `installs a valid pack and verifies its model digest`() {
        val model = "needle-model".toByteArray()
        val pack = archive(
            "manifest.json" to manifest(model),
            "tools.json" to TOOLS,
            "model.cact" to model,
            "LICENSE.txt" to "Apache-2.0".toByteArray(),
        )

        val installed = NbPackArchive.install(pack, temporary.newFolder("packs"), verified = false)
        assertEquals("de.x0bubbuff.needlebub.otp", installed.manifest.id)
        assertEquals(false, installed.verified)
        assertEquals(model.toList(), File(installed.directory, "model.cact").readBytes().toList())
    }

    @Test
    fun `rejects traversal duplicate executable incompatible and checksum failures`() {
        val model = "needle-model".toByteArray()
        val destination = temporary.newFolder("rejects")
        assertRejected(destination, archive("../model.cact" to model))
        assertRejected(destination, archive("manifest.json" to manifest(model), "manifest.json" to manifest(model)))
        assertRejected(destination, archive("payload.so" to model))
        assertRejected(destination, archive(*validEntries(model, String(manifest(model)).replace(PackManifest.ENGINE_ABI, "future-abi").toByteArray())))
        assertRejected(destination, archive(*validEntries(model, manifest("different".toByteArray()))))
    }

    private fun assertRejected(destination: File, archive: File) {
        assertThrows(PackValidationException::class.java) {
            NbPackArchive.install(archive, destination, verified = false)
        }
    }

    private fun validEntries(model: ByteArray, manifest: ByteArray): Array<out Pair<String, ByteArray>> = arrayOf(
        "manifest.json" to manifest,
        "tools.json" to TOOLS,
        "model.cact" to model,
        "NOTICE.md" to "Needle notice".toByteArray(),
    )

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = temporary.newFile("pack-${System.nanoTime()}.nbpack")
        ZipArchiveOutputStream(file).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        return file
    }

    private fun manifest(model: ByteArray): ByteArray = """
        {
          "formatVersion":1,
          "id":"de.x0bubbuff.needlebub.otp",
          "version":"1.0.0",
          "name":"OTP Extractor",
          "author":"BubbleBuffer",
          "description":"Extracts OTPs.",
          "license":"Apache-2.0",
          "engine":{"abi":"${PackManifest.ENGINE_ABI}"},
          "model":{"path":"model.cact","size":${model.size},"sha256":"${model.sha256()}"},
          "queryTemplate":"{{input}}",
          "surfaces":["external","notification"],
          "outputs":{"nb_code":{"type":"string","pointer":"/code"}}
        }
    """.trimIndent().toByteArray()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        val TOOLS = """{"formatVersion":1,"tools":[{"name":"extract_otp","description":"Extract a one-time authentication code.","parameters":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}}]}""".toByteArray()
    }
}
