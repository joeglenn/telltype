package net.jbglenn.telltype.data.mapping

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.session.SessionTimings

/**
 * `history.timings`: `{"recording_ms": 1234.5, ..., "server": [{"name": "stt", "ms": 125.4}]}`. A stage that did
 * not run is absent.
 */
internal fun encodeTimings(timings: SessionTimings): String = buildJsonObject {
    stageFields(timings).forEach { (name, duration) ->
        duration?.let { put(name, it.toDouble(DurationUnit.MILLISECONDS)) }
    }
    putJsonArray(TimingField.SERVER) {
        timings.server.forEach { timing ->
            add(
                buildJsonObject {
                    put(TimingField.NAME, timing.name)
                    put(TimingField.MS, timing.duration.toDouble(DurationUnit.MILLISECONDS))
                },
            )
        }
    }
}.toString()

/** Reads `history.timings`; null when the column is not the documented shape. */
internal fun decodeTimings(json: String): SessionTimings? {
    val root = parseJson(json) as? JsonObject
    val server = (root?.get(TimingField.SERVER) as? JsonArray)?.map(::serverTiming)
    if (root == null || server == null || server.any { it == null }) return null
    fun stage(name: String): Duration? = root[name]?.let(::milliseconds)
    return SessionTimings(
        recording = stage(TimingField.RECORDING),
        finalising = stage(TimingField.FINALISING),
        transcription = stage(TimingField.TRANSCRIPTION),
        cleanup = stage(TimingField.CLEANUP),
        insertion = stage(TimingField.INSERTION),
        endToEnd = stage(TimingField.END_TO_END),
        server = server.filterNotNull(),
    )
}

private fun serverTiming(element: JsonElement): ServerTiming? {
    val entry = element as? JsonObject
    val name = (entry?.get(TimingField.NAME) as? JsonPrimitive)?.takeIf { it.isString }?.content
    val duration = entry?.get(TimingField.MS)?.let(::milliseconds)
    return if (name == null || duration == null) null else ServerTiming(name, duration)
}

private fun stageFields(timings: SessionTimings): List<Pair<String, Duration?>> = listOf(
    TimingField.RECORDING to timings.recording,
    TimingField.FINALISING to timings.finalising,
    TimingField.TRANSCRIPTION to timings.transcription,
    TimingField.CLEANUP to timings.cleanup,
    TimingField.INSERTION to timings.insertion,
    TimingField.END_TO_END to timings.endToEnd,
)

private fun milliseconds(element: JsonElement): Duration? = (element as? JsonPrimitive)?.takeUnless {
    it.isString
}?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }?.milliseconds

/** Field names of the `history.timings` JSON. */
private object TimingField {
    const val RECORDING = "recording_ms"
    const val FINALISING = "finalising_ms"
    const val TRANSCRIPTION = "transcription_ms"
    const val CLEANUP = "cleanup_ms"
    const val INSERTION = "insertion_ms"
    const val END_TO_END = "end_to_end_ms"
    const val SERVER = "server"
    const val NAME = "name"
    const val MS = "ms"
}
