package net.jbglenn.telltype.data.work

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.ruleBasedCleanup
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.text.applyReplacements

/**
 * Turns a transcript from the queue into the text offered in the notification: the domain's rule-based cleanup
 * (`shared/GUARDS.md` section 5), then the dictionary's replacements (section 8.4). Queued text is never sent to
 * the model: the session it belonged to is over, and the rule-based text is what the user would have got offline.
 */
internal fun interface QueuedTextCleanup {
    /** The finished text for [transcript]. Pure. */
    fun finish(transcript: String, replacements: List<Replacement>): String
}

/** The real domain functions (A2b), with the bundled guard rules. */
internal class DomainQueuedTextCleanup(private val rules: GuardRules) : QueuedTextCleanup {
    override fun finish(transcript: String, replacements: List<Replacement>): String =
        applyReplacements(ruleBasedCleanup(transcript, rules), replacements).text
}

/** The dictionary's part in a queue run: hints for the speech engine and the finished text. */
internal class QueuedText @Inject constructor(
    private val dictionary: DictionaryRepository,
    private val cleanup: QueuedTextCleanup,
    private val dispatchers: DispatcherProvider,
) {
    /** The dictionary's terms as speech hints. */
    suspend fun hints(): SttHints = SttHints(dictionary.terms.first().map { it.term })

    /** [transcript] cleaned and with the current replacements applied, on the default dispatcher. */
    suspend fun finish(transcript: String): String {
        val replacements = dictionary.replacements.first()
        return withContext(dispatchers.default) { cleanup.finish(transcript, replacements) }
    }
}
