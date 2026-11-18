package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-table rows that need the server to misbehave, through `android/scripts/stub_gate.py` in front of the real gate
 * (same certificate). The host starts, before this class: `stub_gate.py --port 8178 --busy 1` (503 once) and
 * `stub_gate.py --port 8177 --bloat 4` (a cleanup reply the length guard rejects).
 */
@RunWith(AndroidJUnit4::class)
class StubGateTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(false)
    }

    @After
    fun restore() {
        Arrange.address(Arrange.GATE)
    }

    @Test
    fun warmingUpServerIsRetriedAndTheTextArrives() {
        Arrange.address(BUSY_ONCE)
        Scenario.field()
        Scenario.dictate()
        val seen = mutableSetOf<String>()
        val typed = Scenario.eventually(Scenario.LONG_WAIT_MS) {
            Bubble.state()?.let(seen::add)
            Scenario.fieldText().contains("wish")
        }
        Device.screenshot("et-warming-up-then-typed")
        Log.i(TAG, "warming up: states seen $seen")
        assertTrue("the text arrived after the 503", typed)
        assertTrue("never shown as queued: $seen", seen.none { it.contains("Will transcribe later") })
    }

    @Test
    fun cleanupRejectedByAGuardTypesTheRuleBasedText() {
        Arrange.address(BLOATED_CLEANUP)
        Scenario.field()
        Scenario.dictate()
        assertTrue("text typed", Scenario.awaitFieldText(SPOKEN))
        Device.screenshot("et-cleanup-guard")
        val entry = checkNotNull(Arrange.latestHistory()) { "no History entry" }
        Log.i(TAG, "guard: status ${entry.cleanupStatus}, ${entry.finalText.length} chars")
        assertEquals(CleanupStatus.SKIPPED_GUARD, entry.cleanupStatus)
        assertTrue("the bloated reply was not typed", Scenario.fieldText().length < BLOATED_LENGTH)
    }

    private companion object {
        /** What the server hears in the clip's first two seconds. */
        const val SPOKEN = "(?s).*wish.*"
        val BUSY_ONCE = ServerEndpoint("10.0.2.2", 8178)
        val BLOATED_CLEANUP = ServerEndpoint("10.0.2.2", 8177)

        /** Twice the clip's cleaned text: a typed reply this long would be the bloated one. */
        const val BLOATED_LENGTH = 260
    }
}
