package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Brief M1: Command Mode, snippets and every session end leave info lines, with lengths and codes only. */
class SessionLogTest {
    private val secrets = listOf("Walrus", "zebra", "Ocelot", "Highgarden", "castle")

    private fun CoordinatorWorld.info(): List<String> =
        logger.entries.filter { it.level >= LogLevel.INFO }.map { it.message }

    private fun List<String>.assertHas(part: String) =
        assertTrue(any { part in it }, "no info line with '$part' in:\n${joinToString("\n")}")

    private fun CoordinatorWorld.assertNoSecrets() =
        secrets.forEach { assertFalse(it.lowercase() in logText.lowercase(), "'$it' was logged") }

    @Test
    fun `a command mode session logs its steps and its Undo without text`() = runTest {
        val w = CoordinatorWorld(this)
        w.contextReader.readCall.returns(FieldContext("", "", "Walrus selection"))
        w.cleaner.commandCall.returns(Outcome.Ok(Fixtures.cleanedText("Ocelot rewritten.")))
        w.start(focus = false)
        w.focus(selection = TextRange(0, 16))
        w.dictate("turn the zebra into an ocelot")
        w.send(SessionEvent.ActionTapped(BubbleAction.UNDO))
        w.idle()

        val info = w.info()
        info.assertHas("command mode, selection 16 chars, instruction transcribed (29 chars)")
        info.assertHas("command rewrite applied (17 chars)")
        info.assertHas("(command) done via INPUT_CONNECTION: 17 chars replacing 0, chips [UNDO] (Inserted)")
        info.assertHas("(command) ended: Done -> Ready (action UNDO)")
        w.assertNoSecrets()
        w.closeAndAssertStopped()
    }

    @Test
    fun `a refused command and a snippet say how the session ended`() = runTest {
        val w = CoordinatorWorld(this)
        w.contextReader.readCall.returns(FieldContext("", "", ""))
        w.start(focus = false)
        w.focus(selection = TextRange(0, 5))
        w.dictate("turn the zebra into an ocelot")
        w.idle()
        val refused = w.info()
        refused.assertHas("command mode, selection 0 chars")
        refused.assertHas("command rewrite refused (")
        refused.assertHas("(command) problem ")

        w.snippets.add("my castle", "Highgarden")
        w.focus()
        w.dictate("my castle")
        w.idle()
        w.info().assertHas("snippet expanded: ")
        w.assertNoSecrets()
        w.closeAndAssertStopped()
    }
}
