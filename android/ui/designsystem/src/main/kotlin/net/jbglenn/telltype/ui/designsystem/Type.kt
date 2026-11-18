package net.jbglenn.telltype.ui.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * The type scale: Material 3's default scale on the system font (Appendix H: Material 3 defaults). Every size is
 * in sp, so it follows the system font scale up to 2.0.
 */
internal val TelltypeTypography: Typography = Typography()

/** Digits that do not shift while they change (the bubble's timer, timing tables). */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
