package net.jbglenn.telltype.domain.cleanup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import net.jbglenn.telltype.core.common.Outcome

/**
 * A value inside a parsed JSON file and where it sits (`length.upper_ratio`), for error messages.
 *
 * @property element the value, null when the key or index is missing.
 * @property path the dotted path from the file's root.
 */
internal class JsonNode(val element: JsonElement?, val path: String) {
    /** The member [key] of this object (missing when this is not an object or has no such key). */
    operator fun get(key: String): JsonNode =
        JsonNode((element as? JsonObject)?.get(key), if (path.isEmpty()) key else "$path.$key")
}

/**
 * Reads one bundled JSON file into typed values without throwing: every accessor returns a neutral value on a
 * mismatch and remembers the first problem, which [finish] turns into an [AssetError].
 *
 * @property file the file name inside `shared/`, for the error.
 */
internal class AssetDecoder(private val file: String) {
    private var problem: String? = null

    /** Records [message] when [condition] is false (only the first problem is kept). */
    fun expect(condition: Boolean, message: () -> String) {
        if (!condition && problem == null) problem = message()
    }

    /** Parses [content]; a syntax error is recorded and gives a missing root. */
    fun root(content: String): JsonNode {
        val element = try {
            Json.parseToJsonElement(content)
        } catch (error: SerializationException) {
            expect(false) { "is not valid JSON (${error.message})" }
            null
        }
        return JsonNode(element, "")
    }

    /** The node as a JSON string. */
    fun string(node: JsonNode): String = primitive(node, "a string") { it.takeIf { it.isString }?.content } ?: ""

    /** The node as an integer. */
    fun int(node: JsonNode): Int = primitive(node, "an integer") { it.takeUnless { it.isString }?.intOrNull } ?: 0

    /** The node as a 64-bit integer. */
    fun long(node: JsonNode): Long = primitive(node, "an integer") { it.takeUnless { it.isString }?.longOrNull } ?: 0L

    /** The node as a number. */
    fun double(node: JsonNode): Double =
        primitive(node, "a number") { it.takeUnless { it.isString }?.doubleOrNull } ?: 0.0

    /** The node as a boolean. */
    fun boolean(node: JsonNode): Boolean =
        primitive(node, "a boolean") { it.takeUnless { it.isString }?.booleanOrNull } ?: false

    /** The node's items when it is an array. */
    fun items(node: JsonNode): List<JsonNode> {
        val array = node.element as? JsonArray
        expect(array != null) { describe(node, "an array") }
        return array.orEmpty().mapIndexed { index, item -> JsonNode(item, "${node.path}[$index]") }
    }

    /** The node's members in file order when it is an object; members not in [allowed] (if given) are a problem. */
    fun members(node: JsonNode, allowed: Set<String>? = null): List<Pair<String, JsonNode>> {
        val obj = node.element as? JsonObject
        expect(obj != null) { describe(node, "an object") }
        val unknown = obj.orEmpty().keys.filter { allowed != null && it !in allowed }
        expect(unknown.isEmpty()) { "${node.path.ifEmpty { "the root" }} has unknown keys $unknown" }
        return obj.orEmpty().keys.map { it to node[it] }
    }

    /** [value] when nothing was wrong, else the first problem. */
    fun <T> finish(value: T): Outcome<T, AssetError> =
        problem?.let { Outcome.Err(AssetError(file, it)) } ?: Outcome.Ok(value)
}

private fun <T : Any> AssetDecoder.primitive(node: JsonNode, kind: String, read: (JsonPrimitive) -> T?): T? {
    val value = (node.element as? JsonPrimitive)?.let(read)
    expect(value != null) { describe(node, kind) }
    return value
}

/** The problem text for [node] not being [kind]. */
internal fun describe(node: JsonNode, kind: String): String =
    if (node.element == null) "${node.path} is missing" else "${node.path} must be $kind"
