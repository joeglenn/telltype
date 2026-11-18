package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of every shared component in light, dark and at font scale 2.0. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ComponentScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light", dark = false, fontScale = 1f)

    @Test
    fun dark() = capture("dark", dark = true, fontScale = 1f)

    @Test
    fun largeFont() = capture("font2", dark = false, fontScale = 2f)

    private fun capture(look: String, dark: Boolean, fontScale: Float) {
        val content = Samples.getValue(name)
        compose.setContent {
            TelltypePreviewTheme(darkTheme = dark, fontScale = fontScale) { content() }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/component/${name}_$look.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = Samples.keys.map { arrayOf(it) }
    }
}

private val Samples: Map<String, @Composable () -> Unit> = linkedMapOf(
    "statusCard" to {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = Modifier.padding(Spacing.Large)) {
            StatusCard(title = "Ready to dictate", tone = StatusTone.GOOD, body = "Your server answered in 42 ms.")
            StatusCard(
                title = "The server is offline",
                tone = StatusTone.ATTENTION,
                body = "Recordings are kept and sent later.",
            )
            StatusCard(title = "Two things need attention", tone = StatusTone.PROBLEM) {
                StatusProblemRow(message = "Accessibility is switched off", actionLabel = "Fix", onAction = {})
                StatusProblemRow(message = "Microphone permission is missing", actionLabel = "Fix", onAction = {})
            }
        }
    },
    "statusCardPaused" to {
        Column(modifier = Modifier.padding(Spacing.Large)) {
            StatusCard(title = "Bubble hidden until 10:45", tone = StatusTone.PAUSED, body = "It comes back then.") {
                FilledTonalButton(onClick = {}) { Text(text = "Resume") }
            }
        }
    },
    "sectionHeader" to {
        Column(modifier = Modifier.padding(horizontal = Spacing.Large)) {
            SectionHeader(text = "Bubble")
            Text(text = "Rows follow the header.")
        }
    },
    "settingsRows" to {
        Column {
            SwitchRow(title = "Show the bubble", checked = true, onCheckedChange = {
            }, supporting = "Above the keyboard")
            SwitchRow(title = "Sound cues", checked = false, onCheckedChange = {}, icon = TelltypeIcons.Hearing)
            LinkRow(title = "Styles", onClick = {}, supporting = "Tone per kind of app", icon = TelltypeIcons.Palette)
            InfoRow(title = "Version", value = "0.1.0")
            ChoiceRow(
                title = "Keep history",
                options = ChoiceOptions(listOf("Off", "7 days", "30 days", "Forever")),
                selectedIndex = 2,
                expanded = true,
                onExpandedChange = {},
                onSelect = {},
            )
        }
    },
    "listItems" to {
        Column {
            ContentListItem(headline = "Lunch at noon tomorrow?", supporting = "09:12 · Personal chat", onClick = {})
            ContentListItem(
                headline = "A long dictated paragraph that wraps onto a second line and is then cut off neatly",
                overline = "Yesterday",
                supporting = "Cleanup skipped",
                icon = TelltypeIcons.History,
                selected = true,
                onClick = {},
            )
        }
    },
    "chips" to {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier.padding(Spacing.Large),
        ) {
            ActionChip(label = "Copy", onClick = {}, icon = TelltypeIcons.Copy)
            SelectableChip(label = "Formal", selected = true, onClick = {})
            Tag(label = "Learned")
        }
    },
    "emptyState" to {
        EmptyState(
            icon = TelltypeIcons.History,
            title = "No dictations yet",
            body = "Each dictation is kept here with its raw transcript and timings.",
            actionLabel = "Open settings",
            onAction = {},
        )
    },
    "errorBanner" to {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = Modifier.padding(Spacing.Large)) {
            ErrorBanner(message = "Couldn't save. Try again.")
            ErrorBanner(message = "The server didn't answer.", actionLabel = "Retry", onAction = {}, onDismiss = {})
        }
    },
    "confirmSheet" to {
        ConfirmSheetContent(
            title = "Delete “Oslo”?",
            body = "It will no longer be suggested to the speech model.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    },
    "loadingState" to { LoadingState() },
    "addButton" to { AddButton(label = "Add word", onClick = {}, modifier = Modifier.padding(Spacing.Large)) },
    "topBar" to { TopBar(title = "Diagnostics", onNavigateUp = {}) },
)
