package net.jbglenn.telltype.core.model.session

import kotlin.time.Duration
import net.jbglenn.telltype.core.model.ServerTiming

/**
 * How long each stage of one session took (docs/PLAN.md sections 4.8 and 13: the speed readout and the session
 * trace). Measured by the coordinator with the monotonic clock; stored as JSON in `history.timings`. A stage that
 * did not run is null.
 *
 * @property recording microphone open to recording stopped.
 * @property finalising recording stopped to clip ready (file closed, silence check, encoding).
 * @property transcription request sent to transcript received, upload included (client side).
 * @property cleanup cleanup or Command Mode rewrite, including rule-based fallback and post-processing.
 * @property insertion insertion ladder, or hand-off.
 * @property endToEnd recording stopped to text in the field or handed off: the latency the user feels.
 * @property server the `Server-Timing` entries of every server reply in this session, in order.
 */
public data class SessionTimings(
    public val recording: Duration?,
    public val finalising: Duration?,
    public val transcription: Duration?,
    public val cleanup: Duration?,
    public val insertion: Duration?,
    public val endToEnd: Duration?,
    public val server: List<ServerTiming>,
) {
    public companion object {
        /** Nothing measured yet. */
        public val None: SessionTimings = SessionTimings(
            recording = null,
            finalising = null,
            transcription = null,
            cleanup = null,
            insertion = null,
            endToEnd = null,
            server = emptyList(),
        )
    }
}
