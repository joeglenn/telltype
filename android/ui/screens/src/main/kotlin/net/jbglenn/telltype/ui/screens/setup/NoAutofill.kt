package net.jbglenn.telltype.ui.screens.setup

import android.view.View
import android.view.autofill.AutofillManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps autofill services away from Setup's server form (board B8): while it is shown the view holding the Compose
 * hierarchy (the parent of `LocalView`, whose own flag Compose's autofill sets to "yes") and all of its descendants
 * are not important for autofill, so no service fills the key or offers to save it as a password;
 * when the form leaves (Next, Back, the screen closes) the view's previous setting comes back and the autofill
 * session is cancelled, so nothing typed into it is offered for saving afterwards.
 */
@Composable
internal fun NoAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val host = view.parent as? View ?: view
        val previous = host.importantForAutofill
        host.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            view.context.getSystemService(AutofillManager::class.java)?.cancel()
            host.importantForAutofill = previous
        }
    }
}
