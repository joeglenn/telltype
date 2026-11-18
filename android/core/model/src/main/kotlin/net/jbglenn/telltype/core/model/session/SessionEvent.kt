package net.jbglenn.telltype.core.model.session

import kotlin.time.Instant
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.speech.Transcript

/**
 * Every input the session reducer can receive. Events carry facts only (including any time stamp), so `reduce`
 * stays pure. The coordinator sends user gestures from the overlay, environment changes, and the result of every
 * effect that has one. An event that does not apply to the current state leaves it unchanged with no effects.
 */
public sealed interface SessionEvent {
    /**
     * What is focused changed: field, keyboard, selection, app rule, settings or permissions.
     *
     * @property focus the new focus.
     */
    public data class FocusChanged(public val focus: Focus) : SessionEvent

    /** Finger down on the bubble (any state). From Ready it starts arming at once. */
    public data object MicPressed : SessionEvent

    /** The press lasted past the long-press threshold: the trigger becomes [Trigger.HOLD]. */
    public data object HoldDetected : SessionEvent

    /** Finger up. Finishes a hold; ignored after a tap (the recording continues). */
    public data object MicReleased : SessionEvent

    /**
     * The press turned into a drag (moving the bubble): the recording opened by this press is discarded. The overlay
     * sends it only while the press that started the session is still down.
     */
    public data object DragStarted : SessionEvent

    /** ✓ tapped while listening. */
    public data object FinishTapped : SessionEvent

    /** ✕ tapped while arming, listening or working, or a hold released after sliding away. */
    public data object CancelTapped : SessionEvent

    /**
     * A chip or button tapped.
     *
     * @property action which one; ignored when the current state does not offer it.
     */
    public data class ActionTapped(public val action: BubbleAction) : SessionEvent

    /**
     * Reply to `StartRecording`: audio is flowing. Arriving outside Arming (the start was cancelled), the reducer
     * cancels the recording.
     *
     * @property session the new session's id, made by the coordinator.
     * @property recording the file being written.
     * @property startedAt when audio started (wall clock).
     */
    public data class RecordingStarted(
        public val session: SessionId,
        public val recording: RecordingId,
        public val startedAt: Instant,
    ) : SessionEvent

    /**
     * Reply to `StartRecording`: the recorder could not start.
     *
     * @property error why.
     */
    public data class RecordingFailed(public val error: CaptureError) : SessionEvent

    /**
     * Screen off, call, or microphone lost while recording: Listening ends and moves to Working with what was
     * captured.
     *
     * @property cause what happened.
     */
    public data class Interrupted(public val cause: Interruption) : SessionEvent

    /**
     * A timer scheduled with `ScheduleTimer` expired.
     *
     * @property timer which one.
     */
    public data class TimerFired(public val timer: SessionTimer) : SessionEvent

    /**
     * The edit watcher saw the user replace one word of the dictated text with another.
     *
     * @property correction the pair.
     */
    public data class CorrectionDetected(public val correction: Correction) : SessionEvent

    /**
     * The "Learn X → Y" chip was tapped.
     *
     * @property correction the pair to learn.
     */
    public data class LearnAccepted(public val correction: Correction) : SessionEvent

    /**
     * The reply to an effect that belongs to one session. A result whose [session] is not the current session
     * (it raced with a cancel) is ignored.
     */
    public sealed interface StageResult : SessionEvent {
        /** The session the effect was run for. */
        public val session: SessionId
    }

    /**
     * Reply to `StopRecording`: the file is closed and passed the silence check.
     *
     * @property clip the finished audio.
     */
    public data class AudioFinalised(override val session: SessionId, public val clip: AudioClip) : StageResult

    /**
     * Reply to `StopRecording`: no usable audio (`NoSpeech`, `MicrophoneInUse`, storage).
     *
     * @property error why.
     */
    public data class AudioFailed(override val session: SessionId, public val error: CaptureError) : StageResult

    /**
     * Reply to `Transcribe`.
     *
     * @property transcript the raw transcript.
     */
    public data class Transcribed(override val session: SessionId, public val transcript: Transcript) : StageResult

    /**
     * Reply to `Transcribe`: failed after the engine's own retries and failover.
     *
     * @property error why.
     */
    public data class TranscriptionFailed(override val session: SessionId, public val error: SttError) : StageResult

    /**
     * Reply to `Clean` or `CleanWithRules`: the text to insert.
     *
     * @property text the text and how it was produced.
     */
    public data class Cleaned(override val session: SessionId, public val text: CleanedText) : StageResult

    /**
     * Reply to `Clean`: the model path failed. Dictation falls back to `CleanWithRules`; Command Mode ends in
     * Problem.
     *
     * @property error why.
     */
    public data class CleanupFailed(override val session: SessionId, public val error: CleanupError) : StageResult

    /**
     * Reply to `Insert` or `ReplaceInserted`: the text is in the field.
     *
     * @property receipt where and how.
     */
    public data class Inserted(override val session: SessionId, public val receipt: InsertReceipt) : StageResult

    /**
     * Reply to `Insert` or `ReplaceInserted`: no ladder step succeeded or the target is not focused.
     *
     * @property error why.
     */
    public data class InsertionFailed(override val session: SessionId, public val error: InsertionError) :
        StageResult

    /**
     * Reply to `HandOff`: the text is on the clipboard ([InsertReceipt.via] `HANDOFF`) or, with "Never use the
     * clipboard", only in History (`NONE`).
     *
     * @property receipt the hand-off receipt.
     */
    public data class HandedOff(override val session: SessionId, public val receipt: InsertReceipt) : StageResult

    /**
     * Reply to `EnqueuePendingJob` when the job could not be stored. The file stays on disk and is found as an
     * orphan at the next start.
     *
     * @property error why.
     */
    public data class QueueFailed(override val session: SessionId, public val error: StorageError) : StageResult
}
