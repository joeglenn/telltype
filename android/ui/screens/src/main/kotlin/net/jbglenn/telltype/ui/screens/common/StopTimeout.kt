package net.jbglenn.telltype.ui.screens.common

import kotlin.time.Duration.Companion.seconds

/** How long a screen's state keeps being collected after its last collector left (a rotation, not a backgrounding). */
internal val StopTimeout: Long = 5.seconds.inWholeMilliseconds
