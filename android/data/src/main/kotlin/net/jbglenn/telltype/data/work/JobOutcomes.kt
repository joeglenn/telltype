package net.jbglenn.telltype.data.work

import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.RecordingStore

/**
 * What happens to a queued job once its attempt is decided: History, the job row, the notice and the audio, in the
 * order of saving of docs/PLAN.md section 10 (History, then the job is removed, then the notice, then the audio).
 * Each method answers whether a later run must look at the job again.
 * History goes through [JobHistory], which also writes the entry a session that died never wrote.
 */
internal class JobOutcomes @Inject constructor(
    private val jobs: PendingJobRepository,
    private val history: JobHistory,
    private val notifier: NotifierPort,
    private val recordings: RecordingStore,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    /** The job was transcribed to [text]: saved, offered (never typed: the field is gone) and its audio deleted. */
    suspend fun delivered(job: PendingJob, clip: AudioClip, transcript: Transcript, text: String): QueueRun {
        history.update(job) { entry ->
            entry.copy(
                finalText = text,
                rawTranscript = transcript.text,
                cleanupStatus = CleanupStatus.OFF,
                insertedVia = InsertedVia.NONE,
                audioDuration = clip.duration,
                timings = entry.timings.copy(server = entry.timings.server + transcript.serverTimings),
                errorCode = null,
            )
        }
        if (jobs.remove(job.recording) is Outcome.Err) return QueueRun.RETRY_LATER
        notifier.post(Notice.TranscribedLater(job.session, text))
        deleteAudio(job)
        logger.info(TAG) { "job ${job.recording.value} transcribed later and offered" }
        return QueueRun.DONE
    }

    /** The recording holds no words ([error]): History notes it, the user is told, job and audio go. */
    suspend fun endedWithoutText(job: PendingJob, error: DictationError): QueueRun {
        logger.info(TAG) { "job ${job.recording.value} ends without text: ${error.code}" }
        history.update(job) { it.copy(errorCode = error.code) }
        if (jobs.remove(job.recording) is Outcome.Err) return QueueRun.RETRY_LATER
        notifier.post(Notice.ProblemWhileHidden(error))
        deleteAudio(job)
        return QueueRun.DONE
    }

    /**
     * The recording could not be finalised: when its file is gone the job ends without text, otherwise it is a
     * failed attempt.
     */
    suspend fun unreadable(job: PendingJob, error: StorageError): QueueRun {
        val gone = withContext(dispatchers.io) { !File(job.audioPath).exists() }
        return if (gone) endedWithoutText(job, StorageFailed) else failedAttempt(job, error)
    }

    /**
     * One more failed attempt with [error]. Below [PendingJobProcessor.MAX_JOB_ATTEMPTS] a later run retries;
     * at the limit the job is parked with its audio (the user may retry or discard it) and the user is told once.
     */
    suspend fun failedAttempt(job: PendingJob, error: DictationError): QueueRun {
        val counted = jobs.recordFailure(job.recording, error.code) is Outcome.Ok
        val attempts = job.attempts + 1
        if (attempts < PendingJobProcessor.MAX_JOB_ATTEMPTS || !counted) {
            logger.info(TAG) { "job ${job.recording.value} attempt $attempts failed: ${error.code}" }
            return QueueRun.RETRY_LATER
        }
        logger.warn(TAG, null) { "job ${job.recording.value} parked after $attempts attempts: ${error.code}" }
        history.update(job) { it.copy(errorCode = error.code) }
        notifier.post(Notice.ProblemWhileHidden(error))
        return QueueRun.DONE
    }

    private suspend fun deleteAudio(job: PendingJob) {
        if (recordings.delete(job.recording) is Outcome.Err) {
            logger.warn(TAG, null) { "audio of ${job.recording.value} not deleted; the retention job will find it" }
        }
    }

    private companion object {
        val TAG = LogTag("PendingJobs")
    }
}
