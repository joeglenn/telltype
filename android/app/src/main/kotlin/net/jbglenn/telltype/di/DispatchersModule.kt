package net.jbglenn.telltype.di

import android.os.Process
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import net.jbglenn.telltype.core.common.DispatcherProvider

/** The real dispatchers; tests bind `TestDispatcherProvider` from :testing instead. */
@Singleton
internal class AndroidDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default

    /** One thread for node queries, context reads and insertion, so they stay ordered (docs/PLAN.md section 9). */
    override val accessibility: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { task -> Thread(task, ACCESSIBILITY_THREAD) }.asCoroutineDispatcher()

    /** One urgent-audio-priority thread for the blocking `AudioRecord` read loop. */
    override val audio: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                task.run()
            },
            AUDIO_THREAD,
        )
    }.asCoroutineDispatcher()

    internal companion object {
        const val ACCESSIBILITY_THREAD = "telltype-accessibility"
        const val AUDIO_THREAD = "telltype-audio"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DispatchersModule {
    @Binds
    fun bindDispatcherProvider(provider: AndroidDispatcherProvider): DispatcherProvider
}
