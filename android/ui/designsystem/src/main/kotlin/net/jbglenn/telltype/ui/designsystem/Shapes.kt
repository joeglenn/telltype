package net.jbglenn.telltype.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The Material 3 shape scale (extra small 4 dp to extra large 28 dp). */
internal val TelltypeShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Fully rounded ends: the bubble's pills and chips (Appendix H). */
val PillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)
