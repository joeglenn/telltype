package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.e2e.Device.uiDevice
import net.jbglenn.telltype.e2e.Scenario.PHOEBE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rows of the error table (docs/PLAN.md section 10) that the debug build's fake engines can force, plus the real
 * silent microphone for "no speech": what the user sees, what is typed and what is kept.
 */
@RunWith(AndroidJUnit4::class)
class ErrorTableTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults()
    }

    @After
    fun tearDown() {
        Bubble.faults()
        Bubble.fakeEngines(false)
        Arrange.excluded(HOST, excluded = false)
        Arrange.settings { it.copy(insertion = it.insertion.copy(neverUseClipboard = false)) }
    }

    @Test
    fun noSpeechUploadsNothingAndKeepsNoAudio() {
        Bubble.fakeEngines(false)
        Bubble.marker("clip-microphone", on = false)
        val before = Arrange.latestHistory()?.id
        Scenario.field()
        Scenario.dictate()
        val state = Bubble.expectState("Problem")
        Device.screenshot("et-no-speech")
        assertEquals("Problem. Didn't catch that", state)
        assertEquals("no recording kept", emptyList<String>(), Device.awaitNoRecordings())
        assertEquals("nothing queued", 0, Arrange.pendingJobs().size)
        val latest = Arrange.latestHistory()
        if (latest?.id != before) {
            assertEquals("History keeps only the failure", "", latest?.finalText)
            assertEquals("History records why", "no_speech", latest?.errorCode)
        }
    }

    @Test
    fun emptyTranscriptOffersRetryAndKeepsTheAudioUntilThen() {
        Bubble.faults("stt=empty")
        Scenario.field()
        Scenario.dictate()
        assertEquals("Problem. Didn't catch that", Bubble.expectState("Problem"))
        Bubble.control("Retry")
        Device.screenshot("et-empty-transcript")
        assertTrue("the recording is kept for Retry", Device.recordings().isNotEmpty())
        Scenario.assertNotTyped()
        Bubble.faults()
        Bubble.actionClick("Retry")
        assertTrue("Retry typed the text", Scenario.awaitFieldText(PHOEBE))
        assertEquals("no recording left after Retry", emptyList<String>(), Device.awaitNoRecordings())
    }

    @Test
    fun slowCleanupTypesTheRuleBasedTextAndHistorySaysSkipped() {
        Bubble.faults("cleanup=slow")
        Scenario.field()
        val started = android.os.SystemClock.uptimeMillis()
        Scenario.dictate()
        assertTrue("text typed despite the slow cleanup", Scenario.awaitFieldText(PHOEBE, limit = 30_000L))
        val took = android.os.SystemClock.uptimeMillis() - started
        Device.screenshot("et-cleanup-slow")
        val entry = checkNotNull(Arrange.latestHistory()) { "no History entry" }
        Log.i(TAG, "cleanup slow: typed after $took ms, status ${entry.cleanupStatus}")
        assertEquals(CleanupStatus.SKIPPED_TIMEOUT, entry.cleanupStatus)
        assertFalse(
            "the fake cleaner's full stop is absent (rule-based text)",
            Scenario.fieldText().endsWith("portrait."),
        )
    }

    @Test
    fun fieldGoneBeforeTheTextArrivesOffersPasteAndKeepsTheTextInHistory() {
        Scenario.field()
        Scenario.dictate()
        uiDevice.pressHome()
        uiDevice.wait(Until.gone(By.pkg(HOST)), Device.WAIT_MS)
        assertTrue(
            "the dictation is saved in History",
            Scenario.eventually { Arrange.latestHistory()?.finalText?.contains("Phoebe") == true },
        )
        val handedOff = android.os.SystemClock.uptimeMillis()
        Device.openHostField("plain")
        Bubble.control("Paste")
        Log.i(TAG, "field gone: Paste shown ${android.os.SystemClock.uptimeMillis() - handedOff} ms after the hand-off")
        Device.screenshot("et-field-gone")
        assertEquals("handed off", "HANDOFF", Arrange.latestHistory()?.insertedVia?.name)
        Bubble.actionClick("Paste")
        assertTrue("Paste typed the text into the new field", Scenario.awaitFieldText(PHOEBE, limit = Device.WAIT_MS))
    }

    @Test
    fun neverUseTheClipboardOffersNoPaste() {
        Arrange.settings { it.copy(insertion = it.insertion.copy(neverUseClipboard = true)) }
        Scenario.field()
        Scenario.dictate()
        uiDevice.pressHome()
        uiDevice.wait(Until.gone(By.pkg(HOST)), Device.WAIT_MS)
        assertTrue(
            "the dictation is saved in History",
            Scenario.eventually { Arrange.latestHistory()?.finalText?.contains("Phoebe") == true },
        )
        Scenario.field()
        Device.screenshot("et-never-clipboard")
        val state = Bubble.state()
        Log.i(TAG, "never clipboard: bubble '$state', history via ${Arrange.latestHistory()?.insertedVia}")
        assertFalse(
            "no Paste chip when the clipboard must not be used",
            uiDevice.wait(Until.hasObject(By.desc(Bubble.text("Paste"))), Scenario.SHORT_WAIT_MS) ||
                uiDevice.hasObject(By.text(Bubble.text("Paste"))),
        )
    }

    @Test
    fun excludedAppShowsNoBubble() {
        Arrange.excluded(HOST, excluded = true)
        Device.openHostField("plain")
        assertFalse("no bubble in an excluded app", uiDevice.wait(Until.hasObject(By.desc(Bubble.NAME)), 4_000L))
        Device.screenshot("et-excluded")
        Arrange.excluded(HOST, excluded = false)
        Device.openHostField("plain")
        Bubble.expectState("Ready")
    }

    @Test
    fun problemBubbleIsHiddenInAnExcludedAppAndOnAPasswordField() {
        Bubble.faults("stt=empty")
        Scenario.field()
        Scenario.dictate()
        Bubble.expectState("Problem")
        Device.openHostField("password")
        val onPassword = uiDevice.wait(Until.hasObject(By.desc(Bubble.NAME)), 4_000L)
        Device.screenshot("et-problem-on-password")
        Arrange.excluded(HOST, excluded = true)
        Device.openHostField("plain")
        val onExcluded = uiDevice.wait(Until.hasObject(By.desc(Bubble.NAME)), 4_000L)
        Device.screenshot("et-problem-in-excluded-app")
        Arrange.excluded(HOST, excluded = false)
        Device.openHostField("plain")
        Bubble.expectState("Problem")
        Bubble.actionClick("Dismiss")
        assertFalse("a Problem bubble over a password field", onPassword)
        assertFalse("a Problem bubble in an excluded app", onExcluded)
    }
}
