package net.jbglenn.telltype.domain.port

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript

/**
 * Turns audio into a raw transcript (strategy: remote on the server, on-device, fake). Implemented by `:network`
 * (remote, with retry, back-off for 503, failover across addresses and the circuit breaker inside) and later by
 * the on-device engine (C2). Called by the session coordinator and the pending-job worker.
 */
public interface SpeechToTextEngine {
    /**
     * Opens the connection ahead of the request, so the TLS handshake overlaps speaking (section 8.2). Best
     * effort: never fails and returns once the attempt is under way or finished.
     */
    public suspend fun prepare()

    /**
     * Transcribes [audio]. A result of only whitespace is [EmptyTranscript].
     * Bounded by connect, upload and response timeouts; cancellable at any point.
     */
    public suspend fun transcribe(audio: AudioClip, hints: SttHints): Outcome<Transcript, SttError>
}

/**
 * Asks the model to clean a transcript or to rewrite a selection. Implemented by `:network` using the domain's
 * prompt builder (`buildCleanupMessages`, `buildCommandMessages`), reply extraction and guards (`applyGuards`), so
 * a success is always text that may be inserted. Not retried (section 10: cleanup degrades). The caller applies
 * the deadline from `Settings.cleanup.modelDeadline` and turns a timeout into `CleanupTimedOut`.
 */
public interface TextCleaner {
    /**
     * Cleans a dictation. Success has status `APPLIED`; a guard failure is
     * [CleanupRejected].
     */
    public suspend fun clean(request: CleanupRequest): Outcome<CleanedText, CleanupError>

    /** Rewrites a selection in Command Mode (assistant model). Success has status `APPLIED`. */
    public suspend fun command(request: CommandRequest): Outcome<CleanedText, CleanupError>
}

/**
 * Types text into a field through the insertion ladder, steps 1 to 3 (section 7.4); step 4 (hand-off) is the
 * domain's. Implemented by `:platform`, on the accessibility dispatcher, each attempt with its own timeout and
 * verified by reading back. Honours "Never use the clipboard" by skipping step 3.
 */
public interface TextInserter {
    /**
     * Inserts [text] at the cursor of [target], replacing the selection if there is one. Fails with
     * [FieldChanged] when [target] no longer has focus: text is
     * never typed into another field.
     */
    public suspend fun insert(target: FieldTarget, text: String): Outcome<InsertReceipt, InsertionError>

    /** Replaces the text [receipt] describes with [text] (Raw, Undo). [text] may be empty. */
    public suspend fun replace(receipt: InsertReceipt, text: String): Outcome<InsertReceipt, InsertionError>
}

/**
 * The microphone (section 7.3, 8.1). Implemented by `:audio` (recorder, level meter, silence check) together with
 * `:platform`'s foreground microphone service. Reads run on the audio dispatcher; samples go to the recording's
 * file as they arrive, so a crash loses nothing. At most one recording at a time.
 */
public interface AudioCapture {
    /** Input levels of the running recording, conflated, at most 60 per second. Silent while not recording. */
    public val levels: Flow<AudioLevel>

    /** Interruptions of the running recording (screen off, call, microphone lost). Hot; nothing while idle. */
    public val interruptions: Flow<Interruption>

    /**
     * Opens the microphone and starts writing [recording] (created with [RecordingStore.create]). Returns once
     * audio flows, or fails within the start timeout. Takes audio focus when the setting says so.
     */
    public suspend fun start(recording: RecordingId): Outcome<Unit, CaptureError>

    /**
     * Stops, finalises the file and runs the silence check. Fails with
     * [NoSpeech] for a silent recording and
     * [MicrophoneInUse] when the input was all zeros.
     */
    public suspend fun stop(): Outcome<AudioClip, CaptureError>

    /** Stops and deletes the running recording's file, if any. Never fails. */
    public suspend fun cancel()
}

/**
 * The recording files in app-private storage. Implemented by `:audio`. When space runs low, [create] deletes the
 * oldest retained audio first (section 10, "Storage full").
 */
public interface RecordingStore {
    /** Allocates a new, empty recording file for [session]. */
    public suspend fun create(session: SessionId): Outcome<RecordingId, StorageError>

    /**
     * Allocates a new, empty recording file for [session] like [create], marked [incognito] when the field is
     * incognito. The mark stays with the file, and [finalise] reports it in `AudioClip.incognito`, so a recording
     * recovered after a crash is never saved to History. Added by A7h.
     */
    public suspend fun create(session: SessionId, incognito: Boolean): Outcome<RecordingId, StorageError>

    /**
     * Closes [recording] (fixes the header after a crash too) and returns it as a clip, with the silence check's
     * verdict. Idempotent: finalising a finalised recording returns the same clip.
     */
    public suspend fun finalise(recording: RecordingId): Outcome<AudioClip, StorageError>

    /** Deletes the file. Deleting a recording that does not exist succeeds. */
    public suspend fun delete(recording: RecordingId): Outcome<Unit, StorageError>

    /**
     * Recordings on disk that are not in [owned] (the pending jobs' recordings): left behind by a session that
     * died. Called once at start, before any session; the caller finalises and queues them.
     */
    public suspend fun findOrphans(owned: Set<RecordingId>): Outcome<List<RecordingId>, StorageError>
}

/**
 * The focused editable field, from accessibility events (section 7.1). Implemented by `:platform`; null when no
 * editable field has focus. Debounced and de-duplicated. Password and numeric fields are reported (with their
 * kind) so the domain decides to hide.
 */
public interface FocusedFieldSource {
    /** The current snapshot. */
    public val focused: StateFlow<FieldSnapshot?>
}

/**
 * Reads the text around the cursor at record start (section 4.3). Implemented by `:platform` on the
 * accessibility dispatcher with a strict timeout. Best effort by contract: anything unreadable, a timeout or a
 * vanished field gives [FieldContext.Empty] (or empty parts), never an error, because context only improves
 * cleanup. Never called for incognito fields.
 */
public interface FieldContextReader {
    /** Up to [charsBefore] characters before and [charsAfter] after the cursor, plus the selected text. */
    public suspend fun read(target: FieldTarget, charsBefore: Int, charsAfter: Int): FieldContext
}

/**
 * Watches one field after a dictation, for learning corrections (section 4.5, Appendix F: text-changed events
 * are switched on only for this window). Implemented by `:platform`.
 */
public interface FieldEditWatcher {
    /**
     * The full text of [target] after each edit. Completes after [window], when the field disappears, or when
     * the collector cancels. Cold: watching starts on collection.
     */
    public fun edits(target: FieldTarget, window: Duration): Flow<String>
}
