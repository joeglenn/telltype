package net.jbglenn.telltype.data.mapping

import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/*
 * Small codecs shared by the row mappers: enums by stable id, instants as epoch milliseconds, JSON columns. Every
 * decoder is total: it returns null for a value it cannot read, and the row mapper decides what that means.
 */

/** The entry of [entries] whose [id] is [value], or null. */
internal inline fun <E : Enum<E>> enumById(entries: List<E>, value: String?, id: (E) -> String): E? =
    value?.let { stored -> entries.firstOrNull { id(it) == stored } }

/** Epoch milliseconds, as stored in every `*_at` column. */
internal fun Instant.toEpochMillis(): Long = toEpochMilliseconds()

/** The instant of a stored `*_at` column. */
internal fun instantOf(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)

/** `sounds_like`: a JSON list of strings. */
internal fun encodeStringList(values: List<String>): String =
    buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

/** Reads a JSON list of strings; null when the column is not one. */
internal fun decodeStringList(json: String): List<String>? {
    val strings = (parseJson(json) as? JsonArray)?.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    return strings?.takeIf { list -> list.none { it == null } }?.filterNotNull()
}

/** [json] parsed, or null when it is not JSON. */
internal fun parseJson(json: String): JsonElement? = try {
    Json.parseToJsonElement(json)
} catch (_: SerializationException) {
    null
}
