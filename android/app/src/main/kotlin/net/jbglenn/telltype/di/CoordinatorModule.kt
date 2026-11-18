package net.jbglenn.telltype.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import net.jbglenn.telltype.assets.BundledAssets
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.coordinator.CoordinatorEnvironment
import net.jbglenn.telltype.domain.coordinator.CoordinatorPorts
import net.jbglenn.telltype.domain.coordinator.CoordinatorStores
import net.jbglenn.telltype.domain.coordinator.CrashRecovery
import net.jbglenn.telltype.domain.coordinator.FocusSources
import net.jbglenn.telltype.domain.coordinator.RecoveryLedger
import net.jbglenn.telltype.domain.coordinator.RecoveryStores
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

/** The coordinator's dependency groups (contracts.md page 9), assembled from the module bindings. */
@Module
@InstallIn(SingletonComponent::class)
internal object CoordinatorModule {
    @Provides
    fun provideEnvironment(
        dispatchers: DispatcherProvider,
        clock: Clock,
        ids: IdGenerator,
        logger: Logger,
        assets: BundledAssets,
    ): CoordinatorEnvironment = CoordinatorEnvironment(dispatchers, clock, ids, logger, assets.guards)

    @Provides
    fun providePorts(pipeline: PipelinePortSet, field: FieldPortSet, system: SystemPortSet): CoordinatorPorts =
        CoordinatorPorts(
            speech = pipeline.speech,
            cleaner = pipeline.cleaner,
            inserter = pipeline.inserter,
            audio = pipeline.audio,
            recordings = pipeline.recordings,
            microphone = pipeline.microphone,
            contextReader = field.contextReader,
            editWatcher = field.editWatcher,
            clipboard = field.clipboard,
            haptics = system.haptics,
            notifier = system.notifier,
            fixLauncher = system.fixLauncher,
            health = system.health,
        )

    @Provides
    fun provideStores(stores: StoreSet, ledger: RecoveryLedger): CoordinatorStores = CoordinatorStores(
        settings = stores.settings,
        history = stores.history,
        dictionary = stores.dictionary,
        snippets = stores.snippets,
        appRules = stores.appRules,
        pendingJobs = stores.pendingJobs,
        recoveryLedger = ledger,
    )

    /** Crash recovery for application start (A7h): only the recording store, the queue and the notifier. */
    @Provides
    fun provideCrashRecovery(
        recordings: RecordingStore,
        stores: StoreSet,
        notifier: NotifierPort,
        ledger: RecoveryLedger,
        environment: CoordinatorEnvironment,
    ): CrashRecovery = CrashRecovery(
        RecoveryStores(recordings, stores.pendingJobs, stores.settings),
        notifier,
        ledger,
        environment.clock,
        environment.logger,
        environment.config,
    )

    @Provides
    fun provideFocusSources(
        focused: FocusedFieldSource,
        permissions: PermissionStatusSource,
        snooze: BubbleSnooze,
    ): FocusSources = FocusSources(focused, permissions, snooze)
}

/** The capture-to-insertion ports. */
internal class PipelinePortSet @Inject constructor(
    @param:PipelineEngine val speech: SpeechToTextEngine,
    @param:PipelineEngine val cleaner: TextCleaner,
    val inserter: TextInserter,
    val audio: AudioCapture,
    val recordings: RecordingStore,
    val microphone: MicrophoneServicePort,
)

/** The ports that read or change the focused field. */
internal class FieldPortSet @Inject constructor(
    val contextReader: FieldContextReader,
    val editWatcher: FieldEditWatcher,
    val clipboard: ClipboardPort,
)

/** The system ports. */
internal class SystemPortSet @Inject constructor(
    val haptics: HapticsPort,
    val notifier: NotifierPort,
    val fixLauncher: FixLauncher,
    val health: ServerHealthSource,
)

/** The six repositories. */
internal class StoreSet @Inject constructor(
    val settings: SettingsRepository,
    val history: HistoryRepository,
    val dictionary: DictionaryRepository,
    val snippets: SnippetRepository,
    val appRules: AppRuleRepository,
    val pendingJobs: PendingJobRepository,
)
