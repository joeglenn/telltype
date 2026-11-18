package net.jbglenn.telltype.testing

import kotlin.time.Duration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.FieldContextReader
import net.jbglenn.telltype.domain.port.FieldEditWatcher
import net.jbglenn.telltype.domain.port.FocusedFieldSource
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.domain.port.TextCleaner
import net.jbglenn.telltype.domain.port.TextInserter

/** Speech engine fake. By default every clip transcribes to [Fixtures.transcript]. */
public class FakeSpeechToTextEngine : SpeechToTextEngine {
    /** [transcribe]: arguments are the clip and the hints. */
    public val transcribeCall: ScriptedCall<Pair<AudioClip, SttHints>, Outcome<Transcript, SttError>> =
        ScriptedCall { Outcome.Ok(Fixtures.transcript()) }

    /** How many times [prepare] was called. */
    public var prepareCount: Int = 0
        private set

    override suspend fun prepare() {
        prepareCount++
    }

    override suspend fun transcribe(audio: AudioClip, hints: SttHints): Outcome<Transcript, SttError> =
        transcribeCall.answer(audio to hints)
}

/** Cleaner fake. By default cleanup returns [Fixtures.cleanedText] and a command returns the selection unchanged. */
public class FakeTextCleaner : TextCleaner {
    /** [clean]. */
    public val cleanCall: ScriptedCall<CleanupRequest, Outcome<CleanedText, CleanupError>> =
        ScriptedCall { Outcome.Ok(Fixtures.cleanedText()) }

    /** [command]. */
    public val commandCall: ScriptedCall<CommandRequest, Outcome<CleanedText, CleanupError>> =
        ScriptedCall { request -> Outcome.Ok(Fixtures.cleanedText(text = request.selection)) }

    override suspend fun clean(request: CleanupRequest): Outcome<CleanedText, CleanupError> = cleanCall.answer(request)

    override suspend fun command(request: CommandRequest): Outcome<CleanedText, CleanupError> =
        commandCall.answer(request)
}

/** Inserter fake. By default every insertion succeeds through the input connection at index 0. */
public class FakeTextInserter : TextInserter {
    /** [insert]: arguments are the target and the text. */
    public val insertCall: ScriptedCall<Pair<FieldTarget, String>, Outcome<InsertReceipt, InsertionError>> =
        ScriptedCall { (target, text) -> Outcome.Ok(Fixtures.insertReceipt(target = target, text = text)) }

    /** [replace]: arguments are the receipt and the replacement text. */
    public val replaceCall: ScriptedCall<Pair<InsertReceipt, String>, Outcome<InsertReceipt, InsertionError>> =
        ScriptedCall { (receipt, text) ->
            val start = receipt.range?.start ?: 0
            Outcome.Ok(receipt.copy(text = text, range = TextRange(start, start + text.length)))
        }

    override suspend fun insert(target: FieldTarget, text: String): Outcome<InsertReceipt, InsertionError> =
        insertCall.answer(target to text)

    override suspend fun replace(receipt: InsertReceipt, text: String): Outcome<InsertReceipt, InsertionError> =
        replaceCall.answer(receipt to text)
}

/**
 * Microphone fake. By default starting succeeds and stopping returns [Fixtures.audioClip] for the recording that
 * was started. Push levels with [emitLevel] and interruptions with [interrupt].
 */
public class FakeAudioCapture : AudioCapture {
    private val levelFlow =
        MutableSharedFlow<AudioLevel>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val interruptionFlow = MutableSharedFlow<Interruption>(extraBufferCapacity = 8)

    /** The recording between a successful [start] and [stop] or [cancel]; null otherwise. */
    public var active: RecordingId? = null
        private set

    /** [start]: the argument is the recording. */
    public val startCall: ScriptedCall<RecordingId, Outcome<Unit, CaptureError>> = ScriptedCall { Outcome.Ok(Unit) }

    /** [stop]: the argument is the recording that was active (null when none). */
    public val stopCall: ScriptedCall<RecordingId?, Outcome<AudioClip, CaptureError>> =
        ScriptedCall { recording -> Outcome.Ok(Fixtures.audioClip(recording = recording?.value ?: "recording-1")) }

    /** How many times [cancel] was called. */
    public var cancelCount: Int = 0
        private set

    override val levels: Flow<AudioLevel> = levelFlow

    override val interruptions: Flow<Interruption> = interruptionFlow

    override suspend fun start(recording: RecordingId): Outcome<Unit, CaptureError> =
        startCall.answer(recording).also { if (it is Outcome.Ok) active = recording }

    override suspend fun stop(): Outcome<AudioClip, CaptureError> = stopCall.answer(active).also { active = null }

    override suspend fun cancel() {
        cancelCount++
        active = null
    }

    /** Emits a level to [levels] collectors. */
    public suspend fun emitLevel(level: AudioLevel) {
        levelFlow.emit(level)
    }

    /** Emits an interruption to [interruptions] collectors. */
    public suspend fun interrupt(cause: Interruption) {
        interruptionFlow.emit(cause)
    }
}

/**
 * Recording files in memory. [create] makes `recording-1`, `recording-2`, ...; [finalise] returns a
 * [Fixtures.audioClip] for an existing recording and [StorageFailed] for an unknown one.
 */
public class FakeRecordingStore : RecordingStore {
    private val stored = linkedMapOf<RecordingId, SessionId>()
    private val incognito = mutableSetOf<RecordingId>()
    private var created = 0

    /** Failure and delay switches for every operation. */
    public val faults: StorageFaults = StorageFaults()

    /** The recordings that exist now. */
    public val recordings: Set<RecordingId> get() = stored.keys.toSet()

    /** The recordings created or left with the incognito mark. */
    public val incognitoRecordings: Set<RecordingId> get() = incognito.toSet()

    /** Puts a recording on "disk" directly, as if a crashed session had left it ([private]: the incognito mark). */
    public fun leave(recording: RecordingId, session: SessionId, private: Boolean = false) {
        stored[recording] = session
        if (private) incognito += recording
    }

    override suspend fun create(session: SessionId): Outcome<RecordingId, StorageError> = create(session, false)

    override suspend fun create(session: SessionId, incognito: Boolean): Outcome<RecordingId, StorageError> =
        faults.run {
            RecordingId("recording-${++created}").also {
                stored[it] = session
                if (incognito) this.incognito += it
            }
        }

    override suspend fun finalise(recording: RecordingId): Outcome<AudioClip, StorageError> {
        val session = stored[recording] ?: return Outcome.Err(StorageFailed)
        return faults.run {
            Fixtures.audioClip(recording = recording.value, session = session.value)
                .copy(incognito = recording in incognito)
        }
    }

    override suspend fun delete(recording: RecordingId): Outcome<Unit, StorageError> = faults.run {
        stored.remove(recording)
        incognito.remove(recording)
    }

    override suspend fun findOrphans(owned: Set<RecordingId>): Outcome<List<RecordingId>, StorageError> =
        faults.run { stored.keys.filterNot { it in owned } }
}

/** Focused-field fake: set [focused] through [focus]. Starts with nothing focused. */
public class FakeFocusedFieldSource : FocusedFieldSource {
    private val state = MutableStateFlow<FieldSnapshot?>(null)

    override val focused: StateFlow<FieldSnapshot?> = state.asStateFlow()

    /** Publishes [snapshot] (null: focus lost). */
    public fun focus(snapshot: FieldSnapshot?) {
        state.value = snapshot
    }
}

/** Context reader fake. By default returns [FieldContext.Empty]. */
public class FakeFieldContextReader : FieldContextReader {
    /** [read]: arguments are the target, chars before and chars after. */
    public val readCall: ScriptedCall<Triple<FieldTarget, Int, Int>, FieldContext> =
        ScriptedCall { FieldContext.Empty }

    override suspend fun read(target: FieldTarget, charsBefore: Int, charsAfter: Int): FieldContext =
        readCall.answer(Triple(target, charsBefore, charsAfter))
}

/**
 * Edit watcher fake. Every [edits] flow forwards what [type] sends while it is collected and completes when its
 * window has passed (virtual time).
 */
public class FakeFieldEditWatcher : FieldEditWatcher {
    private val texts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val watched = mutableListOf<Pair<FieldTarget, Duration>>()

    /** The target and window of every [edits] collection, oldest first. */
    public val watches: List<Pair<FieldTarget, Duration>> get() = watched.toList()

    override fun edits(target: FieldTarget, window: Duration): Flow<String> = channelFlow {
        watched += target to window
        val forwarding = launch { texts.collect { send(it) } }
        delay(window)
        forwarding.cancel()
    }

    /** Simulates the user editing the field so that it now holds [fieldText]. */
    public suspend fun type(fieldText: String) {
        texts.emit(fieldText)
    }
}
