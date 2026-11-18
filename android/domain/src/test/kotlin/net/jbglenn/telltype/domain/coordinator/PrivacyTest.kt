package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** With every log level recorded, no dictated text, context, snippet, correction or key reaches a log line. */
class PrivacyTest {
    private val secrets = listOf(
        "zebra", "quokka", "Confidential", "Walrus", "Narwhal", "Highgarden", Spoken.KEY, "Mongoose", "Ocelot",
    )

    @Test
    fun `no scenario logs text, context or the key`() = runTest {
        val w = CoordinatorWorld(this)
        w.snippets.add("my castle", "Highgarden")
        w.dictionary.addReplacement("walrus", "Narwhal", EntrySource.MANUAL)
        w.contextReader.readCall.returns(FieldContext("Confidential before", "Confidential after", "Confidential pick"))
        w.cleaner.cleanCall.answers { request ->
            Outcome.Ok(Fixtures.cleanedText("Zebra quokka walrus ${request.transcript}."))
        }
        w.start()
        w.dictate("the zebra and the quokka met a walrus near my castle")
        w.editWatcher.type("Mongoose quokka walrus")
        w.send(SessionEvent.ActionTapped(BubbleAction.RAW))
        w.advance(6.seconds)
        w.inserter.insertCall.returns(Outcome.Err(FieldChanged))
        w.dictate("zebra ocelot quokka walrus again and again")
        w.send(SessionEvent.ActionTapped(BubbleAction.PASTE))
        w.advance(6.seconds)
        w.cleaner.commandCall.answers { Outcome.Ok(Fixtures.cleanedText("Ocelot rewritten")) }
        w.focus(selection = TextRange(0, 4))
        w.dictate("turn the zebra into an ocelot")
        w.advance(11.seconds)
        w.speech.transcribeCall.answers { error("zebra quokka in an exception message") }
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.speech.transcribeCall.returns(Outcome.Err(ServerUnreachable))
        w.idle()
        w.closeAndAssertStopped()

        assertTrue(w.logger.entries.any { it.level == LogLevel.DEBUG }, "debug lines were recorded")
        val log = w.logText
        secrets.forEach { secret -> assertFalse(secret.lowercase() in log.lowercase(), "'$secret' was logged") }
        assertTrue(w.logger.entries.all { it.throwable == null })
    }
}
