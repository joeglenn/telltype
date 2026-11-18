package net.jbglenn.telltype.ui.designsystem.error

import androidx.annotation.StringRes
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.error.AccessibilityDisabled
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.FieldProtected
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.LocalNetworkPermissionMissing
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.SelectionUnreadable
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.error.UnexpectedFailure
import net.jbglenn.telltype.ui.designsystem.R

/**
 * The one message for each error (docs/PLAN.md section 10): short and plain, shown on the bubble, the Home card,
 * History and Diagnostics. The `when` is exhaustive over the sealed hierarchy, so a new error case does not
 * compile until it has a message here.
 */
@StringRes
@Suppress("CyclomaticComplexMethod") // One branch per error case: the exhaustive when is the point.
fun DictationError.messageRes(): Int = when (this) {
    MicrophonePermissionMissing -> R.string.error_mic_permission_missing
    AccessibilityDisabled -> R.string.error_accessibility_disabled
    LocalNetworkPermissionMissing -> R.string.error_local_network_permission_missing
    MicrophoneInUse -> R.string.error_mic_in_use
    RecorderFailed -> R.string.error_recorder_failed
    NoSpeech -> R.string.error_no_speech
    StorageFull -> R.string.error_storage_full
    StorageFailed -> R.string.error_storage_failed
    MalformedResponse -> R.string.error_malformed_response
    is NetworkError -> serverCause().message
    EmptyTranscript -> R.string.error_empty_transcript
    SpeechEngineFailed -> R.string.error_speech_engine_failed
    CleanupTimedOut -> R.string.error_cleanup_timeout
    is CleanupRejected -> R.string.error_cleanup_rejected
    SelectionUnreadable -> R.string.error_selection_unreadable
    FieldChanged -> R.string.error_field_changed
    FieldGone -> R.string.error_field_gone
    InsertNotConfirmed -> R.string.error_insert_not_confirmed
    FieldProtected -> R.string.error_field_protected
    UnexpectedFailure -> R.string.error_unexpected
}

/**
 * The message for a failed connection check (Setup's Test, Diagnostics' Check now, the self-test, Settings' model
 * lists): the long form of the same [ServerCause] the bubble and Home name.
 */
@StringRes
fun DictationError.connectionMessageRes(): Int = (this as? NetworkError)?.serverCause()?.detail ?: messageRes()

/**
 * The message for a stored `DictationError.code` (History's `error_code`), or null for a code this build does
 * not know (written by a newer version).
 */
@StringRes
fun errorMessageForCode(code: String): Int? = KnownErrors.firstOrNull { it.code == code }?.messageRes()

/** One instance of every error case; the data classes with representative details. */
internal val KnownErrors: List<DictationError> = listOf(
    MicrophonePermissionMissing,
    AccessibilityDisabled,
    LocalNetworkPermissionMissing,
    MicrophoneInUse,
    RecorderFailed,
    NoSpeech,
    StorageFull,
    StorageFailed,
    ServerNotConfigured,
    ServerUnreachable,
    CertificateMismatch,
    KeyRejected,
    ServerWarmingUp,
    ServerFailed(httpStatus = 502, serverCode = "backend_error"),
    RequestRejected(httpStatus = 413, serverCode = "too_large"),
    MalformedResponse,
    EmptyTranscript,
    SpeechEngineFailed,
    CleanupTimedOut,
    CleanupRejected(GuardVerdict.EmptyReply),
    SelectionUnreadable,
    FieldChanged,
    FieldGone,
    InsertNotConfirmed,
    FieldProtected,
    UnexpectedFailure,
)
