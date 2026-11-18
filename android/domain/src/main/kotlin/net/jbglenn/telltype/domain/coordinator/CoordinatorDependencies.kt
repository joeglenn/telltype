package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.ClipboardPort
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.port.FieldContextReader
import net.jbglenn.telltype.domain.port.FieldEditWatcher
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.FocusedFieldSource
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.MicrophoneServicePort
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SnippetRepository
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner
import net.jbglenn.telltype.domain.port.TextInserter

/**
 * The pipeline and platform ports the coordinator drives (all implemented outside `:domain`).
 *
 * @property speech transcription (`Transcribe`, `prepare` at record start).
 * @property cleaner cleanup and Command Mode rewrite (`Clean`).
 * @property inserter insertion ladder steps 1 to 3 (`Insert`, `ReplaceInserted`).
 * @property audio the recorder (`StartRecording`, `StopRecording`, `CancelRecording`) and its interruptions.
 * @property recordings the recording files (`DeleteRecording`, crash recovery).
 * @property microphone the foreground microphone service, held from record start until processing ends.
 * @property contextReader the text around the cursor at record start (never for incognito fields).
 * @property editWatcher the learning window after a dictation (`WatchFieldEdits`).
 * @property clipboard hand-off and Copy.
 * @property haptics feedback cues.
 * @property notifier the crash-recovery notice.
 * @property fixLauncher `OpenFix`.
 * @property health `ProbeServerHealth`.
 */
public data class CoordinatorPorts(
    public val speech: SpeechToTextEngine,
    public val cleaner: TextCleaner,
    public val inserter: TextInserter,
    public val audio: AudioCapture,
    public val recordings: RecordingStore,
    public val microphone: MicrophoneServicePort,
    public val contextReader: FieldContextReader,
    public val editWatcher: FieldEditWatcher,
    public val clipboard: ClipboardPort,
    public val haptics: HapticsPort,
    public val notifier: NotifierPort,
    public val fixLauncher: FixLauncher,
    public val health: ServerHealthSource,
)

/**
 * The repositories the coordinator reads and writes.
 *
 * @property settings every setting; observed, so the latest value is used without a read on the hot path.
 * @property history `SaveHistory`.
 * @property dictionary hints, glossary, protected words, replacements, learned corrections and use counts.
 * @property snippets snippet triggers and their use counts.
 * @property appRules category and tone overrides and exclusions; observed like [settings].
 * @property pendingJobs `EnqueuePendingJob` and crash recovery.
 * @property recoveryLedger shared with the start-up recovery of the application and the orphan sweep: one per
 *   process (A7h).
 */
public data class CoordinatorStores(
    public val settings: SettingsRepository,
    public val history: HistoryRepository,
    public val dictionary: DictionaryRepository,
    public val snippets: SnippetRepository,
    public val appRules: AppRuleRepository,
    public val pendingJobs: PendingJobRepository,
    public val recoveryLedger: RecoveryLedger = RecoveryLedger(),
)

/**
 * What the focus feed combines (with the settings and the app rules) into `FocusChanged` events.
 *
 * @property focusedField the focused field snapshot.
 * @property permissions permission and system-switch state.
 * @property snooze the bubble's snooze.
 */
public data class FocusSources(
    public val focusedField: FocusedFieldSource,
    public val permissions: PermissionStatusSource,
    public val snooze: BubbleSnooze,
)

/**
 * The coordinator's runtime: threads, time, ids, logging, the parsed guard rules and its timeouts.
 *
 * @property dispatchers the event loop and every effect run on `main`, so the coordinator's own state needs no
 *   lock; ports switch to their own dispatchers; text post-processing runs on `default`.
 * @property clock wall clock for stored times, monotonic clock for the session timings.
 * @property ids session ids.
 * @property logger the log; never receives dictated text, context or the key.
 * @property guardRules the parsed `shared/guards.json` (rule-based cleanup, snippet marker syntax).
 * @property config timeouts and timer durations.
 */
public data class CoordinatorEnvironment(
    public val dispatchers: DispatcherProvider,
    public val clock: Clock,
    public val ids: IdGenerator,
    public val logger: Logger,
    public val guardRules: GuardRules,
    public val config: CoordinatorConfig = CoordinatorConfig(),
)
