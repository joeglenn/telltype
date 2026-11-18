package net.jbglenn.telltype.core.model.error

import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict

/** Where a Fix action takes the user (docs/PLAN.md sections 5.3 and 10). */
public enum class FixTarget {
    /** The microphone permission prompt or the app's permission page. */
    MICROPHONE_PERMISSION,

    /** Android's accessibility settings, through the setup walk-through. */
    ACCESSIBILITY_SERVICE,

    /** The notification permission prompt. */
    NOTIFICATION_PERMISSION,

    /** The local network (Nearby devices) permission prompt, Android 17. */
    LOCAL_NETWORK_PERMISSION,

    /** The app's server setup: addresses, key, certificate fingerprint. */
    SERVER_SETUP,

    /** Android's storage settings. */
    STORAGE,
}

/**
 * Every failure the user can meet, one case per row of docs/PLAN.md section 10 (plus the network and engine
 * cases that row "Server unreachable" stands for). Each case has one message (looked up by [code] in `:ui`), one
 * bubble state and a fixed set of actions.
 *
 * Narrow sub-hierarchies let a port declare exactly what it can return: [CaptureError], [SttError],
 * [CleanupError], [InsertionError], [NetworkError], [StorageError], [PermissionError]. A case that can happen in
 * several places implements several of them (for example every [NetworkError] is also an [SttError] and a
 * [CleanupError]), so it keeps one [code].
 *
 * **Audio policy**, applied by the reducer to an error that ends a session before a transcript exists
 * (afterwards the words are safe in History or on the clipboard and the audio is always deleted):
 * - `keepsAudio == false`: the recording is deleted.
 * - `keepsAudio && retryable`: the recording is kept for Retry and deleted when the problem is dismissed.
 * - `keepsAudio && !retryable`: the recording goes to the pending-job queue ("Saved. Will transcribe later").
 */
public sealed interface DictationError {
    /**
     * Stable identifier, stored in `history.error_code` and `pending_job.last_error` and used to look up the
     * message. Lower snake case. Never change or reuse one.
     */
    public val code: String

    /** The recording must survive this error (see the audio policy above). */
    public val keepsAudio: Boolean

    /** The bubble offers Retry: with kept audio it re-runs transcription, without audio it starts a new recording. */
    public val retryable: Boolean

    /** Where the Fix action leads, or null when the problem has no Fix action. */
    public val fix: FixTarget?
}

/** A permission or system switch the app needs is missing. */
public sealed interface PermissionError : DictationError

/** The recording could not start or could not be finished. Returned by `AudioCapture` and `RecordingStore`. */
public sealed interface CaptureError : DictationError

/** Transcription failed. Returned by `SpeechToTextEngine`. */
public sealed interface SttError : DictationError

/** Cleanup or a Command Mode rewrite failed. Returned by `TextCleaner`. Dictation degrades to rule-based cleanup. */
public sealed interface CleanupError : DictationError

/** The text could not be typed into the target field. Returned by `TextInserter`; the domain hands the text off. */
public sealed interface InsertionError : DictationError

/** The server could not be reached or refused the request. Every case is also an [SttError] and a [CleanupError]. */
public sealed interface NetworkError :
    SttError,
    CleanupError

/** A file or database operation failed. Returned by the repositories and `RecordingStore`. */
public sealed interface StorageError : DictationError

/** "Microphone needed" + Fix: the microphone permission is not granted. */
public data object MicrophonePermissionMissing :
    PermissionError,
    CaptureError {
    override val code: String get() = "mic_permission_missing"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.MICROPHONE_PERMISSION
}

/** The accessibility service is switched off: notification and Home card with Fix; the bubble cannot show. */
public data object AccessibilityDisabled : PermissionError {
    override val code: String get() = "accessibility_disabled"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.ACCESSIBILITY_SERVICE
}

/** Android 17 blocked a connection to a home-network address because the local network permission is missing. */
public data object LocalNetworkPermissionMissing :
    PermissionError,
    NetworkError {
    override val code: String get() = "local_network_permission_missing"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.LOCAL_NETWORK_PERMISSION
}

/** "Microphone is in use": a call or another app holds the microphone (all-zero input within half a second). */
public data object MicrophoneInUse : CaptureError {
    override val code: String get() = "mic_in_use"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** The recorder could not start in time or failed to initialise; nothing was captured. */
public data object RecorderFailed : CaptureError {
    override val code: String get() = "recorder_failed"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = true
    override val fix: FixTarget? get() = null
}

/**
 * "Didn't catch that": the silence check found no speech (nothing is uploaded), or a near-silent clip produced
 * text that was dropped.
 */
public data object NoSpeech :
    CaptureError,
    SttError {
    override val code: String get() = "no_speech"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** "Storage is full", even after the oldest retained audio was deleted. */
public data object StorageFull :
    StorageError,
    CaptureError {
    override val code: String get() = "storage_full"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.STORAGE
}

/** A file or database read or write failed for a reason other than space. Files already written are left alone. */
public data object StorageFailed :
    StorageError,
    CaptureError {
    override val code: String get() = "storage_failed"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** No server address or key is set up yet. */
public data object ServerNotConfigured : NetworkError {
    override val code: String get() = "server_not_configured"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.SERVER_SETUP
}

/** "Saved. Will transcribe later": no address answered in time, after one retry and failover to every address. */
public data object ServerUnreachable : NetworkError {
    override val code: String get() = "server_unreachable"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** The server presented a certificate other than the pinned one. Never retried; the user re-confirms in setup. */
public data object CertificateMismatch : NetworkError {
    override val code: String get() = "certificate_mismatch"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.SERVER_SETUP
}

/** "The server rejected the key" + Fix: HTTP 401. Never retried. */
public data object KeyRejected : NetworkError {
    override val code: String get() = "key_rejected"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget get() = FixTarget.SERVER_SETUP
}

/** "The server is warming up": HTTP 503 `not_ready` still answered after back-off within the deadline. */
public data object ServerWarmingUp : NetworkError {
    override val code: String get() = "server_warming_up"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/**
 * The server failed the request: HTTP 429 `busy`, 502 `backend_error`, 504 `backend_timeout` or another 5xx.
 *
 * @property httpStatus the HTTP status code.
 * @property serverCode the `error.code` of the reply body (Appendix C), null when the body had none.
 */
public data class ServerFailed(public val httpStatus: Int, public val serverCode: String?) : NetworkError {
    override val code: String get() = "server_failed"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/**
 * The server refused this request as such: HTTP 400 `bad_request`, 413 `too_large`, 415 `unsupported_audio`.
 * Sending it again unchanged cannot succeed, so it is not queued; the audio is kept for a manual Retry.
 *
 * @property httpStatus the HTTP status code.
 * @property serverCode the `error.code` of the reply body (Appendix C), null when the body had none.
 */
public data class RequestRejected(public val httpStatus: Int, public val serverCode: String?) : NetworkError {
    override val code: String get() = "request_rejected"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = true
    override val fix: FixTarget? get() = null
}

/** The server answered 2xx, but the body was not the documented shape. */
public data object MalformedResponse : NetworkError {
    override val code: String get() = "malformed_response"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** "Didn't catch that" + Retry: the engine returned only whitespace. Audio kept until dismissed. */
public data object EmptyTranscript : SttError {
    override val code: String get() = "empty_transcript"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = true
    override val fix: FixTarget? get() = null
}

/** The on-device speech engine failed (docs/PLAN.md section 8.5, work package C2). */
public data object SpeechEngineFailed : SttError {
    override val code: String get() = "speech_engine_failed"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** The cleanup model missed its deadline. Dictation uses rule-based cleanup. */
public data object CleanupTimedOut : CleanupError {
    override val code: String get() = "cleanup_timeout"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/**
 * A guard rejected the model's reply. Dictation uses rule-based cleanup.
 *
 * @property verdict which guard failed and why.
 */
public data class CleanupRejected(public val verdict: GuardVerdict.Rejected) : CleanupError {
    override val code: String get() = "cleanup_rejected"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** Command Mode: the selected text could not be read back, so there is nothing to rewrite. */
public data object SelectionUnreadable : CleanupError {
    override val code: String get() = "selection_unreadable"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** Paste chip: focus moved to another field since recording started, so the text was not typed. */
public data object FieldChanged : InsertionError {
    override val code: String get() = "field_changed"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** Paste chip: the target field no longer exists (its window closed). */
public data object FieldGone : InsertionError {
    override val code: String get() = "field_gone"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** No ladder step could confirm the text arrived (every attempt timed out or read back differently). */
public data object InsertNotConfirmed : InsertionError {
    override val code: String get() = "insert_not_confirmed"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** The target became a password or numeric field, or its app was excluded, before insertion. Nothing typed. */
public data object FieldProtected : InsertionError {
    override val code: String get() = "field_protected"
    override val keepsAudio: Boolean get() = false
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/**
 * "Recovered 1 dictation": the process died during a session (found at next start), or a bug ended it. The
 * recording goes to the queue; a local crash report is written.
 */
public data object UnexpectedFailure : DictationError {
    override val code: String get() = "unexpected"
    override val keepsAudio: Boolean get() = true
    override val retryable: Boolean get() = false
    override val fix: FixTarget? get() = null
}

/** The [CleanupStatus] recorded when dictation falls back to rule-based cleanup after this error. */
public val CleanupError.fallbackStatus: CleanupStatus
    get() = when (this) {
        CleanupTimedOut -> CleanupStatus.SKIPPED_TIMEOUT
        is CleanupRejected -> CleanupStatus.SKIPPED_GUARD
        SelectionUnreadable, is NetworkError -> CleanupStatus.SKIPPED_ERROR
    }
