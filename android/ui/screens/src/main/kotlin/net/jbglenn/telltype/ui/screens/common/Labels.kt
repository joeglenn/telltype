package net.jbglenn.telltype.ui.screens.common

import androidx.annotation.StringRes
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.ui.screens.R

/** The display name of each app category (Appendix J). */
@get:StringRes
internal val AppCategory.label: Int
    get() = when (this) {
        AppCategory.EMAIL -> R.string.category_email
        AppCategory.WORK_CHAT -> R.string.category_work_chat
        AppCategory.PERSONAL_CHAT -> R.string.category_personal_chat
        AppCategory.NOTES_DOCUMENTS -> R.string.category_notes_documents
        AppCategory.AI_CHAT -> R.string.category_ai_chat
        AppCategory.CODE -> R.string.category_code
        AppCategory.BROWSER -> R.string.category_browser
        AppCategory.OTHER -> R.string.category_other
    }

/** The display name of each tone, in the order the choices are offered. */
@get:StringRes
internal val Tone.label: Int
    get() = when (this) {
        Tone.FORMAL -> R.string.tone_formal
        Tone.CASUAL -> R.string.tone_casual
        Tone.VERY_CASUAL -> R.string.tone_very_casual
        Tone.AS_SPOKEN -> R.string.tone_as_spoken
    }

/** Tones in the order they are offered: most to least polished. */
internal val ToneChoices: List<Tone> = listOf(Tone.FORMAL, Tone.CASUAL, Tone.VERY_CASUAL, Tone.AS_SPOKEN)
