package net.jbglenn.telltype.platform.di

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
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
import net.jbglenn.telltype.platform.overlay.OverlayViewTreeOwner
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.system.AndroidFixLauncher
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(AndroidJUnit4::class)
class PlatformModuleTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var focused: FocusedFieldSource

    @Inject lateinit var context: FieldContextReader

    @Inject lateinit var inserter: TextInserter

    @Inject lateinit var edits: FieldEditWatcher

    @Inject lateinit var microphone: MicrophoneServicePort

    @Inject lateinit var bubbleWindow: BubbleWindowPort

    @Inject lateinit var clipboard: ClipboardPort

    @Inject lateinit var haptics: HapticsPort

    @Inject lateinit var notifier: NotifierPort

    @Inject lateinit var fixLauncher: FixLauncher

    @Inject lateinit var permissions: PermissionStatusSource

    @Inject lateinit var overlayHost: AccessibilityOverlayHost

    @Inject lateinit var androidFixLauncher: AndroidFixLauncher

    @Inject lateinit var bridge: AccessibilityServiceBridge

    /** What `:app` and the data package provide in the real graph. */
    @Module
    @InstallIn(SingletonComponent::class)
    object Provided {
        @Provides
        fun dispatchers(): DispatcherProvider = TestDispatcherProvider()

        @Provides
        fun logger(): Logger = RecordingLogger()

        @Provides
        fun settings(): SettingsRepository = FakeSettingsRepository()
    }

    @Test
    fun `every platform port is bound, and the shared objects are the same instances`() {
        hilt.inject()

        val adapters = listOf(focused, context, inserter, edits, microphone, clipboard, haptics, notifier, permissions)
        adapters.forEach { adapter ->
            val name = adapter.javaClass.name
            assertTrue(name, name.startsWith("net.jbglenn.telltype.platform."))
        }
        assertSame(overlayHost, bubbleWindow)
        assertSame(androidFixLauncher, fixLauncher)
        assertSame(AccessibilityServiceBridge.shared, bridge)
    }

    @Test
    fun `the overlay's view-tree owner is resumed while shown and destroyed after`() {
        val owner = OverlayViewTreeOwner()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
        assertTrue(owner.savedStateRegistry.isRestored)

        owner.resume()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
