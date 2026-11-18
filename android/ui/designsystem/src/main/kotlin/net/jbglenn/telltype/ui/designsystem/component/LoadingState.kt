package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.R
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/** Shown while a screen's data is on its way. The label is announced. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = stringResource(R.string.ds_loading)) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Large),
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.Huge)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator()
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview
@Composable
private fun LoadingStatePreview() {
    TelltypePreviewTheme { LoadingState() }
}
