package net.jbglenn.telltype.ui.screens.setup

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Board B8: while the server form shows, autofill is off for the whole view; afterwards it is as before. */
@RunWith(AndroidJUnit4::class)
class NoAutofillTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHostingViewIsExcludedOnlyWhileTheFormShows() {
        var shown by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (shown) NoAutofill()
        }
        compose.waitForIdle()
        val host = view.parent as View
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, host.importantForAutofill)
        assertFalse("the Compose view is excluded with its parent", view.isImportantForAutofill)

        shown = false
        compose.waitForIdle()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, host.importantForAutofill)
    }
}
