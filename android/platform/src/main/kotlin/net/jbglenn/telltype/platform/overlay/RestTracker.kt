package net.jbglenn.telltype.platform.overlay

/**
 * Whether an overlay window is at rest ([OverlayRoot]): it counts the frames drawn in place and reports at rest
 * after [FRAMES_TO_REST] of them, once the accessibility system's record of the frame has caught up too (asked only
 * while rest is still to be reported, so a window at rest that keeps drawing, like the Listening waveform, costs no
 * call into the system). Waiting is bounded: after [REST_TIMEOUT_MS] and [MIN_WAIT_FRAMES] frames out of rest it
 * stops asking for frames, reports at rest anyway (controls reachable) and calls [onRestTimedOut] once. Main thread.
 *
 * @param onAtRest called with whether the window is at rest.
 * @param onRestTimedOut called once when waiting for rest gave up.
 * @param uptimeMillis the clock (the main looper's, virtual in tests).
 * @param askForFrame asks for another frame (the frame that brings the window in place may draw nothing).
 * @param beforeRest called once each time, right before rest is reported (Compose re-reads its window position).
 */
internal class RestTracker(
    private val onAtRest: (Boolean) -> Unit,
    private val onRestTimedOut: () -> Unit,
    private val uptimeMillis: () -> Long,
    private val askForFrame: () -> Unit,
    private val beforeRest: () -> Unit,
) {
    private var framesAtRest = 0
    private var unsettledSince = SETTLED
    private var gaveUp = false
    private var framesWaited = 0
    private var restReported = false

    /**
     * Before each frame: [inPlace] is whether this process sees the window where it belongs, [systemCaughtUp]
     * whether the accessibility system's record agrees (asked only on the frame that would report rest).
     */
    fun frame(inPlace: Boolean, systemCaughtUp: () -> Boolean) {
        when {
            !inPlace -> leave()

            framesAtRest < FRAMES_TO_REST -> {
                framesAtRest++
                askForFrame()
            }

            restReported -> Unit

            !systemCaughtUp() -> leave()

            else -> {
                unsettledSince = SETTLED
                gaveUp = false
                beforeRest()
                restReported = true
                onAtRest(true)
            }
        }
    }

    /**
     * Not at rest; asks for another frame. After [REST_TIMEOUT_MS] and [MIN_WAIT_FRAMES] frames of this (counted
     * from [newWait], a size change, or from leaving rest) it gives up: no more frames, controls exposed.
     */
    fun leave(newWait: Boolean = false) {
        framesAtRest = 0
        restReported = false
        val now = uptimeMillis()
        if (newWait || unsettledSince == SETTLED) {
            unsettledSince = now
            gaveUp = false
            framesWaited = 0
        }
        when {
            gaveUp -> Unit

            now - unsettledSince >= REST_TIMEOUT_MS && framesWaited >= MIN_WAIT_FRAMES -> {
                gaveUp = true
                onAtRest(true)
                onRestTimedOut()
            }

            else -> {
                framesWaited++
                onAtRest(false)
                askForFrame()
            }
        }
    }

    private companion object {
        /** Frames drawn in place before the window counts as at rest (the second gives the system a frame). */
        const val FRAMES_TO_REST = 2

        /** How long the window may stay out of rest before the tracker stops waiting (milliseconds). */
        const val REST_TIMEOUT_MS = 500L

        /** Frames asked for before the tracker stops waiting, so a slow first frame does not count as a wait. */
        const val MIN_WAIT_FRAMES = 30

        /** [unsettledSince] while at rest. */
        const val SETTLED = -1L
    }
}
