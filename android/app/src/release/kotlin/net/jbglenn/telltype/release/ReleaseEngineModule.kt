package net.jbglenn.telltype.release

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jbglenn.telltype.di.PipelineEngine
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner

/** Release builds: the remote engines only; no fake engine, no clip microphone. */
@Module
@InstallIn(SingletonComponent::class)
internal interface ReleaseEngineModule {
    @Binds
    @PipelineEngine
    fun bindSpeech(engine: SpeechToTextEngine): SpeechToTextEngine

    @Binds
    @PipelineEngine
    fun bindCleaner(cleaner: TextCleaner): TextCleaner
}
