package net.jbglenn.telltype.audio.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.test.StandardTestDispatcher
import net.jbglenn.telltype.audio.capture.AudioRecorder
import net.jbglenn.telltype.audio.store.FileRecordingStore
import net.jbglenn.telltype.audio.store.RecordingFiles
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.SequentialIdGenerator
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Hilt graph builds from [AudioModule] plus what the rest of the app provides. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AudioModuleTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var capture: AudioCapture

    @Inject
    lateinit var sameCapture: AudioCapture

    @Inject
    lateinit var store: RecordingStore

    @Inject
    internal lateinit var files: RecordingFiles

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun bindsTheRecorderAndTheStoreAsSingletons() {
        assertTrue(capture is AudioRecorder)
        assertTrue(store is FileRecordingStore)
        assertSame(capture, sameCapture)
    }

    @Test
    fun recordingsLiveInTheNoBackupDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(context.noBackupFilesDir.resolve(RecordingFiles.DIRECTORY_NAME), files.directory)
    }

    /** What `:app` and `:data` provide in the real graph. */
    @Module
    @InstallIn(SingletonComponent::class)
    object AppProvided {
        @Provides
        @Singleton
        fun dispatchers(): DispatcherProvider = TestDispatcherProvider(StandardTestDispatcher())

        @Provides
        @Singleton
        fun logger(): Logger = RecordingLogger()

        @Provides
        @Singleton
        fun ids(): IdGenerator = SequentialIdGenerator()

        @Provides
        @Singleton
        fun settings(): SettingsRepository = FakeSettingsRepository()
    }
}
