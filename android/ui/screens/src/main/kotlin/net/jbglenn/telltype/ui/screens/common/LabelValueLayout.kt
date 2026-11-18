package net.jbglenn.telltype.ui.screens.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * A label and its value side by side (label at the start, value at the end, centred vertically) when both fit on
 * one line each; otherwise, for long values such as addresses and model names, the value goes under the label and
 * gets the full width, so neither wraps in a narrow half-column. From font scale 1.5 every pair is stacked, so a
 * list of facts keeps one shape instead of alternating between the two.
 *
 * @param gap the space between label and value side by side.
 * @param stackedGap the space between label and value when stacked.
 */
@Composable
internal fun LabelValueLayout(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    gap: Dp,
    stackedGap: Dp,
    modifier: Modifier = Modifier,
) {
    val alwaysStacked = LocalDensity.current.fontScale >= LabelValueDefaults.STACKED_FONT_SCALE
    Layout(
        contents = listOf(label, value),
        modifier = modifier,
    ) { (labels, values), constraints ->
        val labelMeasurable = labels.single()
        val valueMeasurable = values.single()
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val fits = !constraints.hasBoundedWidth || labelWidth + gap.roundToPx() + valueWidth <= constraints.maxWidth
        if (alwaysStacked || !fits) {
            stacked(labelMeasurable, valueMeasurable, stackedGap.roundToPx(), constraints)
        } else {
            sideBySide(labelMeasurable, valueMeasurable, labelWidth, valueWidth, constraints)
        }
    }
}

private fun MeasureScope.sideBySide(
    label: Measurable,
    value: Measurable,
    labelWidth: Int,
    valueWidth: Int,
    constraints: Constraints,
): MeasureResult {
    val labelPlaceable = label.measure(Constraints(maxWidth = labelWidth))
    val valuePlaceable = value.measure(Constraints(maxWidth = valueWidth))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelWidth + valueWidth
    val height = maxOf(labelPlaceable.height, valuePlaceable.height)
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    return layout(width, height) {
        labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
        valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
    }
}

private fun MeasureScope.stacked(
    label: Measurable,
    value: Measurable,
    gap: Int,
    constraints: Constraints,
): MeasureResult {
    val full = Constraints(maxWidth = constraints.maxWidth)
    val labelPlaceable = label.measure(full)
    val valuePlaceable = value.measure(full)
    val content = labelPlaceable.height + gap + valuePlaceable.height
    val height = content.coerceIn(constraints.minHeight, constraints.maxHeight)
    val top = (height - content) / 2
    return layout(constraints.maxWidth, height) {
        labelPlaceable.placeRelative(0, top)
        valuePlaceable.placeRelative(0, top + labelPlaceable.height + gap)
    }
}

/** Fixed values of [LabelValueLayout]. */
private object LabelValueDefaults {
    /** The font scale from which label and value are always stacked. */
    const val STACKED_FONT_SCALE = 1.5f
}
