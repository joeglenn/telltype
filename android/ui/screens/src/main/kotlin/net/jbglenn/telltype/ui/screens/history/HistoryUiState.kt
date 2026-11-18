package net.jbglenn.telltype.ui.screens.history

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.time.ZoneId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.ui.screens.R

/**
 * History's list and the open entry (docs/PLAN.md section 5.3). Dictated text lives only here, in memory: it is
 * never put in a saved-state bundle (the navigation route holds only the id).
 *
 * @property query the search text.
 * @property entries matching entries, newest first; null while loading.
 * @property historyOff history is switched off in Settings (retention zero).
 * @property detail the open entry, null when none is open.
 * @property zone the zone times are shown in.
 * @property total how many entries History holds (all of them, not only the listed ones).
 * @property menuOpen the top bar's menu is open.
 * @property confirmingClear the "Clear history" confirmation is open.
 * @property archive the Archive is shown instead of History (D52); search covers the list on screen.
 * @property archivedCount how many entries the Archive holds.
 * @property undo the last swipe, while its Undo bar shows.
 * @property clearIncludesArchive "Clear history" deletes the Archive too.
 */
@Immutable
data class HistoryUiState(
    val query: String = "",
    val entries: List<HistoryEntry>? = null,
    val historyOff: Boolean = false,
    val detail: HistoryDetailState? = null,
    val zone: ZoneId = ZoneId.of("UTC"),
    val total: Int = 0,
    val menuOpen: Boolean = false,
    val confirmingClear: Boolean = false,
    val archive: Boolean = false,
    val archivedCount: Int = 0,
    val undo: HistoryUndo? = null,
    val clearIncludesArchive: Boolean = false,
) {
    /** How many entries History holds outside the Archive. */
    val historyCount: Int get() = (total - archivedCount).coerceAtLeast(0)

    override fun toString(): String = "HistoryUiState(query=‹${query.length} chars›, entries=${entries?.size}, " +
        "historyOff=$historyOff, detail=$detail, zone=$zone, total=$total, menuOpen=$menuOpen, " +
        "confirmingClear=$confirmingClear, archive=$archive, archivedCount=$archivedCount, undo=$undo, " +
        "clearIncludesArchive=$clearIncludesArchive)"
}

/**
 * A swipe that can still be undone.
 *
 * @property id the entry.
 * @property kind what the swipe did.
 */
@Immutable
data class HistoryUndo(val id: SessionId, val kind: UndoKind)

/** What a swipe did to an entry. */
enum class UndoKind(@param:StringRes val message: Int) {
    /** Moved to the Archive. */
    ARCHIVED(R.string.history_archived),

    /** Moved from the Archive back to History. */
    UNARCHIVED(R.string.history_unarchived),

    /** Deleted, final when the Undo bar goes away. */
    DELETED(R.string.history_deleted),
}

/**
 * One open entry.
 *
 * @property id the entry's id.
 * @property entry the entry; null while loading or when it no longer exists.
 * @property missing the entry could not be found or loaded.
 * @property rerun the state of "Re-run cleanup".
 * @property reportState the state of "That was wrong".
 * @property confirmingDelete the delete confirmation is open.
 */
@Immutable
data class HistoryDetailState(
    val id: SessionId,
    val entry: HistoryEntry? = null,
    val missing: Boolean = false,
    val rerun: RerunState = RerunState.Idle,
    val reportState: ReportState = ReportState.IDLE,
    val confirmingDelete: Boolean = false,
) {
    /** There is final text to copy. */
    val hasFinalText: Boolean get() = entry?.finalText?.isNotBlank() == true

    /** There is a transcript to report as misrecognised. */
    val canReport: Boolean get() = entry?.rawTranscript?.isNotBlank() == true

    /** Cleanup can run again on the raw transcript (not for Command Mode entries). */
    val canRerun: Boolean get() = entry != null && entry.rawTranscript.isNotBlank() &&
        entry.mode != DictationMode.COMMAND
}

/** "Re-run cleanup" on one entry. */
@Immutable
sealed interface RerunState {
    /** Not run. */
    data object Idle : RerunState

    /** Waiting for the model. */
    data object Running : RerunState

    /**
     * The new text.
     *
     * @property text the cleaned text.
     */
    data class Done(val text: String) : RerunState {
        override fun toString(): String = "Done(text=‹${text.length} chars›)"
    }

    /**
     * Cleanup failed.
     *
     * @property message why.
     */
    data class Failed(@param:StringRes val message: Int) : RerunState
}

/** "That was wrong" on one entry. */
enum class ReportState {
    /** Not sent. */
    IDLE,

    /** Sending. */
    SENDING,

    /** Added to the test set. */
    SENT,
}

/** What the user did in History. */
sealed interface HistoryAction {
    /**
     * Typed in the search field.
     *
     * @property query the text.
     */
    data class QueryChanged(val query: String) : HistoryAction

    /**
     * Opened an entry.
     *
     * @property id its id.
     */
    data class Select(val id: SessionId) : HistoryAction

    /** Closed the open entry. */
    data object CloseDetail : HistoryAction

    /** Copy the final text. */
    data object CopyFinal : HistoryAction

    /** Copy the raw transcript. */
    data object CopyRaw : HistoryAction

    /** Copy the re-run result. */
    data object CopyRerun : HistoryAction

    /** Run cleanup again on the raw transcript. */
    data object RerunCleanup : HistoryAction

    /** "That was wrong". */
    data object ReportWrong : HistoryAction

    /** Asked to delete the open entry. */
    data object AskDelete : HistoryAction

    /** Confirmed the deletion. */
    data object ConfirmDelete : HistoryAction

    /** Cancelled the deletion. */
    data object CancelDelete : HistoryAction

    /** Open Settings (to switch history on). */
    data object OpenSettings : HistoryAction

    /** Opened the top bar's menu. */
    data object OpenMenu : HistoryAction

    /** Closed the top bar's menu. */
    data object CloseMenu : HistoryAction

    /** Asked to clear the whole history. */
    data object AskClear : HistoryAction

    /** Confirmed clearing the whole history. */
    data object ConfirmClear : HistoryAction

    /** Cancelled clearing the whole history. */
    data object CancelClear : HistoryAction

    /**
     * Ticked or unticked "also delete the Archive" in the "Clear history" question.
     *
     * @property include the Archive goes too.
     */
    data class ClearIncludesArchive(val include: Boolean) : HistoryAction

    /** Opened the Archive from the menu. */
    data object OpenArchive : HistoryAction

    /** Left the Archive. */
    data object CloseArchive : HistoryAction

    /**
     * Swiped right in History, or chose Archive: moves the entry to the Archive.
     *
     * @property id the entry.
     */
    data class Archive(val id: SessionId) : HistoryAction

    /**
     * Swiped right in the Archive, or chose Move to History: moves the entry back.
     *
     * @property id the entry.
     */
    data class Unarchive(val id: SessionId) : HistoryAction

    /**
     * Swiped left, or chose Delete on the list: hides the entry and deletes it when the Undo bar goes away.
     *
     * @property id the entry.
     */
    data class Delete(val id: SessionId) : HistoryAction

    /** Tapped Undo on the Undo bar. */
    data object Undo : HistoryAction

    /** The Undo bar was swiped away: the last swipe is final now. */
    data object UndoDismissed : HistoryAction
}

/**
 * The top bar's menu and the "Clear history" question.
 *
 * @property menuOpen the menu is open.
 * @property confirmingClear the question is open.
 * @property includeArchive the question's "also delete the Archive" is ticked.
 */
internal data class HistoryMenuState(
    val menuOpen: Boolean = false,
    val confirmingClear: Boolean = false,
    val includeArchive: Boolean = false,
)

/** One-off effects of History. */
sealed interface HistoryEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : HistoryEffect

    /** The open entry was deleted: leave its detail page. */
    data object EntryDeleted : HistoryEffect
}
