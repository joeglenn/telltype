package net.jbglenn.telltype.runtime

import kotlin.time.Instant
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleVisibilityTest {
    private val field = Fixtures.fieldTarget()
    private val available = Focus.Available(field, BubbleMode.DICTATE)
    private val session = Fixtures.session()

    private fun problem(focus: Focus) = SessionState.Problem(EmptyTranscript, session, keptAudio = null, focus = focus)

    private fun done(focus: Focus) =
        SessionState.Done(session, Fixtures.insertReceipt(), rawAlternative = null, focus = focus)

    @Test
    fun `a Problem waits hidden on a password field, in an excluded app or with no field`() {
        assertEquals(false, problem(Focus.None).showsBubble())
        assertEquals(true, problem(available).showsBubble())
        assertEquals(true, problem(Focus.Blocked(UnavailableReason.MICROPHONE_PERMISSION_MISSING)).showsBubble())
    }

    @Test
    fun `Done's chips wait hidden where the bubble may not appear`() {
        assertEquals(false, done(Focus.None).showsBubble())
        assertEquals(true, done(available).showsBubble())
    }

    @Test
    fun `a running session stays visible wherever the focus went, so it can be stopped`() {
        val recording = RecordingId("recording-1")
        val listening = SessionState.Listening(session, recording, Instant.fromEpochMilliseconds(0), Focus.None)
        assertEquals(true, listening.showsBubble())
        assertEquals(true, SessionState.Arming(field, BubbleMode.DICTATE, session.trigger, Focus.None).showsBubble())
    }

    @Test
    fun `idle states show exactly when they have a bubble`() {
        assertEquals(false, SessionState.Hidden.showsBubble())
        assertEquals(true, SessionState.Ready(field, BubbleMode.DICTATE).showsBubble())
    }
}
