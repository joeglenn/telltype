package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.MicIcon

/**
 * The idle bubble's look in DataStore (board U9): five preferences next to the bubble group. Settings stored before
 * they existed read as [BubbleLook.Default], today's look; an unreadable value falls back to its default and is
 * reported by name, like every other setting ([SettingsCodec]).
 */
internal object BubbleLookCodec {
    private const val COLOUR = "bubble.look.colour"
    private const val HUE = "bubble.look.hue"
    private const val LIGHTNESS = "bubble.look.lightness"
    private const val TRANSPARENCY = "bubble.look.transparency"
    private const val ICON = "bubble.look.icon"

    /** Stored in [COLOUR] for a custom colour; presets are stored by name. */
    private const val CUSTOM = "CUSTOM"
    private const val MAX_HUE = 359
    private const val MAX_LIGHTNESS = 100

    /** The custom colour read when its hue or lightness is missing or invalid: a mid blue. */
    private val FallbackCustom = BubbleColour.Custom(hue = 220, lightness = 50)

    /** Reads the look from DataStore [values]; names of invalid preferences are added to [problems]. */
    fun decode(values: Map<String, Any?>, problems: MutableList<String>): BubbleLook {
        fun <T> invalid(name: String, fallback: T): T {
            problems += name
            return fallback
        }

        fun int(name: String, range: IntRange, default: Int): Int = when (val stored = values[name]) {
            null -> default
            is Int -> stored.takeIf { it in range } ?: invalid(name, default)
            else -> invalid(name, default)
        }

        val defaults = BubbleLook.Default
        val colour = when (val stored = values[COLOUR]) {
            null -> defaults.colour

            CUSTOM -> BubbleColour.Custom(
                hue = int(HUE, 0..MAX_HUE, FallbackCustom.hue),
                lightness = int(LIGHTNESS, 0..MAX_LIGHTNESS, FallbackCustom.lightness),
            )

            else -> BubblePreset.entries.firstOrNull { it.name == stored }?.let(BubbleColour::Preset)
                ?: invalid(COLOUR, defaults.colour)
        }
        val icon = when (val stored = values[ICON]) {
            null -> defaults.icon
            else -> MicIcon.entries.firstOrNull { it.name == stored } ?: invalid(ICON, defaults.icon)
        }
        return BubbleLook(
            colour = colour,
            transparency = int(TRANSPARENCY, 0..BubbleLook.MAX_TRANSPARENCY, defaults.transparency),
            icon = icon,
        )
    }

    /** Writes [look] into [preferences]; a preset colour removes the custom hue and lightness. */
    fun encode(look: BubbleLook, preferences: MutablePreferences) {
        when (val colour = look.colour) {
            is BubbleColour.Preset -> {
                preferences[stringPreferencesKey(COLOUR)] = colour.preset.name
                preferences.remove(intPreferencesKey(HUE))
                preferences.remove(intPreferencesKey(LIGHTNESS))
            }

            is BubbleColour.Custom -> {
                preferences[stringPreferencesKey(COLOUR)] = CUSTOM
                preferences[intPreferencesKey(HUE)] = colour.hue
                preferences[intPreferencesKey(LIGHTNESS)] = colour.lightness
            }
        }
        preferences[intPreferencesKey(TRANSPARENCY)] = look.transparency
        preferences[stringPreferencesKey(ICON)] = look.icon.name
    }
}
