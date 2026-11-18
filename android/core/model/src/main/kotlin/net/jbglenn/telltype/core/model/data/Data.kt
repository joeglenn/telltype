package net.jbglenn.telltype.core.model.data

import kotlin.time.Duration
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.lengthOnly
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/**
 * What kind of session a history entry records; the `history.mode` column (docs/PLAN.md Appendix E).
 *
 * @property id stable id stored in the database. Never change one.
 */
public enum class DictationMode(public val id: String) {
    /** Normal dictation, cleaned or not. */
    DICTATE("dictate"),

    /** Command Mode: a spoken instruction rewrote the selection. */
    COMMAND("command"),

    /** The whole utterance matched a snippet trigger; the snippet text was inserted and the model skipped. */
    SNIPPET("snippet"),
}

/**
 * Where a dictionary term or replacement came from.
 *
 * @property id stable id stored in the database. Never change one.
 */
public enum class EntrySource(public val id: String) {
    /** Typed in by the user on the Dictionary screen. */
    MANUAL("manual"),

    /** Learned from a correction the user made after a dictation (docs/PLAN.md section 4.5). */
    LEARNED("learned"),
}

/**
 * One finished or failed session, one `history` row (docs/PLAN.md Appendix E). Written in two steps: once the
 * transcript exists (before insertion) and again when the session ends. Never written for incognito fields.
 * Deleted by the daily retention job unless [archived].
 *
 * @property id the session id; the row key.
 * @property createdAt when recording started (wall clock).
 * @property app the app dictated into.
 * @property category the category used for the tone.
 * @property mode dictation, command or snippet.
 * @property finalText the text that was inserted or handed off; `""` when the session failed before that.
 * @property rawTranscript the untouched transcript; `""` when transcription failed.
 * @property cleanupStatus how [finalText] was produced; null when the session ended before cleanup.
 * @property insertedVia how the text reached the user; [InsertedVia.NONE] when it never did.
 * @property audioDuration length of the recording.
 * @property timings per-stage durations (the speed readout and Diagnostics).
 * @property errorCode the `DictationError.code` that ended the session, null for a success.
 * @property archived the user moved it to the Archive (D52): listed there instead of in History and never deleted
 *   by the retention job, only by the user.
 */
public data class HistoryEntry(
    public val id: SessionId,
    public val createdAt: Instant,
    public val app: AppPackage,
    public val category: AppCategory,
    public val mode: DictationMode,
    public val finalText: String,
    public val rawTranscript: String,
    public val cleanupStatus: CleanupStatus?,
    public val insertedVia: InsertedVia,
    public val audioDuration: Duration,
    public val timings: SessionTimings,
    public val errorCode: String?,
    public val archived: Boolean = false,
) {
    override fun toString(): String = "HistoryEntry(id=$id, createdAt=$createdAt, app=${app.name}, " +
        "category=$category, mode=$mode, finalText=${finalText.lengthOnly()}, " +
        "rawTranscript=${rawTranscript.lengthOnly()}, cleanupStatus=$cleanupStatus, insertedVia=$insertedVia, " +
        "audioDuration=$audioDuration, timings=$timings, errorCode=$errorCode, archived=$archived)"
}

/** Database id of a [DictionaryTerm], assigned by the repository on insert. */
@JvmInline
public value class DictionaryTermId(public val value: Long)

/**
 * A word or name spelled the user's way (docs/PLAN.md section 4.5): sent as a speech hint and as the cleanup
 * glossary. One `dictionary_term` row.
 *
 * @property id the row id.
 * @property term the correct spelling.
 * @property soundsLike how the speech engine tends to mishear it; may be empty.
 * @property source manual or learned.
 * @property uses how many dictations used it; `>= 0`.
 * @property createdAt when it was added.
 */
public data class DictionaryTerm(
    public val id: DictionaryTermId,
    public val term: String,
    public val soundsLike: List<String>,
    public val source: EntrySource,
    public val uses: Int,
    public val createdAt: Instant,
)

/** Database id of a [Replacement], assigned by the repository on insert. */
@JvmInline
public value class ReplacementId(public val value: Long)

/**
 * A known wrong-to-right pair applied as the final exact replacement (docs/PLAN.md section 8.4). One
 * `replacement` row.
 *
 * @property id the row id.
 * @property heard the wrong form as it appears in text.
 * @property replaceWith the right form.
 * @property source manual or learned.
 * @property uses how many dictations it changed; `>= 0`.
 * @property createdAt when it was added.
 */
public data class Replacement(
    public val id: ReplacementId,
    public val heard: String,
    public val replaceWith: String,
    public val source: EntrySource,
    public val uses: Int,
    public val createdAt: Instant,
)

/**
 * A correction seen after a dictation: the user replaced one word with another (docs/PLAN.md section 4.5).
 * Offered as "Learn [heard] → [replaceWith]"; accepting it stores a learned [Replacement].
 *
 * @property heard the word as it was inserted.
 * @property replaceWith the word the user typed instead.
 */
public data class Correction(public val heard: String, public val replaceWith: String) {
    override fun toString(): String = "Correction(heard=${heard.lengthOnly()}, replaceWith=${replaceWith.lengthOnly()})"
}

/** Database id of a [Snippet], assigned by the repository on insert. */
@JvmInline
public value class SnippetId(public val value: Long)

/**
 * Saved text inserted when its trigger phrase is spoken (docs/PLAN.md section 4.6). One `snippet` row.
 *
 * @property id the row id.
 * @property trigger the phrase to say, for example "my address".
 * @property text the saved text to insert. Personal data: never logged.
 * @property uses how many times it was inserted; `>= 0`.
 * @property createdAt when it was added.
 */
public data class Snippet(
    public val id: SnippetId,
    public val trigger: String,
    public val text: String,
    public val uses: Int,
    public val createdAt: Instant,
) {
    override fun toString(): String =
        "Snippet(id=$id, trigger=${trigger.lengthOnly()}, text=${text.lengthOnly()}, uses=$uses, createdAt=$createdAt)"
}

/**
 * A recording waiting to be transcribed later because the server could not be reached (docs/PLAN.md section 8.5).
 * One `pending_job` row. The job owns its recording file until it succeeds; queued text is never typed, only
 * offered in a notification.
 *
 * @property recording the recording to transcribe; the row key (Appendix E `id`).
 * @property session the session it came from, to update that session's history entry. Not in Appendix E's
 *   column list: added by A2a.
 * @property createdAt when it was queued.
 * @property audioPath absolute path of the recording file.
 * @property category the app category at the time, for cleanup.
 * @property tone the tone at the time, for cleanup.
 * @property attempts how many transcription attempts failed so far; `>= 0`.
 * @property lastErrorCode the `DictationError.code` of the last failed attempt, null before the first attempt.
 * @property app the app dictated into, for the History entry the background job writes when the session died
 *   before writing its own; null when unknown (a recording recovered after a crash). Added by A7h.
 * @property incognito whether the dictation came from an incognito field (or that is unknown). Such a job never
 *   gets a History entry; its text is only offered in the notification. Added by A7h.
 */
public data class PendingJob(
    public val recording: RecordingId,
    public val session: SessionId,
    public val createdAt: Instant,
    public val audioPath: String,
    public val category: AppCategory,
    public val tone: Tone,
    public val attempts: Int,
    public val lastErrorCode: String?,
    public val app: AppPackage?,
    public val incognito: Boolean,
)
