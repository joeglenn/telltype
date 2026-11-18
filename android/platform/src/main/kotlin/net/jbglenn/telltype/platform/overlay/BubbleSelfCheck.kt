package net.jbglenn.telltype.platform.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn

/**
 * The bubble's self-check (board B1): [DELAY_MS] after the bubble was shown, moved or dropped it reads the window
 * back ([condition]) and acts on [FrameCheck.verdict]. A shown bubble whose frame is not on the display, or is empty,
 * is logged as a warning with the numbers ([details]) and put at its default place ([placeAtDefault]), at most
 * [MAX_REPAIRS] times per show, so a display this check misjudges cannot keep the bubble moving. A drag with no
 * finger that did not move for [STALE_DRAG_MS] is ended the same way. While a finger is on the bubble it only looks
 * again later: it never moves a bubble the user is touching. Runs on the main thread.
 */
internal class BubbleSelfCheck(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val condition: () -> BubbleCondition?,
    private val details: () -> String,
    private val placeAtDefault: () -> Unit,
) {
    private var due: Job? = null
    private var repairs = 0

    /** A new show: the re-placements start from zero and a check is due. */
    fun restart() {
        repairs = 0
        schedule()
    }

    /** A check is due [DELAY_MS] from now (instead of one that was due). */
    fun schedule() {
        due?.cancel()
        due = scope.launch(dispatchers.main) {
            delay(DELAY_MS)
            due = null
            check()
        }
    }

    /** A check is due unless one already is (a drag moves the window many times a second). */
    fun scheduleIfIdle() {
        if (due?.isActive != true) schedule()
    }

    /** No check is due any more (the window was removed). */
    fun cancel() {
        due?.cancel()
        due = null
    }

    private fun check() {
        when (val verdict = FrameCheck.verdict(condition(), STALE_DRAG_MS)) {
            SelfCheckVerdict.Fine -> Unit

            SelfCheckVerdict.Recheck -> schedule()

            SelfCheckVerdict.NotShown -> logger.warn(TAG, null) { "bubble window open but not shown: ${details()}" }

            SelfCheckVerdict.StaleDrag -> {
                logger.warn(TAG, null) { "bubble drag never ended (no finger for $STALE_DRAG_MS ms): ${details()}" }
                placeAtDefault()
                schedule()
            }

            is SelfCheckVerdict.NotVisible -> if (repairs < MAX_REPAIRS) {
                repairs++
                logger.warn(TAG, null) {
                    "bubble not visible: ${verdict.problem}; ${details()}: placing it at its default place"
                }
                placeAtDefault()
                schedule()
            }
        }
    }

    internal companion object {
        private val TAG = LogTag("OverlayHost")

        /** How long after it was shown or moved the bubble's frame is read back (milliseconds). */
        const val DELAY_MS = 1_500L

        /** A drag with no finger that did not move for this long never ended (milliseconds). */
        const val STALE_DRAG_MS = 10_000L

        /** Re-placements the self-check may make per show. */
        const val MAX_REPAIRS = 3
    }
}
