package net.jbglenn.telltype.ui.overlay.gesture

import kotlin.math.hypot
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.ui.overlay.BubbleEvent

/**
 * The bubble's press gesture as a pure state machine, one instance per press (docs/PLAN.md sections 4.1, 5.2,
 * 7.3). A press that can start a session ([startsSession]) starts it only once it is known not to be a drag
 * (A8c, decision D57): on the Pixel every press that became a drag started the microphone service, and Android's
 * microphone indicator, only to discard it. So:
 * - released without moving beyond [touchSlop] before the start threshold: a tap, `MicPressed` then `MicReleased`
 *   (which the reducer ignores after a tap) at the lift;
 * - still within [touchSlop] at the start threshold (150 ms, [startTimeout]): `MicPressed` there, so hold-to-talk
 *   records from then on; a release before the hold threshold is a tap (`MicReleased`, ignored after a tap);
 * - held past the hold threshold: hold-to-talk, `HoldDetected` (and `MicPressed` first if it was not sent yet);
 *   sliding beyond [cancelDistance] shows "release to cancel", and releasing there sends `CancelTapped`, anywhere
 *   else `MicReleased` (finish);
 * - moved beyond [touchSlop] before the start threshold: a drag; no session was started, and the bubble follows the
 *   finger ([BubbleEvent.Moved]);
 * - moved beyond [touchSlop] after the start threshold but before the hold threshold: a slow drag of the bubble whose
 *   session just started: `DragStarted` cancels that session (no recording is kept) and the bubble follows the
 *   finger.
 * A press that cannot start a session (Unavailable explains, Working says busy) sends `MicPressed` at finger down as
 * before.
 *
 * The bubble menu ("Never show in this app", docs/PLAN.md section 5.2) shares the long press with hold-to-talk, so
 * the rule is: a press held past the threshold is hold-to-talk wherever a press can start dictation (Ready, Done,
 * Problem). Only when [holdOpensMenu] is set (the Unavailable bubble, where a press only explains) does the same
 * long press send [BubbleEvent.MenuRequested] instead of `HoldDetected`; the release that follows sends nothing.
 * In every state the same choices stay reachable by dragging onto the snooze tray and, for screen readers, as
 * custom actions.
 *
 * @property touchSlop distance in pixels before a press counts as a drag.
 * @property cancelDistance distance in pixels a held finger must slide away to cancel.
 * @property startsSession whether this press starts a session (the bubble was Ready, Done or Problem); its
 *   `MicPressed` waits for the lift or the hold threshold.
 * @property holdOpensMenu whether a long press opens the bubble menu instead of starting hold-to-talk.
 */
class PressTracker(
    private val touchSlop: Float,
    private val cancelDistance: Float,
    private val startsSession: Boolean,
    private val holdOpensMenu: Boolean = false,
) {
    private var phase: Phase = Phase.Idle
    private var grabX = 0f
    private var grabY = 0f
    private var started = false
    private var stillLongEnough = false

    /** Whether the hold threshold still matters (the press is neither a hold nor a drag yet). */
    val awaitingHold: Boolean get() = phase == Phase.Pressed

    /** Whether the start threshold still matters (the press is still young and has not moved). */
    val awaitingStart: Boolean get() = phase == Phase.Pressed && !stillLongEnough

    /** Whether the press has become a drag (the caller then consumes the pointer changes). */
    val dragging: Boolean get() = phase == Phase.Dragging

    /** Finger down at ([grabX], [grabY]) in the window that hosts the bubble (for [BubbleEvent.Moved]). */
    fun down(grabX: Float = 0f, grabY: Float = 0f): List<BubbleEvent> {
        this.grabX = grabX
        this.grabY = grabY
        phase = Phase.Pressed
        started = false
        stillLongEnough = false
        return if (startsSession) emptyList() else listOf(BubbleEvent.Session(SessionEvent.MicPressed))
    }

    /**
     * The start threshold passed with the finger still within [touchSlop]: a press that starts a session starts it
     * now (`MicPressed`). Nothing for a press that cannot start a session or opens the menu on a hold.
     */
    fun startTimeout(): List<BubbleEvent> {
        if (!awaitingStart) return emptyList()
        stillLongEnough = true
        return if (startsSession && !holdOpensMenu) listOfNotNull(pressedNow()) else emptyList()
    }

    /** The hold threshold passed without a release or a drag. */
    fun holdTimeout(): List<BubbleEvent> = when {
        phase != Phase.Pressed -> emptyList()

        holdOpensMenu -> {
            phase = Phase.MenuShown
            listOf(BubbleEvent.MenuRequested)
        }

        else -> {
            phase = Phase.Holding(slidAway = false)
            listOfNotNull(pressedNow(), BubbleEvent.Session(SessionEvent.HoldDetected))
        }
    }

    /**
     * The finger is at ([x], [y]) on the screen, ([dx], [dy]) pixels from where it went down. Screen coordinates,
     * not the bubble's own: the bubble's window moves during a drag, so positions inside it drift with the window.
     */
    fun move(dx: Float, dy: Float, x: Float, y: Float): List<BubbleEvent> {
        val distance = hypot(dx, dy)
        return when (val current = phase) {
            Phase.Pressed -> if (distance > touchSlop) startDrag(BubbleEvent.Moved(x, y, grabX, grabY)) else emptyList()

            is Phase.Holding -> {
                val slidAway = distance > cancelDistance
                if (slidAway == current.slidAway) {
                    emptyList()
                } else {
                    phase = Phase.Holding(slidAway)
                    listOf(BubbleEvent.HoldFeedback(slidAway))
                }
            }

            Phase.Dragging -> listOf(BubbleEvent.Moved(x, y, grabX, grabY))

            Phase.Idle, Phase.MenuShown -> emptyList()
        }
    }

    /** Finger up. */
    fun up(): List<BubbleEvent> {
        val events = when (val current = phase) {
            Phase.Pressed -> listOfNotNull(pressedNow(), BubbleEvent.Session(SessionEvent.MicReleased))

            is Phase.Holding -> if (current.slidAway) {
                listOf(BubbleEvent.HoldFeedback(false), BubbleEvent.Session(SessionEvent.CancelTapped))
            } else {
                listOf(BubbleEvent.Session(SessionEvent.MicReleased))
            }

            Phase.Dragging -> listOf(BubbleEvent.MoveEnded)

            Phase.Idle, Phase.MenuShown -> emptyList()
        }
        phase = Phase.Idle
        return events
    }

    /**
     * The system took the touch away (for example a notification shade): a hold is cancelled rather than
     * finished, because the user did not choose to finish.
     */
    fun cancel(): List<BubbleEvent> {
        val events = when (val current = phase) {
            // A deferred press started nothing before the start threshold and cancels what it started after it; one
            // that cannot start a session pressed at finger down.
            Phase.Pressed -> when {
                started -> listOf(BubbleEvent.Session(SessionEvent.CancelTapped))
                startsSession -> emptyList()
                else -> listOf(BubbleEvent.Session(SessionEvent.MicReleased))
            }

            is Phase.Holding -> buildList {
                if (current.slidAway) add(BubbleEvent.HoldFeedback(false))
                add(BubbleEvent.Session(SessionEvent.CancelTapped))
            }

            Phase.Dragging -> listOf(BubbleEvent.MoveEnded)

            Phase.Idle, Phase.MenuShown -> emptyList()
        }
        phase = Phase.Idle
        return events
    }

    /** The press became a drag; a session it started at the start threshold is cancelled first (D57). */
    private fun startDrag(moved: BubbleEvent.Moved): List<BubbleEvent> {
        phase = Phase.Dragging
        return listOfNotNull(BubbleEvent.Session(SessionEvent.DragStarted).takeIf { started }, moved)
    }

    /**
     * The deferred `MicPressed` of a press that starts a session, sent once (at the start threshold, or at a lift or
     * the hold threshold that comes first).
     */
    private fun pressedNow(): BubbleEvent? {
        if (!startsSession || started) return null
        started = true
        return BubbleEvent.Session(SessionEvent.MicPressed)
    }

    private sealed interface Phase {
        data object Idle : Phase

        data object Pressed : Phase

        data class Holding(val slidAway: Boolean) : Phase

        data object Dragging : Phase

        data object MenuShown : Phase
    }
}
