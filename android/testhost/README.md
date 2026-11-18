# Test host app (`:testhost`)

The target app for the instrumented tests of `:platform` (docs/PLAN.md section 14.2). One screen per text-field
type, all exported, no network, no permissions used (the camera permission is declared only so that
`PermissionDialogActivity` can bring up a system permission dialog). Package
`net.jbglenn.telltype.testhost`. Build and install: `./gradlew :testhost:installDebug` (the connected tests of
`:platform` install it themselves).

## Screens

| `field_type` | Activity | Field |
|---|---|---|
| `plain` | `ViewFieldActivity` | single-line `EditText` |
| `multi_line` | `ViewFieldActivity` | multi-line `EditText` |
| `search` | `ViewFieldActivity` | single line, search action, hint "Search" |
| `password` | `ViewFieldActivity` | text password |
| `number` | `ViewFieldActivity` | number |
| `phone` | `ViewFieldActivity` | phone |
| `placeholder` | `ViewFieldActivity` | empty, hint "Type a message" |
| `prefilled` | `ViewFieldActivity` | "The quick brown fox", "quick" selected |
| `incognito` | `ViewFieldActivity` | `IME_FLAG_NO_PERSONALIZED_LEARNING` |
| `compose` | `ComposeFieldActivity` | Material 3 `TextField` (multi-line), test tag `field` |
| `web_input` | `WebFieldActivity` | `<input type="text">` in `assets/fields.html`, focused at start |
| `web_textarea` | `WebFieldActivity` | `<textarea>` in the same page, focused at start |
| `chat` | `ChatFieldActivity` | a chat app's message field (`field`) at the bottom of the screen with a send button (`send`) beside it, outside the field; focused at start |

Other screens: `TestHostActivity` (launcher, one button per type) and `PermissionDialogActivity` (asks for the
camera permission at start).

The WebView runs with JavaScript, file access and content access off; the page is filled in from the extras before
loading (values HTML-escaped). WebView fields expose no resource ids to accessibility, so tests find them as the
first (`input`) and second (`textarea`) `EditText` inside the `WebView`.

## Intent extras

| Extra | Type | Meaning |
|---|---|---|
| `field_type` | string | which screen content to show (default `plain`) |
| `initial_text` | string | text in the field at start, instead of the type's default |
| `selection_start`, `selection_end` | int | selection at start (view and Compose screens) |

Every field screen focuses its field at start (keyboard up).

## Resource ids (UI Automator)

View screens: `field`, `field_label`, `other_field` (a second plain field), `tap_target` (a button counting taps,
"Taps: N"; shown in capitals by the theme), `close` (finishes the activity). The web screen has `web_view`,
`other_field`, `tap_target` and `close`; the Compose screen uses test tags exposed as resource ids
(`testTagsAsResourceId`): `field`, `other_field`, `tap_target` and `close`.
