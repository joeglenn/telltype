package net.jbglenn.telltype.testing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.core.model.system.PermissionStatus

/**
 * Builders for the common model objects, with neutral defaults: override only what a test is about. Texts are
 * synthetic phrases, never real dictation.
 */
public object Fixtures {
    /** The time every fixture and [FixedClock] starts at. */
    public val Epoch: Instant = Instant.parse("2026-11-17T09:00:00Z")

    /** A field in a chat app. */
    public fun fieldTarget(
        handle: String = "field-1",
        app: String = "org.example.chat",
        kind: FieldKind = FieldKind.MULTI_LINE,
        hint: String? = "Message",
        incognito: Boolean = false,
    ): FieldTarget = FieldTarget(FieldHandle(handle), AppPackage(app), kind, hint, incognito)

    /** The field focused with the keyboard up and the cursor at the start. */
    public fun fieldSnapshot(
        target: FieldTarget = fieldTarget(),
        keyboardVisible: Boolean = true,
        selection: TextRange? = TextRange(0, 0),
    ): FieldSnapshot = FieldSnapshot(
        target = target,
        fieldBounds = ScreenRect(left = 40, top = 1200, right = 1040, bottom = 1320),
        keyboardBounds = if (keyboardVisible) ScreenRect(left = 0, top = 1500, right = 1080, bottom = 2400) else null,
        selection = selection,
    )

    /** A session that started by tap. */
    public fun session(
        id: String = "session-1",
        field: FieldTarget = fieldTarget(),
        mode: BubbleMode = BubbleMode.DICTATE,
        trigger: Trigger = Trigger.TAP,
    ): Session = Session(SessionId(id), field, mode, trigger)

    /** A three-second clip with speech. */
    public fun audioClip(
        recording: String = "recording-1",
        session: String = "session-1",
        duration: Duration = 3.seconds,
        speech: SpeechPresence = SpeechPresence.CLEAR,
    ): AudioClip = AudioClip(
        recording = RecordingId(recording),
        session = SessionId(session),
        path = "/data/recordings/$recording.wav",
        format = AudioFormat.Dictation,
        duration = duration,
        speech = speech,
    )

    /** A raw transcript. */
    public fun transcript(text: String = "hello world", audioDuration: Duration = 3.seconds): Transcript =
        Transcript(text, audioDuration, serverTimings = emptyList())

    /** Cleaned text produced by the model. */
    public fun cleanedText(
        text: String = "Hello world.",
        status: CleanupStatus = CleanupStatus.APPLIED,
        snippetOnly: Boolean = false,
    ): CleanedText = CleanedText(text, status, snippetOnly, serverTimings = emptyList())

    /** Text typed through the input connection at the start of an empty field. */
    public fun insertReceipt(
        target: FieldTarget = fieldTarget(),
        text: String = "Hello world.",
        replaced: String = "",
        via: InsertedVia = InsertedVia.INPUT_CONNECTION,
    ): InsertReceipt = InsertReceipt(
        target = target,
        text = text,
        replaced = replaced,
        range = if (via.typed) TextRange(0, text.length) else null,
        via = via,
    )

    /** A successful dictation. */
    public fun historyEntry(
        id: String = "session-1",
        createdAt: Instant = Epoch,
        finalText: String = "Hello world.",
        rawTranscript: String = "hello world",
        errorCode: String? = null,
        archived: Boolean = false,
    ): HistoryEntry = HistoryEntry(
        id = SessionId(id),
        createdAt = createdAt,
        app = AppPackage("org.example.chat"),
        category = AppCategory.PERSONAL_CHAT,
        mode = DictationMode.DICTATE,
        finalText = finalText,
        rawTranscript = rawTranscript,
        cleanupStatus = CleanupStatus.APPLIED,
        insertedVia = InsertedVia.INPUT_CONNECTION,
        audioDuration = 3.seconds,
        timings = SessionTimings.None,
        errorCode = errorCode,
        archived = archived,
    )

    /** A queued recording that has not been attempted yet. */
    public fun pendingJob(recording: String = "recording-1", createdAt: Instant = Epoch): PendingJob = PendingJob(
        recording = RecordingId(recording),
        session = SessionId("session-1"),
        createdAt = createdAt,
        audioPath = "/data/recordings/$recording.wav",
        category = AppCategory.PERSONAL_CHAT,
        tone = Tone.CASUAL,
        attempts = 0,
        lastErrorCode = null,
        app = AppPackage("org.example.chat"),
        incognito = false,
    )

    /** Everything granted, Advanced Protection off. */
    public fun permissionsGranted(): PermissionStatus = PermissionStatus(
        microphoneGranted = true,
        accessibilityEnabled = true,
        notificationsGranted = true,
        localNetworkGranted = true,
        advancedProtectionOn = false,
    )
}
