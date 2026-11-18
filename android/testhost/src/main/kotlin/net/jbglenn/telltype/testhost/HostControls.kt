package net.jbglenn.telltype.testhost

import android.app.Activity
import android.widget.Button

/** Wires the controls every View-based screen shares: the tap counter and the close button. */
internal fun Activity.bindHostControls() {
    var taps = 0
    val tapTarget = findViewById<Button>(R.id.tap_target)
    tapTarget.setOnClickListener {
        taps++
        tapTarget.text = getString(R.string.tap_target_count, taps)
    }
    findViewById<Button>(R.id.close).setOnClickListener { finish() }
}

/** The selection asked for by the intent extras, or null when the intent names none. */
internal fun Activity.requestedSelection(): IntRange? {
    val start = intent.getIntExtra(HostExtras.SELECTION_START, -1)
    val end = intent.getIntExtra(HostExtras.SELECTION_END, -1)
    return if (start >= 0 && end >= start) start..end else null
}
