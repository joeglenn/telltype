package net.jbglenn.telltype.core.model.error

import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictationErrorTest {
    /** Every case. [describe] fails to compile when a case is added and not listed there. */
    private val all: List<DictationError> = listOf(
        MicrophonePermissionMissing, AccessibilityDisabled, LocalNetworkPermissionMissing, MicrophoneInUse,
        RecorderFailed, NoSpeech, StorageFull, StorageFailed, ServerNotConfigured, ServerUnreachable,
        CertificateMismatch, KeyRejected, ServerWarmingUp, ServerFailed(502, "backend_error"),
        RequestRejected(413, "too_large"), MalformedResponse, EmptyTranscript, SpeechEngineFailed, CleanupTimedOut,
        CleanupRejected(GuardVerdict.EmptyReply), SelectionUnreadable, FieldChanged, FieldGone, InsertNotConfirmed,
        FieldProtected, UnexpectedFailure,
    )

    private fun describe(error: DictationError): String = when (error) {
        MicrophonePermissionMissing, AccessibilityDisabled, LocalNetworkPermissionMissing -> "permission"

        MicrophoneInUse, RecorderFailed, NoSpeech -> "capture"

        StorageFull, StorageFailed -> "storage"

        ServerNotConfigured, ServerUnreachable, CertificateMismatch, KeyRejected, ServerWarmingUp,
        is ServerFailed, is RequestRejected, MalformedResponse,
        -> "network"

        EmptyTranscript, SpeechEngineFailed -> "speech"

        CleanupTimedOut, is CleanupRejected, SelectionUnreadable -> "cleanup"

        FieldChanged, FieldGone, InsertNotConfirmed, FieldProtected -> "insertion"

        UnexpectedFailure -> "unexpected"
    }

    @Test
    fun `codes are unique lower snake case`() {
        val codes = all.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "duplicate code in $codes")
        assertTrue(codes.all { it.matches(Regex("[a-z]+(_[a-z]+)*")) }, "$codes")
    }

    @Test
    fun `every case belongs to a group of the error table`() {
        assertEquals(
            setOf("permission", "capture", "storage", "network", "speech", "cleanup", "insertion", "unexpected"),
            all.map(::describe).toSet(),
        )
    }

    @Test
    fun `audio policy matches the error table of plan section 10`() {
        val queued = all.filter { it.keepsAudio && !it.retryable }.toSet()
        val keptForRetry = all.filter { it.keepsAudio && it.retryable }.toSet()

        assertTrue(ServerUnreachable in queued && KeyRejected in queued && ServerWarmingUp in queued)
        assertTrue(UnexpectedFailure in queued)
        assertEquals(setOf(EmptyTranscript, RequestRejected(413, "too_large")), keptForRetry)
        listOf(NoSpeech, CleanupTimedOut, FieldChanged, InsertNotConfirmed, MicrophoneInUse).forEach {
            assertFalse(it.keepsAudio, "$it must not keep audio")
        }
    }

    @Test
    fun `fix actions lead where the table says`() {
        assertEquals(FixTarget.MICROPHONE_PERMISSION, MicrophonePermissionMissing.fix)
        assertEquals(FixTarget.ACCESSIBILITY_SERVICE, AccessibilityDisabled.fix)
        assertEquals(FixTarget.SERVER_SETUP, KeyRejected.fix)
        assertNull(ServerUnreachable.fix)
    }

    @Test
    fun `network errors can be returned by the speech engine and the cleaner`() {
        val network: NetworkError = ServerUnreachable
        val asStt: SttError = network
        val asCleanup: CleanupError = network

        assertEquals(asStt.code, asCleanup.code)
    }

    @Test
    fun `failed cleanup records why rule-based text was used`() {
        assertEquals(CleanupStatus.SKIPPED_TIMEOUT, CleanupTimedOut.fallbackStatus)
        assertEquals(CleanupStatus.SKIPPED_GUARD, CleanupRejected(GuardVerdict.EmptyReply).fallbackStatus)
        assertEquals(CleanupStatus.SKIPPED_ERROR, KeyRejected.fallbackStatus)
        assertEquals(CleanupStatus.SKIPPED_ERROR, SelectionUnreadable.fallbackStatus)
    }
}
