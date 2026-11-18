package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Package V1, snippets (docs/PLAN.md section 4.6), done when "whole-utterance triggers insert in under one second
 * after transcription (initial target) and never reach the model". The clip says "My address."; the saved text is
 * typed, History records a snippet with the model skipped and no model timing from the server, and the time from
 * the transcript to the typed text (the text stage plus typing) stays under a second.
 */
@RunWith(AndroidJUnit4::class)
class SnippetTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        FeatureDictation.realEngines()
        Arrange.clearSnippets()
        Arrange.settings { it.copy(cleanup = it.cleanup.copy(enabled = true)) }
        Arrange.addSnippet(TRIGGER, SAVED)
    }

    @After
    fun tearDown() {
        Arrange.clearSnippets()
        FeatureDictation.defaultClip()
    }

    @Test
    fun aWholeUtteranceTriggerInsertsItsTextWithinASecondWithoutTheModel() {
        val typed = FeatureDictation.dictate("snippet")
        Device.screenshot("v1-snippet-typed")
        assertEquals("the saved text was typed", SAVED, typed.trim())

        val entry = FeatureDictation.lastEntry()
        assertEquals(DictationMode.SNIPPET, entry.mode)
        assertEquals("the model was skipped", CleanupStatus.SKIPPED_SHORT, entry.cleanupStatus)
        val timings = entry.timings
        val afterTranscript = (timings.cleanup ?: Duration.INFINITE) + (timings.insertion ?: Duration.INFINITE)
        val server = timings.server.map { it.name }
        Log.i(TAG, "v1 snippet: transcript to typed $afterTranscript (text ${timings.cleanup}), server $server")
        assertTrue("transcript to typed text $afterTranscript < 1 s", afterTranscript < 1.seconds)
        assertTrue(
            "no model time from the server: $server",
            server.none { it.contains("llm") || it.contains("cleanup") },
        )
        assertEquals("the snippet's use was counted", 1, Arrange.snippets().single().uses)
    }

    private companion object {
        const val TRIGGER = "my address"
        const val SAVED = "221B Baker Street, London NW1 6XE"
    }
}
