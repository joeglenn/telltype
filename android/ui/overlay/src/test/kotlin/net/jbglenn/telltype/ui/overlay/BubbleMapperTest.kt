package net.jbglenn.telltype.ui.overlay

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.data.Correction
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
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.error.messageRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleMapperTest {
    private val field = Fixtures.fieldTarget()
    private val focus = Focus.Available(field, BubbleMode.DICTATE)
    private val online = ServerReachability.Unknown
    private val offline = ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch)

    private fun map(
        state: SessionState,
        timeInState: Duration = Duration.ZERO,
        reachability: ServerReachability = online,
        chip: Chip? = null,
        releaseToCancel: Boolean = false,
    ) = mapBubble(BubbleInput(state, reachability, timeInState, BubbleSide.RIGHT, chip, releaseToCancel))

    @Test
    fun idleStates() {
        assertEquals(BubbleUiModel.Hidden, map(SessionState.Hidden).bubble)
        assertEquals(
            BubbleUiModel.Ready(BubbleMode.DICTATE, offline = false),
            map(SessionState.Ready(field, BubbleMode.DICTATE)).bubble,
        )
        assertEquals(
            BubbleUiModel.Ready(BubbleMode.COMMAND, offline = true),
            map(SessionState.Ready(field, BubbleMode.COMMAND), reachability = offline).bubble,
        )
        UnavailableReason.entries.forEach { reason ->
            assertEquals(BubbleUiModel.Unavailable(reason), map(SessionState.Unavailable(reason)).bubble)
        }
        assertEquals(
            BubbleUiModel.Arming(BubbleMode.COMMAND),
            map(SessionState.Arming(field, BubbleMode.COMMAND, Trigger.TAP, focus)).bubble,
        )
    }

    @Test
    fun listeningShowsTheTimerFromTenSeconds() {
        val state = listening(Trigger.TAP)

        assertEquals(
            BubbleUiModel.Listening(
                BubbleMode.DICTATE,
                hold = false,
                releaseToCancel = false,
                elapsed = null,
                offline = false,
            ),
            map(state, timeInState = 9.999.seconds).bubble,
        )
        assertEquals(10.seconds, (map(state, timeInState = 10.seconds).bubble as BubbleUiModel.Listening).elapsed)
        assertEquals(true, (map(state, reachability = offline).bubble as BubbleUiModel.Listening).offline)
    }

    @Test
    fun releaseToCancelOnlyAppliesToHoldToTalk() {
        val held = map(listening(Trigger.HOLD), releaseToCancel = true).bubble as BubbleUiModel.Listening
        val tapped = map(listening(Trigger.TAP), releaseToCancel = true).bubble as BubbleUiModel.Listening

        assertEquals(true, held.hold)
        assertEquals(true, held.releaseToCancel)
        assertEquals(false, tapped.releaseToCancel)
    }

    @Test
    fun workingLabelAppearsAfterOneAndAHalfSecondsForEveryStage() {
        val stages = mapOf(
            Stage.Finalising to WorkingLabel.TRANSCRIBING,
            Stage.Transcribing(Fixtures.audioClip()) to WorkingLabel.TRANSCRIBING,
            Stage.Cleaning(Fixtures.transcript()) to WorkingLabel.POLISHING,
            Stage.Inserting(Fixtures.transcript(), Fixtures.cleanedText()) to WorkingLabel.POLISHING,
        )
        stages.forEach { (stage, label) ->
            val state = SessionState.Working(Fixtures.session(), RecordingId("recording-1"), stage, focus)
            assertEquals(BubbleUiModel.Working(null), map(state, timeInState = 1499.milliseconds).bubble)
            assertEquals(BubbleUiModel.Working(label), map(state, timeInState = 1500.milliseconds).bubble)
        }
    }

    @Test
    fun doneShowsTheCheckThenTheChipsForEachInsertionPath() {
        val expected = mapOf(
            done(InsertedVia.INPUT_CONNECTION, raw = null) to listOf(BubbleAction.UNDO),
            done(InsertedVia.SET_TEXT, raw = "raw words") to listOf(BubbleAction.RAW, BubbleAction.UNDO),
            done(InsertedVia.PASTE, raw = "raw words") to listOf(BubbleAction.RAW, BubbleAction.UNDO),
            done(InsertedVia.HANDOFF, raw = null) to listOf(BubbleAction.PASTE),
            done(InsertedVia.NONE, raw = null) to listOf(BubbleAction.COPY),
        )
        expected.forEach { (state, chips) ->
            assertEquals(BubbleUiModel.Done(emptyList()), map(state, timeInState = 299.milliseconds).bubble)
            assertEquals(BubbleUiModel.Done(chips), map(state, timeInState = 300.milliseconds).bubble)
        }
    }

    @Test
    fun everyErrorHasItsMessageAndActionsInDisplayOrder() {
        AllErrors.forEach { error ->
            val problem = map(SessionState.Problem(error, Fixtures.session(), keptAudio = null, focus)).bubble
            val expectedActions = buildList {
                if (error.retryable) add(BubbleAction.RETRY)
                if (error.fix != null) add(BubbleAction.FIX)
                add(BubbleAction.DISMISS)
            }
            assertEquals(error.code, BubbleUiModel.Problem(error.messageRes(), expectedActions), problem)
        }
    }

    @Test
    fun everyErrorMessageIsDistinct() {
        assertEquals(AllErrors.size, AllErrors.map { it.messageRes() }.toSet().size)
    }

    @Test
    fun chipsAreMappedAndKeepTheSide() {
        val ready = SessionState.Ready(field, BubbleMode.DICTATE)
        val pair = Correction("cube", "kube")

        assertEquals(ChipUiModel.Busy, map(ready, chip = Chip.Busy).chip)
        assertEquals(ChipUiModel.SessionEndingSoon, map(ready, chip = Chip.SessionEndingSoon).chip)
        assertEquals(
            ChipUiModel.Explanation(UnavailableReason.SERVER_NOT_CONFIGURED),
            map(ready, chip = Chip.Explanation(UnavailableReason.SERVER_NOT_CONFIGURED)).chip,
        )
        assertEquals(ChipUiModel.LearnOffer(pair), map(ready, chip = Chip.LearnOffer(pair)).chip)
        assertNull(map(ready).chip)
        assertEquals(BubbleSide.RIGHT, map(ready).side)
    }

    @Test
    fun menuShowsOnlyOnTheUnavailableBubbleAndHidesTheChip() {
        val unavailable = SessionState.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING)
        val explanation = Chip.Explanation(UnavailableReason.MICROPHONE_PERMISSION_MISSING)
        fun menu(state: SessionState) =
            mapBubble(BubbleInput(state, online, Duration.ZERO, BubbleSide.RIGHT, explanation, menuOpen = true))

        val open = menu(unavailable)
        assertEquals(true, open.menu)
        assertEquals(null, open.chip)
        assertEquals(false, map(unavailable, chip = explanation).menu)
        val ready = menu(SessionState.Ready(field, BubbleMode.DICTATE))
        assertEquals("never on a bubble where a long press is hold-to-talk", false, ready.menu)
        assertEquals(ChipUiModel.Explanation(UnavailableReason.MICROPHONE_PERMISSION_MISSING), ready.chip)
    }

    @Test
    fun nextChangeTellsTheHostWhenToRemap() {
        val listening = listening(Trigger.TAP)
        assertEquals(4.seconds, nextBubbleChange(listening, 6.seconds))
        assertEquals(700.milliseconds, nextBubbleChange(listening, 12.3.seconds))
        val working = SessionState.Working(Fixtures.session(), RecordingId("recording-1"), Stage.Finalising, focus)
        assertEquals(1.seconds, nextBubbleChange(working, 500.milliseconds))
        assertNull(nextBubbleChange(working, 2.seconds))
        assertEquals(100.milliseconds, nextBubbleChange(done(InsertedVia.PASTE, null), 200.milliseconds))
        assertNull(nextBubbleChange(SessionState.Hidden, Duration.ZERO))
    }

    private fun listening(trigger: Trigger) = SessionState.Listening(
        session = Fixtures.session(trigger = trigger),
        recording = RecordingId("recording-1"),
        startedAt = Fixtures.Epoch,
        focus = focus,
    )

    private fun done(via: InsertedVia, raw: String?) = SessionState.Done(
        session = Fixtures.session(),
        receipt = Fixtures.insertReceipt(via = via),
        rawAlternative = raw,
        focus = focus,
    )

    private companion object {
        val AllErrors: List<DictationError> = listOf(
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
            ServerFailed(httpStatus = 500, serverCode = null),
            RequestRejected(httpStatus = 413, serverCode = "too_long"),
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
    }
}
