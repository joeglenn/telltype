package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.domain.SharedAssets
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.testing.FakeAppRuleRepository
import net.jbglenn.telltype.testing.FakeAudioCapture
import net.jbglenn.telltype.testing.FakeBubbleSnooze
import net.jbglenn.telltype.testing.FakeClipboard
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.testing.FakeFieldContextReader
import net.jbglenn.telltype.testing.FakeFieldEditWatcher
import net.jbglenn.telltype.testing.FakeFixLauncher
import net.jbglenn.telltype.testing.FakeFocusedFieldSource
import net.jbglenn.telltype.testing.FakeHaptics
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeMicrophoneService
import net.jbglenn.telltype.testing.FakeNotifier
import net.jbglenn.telltype.testing.FakePendingJobRepository
import net.jbglenn.telltype.testing.FakePermissionStatusSource
import net.jbglenn.telltype.testing.FakeRecordingStore
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeSnippetRepository
import net.jbglenn.telltype.testing.FakeSpeechToTextEngine
import net.jbglenn.telltype.testing.FakeTextCleaner
import net.jbglenn.telltype.testing.FakeTextInserter
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.SequentialIdGenerator
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Texts the coordinator tests dictate. */
object Spoken {
    /** A long dictation (more than three words, no correction cue), so the model runs. */
    const val LONG: String = "so I think we should ship the release on friday"

    /** The rule-based cleanup of [LONG] after field matching into an empty field. */
    const val LONG_BY_RULES: String = "So I think we should ship the release on friday."

    /** The server key used in every world; it must never appear in a log line. */
    const val KEY: String = "sk-never-logged-4711"
}

/** A clock on the test scheduler's virtual time (wall clock from [Fixtures.Epoch]). */
class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
    @OptIn(ExperimentalCoroutinesApi::class) // currentTime
    override fun now(): Instant = Fixtures.Epoch + scheduler.currentTime.milliseconds

    @OptIn(ExperimentalCoroutinesApi::class) // currentTime
    override fun monotonic(): Duration = scheduler.currentTime.milliseconds
}

/** Settings with a configured server, so a focused field is available. */
fun configuredSettings(): Settings = Settings.Defaults.copy(
    server = ServerConfig(
        address = ServerEndpoint("server.example", 8170),
        key = ServerKey(Spoken.KEY),
        certificate = CertificateFingerprint("ab".repeat(32)),
    ),
)

/**
 * A coordinator wired to every fake of `:testing`, on the test scheduler's virtual time. Every helper runs the
 * scheduler until nothing is left at the current instant, so each event is processed with all its effects.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy, advanceUntilIdle, UnconfinedTestDispatcher
class CoordinatorWorld(
    private val scope: TestScope,
    settings: Settings = configuredSettings(),
    config: CoordinatorConfig = CoordinatorConfig(),
    val field: FieldTarget = Fixtures.fieldTarget(),
    haptics: HapticsPort? = null,
) {
    val clock = SchedulerClock(scope.testScheduler)
    val logger = RecordingLogger()
    val speech = FakeSpeechToTextEngine()
    val cleaner = FakeTextCleaner()
    val inserter = FakeTextInserter()
    val audio = FakeAudioCapture()
    val recordings = FakeRecordingStore()
    val microphone = FakeMicrophoneService()
    val contextReader = FakeFieldContextReader()
    val editWatcher = FakeFieldEditWatcher()
    val clipboard = FakeClipboard()
    val haptics = FakeHaptics()
    val notifier = FakeNotifier()
    val fixLauncher = FakeFixLauncher()
    val health = FakeServerHealthSource()
    val settingsRepository = FakeSettingsRepository(settings)
    val history = FakeHistoryRepository()
    val dictionary = FakeDictionaryRepository(clock)
    val snippets = FakeSnippetRepository(clock)
    val appRules = FakeAppRuleRepository()
    val pendingJobs = FakePendingJobRepository()
    val ledger = RecoveryLedger()
    val focused = FakeFocusedFieldSource()
    val permissions = FakePermissionStatusSource()
    val snooze = FakeBubbleSnooze()
    val chips = mutableListOf<Chip>()

    val coordinator = DefaultSessionCoordinator(
        CoordinatorPorts(
            speech, cleaner, inserter, audio, recordings, microphone, contextReader, editWatcher, clipboard,
            haptics ?: this.haptics, notifier, fixLauncher, health,
        ),
        CoordinatorStores(settingsRepository, history, dictionary, snippets, appRules, pendingJobs, ledger),
        FocusSources(focused, permissions, snooze),
        CoordinatorEnvironment(
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(scope.testScheduler)),
            clock = clock,
            ids = SequentialIdGenerator(),
            logger = logger,
            guardRules = SharedAssets.rules,
            config = config,
        ),
    )

    val state: SessionState get() = coordinator.state.value

    /** Starts the coordinator (and, by default, focuses [field] with the keyboard up). */
    fun start(focus: Boolean = true) {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            coordinator.chips.collect { chips += it }
        }
        coordinator.start()
        if (focus) focus()
        scope.runCurrent()
    }

    /** Focuses [target]; a non-empty [selection] means Command Mode. */
    fun focus(target: FieldTarget = field, selection: TextRange? = TextRange(0, 0), keyboard: Boolean = true) {
        focused.focus(Fixtures.fieldSnapshot(target, keyboard, selection))
        scope.runCurrent()
    }

    /** Sends each event and lets it and its effects run before the next. */
    fun send(vararg events: SessionEvent) {
        events.forEach {
            coordinator.dispatch(it)
            scope.runCurrent()
        }
    }

    /** One tap dictation of [text] (the transcript the engine returns). */
    fun dictate(text: String = Spoken.LONG) {
        speech.transcribeCall.returns(Outcome.Ok(Fixtures.transcript(text)))
        send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
    }

    /** Lets [by] of virtual time pass, running everything due. */
    fun advance(by: Duration) {
        scope.advanceTimeBy(by)
        scope.runCurrent()
    }

    /** Lets [by] of virtual time pass, running only what is due before the new instant. */
    fun advanceBefore(by: Duration) {
        scope.advanceTimeBy(by)
    }

    /** Runs everything, timers included, until nothing is scheduled. */
    fun idle() {
        scope.advanceUntilIdle()
    }

    /** Runs what is due now. */
    fun runCurrent() {
        scope.runCurrent()
    }

    /** No effect of any session (no lane, timer, watcher or session job child) is running. */
    fun assertNothingRunning() {
        assertEquals(0, coordinator.runningWork(), "effect work still running")
    }

    /** Closes and checks that the coordinator's whole job tree completed and the microphone is released. */
    fun closeAndAssertStopped() {
        coordinator.close()
        scope.advanceUntilIdle()
        assertTrue(coordinator.lifecycle.isCompleted, "coordinator job still active")
        assertFalse(microphone.running, "microphone service still running")
    }

    /** Every log line, for the privacy checks. */
    val logText: String get() = logger.entries.joinToString("\n") { it.message }
}
