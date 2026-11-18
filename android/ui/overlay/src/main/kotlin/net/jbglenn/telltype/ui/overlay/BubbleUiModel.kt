package net.jbglenn.telltype.ui.overlay

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * Everything the bubble draws, made by [mapBubble] from the session state; [Bubble] renders it and holds no
 * logic of its own.
 *
 * @property bubble the bubble itself.
 * @property chip the transient chip above it, null when none is showing.
 * @property side the screen edge the bubble is docked to: the pill widens away from it and chips align to it.
 * @property menu the long-press menu is open above the bubble.
 * @property look the idle bubble's colour, transparency and microphone glyph (used by Ready only, decision D56).
 */
@Immutable
data class BubbleScene(
    val bubble: BubbleUiModel,
    val chip: ChipUiModel?,
    val side: BubbleSide,
    val menu: Boolean = false,
    val look: BubbleLook = BubbleLook.Default,
)

/** The bubble in one of the states of docs/PLAN.md section 5.2. */
@Immutable
sealed interface BubbleUiModel {
    /** Nothing is drawn. */
    data object Hidden : BubbleUiModel

    /**
     * The small mic (or wand) circle.
     *
     * @property mode mic for dictation, wand for Command Mode.
     * @property offline no answer to the last health probe: a small cloud-off mark (recording still works).
     */
    data class Ready(val mode: BubbleMode, val offline: Boolean) : BubbleUiModel

    /**
     * Mic with a slash; a press explains why.
     *
     * @property reason why dictation cannot start.
     */
    data class Unavailable(val reason: UnavailableReason) : BubbleUiModel

    /**
     * Pressed: the recorder is opening.
     *
     * @property mode mic or wand.
     */
    data class Arming(val mode: BubbleMode) : BubbleUiModel

    /**
     * The pill: ✕, live waveform, ✓.
     *
     * @property mode dictation or command.
     * @property hold the finger is down (hold-to-talk): releasing finishes.
     * @property releaseToCancel the held finger slid away: releasing now cancels.
     * @property elapsed recording time, shown from 10 s on; null before.
     * @property offline The server is unreachable (the recording will be queued).
     */
    data class Listening(
        val mode: BubbleMode,
        val hold: Boolean,
        val releaseToCancel: Boolean,
        val elapsed: Duration?,
        val offline: Boolean,
    ) : BubbleUiModel

    /**
     * The shimmer, then a stage label after 1.5 s.
     *
     * @property label the stage, null for the first 1.5 s.
     */
    data class Working(val label: WorkingLabel?) : BubbleUiModel

    /**
     * The check mark, then the chips.
     *
     * @property chips the chips in display order; empty while the check mark plays.
     */
    data class Done(val chips: List<BubbleAction>) : BubbleUiModel

    /**
     * The error pill: message and actions.
     *
     * @property message the error's message.
     * @property actions the chips in display order; [BubbleAction.DISMISS] is drawn as the pill's close button.
     */
    data class Problem(@param:StringRes val message: Int, val actions: List<BubbleAction>) : BubbleUiModel
}

/** The Working label (contracts: "Transcribing" for finalising and transcribing, "Polishing" for cleaning). */
enum class WorkingLabel(@param:StringRes val text: Int) {
    /** Finalising, uploading, transcribing. */
    TRANSCRIBING(R.string.bubble_working_transcribing),

    /** Cleaning up (or rewriting in Command Mode) and typing. */
    POLISHING(R.string.bubble_working_polishing),
}

/** A short-lived chip above the bubble (contracts `Chip`). */
@Immutable
sealed interface ChipUiModel {
    /** A press during Working was ignored. */
    data object Busy : ChipUiModel

    /** One minute of recording left. */
    data object SessionEndingSoon : ChipUiModel

    /**
     * Why the bubble is unavailable, with Fix.
     *
     * @property reason the reason.
     */
    data class Explanation(val reason: UnavailableReason) : ChipUiModel

    /**
     * "Learn X → Y"; tapping learns the pair.
     *
     * @property correction the pair.
     */
    data class LearnOffer(val correction: Correction) : ChipUiModel
}
