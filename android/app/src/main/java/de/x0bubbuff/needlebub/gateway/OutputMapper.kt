package de.x0bubbuff.needlebub.gateway

import de.x0bubbuff.needlebub.packs.PackManifest
import org.json.JSONArray
import org.json.JSONObject

object OutputMapper {
    fun map(manifest: PackManifest, matched: Boolean, toolName: String?, resultJson: String?, errorCode: String?): Map<String, String> {
        val values = linkedMapOf(
            "nb_matched" to matched.toString(),
            "nb_tool" to (toolName ?: ""),
            "nb_result_json" to (resultJson ?: ""),
            "nb_error_code" to (errorCode ?: ""),
        )
        if (!matched || resultJson == null) return values
        val root: Any = try { JSONObject(resultJson) } catch (_: Exception) { return values }
        manifest.outputs.forEach { (name, output) ->
            val found = resolve(root, output.pointer)
            if (found == null || found == JSONObject.NULL) return@forEach
            val valid = when (output.type) {
                "string" -> found is String
                "boolean" -> found is Boolean
                "number" -> found is Number
                "json" -> found is JSONObject || found is JSONArray
                else -> false
            }
            if (valid) values[name] = when (found) {
                is JSONObject, is JSONArray -> found.toString()
                else -> found.toString()
            }
        }
        return values
    }

    private fun resolve(root: Any, pointer: String): Any? {
        if (pointer.isEmpty()) return root
        var current: Any? = root
        for (rawToken in pointer.removePrefix("/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JSONObject -> if (current.has(token)) current.opt(token) else return null
                is JSONArray -> token.toIntOrNull()?.takeIf { it in 0 until current.length() }?.let(current::opt)
                else -> return null
            }
        }
        return current
    }
}
