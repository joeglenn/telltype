package net.jbglenn.telltype.data.work

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.domain.port.SpeechToTextEngine

/** What one run of the queue asks WorkManager for. */
internal enum class QueueRun {
    /** Nothing left that a later run could do. */
    DONE,

    /** At least one job failed in a way a later run may fix (server unreachable, warming up, key fixed later). */
    RETRY_LATER,
}

/**
 * Works through the pending-job queue (docs/PLAN.md section 8.5): each recording is finalised, transcribed through
 * the [SpeechToTextEngine] port and finished with the rule-based cleanup ([QueuedText]); [JobOutcomes] saves,
 * offers and cleans up in the order of saving (section 10).
 *
 * - A faint recording is never sent; it ends as [NoSpeech].
 * - A recording that is gone, or an empty transcript, ends the job without text (History gets the error, the user a
 *   notice, job and audio go).
 * - Any other failure counts an attempt; after [MAX_JOB_ATTEMPTS] the job is parked with its audio and the user is
 *   told once, so nothing fails silently.
 */
internal class PendingJobProcessor @Inject constructor(
    private val jobs: PendingJobRepository,
    private val recordings: RecordingStore,
    private val engine: SpeechToTextEngine,
    private val text: QueuedText,
    private val outcomes: JobOutcomes,
    private val logger: Logger,
) {
    /** Processes every job that has attempts left. */
    suspend fun processAll(): QueueRun {
        val pending = jobs.jobs.first().filter { it.attempts < MAX_JOB_ATTEMPTS }
        if (pending.isEmpty()) return QueueRun.DONE
        logger.info(TAG) { "processing ${pending.size} queued recordings" }
        val hints = text.hints()
        val runs = pending.map { process(it, hints) }
        return if (QueueRun.RETRY_LATER in runs) QueueRun.RETRY_LATER else QueueRun.DONE
    }

    private suspend fun process(job: PendingJob, hints: SttHints): QueueRun {
        val clip = when (val finalised = recordings.finalise(job.recording)) {
            is Outcome.Ok -> finalised.value
            is Outcome.Err -> return outcomes.unreadable(job, finalised.error)
        }
        val transcribed = if (clip.speech == SpeechPresence.FAINT) null else engine.transcribe(clip, hints)
        return when (transcribed) {
            null -> outcomes.endedWithoutText(job, NoSpeech)

            is Outcome.Ok -> outcomes.delivered(job, clip, transcribed.value, text.finish(transcribed.value.text))

            is Outcome.Err -> when (val error = transcribed.error) {
                EmptyTranscript, NoSpeech -> outcomes.endedWithoutText(job, error)
                else -> outcomes.failedAttempt(job, error)
            }
        }
    }

    internal companion object {
        /**
         * Failed attempts before a job is parked. With WorkManager's exponential back-off from one minute (capped
         * at five hours) twelve runs span about a day, long enough to be back home or on the VPN.
         */
        const val MAX_JOB_ATTEMPTS = 12

        private val TAG = LogTag("PendingJobs")
    }
}
