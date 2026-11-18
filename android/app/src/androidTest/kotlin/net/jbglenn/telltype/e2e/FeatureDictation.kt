package net.jbglenn.telltype.e2e

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.e2e.Device.uiAutomation
import org.junit.Assert.assertTrue

/**
 * Package V1: dictations of the synthetic feature clips (`android/scripts/make-feature-clips.py`) against the live
 * server, through the debug build's clip microphone, into the test host's multi-line field. The clips are synthetic
 * sentences, not anyone's dictation, so the tests log what landed (tag `OwkE2E`) as evidence.
 */
internal object FeatureDictation {
    /** The field type every feature test dictates into (single-line fields drop the final period). */
    const val FIELD = "multi_line"

    /** Clip name -> how long to listen: the clip's length plus a margin. */
    private val ListenMs = mapOf("tone" to 4_200L, "dictionary" to 3_400L, "snippet" to 2_200L)
    private const val COMMAND_LISTEN_MS = 3_000L
    private const val POLL_MS = 200L

    /**
     * The live server's engines: switches off the debug build's fake engines and faults (marker files and the
     * Settings switch), which a class that ran before may have left on.
     */
    fun realEngines() {
        Bubble.fakeEngines(false)
        Bubble.faults()
        Arrange.settings { it.copy(advanced = it.advanced.copy(useFakeEngines = false)) }
    }

    /** Plays the clip [name] on the next recordings. */
    fun useClip(name: String) = Bubble.marker("clip-microphone", true, "$name\n")

    /** Back to the default (Phoebe) clip, for the classes that run after this one. */
    fun defaultClip() = Device.useClipMicrophone()

    /**
     * Opens a fresh multi-line field, dictates the clip [name] and returns what the field holds once the bubble is
     * Done (fails after [Scenario.LONG_WAIT_MS]). Clears History first, so [lastEntry] is this dictation's.
     */
    fun dictate(name: String): String {
        Arrange.clearHistory()
        Scenario.field(FIELD)
        useClip(name)
        Scenario.dictate(ListenMs.getValue(name))
        val done = Bubble.awaitState(Scenario.LONG_WAIT_MS) { it == "Done" || it?.startsWith("Problem") == true }
        assertTrue("dictation of '$name' ended Done, was $done", done == "Done")
        assertTrue("text landed", Scenario.eventually { Scenario.fieldText().isNotEmpty() })
        return Scenario.fieldText().also { Log.i(TAG, "v1 $name: '$it'") }
    }

    /**
     * Command Mode on [original]: a fresh multi-line field holding it, all of it selected (the accessibility
     * selection action, as a user's long press would), the wand bubble, the clip [instruction] spoken, and the
     * field's text once the rewrite has replaced the selection. Returns the rewrite. With [wandShot] the wand
     * bubble is saved as that screenshot.
     */
    fun command(original: String, instruction: String, wandShot: String? = null): String {
        Arrange.clearHistory()
        Scenario.field(FIELD)
        Device.setTextQuietly(0, original)
        select(0, original.length)
        Bubble.expectState("Ready for a command")
        wandShot?.let(Device::screenshot)
        useClip(instruction)
        Scenario.dictate(COMMAND_LISTEN_MS)
        val done = Bubble.awaitState(Scenario.LONG_WAIT_MS) { it == "Done" || it?.startsWith("Problem") == true }
        assertTrue("command '$instruction' ended Done, was $done", done == "Done")
        val replaced = Scenario.eventually(Scenario.LONG_WAIT_MS) { Scenario.fieldText() != original }
        val entry = Arrange.latestHistory()
        assertTrue(
            "the rewrite replaced the selection after '$instruction': field '${Scenario.fieldText()}', " +
                "History '${entry?.finalText}' via ${entry?.insertedVia}",
            replaced,
        )
        return Scenario.fieldText().also { Log.i(TAG, "v1 $instruction: '$original' -> '$it'") }
    }

    /** The History entry of the dictation [dictate] just made (History is cleared before each). */
    fun lastEntry(): HistoryEntry {
        assertTrue("the dictation was saved to History", Scenario.eventually { Arrange.latestHistory() != null })
        return checkNotNull(Arrange.latestHistory())
    }

    /** Selects [start] to [end] in the editable field of the window in front. */
    fun select(start: Int, end: Int) {
        val root = checkNotNull(uiAutomation.rootInActiveWindow) { "no active window" }
        val field = checkNotNull(findEditable(root)) { "no editable field" }
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
        SystemClock.sleep(POLL_MS)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (node.isEditable) {
        node
    } else {
        (0 until node.childCount).asSequence().mapNotNull { node.getChild(it)?.let(::findEditable) }.firstOrNull()
    }
}
