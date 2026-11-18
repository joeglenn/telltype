package net.jbglenn.telltype.core.model.session

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.lengthOnly
import net.jbglenn.telltype.core.model.speech.Transcript

/**
 * The output of one reducer step: the next state and the side effects the coordinator must run, in order.
 *
 * @property state the next state (may equal the current one).
 * @property effects effects to run in list order; empty when nothing happens outside the state.
 */
public data class Transition(public val state: SessionState, public val effects: List<SessionEffect>)

/**
 * A timer the reducer asks for. The coordinator owns the durations; the values below are the plan's initial
 * targets. Scheduling a timer that is already running restarts it.
 */
public enum class SessionTimer {
    /** One minute before [SESSION_LIMIT]: warn the user. Duration: `Settings.dictation.sessionLimit` minus 1 min. */
    SESSION_WARNING,

    /** The recording ends by itself and moves to Working. Duration: `Settings.dictation.sessionLimit`. */
    SESSION_LIMIT,

    /**
     * Done's chips (Raw, Undo, Paste, Copy) disappear. Duration: 5 s (Appendix H). After a hand-off it runs only
     * while a field has focus (the Paste chip waits for a field), see [HANDOFF_LIMIT].
     */
    DONE_CHIPS,

    /** Command Mode's Undo disappears. Duration: 10 s (section 4.7). */
    COMMAND_UNDO,

    /**
     * A problem with no action other than Dismiss closes by itself. Duration: 5 s (initial value chosen by A2a;
     * the plan does not fix one).
     */
    PROBLEM_DISMISS,

    /**
     * The overall limit of a hand-off's Done (Paste or Copy chip) when no field kept the focus for [DONE_CHIPS]:
     * the chip disappears; the text stays on the clipboard and in History. Duration: 2 min (package A7e).
     */
    HANDOFF_LIMIT,
}

/** Feedback for a moment of the session; the adapter plays a haptic and, when enabled, a sound (Appendix H). */
public enum class FeedbackCue {
    /** Recording started (tick, optional start tone). */
    RECORDING_STARTED,

    /** Recording stopped (tick, optional stop tone). */
    RECORDING_STOPPED,

    /** Text arrived (light haptic). */
    DONE,

    /** A problem state appeared (double haptic, "reject"). */
    PROBLEM,
}

/** A short-lived chip next to the bubble that does not change the state. */
public sealed interface Chip {
    /** "Working on it": a press during Working was ignored. */
    public data object Busy : Chip

    /** One minute of recording left. */
    public data object SessionEndingSoon : Chip

    /**
     * Why the bubble is unavailable, with its Fix.
     *
     * @property reason the reason.
     */
    public data class Explanation(public val reason: UnavailableReason) : Chip

    /**
     * "Learn X → Y"; tapping sends `LearnAccepted`.
     *
     * @property correction the pair.
     */
    public data class LearnOffer(public val correction: Correction) : Chip
}

/**
 * Every side effect the coordinator runs for the reducer (docs/PLAN.md section 6.3: pure reducer, effects
 * executed outside). Each says which event, if any, comes back. Effects of one session run in that session's child
 * job, so [CancelWork] cancels upload, transcription and cleanup together.
 */
public sealed interface SessionEffect {
    /**
     * Start a session: make a session id, create the recording file, open the microphone, and in parallel read
     * the field context (skipped for incognito fields) and pre-open the connection to the server. Replies
     * `RecordingStarted` or `RecordingFailed`. The effect handler keeps the context for the later [Clean].
     *
     * @property field the target field.
     * @property mode dictation or command.
     */
    public data class StartRecording(public val field: FieldTarget, public val mode: BubbleMode) : SessionEffect

    /** Cancel an unfinished [StartRecording]. No reply. */
    public data object AbortRecordingStart : SessionEffect

    /**
     * Stop the microphone, close the file and run the silence check. Replies `AudioFinalised` or `AudioFailed`.
     *
     * @property session the session.
     * @property recording the recording.
     */
    public data class StopRecording(public val session: SessionId, public val recording: RecordingId) :
        SessionEffect

    /**
     * Stop the microphone and delete the file without finalising. No reply.
     *
     * @property recording the recording.
     */
    public data class CancelRecording(public val recording: RecordingId) : SessionEffect

    /**
     * Delete a finalised recording (success, or an error that does not keep audio). No reply.
     *
     * @property recording the recording.
     */
    public data class DeleteRecording(public val recording: RecordingId) : SessionEffect

    /**
     * Transcribe with the dictionary as hints. Replies `Transcribed` or `TranscriptionFailed`.
     *
     * @property session the session.
     * @property clip the audio.
     */
    public data class Transcribe(public val session: Session, public val clip: AudioClip) : SessionEffect

    /**
     * Produce the text to insert from the transcript: snippets, short-phrase skip, the model with its deadline
     * (cleanup, or the rewrite in Command Mode), then dictionary replacements and field matching. Replies `Cleaned`
     * or, when the model path fails, `CleanupFailed`.
     *
     * @property session the session (its mode and field select prompt, tone and context).
     * @property transcript the raw transcript.
     */
    public data class Clean(public val session: Session, public val transcript: Transcript) : SessionEffect

    /**
     * Produce the text with rule-based cleanup only (after `CleanupFailed` in dictation). Replies `Cleaned` with
     * [status].
     *
     * @property session the session.
     * @property transcript the raw transcript.
     * @property status the status to record (`CleanupError.fallbackStatus`).
     */
    public data class CleanWithRules(
        public val session: Session,
        public val transcript: Transcript,
        public val status: CleanupStatus,
    ) : SessionEffect

    /**
     * Type [text] into [target] through the insertion ladder (steps 1 to 3). Replies `Inserted` or
     * `InsertionFailed`. Also used by Paste, with the field focused now as [target].
     *
     * @property session the session.
     * @property target the field to type into.
     * @property text the text.
     */
    public data class Insert(public val session: SessionId, public val target: FieldTarget, public val text: String) :
        SessionEffect {
        override fun toString(): String = "Insert(session=$session, target=$target, text=${text.lengthOnly()})"
    }

    /**
     * Replace the text a receipt describes (Raw, Undo). Replies `Inserted` or `InsertionFailed`.
     *
     * @property session the session.
     * @property receipt what to replace.
     * @property text the replacement (`""` removes the dictation).
     */
    public data class ReplaceInserted(
        public val session: SessionId,
        public val receipt: InsertReceipt,
        public val text: String,
    ) : SessionEffect {
        override fun toString(): String =
            "ReplaceInserted(session=$session, receipt=$receipt, text=${text.lengthOnly()})"
    }

    /**
     * Ladder step 4: put the text on the clipboard (flagged sensitive) unless "Never use the clipboard" is on.
     * Replies `HandedOff`.
     *
     * @property session the session.
     * @property text the text.
     */
    public data class HandOff(public val session: Session, public val text: String) : SessionEffect {
        override fun toString(): String = "HandOff(session=$session, text=${text.lengthOnly()})"
    }

    /**
     * Copy text because the user tapped Copy. No reply.
     *
     * @property text the text.
     */
    public data class CopyToClipboard(public val text: String) : SessionEffect {
        override fun toString(): String = "CopyToClipboard(text=${text.lengthOnly()})"
    }

    /**
     * Cancel the session's child job: any upload, transcription or cleanup in flight. No reply.
     *
     * @property session the session.
     */
    public data class CancelWork(public val session: SessionId) : SessionEffect

    /**
     * Write or update the session's history entry (key: session id). The handler adds what the reducer does not
     * know (start time, category, timings) and skips the write when history is switched off. Never emitted for
     * incognito fields. No reply; a failure is logged and does not affect the session.
     *
     * @property session the session.
     * @property transcript the raw transcript, null when transcription did not succeed.
     * @property text the final text, null when there is none yet.
     * @property insertedVia how the text reached the user so far.
     * @property error the error that ended the session, null otherwise.
     */
    public data class SaveHistory(
        public val session: Session,
        public val transcript: Transcript?,
        public val text: CleanedText?,
        public val insertedVia: InsertedVia,
        public val error: DictationError?,
    ) : SessionEffect

    /**
     * Hand the recording to the pending-job queue ("Saved. Will transcribe later"). Replies `QueueFailed` only on
     * failure.
     *
     * @property session the session (its app gives the category and tone).
     * @property clip the audio.
     */
    public data class EnqueuePendingJob(public val session: Session, public val clip: AudioClip) : SessionEffect

    /**
     * Play feedback. No reply.
     *
     * @property cue which moment.
     */
    public data class Haptic(public val cue: FeedbackCue) : SessionEffect

    /**
     * Show a transient chip. No reply.
     *
     * @property chip the chip.
     */
    public data class ShowChip(public val chip: Chip) : SessionEffect

    /**
     * Open the place where a problem is fixed. No reply.
     *
     * @property fix where.
     */
    public data class OpenFix(public val fix: FixTarget) : SessionEffect

    /**
     * Start (or restart) a timer; replies `TimerFired` when it expires.
     *
     * @property timer which one.
     */
    public data class ScheduleTimer(public val timer: SessionTimer) : SessionEffect

    /**
     * Stop a timer if it runs. No reply.
     *
     * @property timer which one.
     */
    public data class CancelTimer(public val timer: SessionTimer) : SessionEffect

    /**
     * Watch the target field for edits for the learning window (60 s), unless learning is off. Replies
     * `CorrectionDetected` for each single-word replacement found.
     *
     * @property receipt what was inserted and where.
     */
    public data class WatchFieldEdits(public val receipt: InsertReceipt) : SessionEffect

    /**
     * Store a learned replacement. No reply.
     *
     * @property correction the pair.
     */
    public data class SaveCorrection(public val correction: Correction) : SessionEffect

    /** Probe the server's health (when the bubble appears), for the offline mark. No reply to the reducer. */
    public data object ProbeServerHealth : SessionEffect
}
