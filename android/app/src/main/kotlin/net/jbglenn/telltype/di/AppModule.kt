package net.jbglenn.telltype.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jbglenn.telltype.assets.BrokenAssetsException
import net.jbglenn.telltype.assets.BundledAssets
import net.jbglenn.telltype.core.SystemClockSource
import net.jbglenn.telltype.core.UuidIdGenerator
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import net.jbglenn.telltype.domain.port.MisrecognitionReporter
import net.jbglenn.telltype.logging.AppLogger
import net.jbglenn.telltype.permissions.ActivityPermissionRequester
import net.jbglenn.telltype.platform.system.RuntimePermissionRequester
import net.jbglenn.telltype.support.FileMisrecognitionReporter
import net.jbglenn.telltype.support.ZipDebugBundleExporter

/** What no library module provides: time, ids, logging, the bundled `shared/` files and the app-support ports. */
@Module
@InstallIn(SingletonComponent::class)
internal interface AppModule {
    @Binds
    @Singleton
    fun bindClock(clock: SystemClockSource): Clock

    @Binds
    fun bindIdGenerator(ids: UuidIdGenerator): IdGenerator

    @Binds
    fun bindLogger(logger: AppLogger): Logger

    @Binds
    fun bindMisrecognitionReporter(reporter: FileMisrecognitionReporter): MisrecognitionReporter

    @Binds
    fun bindPermissionRequester(requester: ActivityPermissionRequester): RuntimePermissionRequester

    @Binds
    fun bindDebugBundleExporter(exporter: ZipDebugBundleExporter): DebugBundleExporter

    companion object {
        private val TAG = LogTag("Assets")

        /** Parsed once per process; a parse failure is logged and stops the app (a broken build). */
        @Provides
        @Singleton
        fun provideBundledAssets(@ApplicationContext context: Context, logger: Logger): BundledAssets =
            when (val loaded = BundledAssets.load(context.assets)) {
                is Outcome.Ok -> loaded.value

                is Outcome.Err -> {
                    val failure = BrokenAssetsException(loaded.error)
                    logger.error(TAG, null) { failure.message.orEmpty() }
                    throw failure
                }
            }

        @Provides
        fun providePromptSet(assets: BundledAssets): PromptSet = assets.prompts

        @Provides
        fun provideGuardRules(assets: BundledAssets): GuardRules = assets.guards
    }
}
