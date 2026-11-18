package net.jbglenn.telltype.core.model.session

import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionStateTest {
    private val field =
        FieldTarget(FieldHandle("f"), AppPackage("org.example"), FieldKind.MULTI_LINE, hint = null, incognito = false)
    private val session = Session(SessionId("s"), field, BubbleMode.DICTATE, Trigger.TAP)

    private fun done(via: InsertedVia, raw: String?) = SessionState.Done(
        session = session,
        receipt = InsertReceipt(field, text = "Hi.", replaced = "", range = TextRange(0, 3), via = via),
        rawAlternative = raw,
        focus = Focus.None,
    )

    @Test
    fun `idle state follows the focus`() {
        assertEquals(SessionState.Hidden, Focus.None.idleState())
        assertEquals(
            SessionState.Ready(field, BubbleMode.COMMAND),
            Focus.Available(field, BubbleMode.COMMAND).idleState(),
        )
        assertEquals(
            SessionState.Unavailable(UnavailableReason.SERVER_NOT_CONFIGURED),
            Focus.Blocked(UnavailableReason.SERVER_NOT_CONFIGURED).idleState(),
        )
    }

    @Test
    fun `done offers raw and undo after typing, paste after a hand-off, copy without clipboard`() {
        assertEquals(setOf(BubbleAction.RAW, BubbleAction.UNDO), done(InsertedVia.INPUT_CONNECTION, "hi").actions)
        assertEquals(setOf(BubbleAction.UNDO), done(InsertedVia.SET_TEXT, raw = null).actions)
        assertEquals(setOf(BubbleAction.PASTE), done(InsertedVia.HANDOFF, raw = null).actions)
        assertEquals(setOf(BubbleAction.COPY), done(InsertedVia.NONE, raw = null).actions)
    }

    @Test
    fun `problem actions come from the error`() {
        assertEquals(
            setOf(BubbleAction.RETRY, BubbleAction.DISMISS),
            SessionState.Problem(EmptyTranscript, session, keptAudio = null, focus = Focus.None).actions,
        )
        assertEquals(
            setOf(BubbleAction.FIX, BubbleAction.DISMISS),
            SessionState.Problem(KeyRejected, session, keptAudio = null, focus = Focus.None).actions,
        )
        assertEquals(setOf(BubbleAction.DISMISS), SessionState.Problem(NoSpeech, null, null, Focus.None).actions)
    }

    @Test
    fun `kept audio always belongs to a session`() {
        val clip = AudioClip(
            recording = RecordingId("r"),
            session = session.id,
            path = "/r.wav",
            format = AudioFormat.Dictation,
            duration = 1.seconds,
            speech = SpeechPresence.CLEAR,
        )

        assertThrows<IllegalArgumentException> { SessionState.Problem(EmptyTranscript, null, clip, Focus.None) }
        assertEquals(clip, SessionState.Problem(EmptyTranscript, session, clip, Focus.None).keptAudio)
    }

    @Test
    fun `effects and done states never print the text`() {
        val printed = listOf(
            SessionEffect.Insert(session.id, field, "secret"),
            SessionEffect.CopyToClipboard("secret"),
            SessionEffect.HandOff(session, "secret"),
            done(InsertedVia.PASTE, raw = "secret"),
        ).joinToString()

        assertFalse(printed.contains("secret"), printed)
    }

    @Test
    fun `no timings measured is all null`() {
        assertEquals(null, SessionTimings.None.endToEnd)
        assertEquals(1.seconds, SessionTimings.None.copy(cleanup = 1.seconds).cleanup)
    }
}
