package de.x0bubbuff.needlebub.packs

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class InstalledPack(
    val manifest: PackManifest,
    val directory: File,
    val verified: Boolean,
)

object NbPackArchive {
    private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 64
    private val requiredEntries = setOf("manifest.json", "tools.json", "model.cact")
    private val dangerousExtensions = setOf(
        "so", "dll", "dylib", "exe", "bat", "cmd", "com", "ps1", "sh", "bash", "zsh",
        "js", "mjs", "cjs", "py", "rb", "pl", "jar", "dex", "class", "apk", "aab", "wasm",
    )
    private val noticeName = Regex("^(?:LICENSE|NOTICE)(?:[-_.][A-Za-z0-9-]+)*(?:\\.(?:txt|md))?\$", RegexOption.IGNORE_CASE)
    private val iconName = Regex("^icon\\.(?:png|webp|svg)\$", RegexOption.IGNORE_CASE)

    @Throws(PackValidationException::class)
    fun install(packFile: File, destinationRoot: File, verified: Boolean): InstalledPack {
        if (!packFile.isFile) throw PackValidationException("pack file is missing")
        destinationRoot.mkdirs()
        val staging = File(destinationRoot, ".staging-${UUID.randomUUID()}")
        if (!staging.mkdir()) throw PackValidationException("could not create pack staging directory")

        try {
            val entries = linkedMapOf<String, ZipArchiveEntry>()
            var expandedBytes = 0L
            var manifestRaw: ByteArray? = null
            var toolsRaw: ByteArray? = null

            ZipFile.builder().setFile(packFile).get().use { zip ->
                val iterator = zip.entries.asIterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val name = validateEntry(entry)
                    if (entries.put(name, entry) != null) invalid("duplicate archive entry: $name")
                    if (entries.size > MAX_ENTRIES) invalid("too many archive entries")
                    if (!entry.isDirectory) {
                        val size = entry.size
                        if (size < 0) invalid("archive entry has unknown size: $name")
                        expandedBytes = Math.addExact(expandedBytes, size)
                        if (expandedBytes > MAX_EXPANDED_BYTES) invalid("archive expands beyond 128 MiB")
                        if (name == "manifest.json") manifestRaw = zip.getInputStream(entry).readBounded(MAX_METADATA_BYTES)
                        if (name == "tools.json") toolsRaw = zip.getInputStream(entry).readBounded(MAX_METADATA_BYTES)
                    }
                }

                if (!entries.keys.containsAll(requiredEntries)) invalid("pack is missing required entries")
                if (entries.keys.none(noticeName::matches)) invalid("pack must include a license or notice file")
                val manifest = PackManifest.parse(String(manifestRaw ?: invalid("manifest.json is missing"), Charsets.UTF_8))
                if (manifest.engineAbi != PackManifest.ENGINE_ABI) invalid("incompatible Needle engine ABI")
                validateTools(String(toolsRaw ?: invalid("tools.json is missing"), Charsets.UTF_8))

                val modelEntry = entries[manifest.modelPath] ?: invalid("declared model is missing")
                if (modelEntry.size != manifest.modelSize) invalid("model size does not match manifest")

                entries.forEach { (name, entry) ->
                    if (entry.isDirectory) return@forEach
                    val target = File(staging, name)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, true)
                    target.setWritable(false, false)
                    target.setExecutable(false, false)
                }

                val actualDigest = File(staging, manifest.modelPath).sha256()
                if (!actualDigest.equals(manifest.modelSha256, ignoreCase = true)) invalid("model checksum does not match manifest")

                val destination = File(File(destinationRoot, manifest.id), manifest.version)
                destination.parentFile?.mkdirs()
                if (destination.exists()) invalid("pack version is already installed")
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return InstalledPack(manifest, destination, verified)
            }
        } catch (error: PackValidationException) {
            throw error
        } catch (error: ArithmeticException) {
            throw PackValidationException("archive size overflow", error)
        } catch (error: Exception) {
            throw PackValidationException("pack archive is invalid", error)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun validateEntry(entry: ZipArchiveEntry): String {
        val name = entry.name.replace('\\', '/')
        if (name.isEmpty() || name.startsWith('/') || name.contains('\u0000') || name.contains(':') ||
            name.split('/').any { it == ".." || it == "." || it.isEmpty() }) {
            invalid("unsafe archive path")
        }
        if (name.contains('/')) invalid("nested archive paths are not allowed in format v1")
        if (entry.isUnixSymlink) invalid("symbolic links are not allowed")
        if (entry.unixMode and 0b001001001 != 0) invalid("executable entries are not allowed")
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in dangerousExtensions) invalid("executable content is not allowed")
        if (!entry.isDirectory && name !in requiredEntries && !noticeName.matches(name) && !iconName.matches(name)) {
            invalid("unexpected archive entry: $name")
        }
        return name
    }

    private fun validateTools(raw: String) {
        val json = JSONObject(raw)
        val keys = json.keys().asSequence().toSet()
        if (keys != setOf("formatVersion", "tools") || json.getInt("formatVersion") != 1) invalid("invalid tools format")
        val tools = json.getJSONArray("tools")
        if (tools.length() != 1) invalid("tools.json must contain exactly one tool")
        val tool = tools.getJSONObject(0)
        if (tool.keys().asSequence().toSet() != setOf("name", "description", "parameters")) invalid("invalid tool fields")
        if (tool.optString("name").isBlank() || tool.optString("description").isBlank()) invalid("tool name and description are required")
        val parameters = tool.optJSONObject("parameters") ?: invalid("tool parameters are required")
        if (parameters.optString("type") != "object") invalid("tool parameters must be an object schema")
    }

    private fun InputStream.readBounded(limit: Int): ByteArray = use { input ->
        val bytes = input.readNBytes(limit + 1)
        if (bytes.size > limit) invalid("metadata entry is too large")
        bytes
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String): Nothing = throw PackValidationException(message)
}
