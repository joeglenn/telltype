package net.jbglenn.telltype.ui.overlay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.ui.designsystem.MotionTokens
import net.jbglenn.telltype.ui.designsystem.error.messageRes

/** Maps the session state to what the bubble shows (docs/PLAN.md section 5.2, Appendix H). Pure. */
fun mapBubble(input: BubbleInput): BubbleScene {
    val bubble = mapState(input)
    val menu = input.menuOpen && bubble.holdOpensMenu
    return BubbleScene(
        bubble = bubble,
        chip = if (menu) null else input.chip?.let(::mapChip),
        side = input.side,
        menu = menu,
    )
}

/**
 * How long until [mapBubble] gives a different result for the same state, or null when it will not change by
 * itself. A host re-maps after this long (the timer ticks once per second).
 */
fun nextBubbleChange(state: SessionState, timeInState: Duration): Duration? = when (state) {
    is SessionState.Listening -> if (timeInState < MotionTokens.ListeningTimerDelay) {
        MotionTokens.ListeningTimerDelay - timeInState
    } else {
        OneSecond - (timeInState - timeInState.inWholeSeconds.seconds)
    }

    is SessionState.Working -> (MotionTokens.WorkingLabelDelay - timeInState).takeIf { it.isPositive() }

    is SessionState.Done -> (MotionTokens.DoneCheckDuration - timeInState).takeIf { it.isPositive() }

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Arming,
    is SessionState.Problem,
    -> null
}

private val OneSecond: Duration = 1.seconds

private fun mapState(input: BubbleInput): BubbleUiModel {
    val offline = input.reachability is ServerReachability.Unreachable
    return when (val state = input.state) {
        SessionState.Hidden -> BubbleUiModel.Hidden

        is SessionState.Ready -> BubbleUiModel.Ready(state.mode, offline)

        is SessionState.Unavailable -> BubbleUiModel.Unavailable(state.reason)

        is SessionState.Arming -> BubbleUiModel.Arming(state.mode)

        is SessionState.Listening -> {
            val hold = state.session.trigger == Trigger.HOLD
            BubbleUiModel.Listening(
                mode = state.session.mode,
                hold = hold,
                releaseToCancel = hold && input.releaseToCancel,
                elapsed = input.timeInState.takeIf { it >= MotionTokens.ListeningTimerDelay },
                offline = offline,
            )
        }

        is SessionState.Working -> BubbleUiModel.Working(
            label = state.stage.label.takeIf { input.timeInState >= MotionTokens.WorkingLabelDelay },
        )

        is SessionState.Done -> BubbleUiModel.Done(
            chips = if (input.timeInState >= MotionTokens.DoneCheckDuration) {
                state.actions.inDisplayOrder()
            } else {
                emptyList()
            },
        )

        is SessionState.Problem -> BubbleUiModel.Problem(
            message = state.error.messageRes(),
            actions = state.actions.inDisplayOrder(),
        )
    }
}

private val Stage.label: WorkingLabel
    get() = when (this) {
        Stage.Finalising, is Stage.Transcribing -> WorkingLabel.TRANSCRIBING
        is Stage.Cleaning, is Stage.Inserting -> WorkingLabel.POLISHING
    }

/** Retry, Fix, Copy, Paste, Raw, Undo, Dismiss: the enum's declaration order. */
private fun Set<BubbleAction>.inDisplayOrder(): List<BubbleAction> = sortedBy { it.ordinal }

private fun mapChip(chip: Chip): ChipUiModel = when (chip) {
    Chip.Busy -> ChipUiModel.Busy
    Chip.SessionEndingSoon -> ChipUiModel.SessionEndingSoon
    is Chip.Explanation -> ChipUiModel.Explanation(chip.reason)
    is Chip.LearnOffer -> ChipUiModel.LearnOffer(chip.correction)
}
