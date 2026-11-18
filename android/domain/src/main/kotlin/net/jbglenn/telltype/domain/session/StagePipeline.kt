package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.core.model.speech.Transcript

/** A result for another session than the current one raced with a cancel and is ignored. */
internal fun onStageResult(state: SessionState, event: SessionEvent.StageResult): Transition =
    if (state.currentSession?.id != event.session) {
        state.unchanged()
    } else {
        when (event) {
            is SessionEvent.AudioFinalised -> onAudioFinalised(state, event.clip)
            is SessionEvent.AudioFailed -> onAudioFailed(state, event.error)
            is SessionEvent.Transcribed -> onTranscribed(state, event.transcript)
            is SessionEvent.TranscriptionFailed -> onTranscriptionFailed(state, event.error)
            is SessionEvent.Cleaned -> onCleaned(state, event.text)
            is SessionEvent.CleanupFailed -> onCleanupFailed(state, event.error)
            is SessionEvent.Inserted -> onInserted(state, event.receipt)
            is SessionEvent.InsertionFailed -> onInsertionFailed(state)
            is SessionEvent.HandedOff -> onHandedOff(state, event.receipt)
            is SessionEvent.QueueFailed -> onQueueFailed(state, event.error)
        }
    }

/** The Working state and its stage when [this] is Working in a stage of type [T], else null. */
internal inline fun <reified T : Stage> SessionState.inStage(): Pair<SessionState.Working, T>? =
    (this as? SessionState.Working)?.let { working -> (working.stage as? T)?.let { working to it } }

private fun onAudioFinalised(state: SessionState, clip: AudioClip): Transition {
    val (working, _) = state.inStage<Stage.Finalising>() ?: return state.unchanged()
    return working.copy(stage = Stage.Transcribing(clip)).with(SessionEffect.Transcribe(working.session, clip))
}

private fun onAudioFailed(state: SessionState, error: DictationError): Transition {
    val (working, _) = state.inStage<Stage.Finalising>() ?: return state.unchanged()
    return failBeforeTranscript(working, error, clip = null)
}

private fun onTranscribed(state: SessionState, transcript: Transcript): Transition {
    val (working, stage) = state.inStage<Stage.Transcribing>() ?: return state.unchanged()
    return when (stage.clip.speech) {
        SpeechPresence.FAINT -> failAfterAudio(working, NoSpeech, null)

        SpeechPresence.CLEAR -> working.copy(stage = Stage.Cleaning(transcript)).with(
            saveHistory(working.session, transcript, null, InsertedVia.NONE, null) +
                SessionEffect.Clean(working.session, transcript),
        )
    }
}

private fun onTranscriptionFailed(state: SessionState, error: DictationError): Transition {
    val (working, stage) = state.inStage<Stage.Transcribing>() ?: return state.unchanged()
    return failBeforeTranscript(working, error, stage.clip)
}

/** Ends a session in Problem when its audio is no longer needed: the recording is deleted. */
internal fun failAfterAudio(working: SessionState.Working, error: DictationError, transcript: Transcript?): Transition =
    enterProblem(
        SessionState.Problem(error, working.session, null, working.focus),
        before = listOf(SessionEffect.DeleteRecording(working.recording)),
        after = saveHistory(working.session, transcript, null, InsertedVia.NONE, error),
    )

/**
 * The audio policy for an error before a transcript exists (`DictationError`): `keepsAudio` false deletes the
 * recording; `keepsAudio && retryable` keeps [clip] for Retry; `keepsAudio && !retryable` queues [clip]. Without a
 * clip (finalising failed) a file that must be kept is left for the orphan scan.
 */
private fun failBeforeTranscript(working: SessionState.Working, error: DictationError, clip: AudioClip?): Transition {
    val kept = clip.takeIf { error.keepsAudio && error.retryable }
    val audio = when {
        !error.keepsAudio -> listOf(SessionEffect.DeleteRecording(working.recording))
        error.retryable || clip == null -> emptyList()
        else -> listOf(SessionEffect.EnqueuePendingJob(working.session, clip))
    }
    return enterProblem(
        SessionState.Problem(error, working.session, kept, working.focus),
        before = audio,
        after = saveHistory(working.session, null, null, InsertedVia.NONE, error),
    )
}
