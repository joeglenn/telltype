package net.jbglenn.telltype.platform.field

import net.jbglenn.telltype.core.model.FieldHandle

/**
 * Mints and resolves [FieldHandle]s (docs/PLAN.md section 6.5): equal handles mean the same field, a handle stays
 * the same while its field exists, and a token is never reused during the process lifetime (tokens count up).
 *
 * Generic over the node type so the rules are tested on the JVM; the app uses `AccessibilityNodeInfo`, whose
 * `equals` compares exactly window id and node id. At most [capacity] fields are remembered, least recently used
 * first out; a forgotten handle resolves to null like a field that is gone. Thread-safe.
 *
 * @param copy makes the private copy that is kept (nodes are mutable).
 */
internal class FieldHandleRegistry<N : Any>(private val capacity: Int = DEFAULT_CAPACITY, private val copy: (N) -> N) {
    private val entries = LinkedHashMap<FieldHandle, N>(capacity, LOAD_FACTOR, true)
    private var minted = 0L

    /** The handle of the field [node] is: the existing one when the same field was seen before, else a new one. */
    @Synchronized
    fun handleFor(node: N): FieldHandle {
        val known = entries.entries.firstOrNull { it.value == node }?.key
        return known?.also { entries[it] } ?: mint(node)
    }

    /** The node kept for [handle], or null when the handle is unknown or forgotten. */
    @Synchronized
    fun resolve(handle: FieldHandle): N? = entries[handle]

    /** Forgets every field, for example when the service disconnects (its nodes cannot be refreshed any more). */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun mint(node: N): FieldHandle {
        minted++
        val handle = FieldHandle("field-$minted")
        entries[handle] = copy(node)
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return handle
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
        const val LOAD_FACTOR = 0.75f
    }
}
