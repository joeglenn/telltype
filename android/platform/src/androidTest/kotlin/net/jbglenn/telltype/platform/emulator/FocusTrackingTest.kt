package net.jbglenn.telltype.platform.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Focusing each field type of the test host produces the right snapshot (kind, bounds, keyboard bounds, hint,
 * incognito flag), and leaving it produces null.
 */
@RunWith(Parameterized::class)
class FocusTrackingTest(private val type: HostField) {
    @Before
    fun connect() {
        Emulator.connectService()
    }

    @Test
    fun focusingReportsTheFieldAndLeavingReportsNull() {
        val snapshot = FieldSteps.focus(type)
        val field = checkNotNull(type.find())
        val keyboard = checkNotNull(snapshot.keyboardBounds)

        assertEquals(type.kind, snapshot.target.kind)
        val skipped = type == HostField.PASSWORD || type == HostField.NUMBER || type == HostField.PHONE
        assertEquals("the app skips password, number and phone fields", !skipped, snapshot.target.kind.acceptsDictation)
        assertEquals(Emulator.HOST, snapshot.target.app.name)
        assertEquals(field.visibleBounds.centerX(), (snapshot.fieldBounds.left + snapshot.fieldBounds.right) / 2)
        assertEquals(field.visibleBounds.centerY(), (snapshot.fieldBounds.top + snapshot.fieldBounds.bottom) / 2)
        assertTrue("keyboard below the field: $keyboard", keyboard.top >= snapshot.fieldBounds.bottom)
        assertEquals(Emulator.device.displayHeight, keyboard.bottom)
        assertEquals(expectedHint(), snapshot.target.hint)
        assertEquals(type == HostField.INCOGNITO, snapshot.target.incognito)
        Emulator.record("focus", type.id, "-", "kind=${snapshot.target.kind} keyboardTop=${keyboard.top} ok")

        Emulator.device.pressHome()
        assertNull(Emulator.awaitSnapshot { it == null })
        Emulator.record("leave", type.id, "-", "null ok")
    }

    private fun expectedHint(): String? = when (type) {
        HostField.PLACEHOLDER -> "Type a message"
        HostField.SEARCH -> "Search"
        HostField.WEB_INPUT -> "Web input"
        HostField.WEB_TEXTAREA -> "Web textarea"
        else -> null
    }

    private companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun types(): List<Array<Any>> = HostField.entries.map { arrayOf(it) }
    }
}
