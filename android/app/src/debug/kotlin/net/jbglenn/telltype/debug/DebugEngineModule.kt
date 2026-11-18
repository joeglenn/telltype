package net.jbglenn.telltype.debug

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jbglenn.telltype.audio.capture.MicrophoneOverride
import net.jbglenn.telltype.audio.store.StorageOverride
import net.jbglenn.telltype.di.PipelineEngine
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner

/** Debug builds: the switchable fake engines, the clip microphone, the simulated full disk (the self-test is bound in `SelfTestModule`). */
@Module
@InstallIn(SingletonComponent::class)
internal interface DebugEngineModule {
    @Binds
    fun bindMicrophoneOverride(microphone: ClipMicrophone): MicrophoneOverride

    @Binds
    fun bindStorageOverride(storage: DebugStorageOverride): StorageOverride

    companion object {
        @Provides
        @PipelineEngine
        fun provideSpeech(
            real: SpeechToTextEngine,
            fake: FakeSpeechToTextEngine,
            switches: DebugSwitches,
        ): SpeechToTextEngine = SwitchingSpeechToTextEngine(real, fake, switches)

        @Provides
        @PipelineEngine
        fun provideCleaner(real: TextCleaner, fake: FakeTextCleaner, switches: DebugSwitches): TextCleaner =
            SwitchingTextCleaner(real, fake, switches)
    }
}
