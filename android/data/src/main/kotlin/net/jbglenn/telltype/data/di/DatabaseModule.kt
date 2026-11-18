package net.jbglenn.telltype.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.data.db.AppRuleDao
import net.jbglenn.telltype.data.db.HistoryDao
import net.jbglenn.telltype.data.db.PendingJobDao
import net.jbglenn.telltype.data.db.SnippetDao
import net.jbglenn.telltype.data.db.TelltypeDatabase

/** The one Room database (queries on the io dispatcher) and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, dispatchers: DispatcherProvider): TelltypeDatabase =
        TelltypeDatabase.open(context, dispatchers.io)

    @Provides
    fun provideHistoryDao(database: TelltypeDatabase): HistoryDao = database.history()

    @Provides
    fun provideSnippetDao(database: TelltypeDatabase): SnippetDao = database.snippets()

    @Provides
    fun provideAppRuleDao(database: TelltypeDatabase): AppRuleDao = database.appRules()

    @Provides
    fun providePendingJobDao(database: TelltypeDatabase): PendingJobDao = database.pendingJobs()
}
