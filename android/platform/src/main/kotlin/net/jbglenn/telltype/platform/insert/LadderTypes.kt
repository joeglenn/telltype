package net.jbglenn.telltype.platform.insert

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertedVia

/** The automatic steps of the insertion ladder (docs/PLAN.md section 7.4), in order. Step 4 is the domain's. */
internal enum class LadderStep(
    /** What the receipt records when this step lands. */
    val via: InsertedVia,
    /**
     * The step inserts relative to the field's current text, so running it after an earlier attempt changed the
     * text could insert twice. Set-text is absolute: it writes the whole expected text.
     */
    val relative: Boolean,
) {
    /** Commit through the accessibility input connection. */
    INPUT_CONNECTION(InsertedVia.INPUT_CONNECTION, relative = true),

    /** Rebuild the text around the cursor and write it with the set-text action. */
    SET_TEXT(InsertedVia.SET_TEXT, relative = false),

    /** Copy (flagged sensitive), paste, restore the previous clipboard. */
    PASTE(InsertedVia.PASTE, relative = true),
}

/**
 * Tuning of the ladder.
 *
 * @property steps the steps to try, in order. Production uses all three; the instrumented tests restrict it to
 *   exercise one step at a time.
 * @property stepTimeout how long one step may take to be confirmed by reading the field back.
 * @property pollInterval how often the field is read back while waiting.
 */
internal data class LadderConfig(
    val steps: List<LadderStep> = LadderStep.entries,
    val stepTimeout: Duration = 1.seconds,
    val pollInterval: Duration = 25.milliseconds,
)

/**
 * What to insert. Holds dictated text: [toString] prints lengths only.
 *
 * @property text the text to type; may be empty for an Undo.
 * @property range where to put it; null means "at the field's current selection".
 * @property expected the text that must still be at [range] (Raw and Undo replace only their own insertion);
 *   null for a new insertion.
 * @property requireFocus the field must have input focus (a new insertion never types into another field).
 * @property allowClipboard step 3 may run ("Never use the clipboard" is off).
 */
internal data class InsertRequest(
    val text: String,
    val range: TextRange?,
    val expected: String?,
    val requireFocus: Boolean,
    val allowClipboard: Boolean,
) {
    override fun toString(): String = "InsertRequest(text=${text.length} chars, range=$range, " +
        "expected=${expected?.length} chars, requireFocus=$requireFocus, allowClipboard=$allowClipboard)"
}

/**
 * A confirmed insertion.
 *
 * @property via the step that landed.
 * @property range where the text sits now.
 * @property replaced the text it replaced. On-screen text.
 * @property landed the text as the field adapted it (auto-capitalised or filtered); null when it landed exactly as
 *   requested. Dictated text.
 */
internal data class Inserted(
    val via: InsertedVia,
    val range: TextRange,
    val replaced: String,
    val landed: String? = null,
) {
    override fun toString(): String =
        "Inserted(via=$via, range=$range, replaced=${replaced.length} chars, landed=${landed?.length} chars)"
}
