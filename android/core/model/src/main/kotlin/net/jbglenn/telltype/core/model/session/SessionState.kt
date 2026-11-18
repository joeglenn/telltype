package net.jbglenn.telltype.core.model.session

import kotlin.time.Instant
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.lengthOnly
import net.jbglenn.telltype.core.model.speech.Transcript

/** What the bubble offers on the focused field. */
public enum class BubbleMode {
    /** Mic: speech becomes text at the cursor. */
    DICTATE,

    /** Wand: text is selected, so speech is an instruction that rewrites the selection (section 4.7). */
    COMMAND,
}

/** How the user started the recording. */
public enum class Trigger {
    /** Tap to start, tap ✓ to finish. Every press starts as this. */
    TAP,

    /** Press and hold; releasing finishes, sliding away and releasing cancels. */
    HOLD,
}

/**
 * Why the bubble is shown as unavailable (mic with a slash; tapping it explains).
 *
 * @property fix where the explanation's Fix action leads.
 */
public enum class UnavailableReason(public val fix: FixTarget) {
    /** The microphone permission is not granted. */
    MICROPHONE_PERMISSION_MISSING(FixTarget.MICROPHONE_PERMISSION),

    /** No server address, key or pinned certificate yet, and no on-device engine. */
    SERVER_NOT_CONFIGURED(FixTarget.SERVER_SETUP),
}

/**
 * What the bubble may do on the currently focused field: the reducer's view of the outside world. Computed by
 * the coordinator with `evaluateFocus` from the focused-field snapshot, the app rule, the settings and the
 * permissions, and delivered as `SessionEvent.FocusChanged` whenever it changes.
 */
public sealed interface Focus {
    /**
     * Nothing to serve: no editable field focused, keyboard hidden, password or numeric field, excluded app,
     * bubble switched off or snoozed.
     */
    public data object None : Focus

    /**
     * An eligible field has focus and the keyboard is up.
     *
     * @property field the focused field.
     * @property mode dictation, or command when text is selected.
     */
    public data class Available(public val field: FieldTarget, public val mode: BubbleMode) : Focus

    /**
     * An eligible field has focus, but dictation cannot start.
     *
     * @property reason why.
     */
    public data class Blocked(public val reason: UnavailableReason) : Focus
}

/**
 * One dictation, from the moment its recording started (docs/PLAN.md section 6.4).
 *
 * @property id the session id.
 * @property field the target field, captured at record start. Text is typed only into this field.
 * @property mode dictation or command, fixed at record start.
 * @property trigger tap or hold; a tap becomes a hold when the press lasts.
 */
public data class Session(
    public val id: SessionId,
    public val field: FieldTarget,
    public val mode: BubbleMode,
    public val trigger: Trigger,
)

/** Where a Working session is in the pipeline: Upload → Transcribe → Clean → Insert (docs/PLAN.md section 6.4). */
public sealed interface Stage {
    /** Recording stopped; waiting for the file to be closed and checked for silence. Shown as transcribing. */
    public data object Finalising : Stage

    /**
     * Uploading and transcribing (one request; the split is in [SessionTimings]). Label "Transcribing".
     *
     * @property clip the audio being transcribed.
     */
    public data class Transcribing(public val clip: AudioClip) : Stage

    /**
     * Cleaning up, or rewriting in Command Mode. Label "Polishing".
     *
     * @property transcript the raw transcript.
     */
    public data class Cleaning(public val transcript: Transcript) : Stage

    /**
     * Typing into the target field, or handing off.
     *
     * @property transcript the raw transcript.
     * @property text the text being inserted.
     */
    public data class Inserting(public val transcript: Transcript, public val text: CleanedText) : Stage
}

/** An action chip or button on the bubble; tapping one sends `SessionEvent.ActionTapped`. */
public enum class BubbleAction {
    /** Problem: run again (from the kept audio, or a new recording). */
    RETRY,

    /** Problem: open where the cause can be fixed. */
    FIX,

    /** Done after a hand-off without clipboard: copy the text now. */
    COPY,

    /** Done after a hand-off: insert the text into the field that has focus now. */
    PASTE,

    /** Done: swap the inserted text for the raw transcript. */
    RAW,

    /** Done: restore what the field held before (remove the dictation, or the selection before a command). */
    UNDO,

    /** Problem: close it. */
    DISMISS,
}

/**
 * The state of the bubble and of the one dictation session (docs/PLAN.md sections 5.2, 6.4 and 6.5). Exactly the
 * eight states of the plan. One instance lives in the coordinator's `StateFlow`; only `reduce` makes a new one.
 *
 * In-session states carry [Focus] as well as their session's target: the target is where the text goes, the focus
 * is what is on screen now. When a session ends, the bubble returns to the idle state for the current focus
 * ([idleState]).
 */
public sealed interface SessionState {
    /** No bubble: nothing eligible is focused (or Accessibility is off). */
    public data object Hidden : SessionState

    /**
     * Small mic (or wand) pill docked above the keyboard.
     *
     * @property field the focused field.
     * @property mode what a press will do.
     */
    public data class Ready(public val field: FieldTarget, public val mode: BubbleMode) : SessionState

    /**
     * Mic with a slash; a press explains why.
     *
     * @property reason why dictation cannot start.
     */
    public data class Unavailable(public val reason: UnavailableReason) : SessionState

    /**
     * Pressed; the recorder is opening (from touch-down, so the first syllable is not clipped). No session id yet:
     * it arrives with `RecordingStarted`.
     *
     * @property field the target field.
     * @property mode dictation or command.
     * @property trigger tap until a hold is detected.
     * @property focus what is focused now.
     */
    public data class Arming(
        public val field: FieldTarget,
        public val mode: BubbleMode,
        public val trigger: Trigger,
        public val focus: Focus,
    ) : SessionState

    /**
     * Recording: ✕, waveform, ✓.
     *
     * @property session the session.
     * @property recording the file being written.
     * @property startedAt when audio started flowing (the bubble's timer counts from here).
     * @property focus what is focused now; leaving the field does not stop the session.
     */
    public data class Listening(
        public val session: Session,
        public val recording: RecordingId,
        public val startedAt: Instant,
        public val focus: Focus,
    ) : SessionState

    /**
     * Processing: shimmer, label after 1.5 s. A press is ignored with a short hint; ✕ cancels.
     *
     * @property session the session.
     * @property recording the recording, kept until the session ends.
     * @property stage where in the pipeline.
     * @property focus what is focused now.
     */
    public data class Working(
        public val session: Session,
        public val recording: RecordingId,
        public val stage: Stage,
        public val focus: Focus,
    ) : SessionState

    /**
     * Check mark, then chips for a few seconds.
     *
     * @property session the finished session.
     * @property receipt where the text went.
     * @property rawAlternative the raw transcript for the Raw chip; null when Raw is not offered (Command Mode,
     *   snippet, hand-off, or the raw transcript equals the inserted text).
     * @property focus what is focused now.
     */
    public data class Done(
        public val session: Session,
        public val receipt: InsertReceipt,
        public val rawAlternative: String?,
        public val focus: Focus,
    ) : SessionState {
        /** The chips shown: Raw and Undo after typing, Paste after a hand-off, Copy when the clipboard was not used. */
        public val actions: Set<BubbleAction>
            get() = when (receipt.via) {
                InsertedVia.HANDOFF -> setOf(BubbleAction.PASTE)

                InsertedVia.NONE -> setOf(BubbleAction.COPY)

                InsertedVia.INPUT_CONNECTION, InsertedVia.SET_TEXT, InsertedVia.PASTE ->
                    if (rawAlternative == null) {
                        setOf(BubbleAction.UNDO)
                    } else {
                        setOf(BubbleAction.RAW, BubbleAction.UNDO)
                    }
            }

        override fun toString(): String = "Done(session=$session, receipt=$receipt, " +
            "rawAlternative=${rawAlternative?.lengthOnly()}, focus=$focus)"
    }

    /**
     * Amber pill with a short message and its actions.
     *
     * @property error what went wrong.
     * @property session the session that failed; null when the recording never started (no session id yet).
     * @property keptAudio audio kept for Retry, deleted when the problem is dismissed; null when none is kept here
     *   (deleted, handed to the queue, or never recorded). Never set without [session].
     * @property focus what is focused now.
     */
    public data class Problem(
        public val error: DictationError,
        public val session: Session?,
        public val keptAudio: AudioClip?,
        public val focus: Focus,
    ) : SessionState {
        init {
            require(keptAudio == null || session != null) { "kept audio always belongs to a session" }
        }

        /** Retry when the error is retryable, Fix when it has a fix, and always Dismiss. */
        public val actions: Set<BubbleAction>
            get() = buildSet {
                if (error.retryable) add(BubbleAction.RETRY)
                if (error.fix != null) add(BubbleAction.FIX)
                add(BubbleAction.DISMISS)
            }
    }
}

/** The idle state for this focus: [SessionState.Hidden], [SessionState.Ready] or [SessionState.Unavailable]. */
public fun Focus.idleState(): SessionState = when (this) {
    Focus.None -> SessionState.Hidden
    is Focus.Available -> SessionState.Ready(field, mode)
    is Focus.Blocked -> SessionState.Unavailable(reason)
}
