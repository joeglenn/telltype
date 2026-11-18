package net.jbglenn.telltype.network.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.OfferedModel

/**
 * Parses `GET /v1/models`: OpenAI's `{"data":[{"id":"…"}]}` (llama.cpp adds the model file as `path`, a router-mode
 * llama-server as `--model` in `status.args`; Telltype Server adds `telltype.roles`), or a top-level `models` array
 * whose entries name the model in `id`, `name` or `model`. Entries without a name are skipped and repeated names
 * kept once; null when the body is neither shape.
 */
internal fun parseModelList(body: String): List<OfferedModel>? = parseObject(body)
    ?.let { root -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) }
    ?.mapNotNull { element ->
        val entry = element as? JsonObject
        val id = entry?.let { it.string("id") ?: it.string("name") ?: it.string("model") }
        id?.takeIf(String::isNotBlank)?.let { OfferedModel(it, modelFile(entry), modelRoles(entry)) }
    }
    ?.distinctBy(OfferedModel::id)

/** The file name of the model behind [entry]: llama.cpp's `path`, else the `--model` argument of a router preset. */
private fun modelFile(entry: JsonObject): String? {
    val args = ((entry["status"] as? JsonObject)?.get("args") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .orEmpty()
    val flag = args.indexOfFirst { it == "--model" || it == "-m" }
    val fromArgs = args.getOrNull(flag + 1)?.takeIf { flag >= 0 }
    return (entry.string("path") ?: fromArgs)?.substringAfterLast('/')
}

/** Telltype Server's `telltype.roles` of [entry]; null when absent. Role names this build does not know are dropped. */
private fun modelRoles(entry: JsonObject): Set<ModelRole>? =
    ((entry["telltype"] as? JsonObject)?.get("roles") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.let(ModelRole::fromWire) }
        ?.toSet()
