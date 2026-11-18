package net.jbglenn.telltype.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Fixed fallback palette (docs/PLAN.md Appendix H), used when dynamic colour is switched off and by every
// screenshot test. Seed: a deep blue. Every role follows the Material 3 tonal pattern (light: accents at tone 40,
// containers at 90; dark: accents at 80, containers at 30) over blue-tinted neutrals. The secondary roles use a
// teal ("Tide") rather than the seed's own desaturated blue, whose tone-90 container was nearly identical to the
// primary one, so that the Working bubble (secondary container) reads differently from Listening (primary).

private val RavenBlue10 = Color(0xFF001946)
private val RavenBlue20 = Color(0xFF002C71)
private val RavenBlue30 = Color(0xFF21428F)
private val RavenBlue40 = Color(0xFF3A5BA9)
private val RavenBlue80 = Color(0xFFB1C5FF)
private val RavenBlue90 = Color(0xFFDAE2FF)
private val Tide10 = Color(0xFF001F25)
private val Tide20 = Color(0xFF003640)
private val Tide30 = Color(0xFF1F4D57)
private val Tide40 = Color(0xFF38656F)
private val Tide80 = Color(0xFFA0CFDA)
private val Tide90 = Color(0xFFC8EAF0)
private val Moss10 = Color(0xFF072108)
private val Moss20 = Color(0xFF1D361B)
private val Moss30 = Color(0xFF334D2F)
private val Moss40 = Color(0xFF4A6545)
private val Moss80 = Color(0xFFB0CFA8)
private val Moss90 = Color(0xFFCCEBC3)
private val Red20 = Color(0xFF690005)
private val Red30 = Color(0xFF93000A)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)
private val Amber10 = Color(0xFF2A1800)
private val Amber30 = Color(0xFF633F00)
private val Amber90 = Color(0xFFFFDDB3)
private val Neutral4 = Color(0xFF0D0E13)
private val Neutral6 = Color(0xFF121318)
private val Neutral10 = Color(0xFF1A1B20)
private val Neutral12 = Color(0xFF1E1F25)
private val Neutral17 = Color(0xFF292A2F)
private val Neutral20 = Color(0xFF2F3036)
private val Neutral22 = Color(0xFF33353A)
private val Neutral24 = Color(0xFF38393F)
private val Neutral87 = Color(0xFFDAD9E0)
private val Neutral90 = Color(0xFFE2E2E9)
private val Neutral92 = Color(0xFFE8E7EF)
private val Neutral94 = Color(0xFFEEEDF4)
private val Neutral95 = Color(0xFFF1F0F7)
private val Neutral96 = Color(0xFFF4F3FA)
private val Neutral98 = Color(0xFFFAF9FF)
private val NeutralVariant30 = Color(0xFF44464F)
private val NeutralVariant50 = Color(0xFF757780)
private val NeutralVariant60 = Color(0xFF8F909A)
private val NeutralVariant80 = Color(0xFFC5C6D0)
private val NeutralVariant90 = Color(0xFFE1E2EC)

internal val TelltypeLightColors: ColorScheme = lightColorScheme(
    primary = RavenBlue40,
    onPrimary = Color.White,
    primaryContainer = RavenBlue90,
    onPrimaryContainer = RavenBlue10,
    inversePrimary = RavenBlue80,
    secondary = Tide40,
    onSecondary = Color.White,
    secondaryContainer = Tide90,
    onSecondaryContainer = Tide10,
    tertiary = Moss40,
    onTertiary = Color.White,
    tertiaryContainer = Moss90,
    onTertiaryContainer = Moss10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = RavenBlue40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red30,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Color.Black,
    surfaceBright = Neutral98,
    surfaceDim = Neutral87,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
)

internal val TelltypeDarkColors: ColorScheme = darkColorScheme(
    primary = RavenBlue80,
    onPrimary = RavenBlue20,
    primaryContainer = RavenBlue30,
    onPrimaryContainer = RavenBlue90,
    inversePrimary = RavenBlue40,
    secondary = Tide80,
    onSecondary = Tide20,
    secondaryContainer = Tide30,
    onSecondaryContainer = Tide90,
    tertiary = Moss80,
    onTertiary = Moss20,
    tertiaryContainer = Moss30,
    onTertiaryContainer = Moss90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = RavenBlue80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Color.Black,
    surfaceBright = Neutral24,
    surfaceDim = Neutral6,
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
)

/**
 * The amber of the bubble's Problem pill (docs/PLAN.md section 5.2). Material 3 has no warning role, so it is a
 * fixed extra pair, tone 90 on 10 in light and tone 30 on 90 in dark (contrast above 7:1 in both).
 *
 * @property container the pill.
 * @property content text and icons on it.
 */
internal data class WarningColors(val container: Color, val content: Color)

/** The warning pair that suits [this] scheme's lightness. */
internal fun ColorScheme.warningColors(): WarningColors =
    if (surface.luminance() < HALF) WarningColors(Amber30, Amber90) else WarningColors(Amber90, Amber10)

private const val HALF = 0.5f
