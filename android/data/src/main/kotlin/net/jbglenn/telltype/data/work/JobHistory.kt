package net.jbglenn.telltype.data.work

import javax.inject.Inject
import kotlin.time.Duration
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * The History entry of a queued job (A7h). Updates the entry its session wrote; when the session died before
 * writing one (a recording recovered after a crash, or a death between queueing and saving), writes it ([newEntry]),
 * so the words are not left only in a notification. Never writes for a job from an incognito field, nor while
 * History is off (retention zero): then the text exists only in the "Transcribed later" notification, whose Copy
 * action carries it.
 */
internal class JobHistory @Inject constructor(
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val logger: Logger,
) {
    /** Applies [change] to [job]'s entry, creating it first when its session never wrote one. */
    suspend fun update(job: PendingJob, change: (HistoryEntry) -> HistoryEntry) {
        if (job.incognito || !settings.current().privacy.historyOn) return
        val saved = history.get(job.session) as? Outcome.Ok ?: return
        val entry = saved.value ?: newEntry(job)
        if (history.upsert(change(entry)) is Outcome.Err) {
            logger.warn(TAG, null) { "history of ${job.session.value} not updated" }
        }
    }

    /** The entry the session never wrote: as for a queued dictation that was not typed. */
    private fun newEntry(job: PendingJob): HistoryEntry = HistoryEntry(
        id = job.session,
        createdAt = job.createdAt,
        app = job.app ?: UnknownApp,
        category = job.category,
        mode = DictationMode.DICTATE,
        finalText = "",
        rawTranscript = "",
        cleanupStatus = null,
        insertedVia = InsertedVia.NONE,
        audioDuration = Duration.ZERO,
        timings = SessionTimings.None,
        errorCode = job.lastErrorCode,
    )

    private companion object {
        val TAG = LogTag("PendingJobs")

        /** The app of a recording recovered after a crash: unknown (History shows an empty app name). */
        val UnknownApp = AppPackage("")
    }
}
