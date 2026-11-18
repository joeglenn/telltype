package net.jbglenn.telltype.platform.edits

/**
 * Switches text-changed accessibility events on while at least one edit watch runs (Appendix F: they are added at
 * run time for the learning window only). Reference-counted and thread-safe.
 *
 * @param apply turns the events on (true) or off (false) in the service's configuration.
 */
internal class TextChangeSwitch(private val apply: (Boolean) -> Unit) {
    private var holders = 0

    /** Whether the events are switched on now. */
    val on: Boolean @Synchronized get() = holders > 0

    /** One more watch needs the events. */
    @Synchronized
    fun acquire() {
        holders++
        if (holders == 1) apply(true)
    }

    /** One watch ended. */
    @Synchronized
    fun release() {
        check(holders > 0) { "release without acquire" }
        holders--
        if (holders == 0) apply(false)
    }
}
