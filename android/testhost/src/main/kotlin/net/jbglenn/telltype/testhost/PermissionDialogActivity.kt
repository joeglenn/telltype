package net.jbglenn.telltype.testhost

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Asks for the camera permission as soon as it starts, so the system permission dialog comes up over a field-less
 * screen. The :platform tests check that the bubble and the focused field disappear behind that dialog.
 */
class PermissionDialogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        label.setText(R.string.permission_dialog)
        setContentView(label)
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST)
    }

    private companion object {
        const val REQUEST = 1
    }
}
