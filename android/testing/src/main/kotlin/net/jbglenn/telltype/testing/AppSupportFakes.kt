package net.jbglenn.telltype.testing

import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import net.jbglenn.telltype.domain.port.MisrecognitionReporter
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.domain.port.SelfTestReport

/** [BubbleSnooze] held in memory. */
public class FakeBubbleSnooze : BubbleSnooze {
    private val state = MutableStateFlow<Instant?>(null)

    override val snoozedUntil: StateFlow<Instant?> = state.asStateFlow()

    override suspend fun snoozeUntil(until: Instant) {
        state.value = until
    }

    override suspend fun resume() {
        state.value = null
    }
}

/** [MisrecognitionReporter] that accepts every report unless scripted otherwise. */
public class FakeMisrecognitionReporter : MisrecognitionReporter {
    /** Every entry reported; the answer. */
    public val reportCall: ScriptedCall<HistoryEntry, Outcome<Unit, DictationError>> = ScriptedCall { Outcome.Ok(Unit) }

    /** Every entry whose reports were forgotten; the answer. */
    public val forgetCall: ScriptedCall<SessionId, Outcome<Unit, StorageError>> = ScriptedCall { Outcome.Ok(Unit) }

    /** Every "forget all reports"; the answer. */
    public val forgetAllCall: ScriptedCall<Unit, Outcome<Unit, StorageError>> = ScriptedCall { Outcome.Ok(Unit) }

    override suspend fun report(entry: HistoryEntry): Outcome<Unit, DictationError> = reportCall.answer(entry)

    override suspend fun forget(id: SessionId): Outcome<Unit, StorageError> = forgetCall.answer(id)

    override suspend fun forgetAll(): Outcome<Unit, StorageError> = forgetAllCall.answer(Unit)
}

/** [SelfTest] that passes with no timings unless scripted otherwise. */
public class FakeSelfTest : SelfTest {
    /** Every run; the answer. */
    public val runCall: ScriptedCall<Unit, Outcome<SelfTestReport, DictationError>> =
        ScriptedCall { Outcome.Ok(SelfTestReport(passed = true, timings = SessionTimings.None)) }

    override suspend fun run(): Outcome<SelfTestReport, DictationError> = runCall.answer(Unit)
}

/** [DebugBundleExporter] that succeeds unless scripted otherwise. */
public class FakeDebugBundleExporter : DebugBundleExporter {
    /** Every export; the answer. */
    public val exportCall: ScriptedCall<Unit, Outcome<Unit, StorageError>> = ScriptedCall { Outcome.Ok(Unit) }

    override suspend fun export(): Outcome<Unit, StorageError> = exportCall.answer(Unit)
}
