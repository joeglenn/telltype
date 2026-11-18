package net.jbglenn.telltype.platform.emulator

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import net.jbglenn.telltype.core.model.field.FieldKind

/**
 * The test host's field screens (mirrors `FieldType` in `:testhost`, which this module may not depend on).
 *
 * @property id the `field_type` extra.
 * @property screen the activity class.
 * @property kind the kind the focus tracker must report.
 * @property resource the field's resource name (`field`); web fields have none (the WebView does not expose HTML
 *   ids), so they are found as the n-th editable node inside the WebView, see [webIndex].
 * @property compose the resource name is a Compose test tag (no package prefix).
 * @property webIndex for a web field, its position among the page's editable nodes; -1 otherwise.
 */
enum class HostField(
    val id: String,
    val screen: String,
    val kind: FieldKind,
    val resource: String = "field",
    val compose: Boolean = false,
    val webIndex: Int = -1,
) {
    PLAIN("plain", VIEW, FieldKind.SINGLE_LINE),
    MULTI_LINE("multi_line", VIEW, FieldKind.MULTI_LINE),
    SEARCH("search", VIEW, FieldKind.SINGLE_LINE),
    PASSWORD("password", VIEW, FieldKind.PASSWORD),
    NUMBER("number", VIEW, FieldKind.NUMERIC),
    PHONE("phone", VIEW, FieldKind.NUMERIC),
    PLACEHOLDER("placeholder", VIEW, FieldKind.SINGLE_LINE),
    PREFILLED("prefilled", VIEW, FieldKind.SINGLE_LINE),
    INCOGNITO("incognito", VIEW, FieldKind.SINGLE_LINE),

    // A Material 3 TextField without singleLine is multi-line (its editor asks for TYPE_TEXT_FLAG_MULTI_LINE).
    COMPOSE("compose", "$PACKAGE.ComposeFieldActivity", FieldKind.MULTI_LINE, compose = true),
    WEB_INPUT("web_input", "$PACKAGE.WebFieldActivity", FieldKind.SINGLE_LINE, webIndex = 0),
    WEB_TEXTAREA("web_textarea", "$PACKAGE.WebFieldActivity", FieldKind.MULTI_LINE, webIndex = 1),
    ;

    /** The field is in the WebView page. */
    val web: Boolean get() = webIndex >= 0

    /** How UI Automator finds the field (for web fields: every editable node of the page). */
    internal fun selector(): BySelector = when {
        web -> By.clazz("android.widget.EditText").hasAncestor(By.clazz("android.webkit.WebView"))
        compose -> By.res(resource)
        else -> By.res(Emulator.HOST, resource)
    }

    /** The field on screen now, or null. */
    internal fun find(): UiObject2? = Emulator.device.findObjects(selector()).getOrNull(maxOf(webIndex, 0))

    companion object {
        /** The types the bubble may be offered for. */
        internal val dictatable: List<HostField> = entries.filter { it.kind.acceptsDictation }
    }
}

private const val PACKAGE = "net.jbglenn.telltype.testhost"
private const val VIEW = "$PACKAGE.ViewFieldActivity"
