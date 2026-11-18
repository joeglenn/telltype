package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Package V1, feature C1 (docs/PLAN.md section 4.7), done when "ten standard commands produce correct rewrites and
 * Undo restores the exact original every time". Each command: a selection in the test host's field, the wand
 * bubble, the instruction spoken by a synthetic clip, the live assistant model's rewrite in place of the selection,
 * checked by a structural rule (the model's wording varies), then Undo and the exact original back. The last command
 * waits 7 s after the test sees the rewrite (about 8 s after it was typed) before Undo, which is offered for ten
 * seconds from typing.
 */
@RunWith(AndroidJUnit4::class)
class CommandModeTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    /** A standard command: the selected text, the clip that speaks the instruction, what a correct rewrite shows. */
    private data class Command(val original: String, val clip: String, val correct: (String) -> Boolean)

    private val commands = listOf(
        Command("We need milk, eggs, bread and butter.", "command-list") { rewrite ->
            rewrite.lines().count { it.trimStart().matches(Regex("^([-*•]|\\d+[.)]) .*")) } >= ITEMS
        },
        Command(LONG, "command-shorter") { it.length < LONG.length },
        Command("Send me the report now.", "command-polite") { it.contains(Regex("(?i)please|could|would|kindly")) },
        Command("hey guys, gonna be late, sorry", "command-formal") { !it.contains("gonna", ignoreCase = true) },
        Command("Their going to the libary tomorow.", "command-grammar") {
            (it.contains("They're") || it.contains("They are")) && it.contains("library") && it.contains("tomorrow")
        },
        Command(FORMAL, "command-casual") { it.isNotBlank() && it != FORMAL },
        Command("You finished the report.", "command-question") { it.trimEnd().endsWith("?") },
        Command("please call me back", "command-capitals") { it.any(Char::isLetter) && it == it.uppercase() },
        Command("The files are ready for review.", "command-greeting") {
            it.contains(Regex("(?i)^\\W*(hi|hello|hey|good|dear|greetings)")) && it.contains("files")
        },
        Command("The build passed. The tests are green. Lunch is at noon.", "command-last") {
            !it.contains("Lunch") && it.contains("build")
        },
    )

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        FeatureDictation.realEngines()
    }

    @After
    fun tearDown() {
        FeatureDictation.defaultClip()
    }

    @Test
    fun tenStandardCommandsRewriteAndUndoRestoresTheExactOriginal() {
        val wrong = mutableListOf<String>()
        commands.forEachIndexed { index, command ->
            val wandShot = "v1-c1-wand".takeIf { index == 0 }
            val rewrite = FeatureDictation.command(command.original, command.clip, wandShot)
            if (index == 0) Device.screenshot("v1-c1-rewrite")
            if (!command.correct(rewrite)) wrong += "${command.clip}: '$rewrite'"
            if (index == commands.lastIndex) SystemClock.sleep(LATE_UNDO_MS)
            Bubble.actionClick("Undo")
            assertTrue(
                "Undo restored the exact original after ${command.clip}, field '${Scenario.fieldText()}'",
                Scenario.eventually { Scenario.fieldText() == command.original },
            )
            Bubble.expectState("Ready")
        }
        Log.i(TAG, "v1 command mode: ${commands.size - wrong.size} of ${commands.size} correct")
        assertEquals("rewrites that missed their command: $wrong", emptyList<String>(), wrong)
    }

    private companion object {
        const val ITEMS = 3
        const val LATE_UNDO_MS = 7_000L
        const val LONG = "I wanted to let you know that I will not be able to attend the meeting on Thursday " +
            "afternoon because I have a doctor's appointment that I scheduled several weeks ago."
        const val FORMAL = "I would be grateful if you could confirm your attendance at the meeting."
    }
}
