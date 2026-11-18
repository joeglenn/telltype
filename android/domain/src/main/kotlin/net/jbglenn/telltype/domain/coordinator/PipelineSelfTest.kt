package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.domain.port.SelfTestReport
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner

/**
 * The clip the self-test sends and the text it must produce.
 *
 * @property clip the bundled sample recording.
 * @property expectedText what the pipeline must return; compared ignoring case, punctuation and spacing.
 */
public data class SelfTestSample(public val clip: AudioClip, public val expectedText: String) {
    override fun toString(): String = "SelfTestSample(clip=$clip, expectedText=‹${expectedText.length} chars›)"
}

/**
 * The Diagnostics self-test (docs/PLAN.md section 13) against the pipeline ports: transcribes [sample] (no
 * dictionary hints), cleans the transcript as a dictation in an `OTHER` app with the configured tone and deadline,
 * and compares the result with the expected text. An error is the first stage that failed (a timeout is
 * `ServerUnreachable` for transcription and `CleanupTimedOut` for cleanup); the report has the client-side timings
 * and the server's timings.
 */
public class PipelineSelfTest(
    private val sample: SelfTestSample,
    private val speech: SpeechToTextEngine,
    private val cleaner: TextCleaner,
    private val settings: SettingsRepository,
    private val environment: CoordinatorEnvironment,
) : SelfTest {
    private val calls = PortCalls(environment.logger)
    private val config: CoordinatorConfig get() = environment.config

    override suspend fun run(): Outcome<SelfTestReport, DictationError> = withContext(environment.dispatchers.default) {
        val started = environment.clock.monotonic()
        val unreachable: Outcome<Transcript, SttError> = Outcome.Err(ServerUnreachable)
        val transcript = calls.call(
            "self-test transcription",
            config.transcription,
            unreachable,
            Outcome.Err(SpeechEngineFailed),
        ) {
            speech.transcribe(sample.clip, SttHints(emptyList()))
        }
        when (transcript) {
            is Outcome.Err -> transcript
            is Outcome.Ok -> clean(transcript.value, started, environment.clock.monotonic() - started)
        }
    }

    private suspend fun clean(
        transcript: Transcript,
        started: Duration,
        transcription: Duration,
    ): Outcome<SelfTestReport, DictationError> {
        val current = calls.call("self-test settings", config.storage, Settings.Defaults) { settings.current() }
        val request = CleanupRequest(
            session = environment.ids.newSessionId(),
            transcript = transcript.text,
            tone = current.styles.toneFor(AppCategory.OTHER),
            category = AppCategory.OTHER,
            dictionary = emptyList(),
            contextBefore = "",
            fieldHint = null,
        )
        val deadline = current.cleanup.modelDeadline.takeIf { it.isFinite() } ?: config.modelCeiling
        val cleanupStarted = environment.clock.monotonic()
        val timedOut: Outcome<CleanedText, CleanupError> = Outcome.Err(CleanupTimedOut)
        val cleaned = calls.call("self-test cleanup", deadline, timedOut, Outcome.Err(MalformedResponse)) {
            cleaner.clean(request)
        }
        val finished = environment.clock.monotonic()
        return cleaned.map { text ->
            val timings = SessionTimings.None.copy(
                transcription = transcription,
                cleanup = finished - cleanupStarted,
                endToEnd = finished - started,
                server = transcript.serverTimings + text.serverTimings,
            )
            SelfTestReport(passed = comparable(text.text) == comparable(sample.expectedText), timings = timings)
        }
    }

    private fun comparable(text: String): String = text.lowercase().map {
        if (it.isLetterOrDigit()) it else ' '
    }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
