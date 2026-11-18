package net.jbglenn.telltype.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.data.repository.RoomAppRuleRepository
import net.jbglenn.telltype.data.repository.RoomDictionaryRepository
import net.jbglenn.telltype.data.repository.RoomHistoryRepository
import net.jbglenn.telltype.data.repository.RoomPendingJobRepository
import net.jbglenn.telltype.data.repository.RoomSnippetRepository
import net.jbglenn.telltype.data.settings.DataStoreBubbleSnooze
import net.jbglenn.telltype.data.settings.DataStoreSettingsRepository
import net.jbglenn.telltype.data.settings.KeystoreSecretCipher
import net.jbglenn.telltype.data.settings.preferencesStore
import net.jbglenn.telltype.data.work.DataStoreOrphanSuspects
import net.jbglenn.telltype.data.work.DomainQueuedTextCleanup
import net.jbglenn.telltype.data.work.OrphanSuspects
import net.jbglenn.telltype.data.work.PendingWorkScheduler
import net.jbglenn.telltype.data.work.QueuedTextCleanup
import net.jbglenn.telltype.data.work.WorkManagerScheduler
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.coordinator.RecoveryLedger
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SnippetRepository

/**
 * Binds `:data`'s six repositories and the work scheduler to their ports, and provides the database, the three
 * DataStore files, the bubble snooze ([BubbleSnooze]) and the worker dependencies. Needs from the graph: `Clock`,
 * `Logger`, `DispatcherProvider`, the parsed `GuardRules` (`:app`), `RecordingStore` (`:audio`), `NotifierPort`
 * (`:platform`) and `SpeechToTextEngine` (`:network`). The app must also register `DataWorkerFactory` with
 * WorkManager and call `PendingWorkScheduler.scheduleRetention()` and `schedulePendingJobs()` at start.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindHistory(repository: RoomHistoryRepository): HistoryRepository

    @Binds
    abstract fun bindDictionary(repository: RoomDictionaryRepository): DictionaryRepository

    @Binds
    abstract fun bindSnippets(repository: RoomSnippetRepository): SnippetRepository

    @Binds
    abstract fun bindAppRules(repository: RoomAppRuleRepository): AppRuleRepository

    @Binds
    abstract fun bindPendingJobs(repository: RoomPendingJobRepository): PendingJobRepository

    @Binds
    abstract fun bindScheduler(scheduler: WorkManagerScheduler): PendingWorkScheduler

    companion object {
        private const val SETTINGS_FILE = "settings.preferences_pb"
        private const val MAINTENANCE_FILE = "data_maintenance.preferences_pb"
        private const val SNOOZE_FILE = "bubble_snooze.preferences_pb"

        @Provides
        @Singleton
        fun provideSettings(
            @ApplicationContext context: Context,
            dispatchers: DispatcherProvider,
            logger: Logger,
        ): SettingsRepository = DataStoreSettingsRepository(
            store = preferencesStore(storeFile(context, SETTINGS_FILE), appScope(dispatchers), logger),
            cipher = KeystoreSecretCipher(),
            dispatchers = dispatchers,
            logger = logger,
        )

        /** One per process: shared by the start-up recovery, every coordinator and the orphan sweep (A7h). */
        @Provides
        @Singleton
        fun provideRecoveryLedger(): RecoveryLedger = RecoveryLedger()

        @Provides
        @Singleton
        fun provideOrphanSuspects(
            @ApplicationContext context: Context,
            dispatchers: DispatcherProvider,
            logger: Logger,
        ): OrphanSuspects = DataStoreOrphanSuspects(
            store = preferencesStore(storeFile(context, MAINTENANCE_FILE), appScope(dispatchers), logger),
            dispatchers = dispatchers,
            logger = logger,
        )

        @Provides
        @Singleton
        fun provideBubbleSnooze(
            @ApplicationContext context: Context,
            clock: Clock,
            dispatchers: DispatcherProvider,
            logger: Logger,
        ): BubbleSnooze {
            val scope = appScope(dispatchers)
            return DataStoreBubbleSnooze(
                store = preferencesStore(storeFile(context, SNOOZE_FILE), scope, logger),
                clock = clock,
                scope = scope,
                dispatchers = dispatchers,
                logger = logger,
            )
        }

        @Provides
        fun provideQueuedTextCleanup(rules: GuardRules): QueuedTextCleanup = DomainQueuedTextCleanup(rules)

        /** DataStore files live in the app's private `files/datastore` directory. */
        private fun storeFile(context: Context, name: String): File = File(context.filesDir, "datastore/$name")

        /** The lifetime of a DataStore: the whole process, its I/O on the io dispatcher. */
        private fun appScope(dispatchers: DispatcherProvider) = CoroutineScope(SupervisorJob() + dispatchers.io)
    }
}
