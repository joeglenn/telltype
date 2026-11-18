package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.first
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.UnexpectedFailure
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * Start-up recovery (docs/PLAN.md sections 9 and 10, "Unexpected crash"): recordings on disk that no pending job
 * owns were left by a session that died. Each is finalised (the store repairs the header) and queued (category
 * `OTHER`, since the app is unknown, its tone from the settings, last error `unexpected`, incognito as the file's
 * mark says); then one notice says how many were recovered. A recording that cannot be finalised or queued stays on
 * disk for the next run.
 *
 * Runs at application start ([run], A7h: it needs only the recording store and the queue, not the accessibility
 * service) and again when a coordinator starts. The runs take turns through the [ledger] and re-read the queue
 * before queueing, so each recording is queued once; the ledger also remembers what was examined, for the
 * retention job's orphan sweep.
 */
public class CrashRecovery(
    private val stores: RecoveryStores,
    private val notifier: NotifierPort,
    private val ledger: RecoveryLedger,
    private val clock: Clock,
    private val logger: Logger,
    private val config: CoordinatorConfig = CoordinatorConfig(),
) {
    private val calls = PortCalls(logger)
    private val timeout get() = config.storage

    /** One whole recovery run: finds the orphaned recordings and queues them. For application start. */
    public suspend fun run() {
        ledger.exclusive { queueAll(scan()) }
    }

    /** The orphaned recordings; must run before any session starts, or a live recording would look orphaned. */
    internal suspend fun findOrphans(): List<RecordingId> = ledger.exclusive { scan() }

    /** Finalises and queues those of [orphans] no job owns yet; posts one notice when at least one was recovered. */
    internal suspend fun recover(orphans: List<RecordingId>) {
        ledger.exclusive { queueAll(orphans) }
    }

    private suspend fun scan(): List<RecordingId> {
        val owned = ownedRecordings()
        val failure: Outcome<List<RecordingId>, StorageError> = Outcome.Err(StorageFailed)
        val found = if (owned == null) {
            failure
        } else {
            calls.call("orphan scan", timeout, failure) { stores.recordings.findOrphans(owned) }
        }
        return when (found) {
            is Outcome.Ok -> found.value.also { ledger.markExamined(it) }

            is Outcome.Err -> emptyList<RecordingId>().also {
                logger.warn(CoordinatorTag, null) { "orphan scan failed: ${found.error.code}" }
            }
        }
    }

    private suspend fun queueAll(orphans: List<RecordingId>) {
        if (orphans.isEmpty()) return
        val owned = ownedRecordings() ?: return
        val recovered = orphans.filterNot { it in owned }.count { queue(it) }
        if (recovered > 0) {
            logger.info(CoordinatorTag) { "recovered $recovered of ${orphans.size} recordings" }
            calls.call("recovery notice", config.system, Unit) {
                notifier.post(Notice.DictationsRecovered(recovered))
            }
        }
    }

    private suspend fun ownedRecordings(): Set<RecordingId>? =
        calls.attempt("pending jobs read", timeout) { stores.pendingJobs.jobs.first() }?.map { it.recording }?.toSet()

    private suspend fun queue(recording: RecordingId): Boolean {
        val failure: Outcome<AudioClip, StorageError> = Outcome.Err(StorageFailed)
        val settings = calls.call("settings read", timeout, Settings.Defaults) { stores.settings.current() }
        val queued = calls.call("orphan finalise", timeout, failure) {
            stores.recordings.finalise(recording)
        }.flatMap { clip ->
            val job = PendingJob(
                recording = clip.recording,
                session = clip.session,
                createdAt = clock.now(),
                audioPath = clip.path,
                category = AppCategory.OTHER,
                tone = settings.styles.toneFor(AppCategory.OTHER),
                attempts = 0,
                lastErrorCode = UnexpectedFailure.code,
                app = null,
                incognito = clip.incognito,
            )
            calls.call("orphan enqueue", timeout, Outcome.Err(StorageFailed)) { stores.pendingJobs.enqueue(job) }
        }
        if (queued is Outcome.Err) {
            logger.warn(CoordinatorTag, null) { "orphan not recovered: ${queued.error.code}" }
        }
        return queued is Outcome.Ok
    }
}

/**
 * The stores crash recovery needs.
 *
 * @property recordings the recordings on disk.
 * @property pendingJobs the queue the recovered recordings join.
 * @property settings for the tone of category `OTHER`.
 */
public data class RecoveryStores(
    public val recordings: RecordingStore,
    public val pendingJobs: PendingJobRepository,
    public val settings: SettingsRepository,
)
