package net.jbglenn.telltype.ui.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing on the 4 dp grid (Appendix H). Use these instead of literal dp values. */
object Spacing {
    /** 4 dp: between an icon and its label, inside dense groups. */
    val ExtraSmall: Dp = 4.dp

    /** 8 dp: between related items, chip gaps. */
    val Small: Dp = 8.dp

    /** 12 dp: between items in a card. */
    val Medium: Dp = 12.dp

    /** 16 dp: card padding, screen margin on phones. */
    val Large: Dp = 16.dp

    /** 24 dp: between sections, screen margin on tablets. */
    val ExtraLarge: Dp = 24.dp

    /** 32 dp: around empty states. */
    val Huge: Dp = 32.dp
}

/** Layout measures shared by every screen (Appendix H). */
object Layout {
    /** Smallest touch target for any control (section 5.2). */
    val MinTouchTarget: Dp = 48.dp

    /** Screen margin on phones. */
    val PhoneMargin: Dp = 16.dp

    /** Screen margin on tablets (600 dp and wider). */
    val TabletMargin: Dp = 24.dp

    /** From this width on, a list and its detail are shown side by side. */
    val ListDetailMinWidth: Dp = 840.dp

    /** From this width on, the navigation rail replaces the bottom bar and tablet margins apply. */
    val MediumMinWidth: Dp = 600.dp

    /** Text columns stay readable on wide screens. */
    val MaxContentWidth: Dp = 720.dp

    /** Width of the list pane in a list-detail layout. */
    val ListPaneWidth: Dp = 400.dp
}

/** The window width classes the screens adapt to. */
enum class WidthClass {
    /** Phones in portrait: bottom bar, 16 dp margins, one pane. */
    COMPACT,

    /** Large phones in landscape, small tablets: navigation rail, 24 dp margins, one pane. */
    MEDIUM,

    /** Tablets: navigation rail, 24 dp margins, list and detail side by side. */
    EXPANDED,
    ;

    /** The screen margin for this width. */
    val margin: Dp get() = if (this == COMPACT) Layout.PhoneMargin else Layout.TabletMargin

    /** Whether list and detail panes are shown together. */
    val showsTwoPanes: Boolean get() = this == EXPANDED

    companion object {
        /** The class for a window [width]. */
        fun of(width: Dp): WidthClass = when {
            width >= Layout.ListDetailMinWidth -> EXPANDED
            width >= Layout.MediumMinWidth -> MEDIUM
            else -> COMPACT
        }
    }
}
