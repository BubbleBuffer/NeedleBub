package de.x0bubbuff.needlebub.packs

import org.json.JSONException
import org.json.JSONObject

class PackValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class PackOutput(
    val type: String,
    val pointer: String,
    val optional: Boolean,
)

data class PackManifest(
    val formatVersion: Int,
    val id: String,
    val version: String,
    val name: String,
    val author: String,
    val description: String,
    val license: String,
    val engineAbi: String,
    val modelPath: String,
    val modelSize: Long,
    val modelSha256: String,
    val queryTemplate: String,
    val surfaces: Set<String>,
    val outputs: Map<String, PackOutput>,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val ENGINE_ABI = "needle2-hf-98fbd955b0347e78059be0c253cc1ffa09b87bc7-android-arm64"
        private val topLevelKeys = setOf(
            "formatVersion", "id", "version", "name", "author", "description", "license",
            "engine", "model", "queryTemplate", "surfaces", "outputs",
        )
        private val outputTypes = setOf("string", "boolean", "number", "json")
        private val allowedSurfaces = setOf("external", "notification")

        @Throws(PackValidationException::class)
        fun parse(raw: String): PackManifest {
            try {
                val json = JSONObject(raw)
                requireExactKeys(json, topLevelKeys, "manifest")
                if (json.getInt("formatVersion") != FORMAT_VERSION) invalid("unsupported formatVersion")

                val id = json.requiredString("id")
                if (id.length > 200) invalid("id is too long")
                if (!id.matches(Regex("^[a-z][a-z0-9]*(?:\\.[a-z0-9][a-z0-9-]*){2,}\$"))) invalid("id must use reverse-domain notation")
                val version = json.requiredString("version")
                if (version.length > 100) invalid("version is too long")
                if (!version.matches(Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?\$"))) invalid("version must be semantic")

                val engine = json.getJSONObject("engine")
                requireExactKeys(engine, setOf("abi"), "engine")
                val engineAbi = engine.requiredString("abi")

                val model = json.getJSONObject("model")
                requireExactKeys(model, setOf("path", "size", "sha256"), "model")
                val modelPath = model.requiredString("path")
                if (modelPath != "model.cact") invalid("model.path must be model.cact")
                val modelSize = model.getLong("size")
                if (modelSize !in 1..MAX_MODEL_BYTES) invalid("model.size is out of range")
                val digest = model.requiredString("sha256")
                if (!digest.matches(Regex("^[a-fA-F0-9]{64}\$"))) invalid("model.sha256 must be SHA-256")

                val queryTemplate = json.requiredString("queryTemplate")
                if (queryTemplate.length > MAX_QUERY_TEMPLATE_CHARS) invalid("queryTemplate is too long")
                if (Regex(Regex.escape("{{input}}")).findAll(queryTemplate).count() != 1) invalid("queryTemplate needs exactly one {{input}}")

                val rawSurfaces = json.getJSONArray("surfaces")
                val surfaces = (0 until rawSurfaces.length()).map { rawSurfaces.getString(it) }.toSet()
                if (surfaces.isEmpty() || surfaces.size != rawSurfaces.length() || !allowedSurfaces.containsAll(surfaces)) invalid("invalid surfaces")

                val rawOutputs = json.getJSONObject("outputs")
                if (rawOutputs.length() > MAX_OUTPUTS) invalid("too many declared outputs")
                val outputs = rawOutputs.keys().asSequence().associateWith { name ->
                    if (!name.matches(Regex("^nb_[a-z][a-z0-9_]*\$"))) invalid("invalid output name")
                    val output = rawOutputs.getJSONObject(name)
                    requireExactKeys(output, setOf("type", "pointer", "optional"), "output $name")
                    val type = output.requiredString("type")
                    if (type !in outputTypes) invalid("invalid output type")
                    val pointer = output.requiredString("pointer")
                    if (!pointer.matches(Regex("^(?:/(?:[^~/]|~[01])*)*\$"))) invalid("invalid JSON Pointer")
                    PackOutput(type, pointer, output.optBoolean("optional", false))
                }

                return PackManifest(
                    FORMAT_VERSION, id, version, json.requiredString("name"), json.requiredString("author"),
                    json.requiredString("description"), json.requiredString("license"), engineAbi,
                    modelPath, modelSize, digest.lowercase(), queryTemplate, surfaces, outputs,
                )
            } catch (error: PackValidationException) {
                throw error
            } catch (error: JSONException) {
                throw PackValidationException("invalid manifest JSON", error)
            }
        }

        private fun requireExactKeys(json: JSONObject, allowed: Set<String>, label: String) {
            val unknown = json.keys().asSequence().filterNot(allowed::contains).toList()
            if (unknown.isNotEmpty()) invalid("$label contains unknown fields: ${unknown.joinToString()}")
        }

        private fun JSONObject.requiredString(name: String): String =
            getString(name).takeIf(String::isNotBlank) ?: invalid("$name is required")

        private fun invalid(message: String): Nothing = throw PackValidationException(message)

        private const val MAX_MODEL_BYTES = 128L * 1024L * 1024L
        private const val MAX_QUERY_TEMPLATE_CHARS = 8 * 1024
        private const val MAX_OUTPUTS = 32
    }
}
