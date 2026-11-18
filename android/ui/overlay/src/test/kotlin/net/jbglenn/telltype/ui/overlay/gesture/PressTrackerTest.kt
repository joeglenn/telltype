package net.jbglenn.telltype.ui.overlay.gesture

import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.ui.overlay.BubbleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressTrackerTest {
    private fun tracker(startsSession: Boolean = true) =
        PressTracker(touchSlop = 10f, cancelDistance = 100f, startsSession = startsSession)

    @Test
    fun tapPressesAtTheLift() {
        val press = tracker()

        assertEquals("nothing starts at finger down", emptyList<BubbleEvent>(), press.down())
        assertEquals(emptyList<BubbleEvent>(), press.move(4f, 3f, 504f, 1003f))
        assertEquals(listOf(session(SessionEvent.MicPressed), session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun aPressThatCannotStartASessionStillPressesAtOnce() {
        val press = tracker(startsSession = false)

        assertEquals(listOf(session(SessionEvent.MicPressed)), press.down())
        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun aSystemCancelBeforeTheLiftStartsNothing() {
        val press = tracker()
        press.down()

        assertEquals(emptyList<BubbleEvent>(), press.cancel())
    }

    @Test
    fun holdThenReleaseFinishes() {
        val press = tracker()
        press.down()

        assertEquals(
            "the session starts at the threshold",
            listOf(session(SessionEvent.MicPressed), session(SessionEvent.HoldDetected)),
            press.holdTimeout(),
        )
        assertEquals("small movement keeps the hold", emptyList<BubbleEvent>(), press.move(30f, 0f, 530f, 1000f))
        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun holdSlideAwayAndReleaseCancels() {
        val press = tracker()
        press.down()
        press.holdTimeout()

        assertEquals(listOf(BubbleEvent.HoldFeedback(true)), press.move(0f, -120f, 500f, 880f))
        assertEquals("no repeat while still away", emptyList<BubbleEvent>(), press.move(0f, -130f, 500f, 870f))
        assertEquals(
            listOf(BubbleEvent.HoldFeedback(false), session(SessionEvent.CancelTapped)),
            press.up(),
        )
    }

    @Test
    fun slidingBackBeforeReleaseFinishesInstead() {
        val press = tracker()
        press.down()
        press.holdTimeout()
        press.move(0f, -120f, 500f, 880f)

        assertEquals(listOf(BubbleEvent.HoldFeedback(false)), press.move(0f, -20f, 500f, 980f))
        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun dragBeforeTheThresholdMovesTheBubbleAndNeverStartsASession() {
        val press = tracker(startsSession = true)
        assertEquals("the microphone does not start at finger down", emptyList<BubbleEvent>(), press.down())

        assertEquals(listOf(BubbleEvent.Moved(520f, 1000f, 0f, 0f)), press.move(20f, 0f, 520f, 1000f))
        assertTrue(press.dragging)
        assertEquals("a drag never becomes a hold", emptyList<BubbleEvent>(), press.holdTimeout())
        assertEquals(listOf(BubbleEvent.Moved(540f, 1015f, 0f, 0f)), press.move(40f, 15f, 540f, 1015f))
        assertEquals(listOf(BubbleEvent.MoveEnded), press.up())
    }

    @Test
    fun aDragCarriesWhereTheFingerWentDownInTheWindow() {
        val press = tracker(startsSession = false)
        press.down(grabX = 30f, grabY = 40f)

        assertEquals(listOf(BubbleEvent.Moved(560f, 1040f, 30f, 40f)), press.move(60f, 40f, 560f, 1040f))
    }

    @Test
    fun dragDuringASessionOnlyMovesTheBubble() {
        val press = tracker(startsSession = false)
        press.down()

        assertEquals(listOf(BubbleEvent.Moved(500f, 1050f, 0f, 0f)), press.move(0f, 50f, 500f, 1050f))
    }

    @Test
    fun systemCancelDuringAHoldCancelsTheRecording() {
        val press = tracker()
        press.down()
        press.holdTimeout()
        press.move(0f, 200f, 500f, 1200f)

        assertEquals(
            listOf(BubbleEvent.HoldFeedback(false), session(SessionEvent.CancelTapped)),
            press.cancel(),
        )
        assertEquals("idle after the press", emptyList<BubbleEvent>(), press.up())
    }

    @Test
    fun longPressOpensTheMenuWhereAPressCannotStartDictation() {
        val press = PressTracker(touchSlop = 10f, cancelDistance = 100f, startsSession = false, holdOpensMenu = true)
        press.down()

        assertEquals(listOf(BubbleEvent.MenuRequested), press.holdTimeout())
        assertEquals(
            "moving after the menu opened does nothing",
            emptyList<BubbleEvent>(),
            press.move(0f, -200f, 500f, 800f),
        )
        assertEquals("the release sends nothing", emptyList<BubbleEvent>(), press.up())
    }

    @Test
    fun shortPressWithTheMenuRuleIsStillATap() {
        val press = PressTracker(touchSlop = 10f, cancelDistance = 100f, startsSession = false, holdOpensMenu = true)

        assertEquals(listOf(session(SessionEvent.MicPressed)), press.down())
        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    private fun session(event: SessionEvent) = BubbleEvent.Session(event)

    @Test
    fun aStillPressStartsItsSessionAtTheStartThreshold() {
        val press = tracker()
        press.down()
        press.move(4f, 3f, 504f, 1003f)

        assertTrue(press.awaitingStart)
        assertEquals(
            "150 ms within the slop: the session starts",
            listOf(session(SessionEvent.MicPressed)),
            press.startTimeout(),
        )
        assertEquals("once", emptyList<BubbleEvent>(), press.startTimeout())
        assertEquals("the hold adds only its meaning", listOf(session(SessionEvent.HoldDetected)), press.holdTimeout())
        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun aReleaseBetweenTheThresholdsIsATapWithoutASecondPress() {
        val press = tracker()
        press.down()
        press.startTimeout()

        assertEquals(listOf(session(SessionEvent.MicReleased)), press.up())
    }

    @Test
    fun aPressThatMovesAfterItsSessionStartedCancelsIt() {
        val press = tracker()
        press.down()
        press.startTimeout()

        assertEquals(
            "a slow drag cancels the session it started, then moves the bubble",
            listOf(session(SessionEvent.DragStarted), BubbleEvent.Moved(530f, 1000f, 0f, 0f)),
            press.move(30f, 0f, 530f, 1000f),
        )
        assertEquals(listOf(BubbleEvent.Moved(560f, 1000f, 0f, 0f)), press.move(60f, 0f, 560f, 1000f))
        assertEquals(listOf(BubbleEvent.MoveEnded), press.up())
    }

    @Test
    fun aDragBeforeTheStartThresholdNeverStartsASession() {
        val press = tracker()
        press.down()

        val events = press.move(30f, 0f, 530f, 1000f) + press.startTimeout() + press.holdTimeout() + press.up()

        assertTrue("no session event: $events", events.none { it is BubbleEvent.Session })
    }

    @Test
    fun aSystemCancelAfterTheStartCancelsTheSession() {
        val press = tracker()
        press.down()
        press.startTimeout()

        assertEquals(listOf(session(SessionEvent.CancelTapped)), press.cancel())
    }

    @Test
    fun theStartThresholdStartsNothingWhereAPressCannotStartASession() {
        val press = tracker(startsSession = false)
        press.down()

        assertEquals(emptyList<BubbleEvent>(), press.startTimeout())
    }
}
