package net.jbglenn.telltype.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.settings.ServerModels

/** The three models the app sends. */
enum class ModelKind {
    /** Speech to text (`/v1/audio/transcriptions`). */
    SPEECH,

    /** Cleanup (`/v1/chat/completions`). */
    CLEANUP,

    /** Command Mode (`/v1/chat/completions`). */
    COMMAND,
}

/**
 * One model a row offers.
 *
 * @property id the name sent as `model`.
 * @property recommended carries "(Recommended)" in its row ([net.jbglenn.telltype.domain.settings.ModelChoices]).
 */
@Immutable
data class ModelOption(val id: String, val recommended: Boolean)

/**
 * Settings → Models.
 *
 * @property configured a server is set up; otherwise the section only says so.
 * @property chosen the stored choices.
 * @property speech what the server address lists; null when it lists none (speech is then typed).
 * @property cleanup what the text model address lists, marked for the cleanup row; null while unknown.
 * @property command the same list, marked for the command row; null while unknown.
 * @property loading the lists are being read.
 * @property problem why the lists could not be read, null when they could.
 */
@Immutable
data class ModelsUiState(
    val configured: Boolean = false,
    val chosen: ServerModels = ServerModels.Unset,
    val speech: List<ModelOption>? = null,
    val cleanup: List<ModelOption>? = null,
    val command: List<ModelOption>? = null,
    val loading: Boolean = false,
    @param:StringRes val problem: Int? = null,
) {
    /** The chosen name of [kind], null for the default. */
    fun chosenFor(kind: ModelKind): String? = when (kind) {
        ModelKind.SPEECH -> chosen.speech
        ModelKind.CLEANUP -> chosen.cleanup
        ModelKind.COMMAND -> chosen.command
    }

    /** The options of [kind]'s row, null when its address lists none. */
    fun optionsFor(kind: ModelKind): List<ModelOption>? = when (kind) {
        ModelKind.SPEECH -> speech
        ModelKind.CLEANUP -> cleanup
        ModelKind.COMMAND -> command
    }

    /** The choice of [kind] is no longer in its address's list (D60: say so and offer the list). */
    fun isMissing(kind: ModelKind): Boolean {
        val name = chosenFor(kind)
        val options = optionsFor(kind)
        return name != null && options != null && options.none { it.id == name }
    }
}

/** What the user did in the Models section. */
sealed interface ModelsAction {
    /** Read the lists again. */
    data object Refresh : ModelsAction

    /**
     * Chose or typed a model.
     *
     * @property kind which row.
     * @property name the model name; blank returns to the default.
     */
    data class Choose(val kind: ModelKind, val name: String) : ModelsAction
}
