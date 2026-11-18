package net.jbglenn.telltype.platform.emulator

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.domain.port.TextInserter

/** Shared steps of the insertion tests. */
internal object InsertSteps {
    /**
     * Opens [type] with [text] (ignored for the prefilled and placeholder screens, which bring their own), puts the
     * selection at [selection] through the service when given, and returns the snapshot once the tracker reports it.
     */
    fun prepare(type: HostField, text: String?, selection: TextRange?): FieldSnapshot {
        val own = type == HostField.PREFILLED || type == HostField.PLACEHOLDER
        val opened = FieldSteps.focus(type, text = text.takeUnless { own })
        val wanted = selection ?: return opened
        Emulator.select(wanted.start, wanted.end)
        return checkNotNull(
            Emulator.awaitSnapshot(10.seconds) { it?.target == opened.target && it.selection == wanted },
        )
    }

    /** Inserts with [inserter] (the graph's, by default) and records the outcome. */
    fun insert(
        test: String,
        type: HostField,
        target: FieldSnapshot,
        text: String,
        inserter: TextInserter = Emulator.graph.textInserter,
        step: String = "ladder",
    ): Outcome<InsertReceipt, InsertionError> = runBlocking { inserter.insert(target.target, text) }.also {
        Emulator.record(test, type.id, step, it.describe())
    }

    /** The field's text read independently through UI Automator (the placeholder screen reads as empty). */
    fun fieldText(type: HostField): String {
        val shown = checkNotNull(type.find()).text.orEmpty()
        return if (type == HostField.PLACEHOLDER && shown == "Type a message") "" else shown
    }

    /** One line for the results table: the step or the error code. */
    fun Outcome<InsertReceipt, InsertionError>.describe(): String = when (this) {
        is Outcome.Ok -> "${value.via.id} range=${value.range}"
        is Outcome.Err -> "error=${error.code}"
    }
}
