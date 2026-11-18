package net.jbglenn.telltype.testhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * A Compose Material 3 `TextField` under test (`field`), plus the shared controls. Test tags are exposed as
 * resource ids, so UI Automator finds them like the View ids.
 */
class ComposeFieldActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(HostExtras.INITIAL_TEXT).orEmpty()
        val selection = requestedSelection()?.let { TextRange(it.first, it.last) } ?: TextRange(text.length)
        setContent {
            MaterialTheme {
                ComposeFieldScreen(initial = TextFieldValue(text, selection), onClose = ::finish)
            }
        }
    }
}

/** The field under test, a second field, the tap counter and the close button. */
@Composable
internal fun ComposeFieldScreen(initial: TextFieldValue, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var field by remember { mutableStateOf(initial) }
    var other by remember { mutableStateOf(TextFieldValue()) }
    var taps by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = field,
            onValueChange = { field = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("field"),
            label = { Text(stringResource(R.string.field_label)) },
        )
        TextField(
            value = other,
            onValueChange = { other = it },
            modifier = Modifier.fillMaxWidth().testTag("other_field"),
            label = { Text(stringResource(R.string.other_field_label)) },
        )
        Button(onClick = { taps++ }, modifier = Modifier.fillMaxWidth().testTag("tap_target")) {
            Text(stringResource(R.string.tap_target_count, taps))
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("close")) {
            Text(stringResource(R.string.close))
        }
    }
}
