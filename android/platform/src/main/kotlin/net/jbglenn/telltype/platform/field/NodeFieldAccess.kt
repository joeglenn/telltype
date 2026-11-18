package net.jbglenn.telltype.platform.field

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.field.TextRange

/** [FieldAccess] over one accessibility node. Every method blocks: accessibility thread only. */
internal class NodeFieldAccess(private val node: AccessibilityNodeInfo, private val gateway: AccessibilityGateway) :
    FieldAccess {
    override val windowId: Int get() = node.windowId

    override fun read(): FieldState? = gateway.withService(null) { service -> gateway.state(service, node) }

    override fun commitText(range: TextRange?, text: String): Boolean = gateway.withService(false) { service ->
        val connection = gateway.inputConnectionFor(service, node)
        connection?.let {
            range?.let { connection.setSelection(it.start, it.end) }
            connection.commitText(text, NEW_CURSOR_AFTER_TEXT, null)
        }
        connection != null
    }

    override fun setText(text: String, cursor: Int): Boolean = gateway.guarded(false) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments).also { set ->
            if (set) select(TextRange(cursor, cursor))
        }
    }

    override fun paste(range: TextRange?): Boolean = gateway.guarded(false) {
        val placed = range == null || select(range) || selectionIs(range)
        placed && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /**
     * The field's selection is [range] already. Views answer false to a set-selection that changes nothing, so a
     * refused selection is accepted when the field is where it should be.
     */
    private fun selectionIs(range: TextRange): Boolean {
        node.refresh()
        val empty = node.isShowingHintText || node.text.isNullOrEmpty()
        val at = if (empty) TextRange(0, 0) else gateway.selectionOf(node)
        return at == range
    }

    private fun select(range: TextRange): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, range.start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, range.end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    private companion object {
        /** `commitText` cursor position: right after the committed text. */
        const val NEW_CURSOR_AFTER_TEXT = 1
    }
}

/** Resolves a [FieldHandle] minted by the focus reader to the field's live node. */
internal class FieldResolver(
    private val registry: FieldHandleRegistry<AccessibilityNodeInfo>,
    private val gateway: AccessibilityGateway,
) {
    /** Access to [handle]'s field, or null when the handle was never minted or has been forgotten. */
    fun resolve(handle: FieldHandle): FieldAccess? = registry.resolve(handle)?.let { NodeFieldAccess(it, gateway) }
}
