package net.jbglenn.telltype.platform.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** When an overlay window counts as at rest, and how often the accessibility system is asked (board B3). */
class RestTrackerTest {
    private val reports = mutableListOf<Boolean>()
    private var now = 0L
    private var systemAsked = 0
    private var refreshed = 0
    private val tracker = RestTracker(
        onAtRest = { reports += it },
        onRestTimedOut = {},
        uptimeMillis = { now },
        askForFrame = {},
        beforeRest = { refreshed++ },
    )

    @Test
    fun `rest waits for the system's record of the frame, then the system is not asked again`() {
        var caughtUp = false
        val system = {
            systemAsked++
            caughtUp
        }
        repeat(3) { frame(system) }
        assertEquals("not at rest while the record lags", false, reports.last())
        caughtUp = true
        repeat(3) { frame(system) }
        assertEquals("at rest once it caught up", true, reports.last())
        assertEquals("Compose re-read its position once", 1, refreshed)
        val asked = systemAsked
        repeat(50) { frame(system) }
        assertEquals("frames drawn at rest (the waveform) ask the system nothing", asked, systemAsked)
    }

    @Test
    fun `a record that never catches up stops the wait after the timeout and exposes the controls`() {
        repeat(200) { frame { false } }
        assertEquals(true, reports.last())
    }

    private fun frame(system: () -> Boolean) {
        now += FRAME_MS
        tracker.frame(inPlace = true, systemCaughtUp = system)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
