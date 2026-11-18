package net.jbglenn.telltype.ui.screens.styles

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.ui.screens.R

/**
 * The Styles screen (docs/PLAN.md sections 4.3 and 5.3, Appendix J): the tone of each kind of app, per-app
 * overrides, and the apps where the bubble never appears.
 *
 * @property loading nothing has arrived yet.
 * @property tones the tone of each category, in display order.
 * @property overrides apps with a category or tone of their own, by package.
 * @property excluded apps where the bubble never appears, by package.
 * @property editor the open form, null when none is open.
 */
@Immutable
data class StylesUiState(
    val loading: Boolean = true,
    val tones: List<CategoryTone> = emptyList(),
    val overrides: List<AppRule> = emptyList(),
    val excluded: List<AppRule> = emptyList(),
    val editor: StylesEditor? = null,
)

/**
 * One category and its tone.
 *
 * @property category the category.
 * @property tone its tone.
 */
@Immutable
data class CategoryTone(val category: AppCategory, val tone: Tone)

/** The open form. */
@Immutable
sealed interface StylesEditor {
    /** The package name typed so far. */
    val app: String

    /** The validation message, null while the input is acceptable. */
    val error: Int?

    /**
     * A per-app override.
     *
     * @property category the category to use instead of the shipped map, null to keep it.
     * @property tone the tone to use instead of the category's, null to keep it.
     */
    data class Override(
        override val app: String,
        val category: AppCategory?,
        val tone: Tone?,
        @param:StringRes override val error: Int? = null,
    ) : StylesEditor

    /** An app to exclude. */
    data class Exclusion(override val app: String, @param:StringRes override val error: Int? = null) : StylesEditor
}

/** What the user did on the Styles screen. */
sealed interface StylesAction {
    /**
     * Picked a tone for a category.
     *
     * @property category the category.
     * @property tone the tone.
     */
    data class SetTone(val category: AppCategory, val tone: Tone) : StylesAction

    /** Opened an empty override form. */
    data object AddOverride : StylesAction

    /**
     * Opened an app's override for editing.
     *
     * @property app the app.
     */
    data class EditOverride(val app: AppPackage) : StylesAction

    /** Opened an empty exclusion form. */
    data object AddExclusion : StylesAction

    /**
     * Typed a package name.
     *
     * @property value the text.
     */
    data class AppChanged(val value: String) : StylesAction

    /**
     * Picked the override's category (null keeps the shipped one).
     *
     * @property category the category.
     */
    data class CategoryChanged(val category: AppCategory?) : StylesAction

    /**
     * Picked the override's tone (null keeps the category's).
     *
     * @property tone the tone.
     */
    data class ToneChanged(val tone: Tone?) : StylesAction

    /** Saved the form. */
    data object Save : StylesAction

    /** Closed the form. */
    data object CancelEdit : StylesAction

    /**
     * Removed an app's override.
     *
     * @property app the app.
     */
    data class RemoveOverride(val app: AppPackage) : StylesAction

    /**
     * Let the bubble appear in an excluded app again.
     *
     * @property app the app.
     */
    data class RemoveExclusion(val app: AppPackage) : StylesAction
}

/** One-off effects of the Styles screen. */
sealed interface StylesEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : StylesEffect
}

/** Whether [text] looks like an Android package name (`com.example.app`). */
fun isPackageName(text: String): Boolean = PackagePattern.matches(text.trim())

/** Validates a form; null when it may be saved. */
@StringRes
fun validateStylesEditor(editor: StylesEditor): Int? = when {
    !isPackageName(editor.app) -> R.string.styles_error_package

    editor is StylesEditor.Override && editor.category == null && editor.tone == null ->
        R.string.styles_error_override_empty

    else -> null
}

private val PackagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
