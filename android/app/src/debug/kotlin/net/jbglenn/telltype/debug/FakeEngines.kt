package net.jbglenn.telltype.debug

import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner

/**
 * Debug builds: canned transcript after an artificial delay, so the UI runs without a server (Appendix G). The line
 * `stt=empty` in the faults file ([FakeFaults]) makes it answer [EmptyTranscript], as the server does for silence.
 */
internal class FakeSpeechToTextEngine @Inject constructor(private val faults: FakeFaults) : SpeechToTextEngine {
    override suspend fun prepare() = Unit

    override suspend fun transcribe(audio: AudioClip, hints: SttHints): Outcome<Transcript, SttError> {
        delay(TRANSCRIPTION_DELAY)
        return if (faults.has(FakeFaults.STT_EMPTY)) {
            Outcome.Err(EmptyTranscript)
        } else {
            Outcome.Ok(Transcript(SampleClip.TRANSCRIPT, audio.duration, emptyList()))
        }
    }

    private companion object {
        val TRANSCRIPTION_DELAY = 600.milliseconds
    }
}

/**
 * Debug builds: a scripted cleaner (adds the final full stop), and Command Mode that returns the selection. The
 * line `cleanup=slow` in the faults file ([FakeFaults]) makes it answer only after [SLOW_CLEANUP_DELAY], far past
 * any cleanup deadline; `cleanup=rewrite` drops a leading "Well, " as the server's model does, so Raw differs.
 */
internal class FakeTextCleaner @Inject constructor(private val faults: FakeFaults) : TextCleaner {
    override suspend fun clean(request: CleanupRequest): Outcome<CleanedText, CleanupError> {
        delay(if (faults.has(FakeFaults.CLEANUP_SLOW)) SLOW_CLEANUP_DELAY else CLEANUP_DELAY)
        val spoken = if (faults.has(FakeFaults.CLEANUP_REWRITE)) rewrite(request.transcript) else request.transcript
        val text = spoken.trimEnd().let { if (it.endsWith('.')) it else "$it." }
        return Outcome.Ok(CleanedText(text, CleanupStatus.APPLIED, snippetOnly = false, serverTimings = emptyList()))
    }

    override suspend fun command(request: CommandRequest): Outcome<CleanedText, CleanupError> {
        delay(CLEANUP_DELAY)
        return Outcome.Ok(CleanedText(request.selection, CleanupStatus.APPLIED, snippetOnly = false, emptyList()))
    }

    private fun rewrite(transcript: String): String =
        transcript.removePrefix(FILLER).replaceFirstChar(Char::uppercaseChar)

    private companion object {
        const val FILLER = "Well, "
        val CLEANUP_DELAY = 400.milliseconds
        val SLOW_CLEANUP_DELAY = 60.seconds
    }
}

/** Debug builds: the remote engine, or the fake one while [DebugSwitches.fakeEngines] is on (read per call). */
internal class SwitchingSpeechToTextEngine(
    private val real: SpeechToTextEngine,
    private val fake: SpeechToTextEngine,
    private val switches: DebugSwitches,
) : SpeechToTextEngine {
    override suspend fun prepare() = current().prepare()

    override suspend fun transcribe(audio: AudioClip, hints: SttHints): Outcome<Transcript, SttError> =
        current().transcribe(audio, hints)

    private suspend fun current() = if (switches.fakeEngines()) fake else real
}

/** Debug builds: the remote cleaner, or the fake one while [DebugSwitches.fakeEngines] is on (read per call). */
internal class SwitchingTextCleaner(
    private val real: TextCleaner,
    private val fake: TextCleaner,
    private val switches: DebugSwitches,
) : TextCleaner {
    override suspend fun clean(request: CleanupRequest): Outcome<CleanedText, CleanupError> = current().clean(request)

    override suspend fun command(request: CommandRequest): Outcome<CleanedText, CleanupError> =
        current().command(request)

    private suspend fun current() = if (switches.fakeEngines()) fake else real
}
