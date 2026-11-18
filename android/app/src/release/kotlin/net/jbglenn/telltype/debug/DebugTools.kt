package net.jbglenn.telltype.debug

import net.jbglenn.telltype.core.common.Logger

/** Release builds: no StrictMode and no stall detector. */
internal object DebugTools {
    /** Does nothing in release builds. */
    @Suppress("UNUSED_PARAMETER", "unused") // Same signature as the debug build's version.
    fun install(logger: Logger) = Unit
}
