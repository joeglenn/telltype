package net.jbglenn.telltype.ui.designsystem.component

/** How a status card reads at a glance; the icon carries the meaning as well as the colour. */
enum class StatusTone {
    /** All is well (check mark, tertiary container). */
    GOOD,

    /** Something to know or to do, nothing broken (info mark, secondary container). */
    ATTENTION,

    /** Something stops dictation (error mark, error container). */
    PROBLEM,

    /**
     * Nothing is wrong but dictation is paused, for example while the bubble is snoozed (snooze mark, neutral
     * surface container).
     */
    PAUSED,
}
