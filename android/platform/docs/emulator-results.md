# `:platform` on the emulator: results

Device: `emulator-5562`, AVD `owk-a5`, Android 15 (API 35), Google APIs image
`google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug`, 1080 x 2400, Gboard, no audio hardware.
Test APK: `net.jbglenn.telltype.platform.test` (the library's androidTest APK, which carries the
library manifest, so it hosts the real `TelltypeAccessibilityService` and `MicService`). Target app:
`:testhost` (installed by the task dependency `connectedDebugAndroidTest -> :testhost:installDebug`).

Run: `./gradlew :platform:connectedDebugAndroidTest` with `ANDROID_SERIAL=emulator-5562`. The tests enable the
service with `settings put secure enabled_accessibility_services <test package>/<service>` through `UiAutomation`
obtained with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, and wait until the service is connected. Each row below
comes from a log line (tag `A5Result`) written by the test after its assertions passed.

Last full run (2026-11-17): `:platform:connectedDebugAndroidTest`, "Running tests on devices: owk-a5(AVD) - 15",
BUILD SUCCESSFUL in 5m 14s, 59 tests, 0 failures, 0 skipped: FocusTrackingTest 12, InsertionTest 36,
LadderStepsTest 5, ContextAndEditsTest 3, OverlayTest 2, MicServiceTest 1. Earlier full runs showed three flaky
failures (Compose focus, keyboard bounds mid-animation, a slow WebView page), each traced and fixed (see the last
section); the run above is the first one after those fixes, not a best-of-several.

## Focus tracking (FocusTrackingTest)

For each field type: open the screen, wait for the snapshot with the keyboard up, check kind, field bounds against
UI Automator's bounds (centre), keyboard below the field and reaching the bottom of the screen, hint, incognito flag;
then press Home and wait for null.

| Field type | Kind reported | Bubble offered (`acceptsDictation`) | Keyboard top | Hint | Leaving gives null |
|---|---|---|---|---|---|
| plain | SINGLE_LINE | yes | 1517 | - | yes |
| multi_line | MULTI_LINE | yes | 1517 | - | yes |
| search | SINGLE_LINE | yes | 1517 | "Search" | yes |
| password | PASSWORD | no | 1517 | - | yes |
| number | NUMERIC | no | 1633 (number pad) | - | yes |
| phone | NUMERIC | no | 1633 (phone pad) | - | yes |
| placeholder | SINGLE_LINE | yes | 1517 | "Type a message" | yes |
| prefilled | SINGLE_LINE | yes | 1517 | - | yes |
| incognito | SINGLE_LINE, incognito = true | yes | 1517 | - | yes |
| compose | MULTI_LINE (a Material 3 `TextField` without `singleLine`) | yes | 1517 | - | yes |
| web_input | SINGLE_LINE | yes | 1517 | "Web input" (the HTML placeholder) | yes |
| web_textarea | MULTI_LINE | yes | 1517 | "Web textarea" | yes |

## Insertion (InsertionTest, LadderStepsTest)

Full ladder, per field type. "Step" is `InsertReceipt.via`; every result was checked independently through UI
Automator (the field's text), and the receipt's range against the expected splice.

| Field type | At the cursor | Mid-text | Replaces a selection | Raw then Undo via `replace` |
|---|---|---|---|---|
| plain | input_connection | input_connection | input_connection | input_connection, input_connection |
| multi_line | input_connection | input_connection | input_connection | input_connection, input_connection |
| search | input_connection | input_connection | input_connection | input_connection, input_connection |
| placeholder | input_connection (empty field, cursor 0) | n/a (empty) | n/a (empty) | input_connection, input_connection |
| prefilled | input_connection (19..25) | input_connection | input_connection ("quick" -> "slow", app's own selection) | input_connection, input_connection |
| incognito | input_connection | input_connection | input_connection | input_connection, input_connection |
| compose | input_connection | input_connection | input_connection | input_connection, input_connection |
| web_input | input_connection | input_connection | input_connection | input_connection, input_connection |
| web_textarea | input_connection | input_connection | input_connection | input_connection, input_connection |

Each automatic step forced on its own (an inserter whose ladder holds only that step), "Hello" + " world" at the end:

| Field type | INPUT_CONNECTION | SET_TEXT | PASTE |
|---|---|---|---|
| plain | input_connection, verified | set_text, verified | paste, verified |
| compose | input_connection, verified | set_text, verified | paste, verified |
| web_input | input_connection, verified | set_text, verified | paste, verified |

Errors and the clipboard:

| Case | Result |
|---|---|
| password field | `FieldProtected`; the field keeps its 6 characters |
| field closed (activity finished) before the insert | `FieldGone` |
| user replaced the text before a `replace` (Raw/Undo) | `FieldChanged`; the user's text stays |
| paste into a field of the test APK's own process (which may read the clipboard while focused) | paste verified; a sensitive clip was on the clipboard during the paste; afterwards the user's previous clip ("earlier") is back, not flagged sensitive |
| paste into the test host (another app) | paste verified; the service process cannot read the clipboard in the background (`ClipboardService: Denying clipboard access to ... application is not in focus`), so the previous clip is unknown and the clipboard is cleared afterwards, as designed |

## Context and edits (ContextAndEditsTest)

| Case | Result |
|---|---|
| plain, compose, web_textarea: "Hello brave world", "brave" selected, limits 100/100 | before "Hello ", after " world", selected "brave" |
| same, limits 3/2 | before "lo ", after " w", selected "brave" |
| password field | `FieldContext.Empty` |
| edit watcher after an insertion ("Hello wrld"), user fixes it with the keyboard | 7 edits reported, last "Hello world"; text-changed events in the service's `eventTypes` only while the watch runs (checked before, during, after) |

## Overlay (OverlayTest)

| Case | Result |
|---|---|
| bubble shown over the plain field, docked right | accessibility-overlay window at (912, 1349)-(1059, 1496): above the keyboard (top 1517), not over the field (334..460), 21 px from the right edge |
| focus and keys | the keyboard stays up, the field keeps input focus, a key press (`KEYCODE_X`) reaches the field |
| touch outside the bubble | the test host's tap counter goes to 1 |
| system permission dialog (camera, from `PermissionDialogActivity`) | placement becomes null, no overlay window is listed, the focus snapshot is null |
| `hide()` | the overlay window is removed |

## Spike S2, emulator half (MicServiceTest and `S2ProbeReceiver`)

Question: can the accessibility service start `MicService` (foreground, type microphone) while no activity of the
app is in front and without "Display over other apps", and does `AudioRecord` then record without being silenced?

Two runs, both with the launcher in front (`topResumedActivity=...NexusLauncherActivity`), `canDrawOverlays = false`
(app op `SYSTEM_ALERT_WINDOW`: default, no operations), `RECORD_AUDIO` granted:

1. Instrumented (`MicServiceTest`). Caveat: the process also runs the instrumentation.
2. Not instrumented: the test APK reinstalled alone, the service enabled with `adb shell settings`, the process
   started by the system for the accessibility service only, the probe triggered with
   `adb shell am broadcast -n net.jbglenn.telltype.platform.test/net.jbglenn.telltype.platform.emulator.S2ProbeReceiver`
   (the receiver requires the DUMP permission, so only the shell can send it).

| Step | Instrumented | Not instrumented |
|---|---|---|
| process importance before | 125 (foreground service) | 125; `dumpsys activity processes`: curProcState 5, oom adj 100, `curCapability=LCMNFUA` (M = microphone) |
| `AudioRecord` before `MicService` | initialized, RECORDSTATE_RECORDING, `isClientSilenced` false, 32000 frames in 2 s, peak 8 | same |
| `MicService` start from the accessibility service | `Ok`; `dumpsys activity services`: `isForeground=true`, `types=0x00000080` (microphone), notification on channel `recording`, `vis=PRIVATE` | `Ok` ("microphone service start: Running (from accessibility: true)") |
| `AudioRecord` with `MicService` | RECORDSTATE_RECORDING, not silenced, 32000 frames, peak 8 | same |
| stop | Stopped | Stopped |

Findings on Android 15 (API 35), emulator:
- Starting the microphone foreground service from the accessibility service's context with no activity in front and
  without "Display over other apps" works (no `ForegroundServiceStartNotAllowedException`, no `SecurityException`).
- A process bound as an enabled accessibility service already holds the microphone capability (`M` in
  `curCapability`) and records unsilenced even before `MicService` starts. `MicService` is therefore not what makes
  recording possible here; it keeps the process eligible if the binding's rank drops and shows the recording
  notification, as planned.
- Not verified: real audio content (the emulator has no audio input: peak amplitude 8 of 32767, i.e. silence),
  physical devices, other Android versions (in particular 16 and 17), and the case where the app's main process is
  separate from the service.

## Behaviour found on the emulator and handled in the code

- `findFocus(FOCUS_INPUT)` answers from the service's node cache and can name a Compose host view, or a field of a
  window that has just closed; Compose hosts and WebViews can also answer for their whole tree. `FocusedFieldFinder`
  searches below a non-editable answer (at most 400 nodes) and clears the cache and asks once more when the answer
  is not a focused editable node in the focused window.
- WebView nodes report fewer input-type flags than their editor (`textarea` lacks the multi-line flag on the node),
  so the editor is matched to the node by package and input class only.
- An empty `EditText` reports no selection; it is treated as cursor 0.
- `ACTION_SET_SELECTION` answers false when the selection is already there; the paste step then checks the
  selection instead of giving up.
- While the keyboard slides in, its window reaches below the screen (bottom 3048 on 2400); keyboard bounds are cut
  to the screen.
- Since Android 14 the permission dialog's content is accessibility-data-sensitive: a service that is not an
  accessibility tool (decision D15) gets no root for that window. A focused application window with no readable root
  counts as a blocked screen.
- `ACTION_SET_TEXT` from outside sends no `TYPE_VIEW_TEXT_CHANGED` (only selection changes); keyboard edits do. The
  edit watcher therefore only sees edits typed by the user, which is what learning needs.
- Compose and WebView apply `ACTION_SET_SELECTION` a frame later; the tests wait for the selection to show.
