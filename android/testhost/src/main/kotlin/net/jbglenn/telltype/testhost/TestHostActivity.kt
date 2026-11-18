package net.jbglenn.telltype.testhost

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

/** The launcher: the title, then one button per field screen, for trying the bubble by hand. */
class TestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_host)
        val list = findViewById<LinearLayout>(R.id.fields)
        FieldType.entries.forEach { type ->
            list.addView(
                Button(this).apply {
                    text = type.id
                    setOnClickListener {
                        startActivity(
                            Intent(this@TestHostActivity, type.screen).putExtra(HostExtras.FIELD_TYPE, type.id),
                        )
                    }
                },
            )
        }
    }
}
