package net.jbglenn.telltype.ui.designsystem

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The Telltype Material 3 theme: colour, type scale and shapes. Uses the wallpaper-based dynamic colour
 * scheme by default (always available at min SDK 33) and the fixed fallback palette when [dynamicColor] is false.
 */
@Composable
fun TelltypeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = telltypeColorScheme(LocalContext.current, darkTheme, dynamicColor),
        typography = TelltypeTypography,
        shapes = TelltypeShapes,
        content = content,
    )
}

/** Picks the colour scheme for the given mode; exposed for tests and for non-Compose surfaces. */
fun telltypeColorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme = when {
    dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
    dynamicColor -> dynamicLightColorScheme(context)
    darkTheme -> TelltypeDarkColors
    else -> TelltypeLightColors
}

/**
 * The theme as previews and screenshot tests use it: the fallback palette (deterministic), a surface behind the
 * content, and an optional font scale (2.0 for the large-font goldens).
 */
@Composable
fun TelltypePreviewTheme(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        TelltypeTheme(darkTheme = darkTheme, dynamicColor = false) {
            Surface(modifier = modifier, content = content)
        }
    }
}
