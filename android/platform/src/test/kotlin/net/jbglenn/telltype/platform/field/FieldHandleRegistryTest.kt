package net.jbglenn.telltype.platform.field

import net.jbglenn.telltype.core.model.FieldHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FieldHandleRegistryTest {
    /** Stands in for a node: equal when window and node id are equal, like `AccessibilityNodeInfo`. */
    private data class Node(val window: Int, val id: Long, val label: String = "")

    private val copies = mutableListOf<Node>()
    private val registry = FieldHandleRegistry<Node>(capacity = 3) { node -> node.copy().also { copies += it } }

    @Test
    fun `the same field gets the same handle, also through a fresh node object`() {
        val first = registry.handleFor(Node(1, 10))

        assertEquals(first, registry.handleFor(Node(1, 10)))
        assertEquals(1, copies.size)
    }

    @Test
    fun `different fields get different handles`() {
        assertNotEquals(registry.handleFor(Node(1, 10)), registry.handleFor(Node(1, 11)))
        assertNotEquals(registry.handleFor(Node(1, 10)), registry.handleFor(Node(2, 10)))
    }

    @Test
    fun `a handle resolves to the kept copy`() {
        val handle = registry.handleFor(Node(1, 10))

        assertSame(copies.single(), registry.resolve(handle))
        assertNull(registry.resolve(FieldHandle("field-999")))
    }

    @Test
    fun `the least recently used field is forgotten beyond the capacity, and its token is never reused`() {
        val oldest = registry.handleFor(Node(1, 1))
        registry.handleFor(Node(1, 2))
        registry.handleFor(Node(1, 3))
        registry.handleFor(Node(1, 2))
        registry.handleFor(Node(1, 1))
        val newest = registry.handleFor(Node(1, 4))

        assertEquals(oldest, registry.handleFor(Node(1, 1)))
        assertNull("node 3 was used least recently", registry.resolve(FieldHandle("field-3")))
        val again = registry.handleFor(Node(1, 3))
        assertNotEquals(FieldHandle("field-3"), again)
        assertEquals(listOf("field-1", "field-4", "field-5"), listOf(oldest, newest, again).map { it.token })
    }

    @Test
    fun `clear forgets every field but keeps counting`() {
        val before = registry.handleFor(Node(1, 1))
        registry.clear()

        assertNull(registry.resolve(before))
        assertNotEquals(before, registry.handleFor(Node(1, 1)))
    }
}
