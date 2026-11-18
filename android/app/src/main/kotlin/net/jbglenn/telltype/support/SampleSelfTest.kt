package net.jbglenn.telltype.support

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.di.PipelineEngine
import net.jbglenn.telltype.domain.coordinator.CoordinatorEnvironment
import net.jbglenn.telltype.domain.coordinator.PipelineSelfTest
import net.jbglenn.telltype.domain.coordinator.SelfTestSample
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.domain.port.SelfTestReport
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner

/**
 * The Diagnostics self-test in every build type: copies the bundled [SelfTestSampleAsset] to the cache and runs
 * [PipelineSelfTest] with the pipeline's engines, which expects [SelfTestSampleAsset.EXPECTED_TEXT] back.
 */
internal class SampleSelfTest @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:PipelineEngine private val speech: SpeechToTextEngine,
    @param:PipelineEngine private val cleaner: TextCleaner,
    private val settings: SettingsRepository,
    private val environment: CoordinatorEnvironment,
) : SelfTest {
    override suspend fun run(): Outcome<SelfTestReport, DictationError> {
        val clip = withContext(environment.dispatchers.io) {
            SelfTestSampleAsset.copyTo(context.assets, context.cacheDir, environment.ids.newSessionId())
        } ?: return Outcome.Err(StorageFailed)
        val sample = SelfTestSample(clip, SelfTestSampleAsset.EXPECTED_TEXT)
        return PipelineSelfTest(sample, speech, cleaner, settings, environment).run()
    }
}
