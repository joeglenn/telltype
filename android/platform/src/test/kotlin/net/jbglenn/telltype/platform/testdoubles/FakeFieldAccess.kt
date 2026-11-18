package net.jbglenn.telltype.platform.testdoubles

import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.FieldAccess
import net.jbglenn.telltype.platform.field.FieldState
import net.jbglenn.telltype.platform.insert.ClipboardAccess
import net.jbglenn.telltype.platform.insert.SavedClip
import net.jbglenn.telltype.platform.insert.Splice

/**
 * A scriptable text field for the JVM tests: behaves like a well-behaved `EditText` by default (every step lands at
 * once), and each step can be told to be unavailable, refused, ignored, delayed or to do something else.
 */
internal class FakeFieldAccess(
    var text: String? = "",
    var selection: TextRange? = TextRange(0, 0),
    var showingHint: Boolean = false,
    var kind: FieldKind = FieldKind.SINGLE_LINE,
    var focused: Boolean = true,
    override val windowId: Int = 7,
) : FieldAccess {
    /**
     * How a step reacts. [ADAPTS] lands the text the way an auto-capitalising field with a comma filter would
     * ([adapt]): upper case, commas left out.
     */
    enum class Behaviour { LANDS, NOT_AVAILABLE, IGNORED, MANGLES, ADAPTS }

    var exists = true
    var inputConnection = Behaviour.LANDS
    var setTextAction = Behaviour.LANDS
    var pasteAction = Behaviour.LANDS

    /** Reads that still show the old state after an action (an asynchronous commit). */
    var readsBeforeLanding = 0

    /** What the clipboard holds, for paste. */
    var clipboard: FakeClipboardAccess? = null

    val calls = mutableListOf<String>()
    private var pending: (() -> Unit)? = null
    var reads = 0
        private set

    override fun read(): FieldState? {
        reads++
        if (pending != null && readsBeforeLanding > 0) {
            readsBeforeLanding--
        } else {
            pending?.invoke()
            pending = null
        }
        return if (exists) FieldState(text, selection, showingHint, kind, focused) else null
    }

    override fun commitText(range: TextRange?, text: String): Boolean {
        calls += "commit"
        val typed = if (inputConnection == Behaviour.ADAPTS) adapt(text) else text
        return act(inputConnection) { insertAt(range ?: selection ?: TextRange(0, 0), typed) }
    }

    override fun setText(text: String, cursor: Int): Boolean {
        calls += "setText"
        return act(setTextAction) {
            this.text = if (setTextAction == Behaviour.ADAPTS) adapt(text) else text
            selection = TextRange(cursor, cursor).takeIf { setTextAction != Behaviour.ADAPTS }
            showingHint = false
        }
    }

    override fun paste(range: TextRange?): Boolean {
        calls += "paste"
        val clip = clipboard?.content.orEmpty()
        return act(pasteAction) { insertAt(range ?: selection ?: TextRange(0, 0), clip) }
    }

    private fun act(behaviour: Behaviour, landing: () -> Unit): Boolean = when (behaviour) {
        Behaviour.NOT_AVAILABLE -> false

        Behaviour.IGNORED -> true

        Behaviour.LANDS, Behaviour.ADAPTS -> {
            pending = landing
            true
        }

        Behaviour.MANGLES -> {
            pending = { text = (if (showingHint) "" else text.orEmpty()) + "#" }
            true
        }
    }

    private fun adapt(value: String): String = value.uppercase().replace(",", "")

    private fun insertAt(range: TextRange, inserted: String) {
        val cursor = range.start + inserted.length
        selection = TextRange(cursor, cursor)
        val current = text
        if (current == null && !showingHint) return
        text = Splice.apply(if (showingHint) "" else current.orEmpty(), range, inserted)
        showingHint = false
    }
}

/** A clipboard for the ladder tests: remembers its content and every operation. */
internal class FakeClipboardAccess(var content: String? = "previous clip") : ClipboardAccess {
    val operations = mutableListOf<String>()
    var refusesPut = false

    override fun save(): SavedClip {
        operations += "save"
        return Saved(content)
    }

    override fun putSensitive(text: String): Boolean {
        operations += "put"
        if (!refusesPut) content = text
        return !refusesPut
    }

    override fun restore(saved: SavedClip) {
        operations += "restore"
        content = (saved as Saved).content
    }

    private class Saved(val content: String?) : SavedClip
}
