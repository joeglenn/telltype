package net.jbglenn.telltype.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.domain.port.ClipboardPort
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.MisrecognitionReporter
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.TextCleaner
import net.jbglenn.telltype.ui.designsystem.error.messageRes
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.LocalTime
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/**
 * History and its Archive: search, the open entry, copy, re-run cleanup, "that was wrong", swipe to archive or delete
 * with Undo (D52), delete one entry or clear all of them.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class) // flatMapLatest and debounce
@Suppress("LongParameterList", "TooManyFunctions") // One port per action on this screen; one function per action.
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val dictionary: DictionaryRepository,
    private val cleaner: TextCleaner,
    private val clipboard: ClipboardPort,
    private val reporter: MisrecognitionReporter,
    private val eraser: HistoryEraser,
    private val deletions: HistoryDeletions,
    time: LocalTime,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val archive = MutableStateFlow(false)
    private val detail = MutableStateFlow<HistoryDetailState?>(null)
    private val menu = MutableStateFlow(HistoryMenuState())
    private val undo = MutableStateFlow<HistoryUndo?>(null)
    private var undoTimer: Job? = null
    private val effectChannel = Channel<HistoryEffect>(Channel.BUFFERED)

    /** One-off effects: messages, leaving a deleted entry. */
    val effects: Flow<HistoryEffect> = effectChannel.receiveAsFlow()

    private val results: Flow<List<HistoryEntry>?> = combine(query, archive, ::Pair)
        .debounce { (text, _) -> if (text.isBlank()) Duration.ZERO else SearchDebounce }
        .flatMapLatest { (text, inArchive) ->
            val source = if (text.isBlank()) {
                history.list(inArchive, LIST_LIMIT)
            } else {
                history.search(text.trim(), inArchive, LIST_LIMIT)
            }
            combine(source, deletions.pending) { entries, hidden -> entries.filterNot { it.id in hidden } }
                .map<List<HistoryEntry>, List<HistoryEntry>?> { it }
                .onStart { emit(null) }
        }

    private val list = combine(query, archive, results, ::Triple)
    private val counts = combine(eraser.count, eraser.archivedCount, ::Pair)
    private val overlays = combine(menu, undo, ::Pair)

    /** What History shows. */
    val state: StateFlow<HistoryUiState> = combine(
        list,
        settings.settings,
        detail,
        counts,
        overlays,
    ) { (text, inArchive, entries), current, open, (total, archived), (shownMenu, lastSwipe) ->
        HistoryUiState(
            query = text,
            entries = entries,
            historyOff = !current.privacy.historyOn,
            detail = open,
            zone = time.zone(),
            total = total,
            menuOpen = shownMenu.menuOpen,
            confirmingClear = shownMenu.confirmingClear,
            archive = inArchive,
            archivedCount = archived,
            undo = lastSwipe,
            clearIncludesArchive = shownMenu.includeArchive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), HistoryUiState(zone = time.zone()))

    /** Handles one user action. */
    fun onAction(action: HistoryAction) {
        when (action) {
            is HistoryAction.QueryChanged -> query.value = action.query
            is HistoryAction.Select -> select(action.id)
            HistoryAction.CloseDetail -> detail.value = null
            HistoryAction.CopyFinal -> copy { it.entry?.finalText }
            HistoryAction.CopyRaw -> copy { it.entry?.rawTranscript }
            HistoryAction.CopyRerun -> copy { (it.rerun as? RerunState.Done)?.text }
            HistoryAction.RerunCleanup -> rerun()
            HistoryAction.ReportWrong -> report()
            HistoryAction.AskDelete -> detail.update { it?.copy(confirmingDelete = true) }
            HistoryAction.CancelDelete -> detail.update { it?.copy(confirmingDelete = false) }
            HistoryAction.ConfirmDelete -> delete()
            HistoryAction.OpenSettings -> Unit
            else -> onListAction(action)
        }
    }

    private fun onListAction(action: HistoryAction) {
        when (action) {
            is HistoryAction.Archive -> swipe(action.id, UndoKind.ARCHIVED)
            is HistoryAction.Unarchive -> swipe(action.id, UndoKind.UNARCHIVED)
            is HistoryAction.Delete -> swipe(action.id, UndoKind.DELETED)
            HistoryAction.Undo -> undoLast()
            HistoryAction.UndoDismissed -> settleUndo()
            HistoryAction.OpenArchive -> showArchive(true)
            HistoryAction.CloseArchive -> showArchive(false)
            else -> onMenuAction(action)
        }
    }

    private fun onMenuAction(action: HistoryAction) {
        when (action) {
            HistoryAction.OpenMenu -> menu.value = HistoryMenuState(menuOpen = true)
            HistoryAction.CloseMenu -> menu.update { it.copy(menuOpen = false) }
            HistoryAction.AskClear -> menu.value = HistoryMenuState(confirmingClear = true)
            HistoryAction.CancelClear -> menu.value = HistoryMenuState()
            HistoryAction.ConfirmClear -> clear()
            is HistoryAction.ClearIncludesArchive -> menu.update { it.copy(includeArchive = action.include) }
            else -> Unit
        }
    }

    private fun showArchive(shown: Boolean) {
        menu.value = HistoryMenuState()
        if (archive.value == shown) return
        query.value = ""
        detail.value = null
        archive.value = shown
    }

    /** Applies a swipe at once (a deletion is only hidden) and shows its Undo bar; the previous one is final now. */
    private fun swipe(id: SessionId, kind: UndoKind) {
        settleUndo()
        viewModelScope.launch {
            val applied = when (kind) {
                UndoKind.DELETED -> {
                    deletions.hide(id)
                    true
                }

                UndoKind.ARCHIVED -> history.setArchived(id, archived = true) is Outcome.Ok

                UndoKind.UNARCHIVED -> history.setArchived(id, archived = false) is Outcome.Ok
            }
            if (!applied) {
                effectChannel.send(HistoryEffect.ShowMessage(R.string.message_save_failed))
                return@launch
            }
            followInDetail(id, kind)
            undo.value = HistoryUndo(id, kind)
            undoTimer = launch {
                delay(HistoryDeletions.UndoWindow)
                undo.update { if (it?.id == id) null else it }
            }
        }
    }

    private suspend fun followInDetail(id: SessionId, kind: UndoKind) {
        val open = detail.value?.takeIf { it.id == id } ?: return
        if (kind == UndoKind.DELETED) {
            detail.value = null
            effectChannel.send(HistoryEffect.EntryDeleted)
        } else {
            detail.value = open.copy(entry = open.entry?.copy(archived = kind == UndoKind.ARCHIVED))
        }
    }

    private fun undoLast() {
        val last = undo.value ?: return
        undo.value = null
        undoTimer?.cancel()
        viewModelScope.launch {
            val restored = when (last.kind) {
                UndoKind.DELETED -> deletions.restore(last.id)
                UndoKind.ARCHIVED -> history.setArchived(last.id, archived = false) is Outcome.Ok
                UndoKind.UNARCHIVED -> history.setArchived(last.id, archived = true) is Outcome.Ok
            }
            if (!restored) {
                effectChannel.send(HistoryEffect.ShowMessage(R.string.message_save_failed))
                return@launch
            }
            val archived = last.kind == UndoKind.UNARCHIVED
            detail.update { open ->
                if (open?.id == last.id) open.copy(entry = open.entry?.copy(archived = archived)) else open
            }
        }
    }

    private fun settleUndo() {
        val last = undo.value ?: return
        undo.value = null
        undoTimer?.cancel()
        if (last.kind == UndoKind.DELETED) deletions.commit(last.id)
    }

    private fun clear() {
        val includeArchive = menu.value.includeArchive
        menu.value = HistoryMenuState()
        viewModelScope.launch {
            val message = when (eraser.deleteAll(includeArchived = includeArchive)) {
                is Outcome.Ok -> {
                    detail.value = null
                    R.string.history_cleared
                }

                is Outcome.Err -> R.string.history_clear_failed
            }
            effectChannel.send(HistoryEffect.ShowMessage(message))
        }
    }

    private fun select(id: SessionId) {
        if (detail.value?.id == id) return
        detail.value = HistoryDetailState(id = id)
        viewModelScope.launch {
            val loaded = when (val result = history.get(id)) {
                is Outcome.Ok -> result.value?.let { HistoryDetailState(id, entry = it) }
                    ?: HistoryDetailState(id, missing = true)

                is Outcome.Err -> HistoryDetailState(id, missing = true)
            }
            detail.update { if (it?.id == id) loaded else it }
        }
    }

    private fun copy(text: (HistoryDetailState) -> String?) {
        val value = detail.value?.let(text)?.takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            clipboard.copy(value)
            effectChannel.send(HistoryEffect.ShowMessage(R.string.history_copied))
        }
    }

    private fun rerun() {
        val open = detail.value?.takeIf { it.canRerun && it.rerun != RerunState.Running }
        val entry = open?.entry ?: return
        detail.update { it?.copy(rerun = RerunState.Running) }
        viewModelScope.launch {
            val current = settings.current()
            val request = CleanupRequest(
                session = entry.id,
                transcript = entry.rawTranscript,
                tone = current.styles.toneFor(entry.category),
                category = entry.category,
                dictionary = dictionary.terms.first().map { it.term },
                contextBefore = "",
                fieldHint = null,
            )
            val outcome = when (val result = cleaner.clean(request)) {
                is Outcome.Ok -> RerunState.Done(result.value.text)
                is Outcome.Err -> RerunState.Failed(result.error.messageRes())
            }
            detail.update { if (it?.id == entry.id) it.copy(rerun = outcome) else it }
        }
    }

    private fun report() {
        val open = detail.value?.takeIf { it.canReport && it.reportState == ReportState.IDLE }
        val entry = open?.entry ?: return
        detail.update { it?.copy(reportState = ReportState.SENDING) }
        viewModelScope.launch {
            val result = reporter.report(entry)
            val next = if (result is Outcome.Ok) ReportState.SENT else ReportState.IDLE
            detail.update { if (it?.id == entry.id) it.copy(reportState = next) else it }
            val message = when (result) {
                is Outcome.Ok -> R.string.history_reported
                is Outcome.Err -> result.error.messageRes()
            }
            effectChannel.send(HistoryEffect.ShowMessage(message))
        }
    }

    private fun delete() {
        val id = detail.value?.id ?: return
        viewModelScope.launch {
            when (eraser.delete(id)) {
                is Outcome.Ok -> {
                    detail.value = null
                    effectChannel.send(HistoryEffect.EntryDeleted)
                    effectChannel.send(HistoryEffect.ShowMessage(R.string.history_deleted))
                }

                is Outcome.Err -> {
                    detail.update { it?.copy(confirmingDelete = false) }
                    effectChannel.send(HistoryEffect.ShowMessage(R.string.message_save_failed))
                }
            }
        }
    }

    private companion object {
        const val LIST_LIMIT = 200
        val SearchDebounce = 250.milliseconds
    }
}
