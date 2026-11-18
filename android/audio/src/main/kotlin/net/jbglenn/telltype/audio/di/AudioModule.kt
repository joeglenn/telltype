package net.jbglenn.telltype.audio.di

import android.content.Context
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Optional
import javax.inject.Singleton
import net.jbglenn.telltype.audio.capture.AudioRecordSourceFactory
import net.jbglenn.telltype.audio.capture.AudioRecorder
import net.jbglenn.telltype.audio.capture.MicrophoneOverride
import net.jbglenn.telltype.audio.capture.PcmSourceFactory
import net.jbglenn.telltype.audio.platform.AndroidAudioFocus
import net.jbglenn.telltype.audio.platform.AndroidInterruptionMonitor
import net.jbglenn.telltype.audio.platform.AndroidMicrophonePermission
import net.jbglenn.telltype.audio.platform.AudioFocus
import net.jbglenn.telltype.audio.platform.InterruptionMonitor
import net.jbglenn.telltype.audio.platform.MicrophonePermission
import net.jbglenn.telltype.audio.store.FileRecordingStore
import net.jbglenn.telltype.audio.store.OverriddenSinkFactory
import net.jbglenn.telltype.audio.store.OverriddenStorageSpace
import net.jbglenn.telltype.audio.store.RecordingFiles
import net.jbglenn.telltype.audio.store.StorageOverride
import net.jbglenn.telltype.audio.store.StorageSpace
import net.jbglenn.telltype.audio.store.SystemStorageSpace
import net.jbglenn.telltype.audio.wav.RecordingSinkFactory
import net.jbglenn.telltype.audio.wav.WavFileWriter
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.RecordingStore

/**
 * Binds `:audio`'s implementations to the [AudioCapture] and [RecordingStore] ports. Needs, from elsewhere in the
 * graph: `DispatcherProvider`, `Logger`, `IdGenerator` and `SettingsRepository`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface AudioModule {
    @Binds
    fun bindAudioCapture(recorder: AudioRecorder): AudioCapture

    @Binds
    fun bindRecordingStore(store: FileRecordingStore): RecordingStore

    /** Bound only by `:app`'s debug source set. */
    @BindsOptionalOf
    fun optionalMicrophoneOverride(): MicrophoneOverride

    @Binds
    fun bindAudioFocus(focus: AndroidAudioFocus): AudioFocus

    @Binds
    fun bindInterruptionMonitor(monitor: AndroidInterruptionMonitor): InterruptionMonitor

    @Binds
    fun bindMicrophonePermission(permission: AndroidMicrophonePermission): MicrophonePermission

    /** Bound only by `:app`'s debug source set. */
    @BindsOptionalOf
    fun optionalStorageOverride(): StorageOverride

    companion object {
        /** The real microphone, unless a debug build replaced it ([MicrophoneOverride]). */
        @Provides
        fun providePcmSourceFactory(
            real: AudioRecordSourceFactory,
            override: Optional<MicrophoneOverride>,
        ): PcmSourceFactory = override.map { it.wrap(real) }.orElse(real)

        /** The WAV writer, unless a debug build simulates a disk that fills up ([StorageOverride]). */
        @Provides
        fun provideRecordingSinkFactory(
            real: WavFileWriter.Factory,
            override: Optional<StorageOverride>,
        ): RecordingSinkFactory = override.map<RecordingSinkFactory> { OverriddenSinkFactory(real, it) }.orElse(real)

        /** The system's free space, unless a debug build simulates a full disk ([StorageOverride]). */
        @Provides
        fun provideStorageSpace(real: SystemStorageSpace, override: Optional<StorageOverride>): StorageSpace =
            override.map<StorageSpace> { OverriddenStorageSpace(real, it) }.orElse(real)

        /** Recording files live in `noBackupFilesDir/recordings`: app-private and never backed up. */
        @Provides
        @Singleton
        fun provideRecordingFiles(@ApplicationContext context: Context): RecordingFiles =
            RecordingFiles(File(context.noBackupFilesDir, RecordingFiles.DIRECTORY_NAME))
    }
}
