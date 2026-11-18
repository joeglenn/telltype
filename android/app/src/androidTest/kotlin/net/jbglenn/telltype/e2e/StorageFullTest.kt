package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-table row "storage full" (docs/PLAN.md section 10) through the debug build's simulated full disk (lines of
 * the faults file, `DebugStorageOverride`): full at the start, and filling up in the middle of a recording, once
 * after speech (the part is kept and transcribed by the server) and once before any speech ("Storage is full").
 */
@RunWith(AndroidJUnit4::class)
class StorageFullTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(false)
        Bubble.faults()
    }

    @After
    fun tearDown() {
        Bubble.faults()
    }

    @Test
    fun aFullDiskAtTheStartRecordsNothing() {
        Bubble.faults("storage=full")
        Scenario.field()
        Bubble.await().click()
        val state = Bubble.expectState("Problem")
        Device.screenshot("et-storage-full-at-start")
        Log.i(TAG, "storage full at start: '$state', recordings ${Device.recordings()}")
        assertEquals("Problem. Storage is full", state)
        assertEquals("nothing recorded", emptyList<String>(), Device.recordings())
        Scenario.assertNotTyped()
    }

    @Test
    fun aDiskThatFillsAfterSpeechKeepsThePartAndTranscribesIt() {
        Bubble.faults("storage=fills-after=$THREE_SECONDS_BYTES")
        Scenario.field()
        // Earlier tests of the suite may keep audio on purpose (Retry, the queue): only this dictation's counts.
        val kept = Device.recordings().toSet()
        Bubble.startListening()
        val typed = Scenario.awaitFieldText("(?s).+")
        val text = Scenario.fieldText()
        Device.screenshot("et-storage-fills-after-speech")
        Log.i(TAG, "storage filled after speech: ${text.length} chars typed, bubble '${Bubble.state()}'")
        assertTrue("the part recorded before the disk filled was transcribed and typed", typed)
        assertFalse("only the first seconds of the clip", text.contains("portrait", ignoreCase = true))
        Scenario.eventually { (Device.recordings() - kept).isEmpty() }
        assertEquals("this dictation's recording was deleted", emptyList<String>(), Device.recordings() - kept)
    }

    @Test
    fun aDiskThatFillsBeforeSpeechSaysStorageIsFull() {
        Bubble.faults("storage=fills-after=$TENTH_SECOND_BYTES")
        Scenario.field()
        Bubble.await().click()
        val state = Bubble.awaitState(Scenario.LONG_WAIT_MS) { it?.startsWith("Problem") == true }
        Device.screenshot("et-storage-fills-before-speech")
        Log.i(TAG, "storage filled before speech: '$state', recordings ${Device.recordings()}")
        assertEquals("Problem. Storage is full", state)
        SystemClock.sleep(Scenario.SHORT_WAIT_MS)
        assertTrue("nothing typed", Scenario.fieldText().isEmpty())
    }

    private companion object {
        /** 16 kHz, 16-bit mono: 32 000 bytes a second. */
        const val THREE_SECONDS_BYTES = 96_000
        const val TENTH_SECOND_BYTES = 3_200
    }
}
