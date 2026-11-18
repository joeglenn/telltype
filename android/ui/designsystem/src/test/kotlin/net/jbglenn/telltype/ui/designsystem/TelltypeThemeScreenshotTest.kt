package net.jbglenn.telltype.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of the fallback palette in light and dark; `check` fails if rendering changes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h240dp-xhdpi")
class TelltypeThemeScreenshotTest {
    @Test
    fun fallbackLight() {
        captureRoboImage("src/test/screenshots/TelltypeTheme_fallbackLight.png") {
            ThemeSample(darkTheme = false)
        }
    }

    @Test
    fun fallbackDark() {
        captureRoboImage("src/test/screenshots/TelltypeTheme_fallbackDark.png") {
            ThemeSample(darkTheme = true)
        }
    }
}

@Composable
private fun ThemeSample(darkTheme: Boolean) {
    TelltypeTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Telltype", style = MaterialTheme.typography.headlineSmall)
                Text(text = "Fallback palette", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {}) { Text(text = "Primary") }
            }
        }
    }
}
