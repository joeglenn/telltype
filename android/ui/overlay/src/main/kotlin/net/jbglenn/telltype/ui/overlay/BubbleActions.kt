package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.session.BubbleAction

/**
 * The chips of one state, in order.
 *
 * @property items the actions.
 */
@Immutable
internal data class BubbleActions(val items: List<BubbleAction>)
