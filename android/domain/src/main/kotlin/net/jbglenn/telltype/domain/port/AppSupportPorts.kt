package net.jbglenn.telltype.domain.port

import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.session.SessionTimings

/*
 * Ports the app's screens need beyond the pipeline and the repositories (added by A6). Each is small and has a
 * fake in `:testing`.
 */

/**
 * Snooze: the bubble stays hidden until a time (Home's snooze switch, the bubble's snooze band; section 5.2).
 * Implemented by `:data` (`DataStoreBubbleSnooze`); the coordinator treats a snoozed bubble as `Focus.None`.
 */
public interface BubbleSnooze {
    /** When the snooze ends; null while the bubble is not snoozed. */
    public val snoozedUntil: StateFlow<Instant?>

    /** Hides the bubble until [until]. */
    public suspend fun snoozeUntil(until: Instant)

    /** Ends the snooze now. */
    public suspend fun resume()
}

/**
 * "That was wrong" in History (section 4.8, work package F3): adds an entry to the server's test set so prompts and
 * models are checked against real speech.
 */
public interface MisrecognitionReporter {
    /** Sends [entry]'s raw transcript and final text (never audio) as a new test case. */
    public suspend fun report(entry: HistoryEntry): Outcome<Unit, DictationError>

    /** Removes what is still kept of the reports made from the History entry [id] (the entry was deleted). */
    public suspend fun forget(id: SessionId): Outcome<Unit, StorageError>

    /** Removes what is still kept of every report (History was cleared). */
    public suspend fun forgetAll(): Outcome<Unit, StorageError>
}

/**
 * The Diagnostics self-test (section 13): sends the bundled sample clip through the whole pipeline and compares
 * the result with the expected text.
 */
public interface SelfTest {
    /** Runs the test once; an error is the first stage that failed. */
    public suspend fun run(): Outcome<SelfTestReport, DictationError>
}

/**
 * The result of one self-test run.
 *
 * @property passed the final text matched the expected text.
 * @property timings how long each stage took.
 */
public data class SelfTestReport(public val passed: Boolean, public val timings: SessionTimings)

/**
 * Exports the debug bundle (section 13): logs, traces and settings, never the key or dictated text, handed to the
 * system share sheet.
 */
public interface DebugBundleExporter {
    /** Writes the bundle and opens the share sheet. */
    public suspend fun export(): Outcome<Unit, StorageError>
}
