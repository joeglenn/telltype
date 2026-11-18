package net.jbglenn.telltype.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.random.Random
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.port.ServerCertificateReader
import net.jbglenn.telltype.domain.port.ServerConnectionChecker
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.ServerModelCatalog
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner
import net.jbglenn.telltype.network.check.RemoteConnectionChecker
import net.jbglenn.telltype.network.check.RemoteModelCatalog
import net.jbglenn.telltype.network.cleanup.CleanupAssets
import net.jbglenn.telltype.network.cleanup.CleanupLogic
import net.jbglenn.telltype.network.cleanup.DomainCleanupLogic
import net.jbglenn.telltype.network.cleanup.RemoteTextCleaner
import net.jbglenn.telltype.network.health.RemoteHealthSource
import net.jbglenn.telltype.network.http.GateClients
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.route.CircuitBreakers
import net.jbglenn.telltype.network.route.RetryPolicy
import net.jbglenn.telltype.network.route.ServerRouter
import net.jbglenn.telltype.network.setup.TlsServerCertificateReader
import net.jbglenn.telltype.network.stt.RemoteSpeechToTextEngine

/**
 * Binds `:network`'s implementations to their ports: [SpeechToTextEngine], [TextCleaner], [ServerHealthSource],
 * [ServerCertificateReader], [ServerConnectionChecker] and [ServerModelCatalog].
 * Needs from the graph: `SettingsRepository` (`:data`), `Clock`, `Logger` and `DispatcherProvider` (`:app`), and
 * the parsed `PromptSet` and `GuardRules` (`:app`, from the bundled `shared/`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds
    abstract fun bindSpeechToText(engine: RemoteSpeechToTextEngine): SpeechToTextEngine

    @Binds
    abstract fun bindTextCleaner(cleaner: RemoteTextCleaner): TextCleaner

    @Binds
    abstract fun bindServerHealth(source: RemoteHealthSource): ServerHealthSource

    @Binds
    abstract fun bindCertificateReader(reader: TlsServerCertificateReader): ServerCertificateReader

    @Binds
    abstract fun bindConnectionChecker(checker: RemoteConnectionChecker): ServerConnectionChecker

    @Binds
    abstract fun bindModelCatalog(catalog: RemoteModelCatalog): ServerModelCatalog

    companion object {
        @Provides
        fun provideTimeouts(): NetworkTimeouts = NetworkTimeouts.Default

        @Provides
        fun provideCleanupLogic(): CleanupLogic = DomainCleanupLogic

        @Provides
        fun provideCleanupAssets(prompts: PromptSet, rules: GuardRules): CleanupAssets = CleanupAssets(prompts, rules)

        @Provides
        @Singleton
        fun provideClients(timeouts: NetworkTimeouts): GateClients = GateClients(timeouts)

        @Provides
        @Singleton
        fun provideRouter(
            settings: SettingsRepository,
            clients: GateClients,
            clock: Clock,
            logger: Logger,
        ): ServerRouter = ServerRouter(settings, clients, CircuitBreakers(clock), RetryPolicy(Random.Default), logger)
    }
}
