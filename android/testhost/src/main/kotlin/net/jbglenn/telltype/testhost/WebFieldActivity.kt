package net.jbglenn.telltype.testhost

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/**
 * A local page (no network) with an input and a textarea. The [HostExtras.FIELD_TYPE] extra picks which of the two
 * has `autofocus`; [HostExtras.INITIAL_TEXT] becomes that element's value. JavaScript stays off.
 */
class WebFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_field)
        bindHostControls()
        val webView = findViewById<WebView>(R.id.web_view)
        webView.settings.javaScriptEnabled = false
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccess = false
        webView.loadDataWithBaseURL(null, page(FieldType.of(intent)), "text/html", "utf-8", null)
        webView.requestFocus()
    }

    private fun page(type: FieldType): String {
        val value = escapeHtml(intent.getStringExtra(HostExtras.INITIAL_TEXT).orEmpty())
        val textarea = type == FieldType.WEB_TEXTAREA
        val template = assets.open(PAGE).bufferedReader().use { it.readText() }
        return template
            .replace("{{INPUT_VALUE}}", if (textarea) "" else value)
            .replace("{{TEXTAREA_VALUE}}", if (textarea) value else "")
            .replace("{{INPUT_AUTOFOCUS}}", if (textarea) "" else AUTOFOCUS)
            .replace("{{TEXTAREA_AUTOFOCUS}}", if (textarea) AUTOFOCUS else "")
    }

    /** The five characters HTML gives meaning to, as entities, so the value stays text. */
    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private companion object {
        const val PAGE = "fields.html"
        const val AUTOFOCUS = "autofocus"
    }
}
