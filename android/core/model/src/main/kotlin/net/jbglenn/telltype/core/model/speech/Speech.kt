package net.jbglenn.telltype.core.model.speech

import kotlin.time.Duration
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.lengthOnly

/**
 * Hints passed to the speech engine with a clip (docs/PLAN.md sections 4.5 and 8.2). The language is fixed to
 * English (decision D8), so it is not a hint.
 *
 * @property terms the dictionary's correct spellings, sent in the request's `prompt` field; may be empty.
 */
public data class SttHints(public val terms: List<String>)

/**
 * What the speech engine heard: the raw transcript, before any cleanup.
 *
 * @property text the transcript. Never blank: an engine reports a blank result as `EmptyTranscript`.
 * @property audioDuration length of the audio the engine processed, as it reports it.
 * @property serverTimings the `Server-Timing` entries of the reply; empty for on-device or fake engines.
 */
public data class Transcript(
    public val text: String,
    public val audioDuration: Duration,
    public val serverTimings: List<ServerTiming>,
) {
    override fun toString(): String =
        "Transcript(text=${text.lengthOnly()}, audioDuration=$audioDuration, serverTimings=$serverTimings)"
}
