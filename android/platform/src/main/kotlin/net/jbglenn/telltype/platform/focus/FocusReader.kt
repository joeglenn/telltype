package net.jbglenn.telltype.platform.focus

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.AccessibilityGateway
import net.jbglenn.telltype.platform.field.FieldHandleRegistry
import net.jbglenn.telltype.platform.field.toScreenRect

/**
 * What the focus reader saw once.
 *
 * @property snapshot the focused editable field, or null.
 * @property blocked the screen is locked or asleep, or a permission dialog is in front: no field and no bubble.
 */
internal data class FocusReading(val snapshot: FieldSnapshot?, val blocked: Boolean) {
    companion object {
        /** No field, nothing blocking (also: the service is not connected). */
        val Nothing: FocusReading = FocusReading(snapshot = null, blocked = false)

        /** No field because the screen must not offer one. */
        val Blocked: FocusReading = FocusReading(snapshot = null, blocked = true)
    }
}

/**
 * Reads the focused field into a [FieldSnapshot] (docs/PLAN.md section 7.1), minting its handle. Blocking: run on
 * the accessibility thread only.
 */
internal class FocusReader(
    private val gateway: AccessibilityGateway,
    private val registry: FieldHandleRegistry<AccessibilityNodeInfo>,
) {
    /** The current reading; [FocusReading.Nothing] without a service or when a call failed. */
    fun read(): FocusReading = gateway.withService(FocusReading.Nothing) { service ->
        if (gateway.screenBlocked(service)) {
            FocusReading.Blocked
        } else {
            gateway.focusedEditable(service)?.let { FocusReading(snapshot(service, it), blocked = false) }
                ?: FocusReading.Nothing
        }
    }

    /** Whether an input-method window is on the screen now; false without a service or when a call failed. */
    fun keyboardShown(): Boolean = gateway.withService(false) { gateway.keyboardBounds(it) != null }

    private fun snapshot(service: AccessibilityService, node: AccessibilityNodeInfo): FieldSnapshot {
        val description = gateway.describe(service, node)
        val target = FieldTarget(
            handle = registry.handleFor(node),
            app = AppPackage(node.packageName?.toString().orEmpty()),
            kind = description.kind,
            hint = description.hint,
            incognito = description.incognito,
        )
        return FieldSnapshot(
            target = target,
            fieldBounds = Rect().also(node::getBoundsInScreen).toScreenRect(),
            keyboardBounds = gateway.keyboardBounds(service),
            selection = if (node.isShowingHintText || node.text?.isEmpty() == true) {
                TextRange(0, 0)
            } else {
                gateway.selectionOf(node)
            },
        )
    }
}
