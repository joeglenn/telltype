package net.jbglenn.telltype.platform.emulator

import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.emulator.InsertSteps.describe
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Context reading around the cursor, its refusal on password fields, and the edit watcher after an insertion. */
class ContextAndEditsTest {
    @Before
    fun connect() {
        Emulator.connectService()
    }

    @Test
    fun contextIsTheTextAroundTheCursorWithinTheLimits() {
        for (type in listOf(HostField.PLAIN, HostField.COMPOSE, HostField.WEB_TEXTAREA)) {
            val snapshot = InsertSteps.prepare(type, "Hello brave world", TextRange(6, 11))
            val reader = Emulator.graph.contextReader

            val full = runBlocking { reader.read(snapshot.target, 100, 100) }
            val limited = runBlocking { reader.read(snapshot.target, 3, 2) }

            assertEquals(type.id, FieldContext("Hello ", " world", "brave"), full)
            assertEquals(type.id, FieldContext("lo ", " w", "brave"), limited)
            Emulator.record("context", type.id, "-", "before=6 after=6 selected=5, limits 3/2 ok")
        }
    }

    @Test
    fun aPasswordFieldGivesNoContext() {
        val snapshot = FieldSteps.focus(HostField.PASSWORD, text = "secret")

        val context = runBlocking { Emulator.graph.contextReader.read(snapshot.target, 100, 100) }

        assertEquals(FieldContext.Empty, context)
        Emulator.record("context", HostField.PASSWORD.id, "-", "empty")
    }

    @Test
    fun editsAfterAnInsertionAreReportedAndTheWatchEnds() {
        val snapshot = InsertSteps.prepare(HostField.PLAIN, "Hello", TextRange(5, 5))
        val inserted = InsertSteps.insert("edits", HostField.PLAIN, snapshot, " wrld")
        assertTrue(inserted.describe(), inserted is Outcome.Ok)

        assertFalse("text-changed events off before", textChangedEventsOn())

        var onDuringWatch = false
        val edits = runBlocking {
            val watch = async(Dispatchers.Default) {
                Emulator.graph.editWatcher.edits(snapshot.target, 8.seconds).toList()
            }
            // The watch is cold; wait until it has switched text-changed events on.
            withTimeout(5.seconds) { while (!Emulator.graph.textChangeSwitch.on) delay(50) }
            onDuringWatch = textChangedEventsOn()
            // The user corrects "wrld" with the keyboard (a programmatic set-text sends no text-changed event).
            repeat(3) { Emulator.device.pressKeyCode(KeyEvent.KEYCODE_DEL) }
            listOf(KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_D)
                .forEach { Emulator.device.pressKeyCode(it) }
            watch.await()
        }

        assertTrue("text-changed events on during the watch", onDuringWatch)
        assertTrue("edits=${edits.map { it.length }}", edits.isNotEmpty())
        assertEquals("Hello world", edits.last())
        assertFalse("text-changed events off after", textChangedEventsOn())
        Emulator.record("edits", HostField.PLAIN.id, "-", "${edits.size} edit(s), last matches, events on only during")
    }

    private fun textChangedEventsOn(): Boolean {
        val info = checkNotNull(AccessibilityServiceBridge.shared.service.value).serviceInfo
        return info.eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED != 0
    }
}
