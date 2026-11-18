package net.jbglenn.telltype.platform.di

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.port.BubbleWindowPort
import net.jbglenn.telltype.domain.port.ClipboardPort
import net.jbglenn.telltype.domain.port.FieldContextReader
import net.jbglenn.telltype.domain.port.FieldEditWatcher
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.FocusedFieldSource
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.domain.port.MicrophoneServicePort
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.TextInserter
import net.jbglenn.telltype.platform.overlay.AccessibilityOverlayHost
import net.jbglenn.telltype.platform.overlay.OverlayTray
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.system.AndroidFixLauncher
import net.jbglenn.telltype.platform.system.RequestingFixLauncher
import net.jbglenn.telltype.platform.system.RuntimePermissionRequester

/**
 * The `:platform` graph and the concrete types `:app` needs besides the ports: the [AccessibilityOverlayHost] (to
 * show the bubble's content), the [OverlayTray] (the snooze band), the [AccessibilityServiceBridge] (to start the
 * coordinator when the service connects) and the [AndroidFixLauncher] (for setup's restricted-settings step). Needs
 * `DispatcherProvider`, `Logger` and `SettingsRepository` from the rest of the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object PlatformModule {
    @Provides
    @Singleton
    fun graph(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
        settings: SettingsRepository,
    ): PlatformGraph = PlatformGraph(
        context = context,
        dispatchers = dispatchers,
        logger = logger,
        settings = settings,
        scope = CoroutineScope(SupervisorJob() + dispatchers.default),
    )

    @Provides
    fun bridge(): AccessibilityServiceBridge = AccessibilityServiceBridge.shared

    @Provides
    fun overlayHost(graph: PlatformGraph): AccessibilityOverlayHost = graph.overlayHost

    @Provides
    fun overlayTray(graph: PlatformGraph): OverlayTray = graph.overlayTray

    @Provides
    fun androidFixLauncher(graph: PlatformGraph): AndroidFixLauncher = graph.fixLauncher
}

/** The ports the dictation pipeline uses: focus, context, insertion, edits, microphone service. */
@Module
@InstallIn(SingletonComponent::class)
internal object PlatformPipelineModule {
    @Provides
    fun focusedFieldSource(graph: PlatformGraph): FocusedFieldSource = graph.focusTracker

    @Provides
    fun fieldContextReader(graph: PlatformGraph): FieldContextReader = graph.contextReader

    @Provides
    fun textInserter(graph: PlatformGraph): TextInserter = graph.textInserter

    @Provides
    fun fieldEditWatcher(graph: PlatformGraph): FieldEditWatcher = graph.editWatcher

    @Provides
    fun microphoneService(graph: PlatformGraph): MicrophoneServicePort = graph.micService
}

/** The system ports: bubble window, clipboard, haptics, notifications, fixes, permissions. */
@Module
@InstallIn(SingletonComponent::class)
internal object PlatformSystemModule {
    @Provides
    fun bubbleWindow(graph: PlatformGraph): BubbleWindowPort = graph.overlayHost

    @Provides
    fun clipboard(graph: PlatformGraph): ClipboardPort = graph.clipboard

    @Provides
    fun haptics(graph: PlatformGraph): HapticsPort = graph.haptics

    @Provides
    fun notifier(graph: PlatformGraph): NotifierPort = graph.notifier

    /** Runtime permissions through the app's activity when `:app` binds a [RuntimePermissionRequester]. */
    @Provides
    fun fixLauncher(graph: PlatformGraph, requester: Optional<RuntimePermissionRequester>): FixLauncher =
        requester.map<FixLauncher> { RequestingFixLauncher(it, graph.fixLauncher) }.orElse(graph.fixLauncher)

    @Provides
    fun permissionStatusSource(graph: PlatformGraph): PermissionStatusSource = graph.permissions
}

/** What `:app` may bind for the platform adapters. */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlatformOptionalModule {
    /** The activity-side permission dialog; absent in the module's own tests. */
    @BindsOptionalOf
    fun optionalPermissionRequester(): RuntimePermissionRequester
}
