package net.jbglenn.telltype.di

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.coordinator.CoordinatorPorts
import net.jbglenn.telltype.domain.coordinator.CoordinatorStores
import net.jbglenn.telltype.domain.coordinator.FocusSources
import net.jbglenn.telltype.domain.coordinator.SessionCoordinator
import net.jbglenn.telltype.domain.port.BubbleWindowPort
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import net.jbglenn.telltype.domain.port.MisrecognitionReporter
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.domain.port.ServerCertificateReader
import net.jbglenn.telltype.runtime.DictationRuntime
import net.jbglenn.telltype.runtime.SessionCoordinatorFactory
import net.jbglenn.telltype.startup.AppStartup
import net.jbglenn.telltype.ui.screens.common.ScreenViewModelFactory
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsViewModel
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryViewModel
import net.jbglenn.telltype.ui.screens.history.HistoryViewModel
import net.jbglenn.telltype.ui.screens.home.HomeViewModel
import net.jbglenn.telltype.ui.screens.settings.SettingsViewModel
import net.jbglenn.telltype.ui.screens.setup.SetupViewModel
import net.jbglenn.telltype.ui.screens.snippets.SnippetsViewModel
import net.jbglenn.telltype.ui.screens.styles.StylesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Builds the real app graph and resolves the coordinator, every port it needs and every screen ViewModel. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    internal lateinit var coordinators: SessionCoordinatorFactory

    @Inject
    internal lateinit var ports: CoordinatorPorts

    @Inject
    internal lateinit var stores: CoordinatorStores

    @Inject
    internal lateinit var focus: FocusSources

    @Inject
    internal lateinit var viewModels: ScreenViewModelFactory

    @Inject
    internal lateinit var support: SupportPorts

    @Inject
    internal lateinit var startup: AppStartup

    @Inject
    internal lateinit var runtime: DictationRuntime

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun buildsACoordinatorFromTheRealGraph() {
        val first: SessionCoordinator = coordinators.create()
        val second: SessionCoordinator = coordinators.create()
        assertNotSame(first, second)
        first.close()
        second.close()
    }

    @Test
    fun resolvesEveryCoordinatorPort() {
        listOf(
            ports.speech, ports.cleaner, ports.inserter, ports.audio, ports.recordings, ports.microphone,
            ports.contextReader, ports.editWatcher, ports.clipboard, ports.haptics, ports.notifier,
            ports.fixLauncher, ports.health,
        ).forEach(::assertNotNull)
        listOf(stores.settings, stores.history, stores.dictionary, stores.snippets, stores.appRules, stores.pendingJobs)
            .forEach(::assertNotNull)
        listOf(focus.focusedField, focus.permissions, focus.snooze).forEach(::assertNotNull)
    }

    @Test
    fun resolvesTheAppSupportPorts() {
        listOf(
            support.clock, support.ids, support.logger, support.prompts, support.guards, support.reporter,
            support.exporter, support.selfTest, support.certificates, support.window,
        ).forEach(::assertNotNull)
        assertSame(support.clock, support.sameClock)
    }

    @Test
    fun createsEveryScreenViewModel() {
        val classes = listOf(
            SetupViewModel::class.java,
            HomeViewModel::class.java,
            HistoryViewModel::class.java,
            DictionaryViewModel::class.java,
            SnippetsViewModel::class.java,
            StylesViewModel::class.java,
            SettingsViewModel::class.java,
            DiagnosticsViewModel::class.java,
        )
        classes.forEach { assertEquals(it, viewModels.create(it)::class.java) }
    }
}

/** The ports outside the coordinator's groups, injected in one place. */
internal class SupportPorts @Inject constructor(
    val clock: Clock,
    val sameClock: Clock,
    val ids: IdGenerator,
    val logger: Logger,
    val prompts: PromptSet,
    val guards: GuardRules,
) {
    @Inject
    lateinit var reporter: MisrecognitionReporter

    @Inject
    lateinit var exporter: DebugBundleExporter

    @Inject
    lateinit var selfTest: SelfTest

    @Inject
    lateinit var certificates: ServerCertificateReader

    @Inject
    lateinit var window: BubbleWindowPort
}
