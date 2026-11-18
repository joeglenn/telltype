# Privacy

Telltype is a dictation app. It records what you say, turns it into text on a server **you** choose, and types the
text into the app you are using. This page says exactly what leaves your phone, what stays on it, and for how long.
It describes the Telltype Android app and, where noted, Telltype Server.

## What leaves your phone

Telltype connects to one place only: the server address you enter in Setup (and, if you set one, the separate text
model address). It sends:

| What | When | To |
|---|---|---|
| The recording of your dictation | When you finish a dictation | Your server's speech endpoint (`/v1/audio/transcriptions`) |
| The transcript, the tone for the kind of app you are in, and up to 500 characters before and 200 after the cursor | When the transcript is cleaned up | Your server's chat endpoint (`/v1/chat/completions`) |
| The selected text and your spoken instruction | In Command Mode | Your server's chat endpoint |
| Dictionary terms | With a dictation, as hints for the models | Your server |
| Requests for the model list and health checks | During Setup, in Diagnostics and when checking the server | Your server |
| Your server key, if you set one | As `Authorization: Bearer` on each request | Your server |

Nothing else is sent anywhere. Telltype has **no analytics, no crash-reporting service, no advertising id and no
account**, and it does not contact the developer or any third party. You can turn off "Read text around the cursor"
in Settings; cleanup can be switched off too (then simple rules on the phone tidy the text and no transcript is
sent for cleanup).

**Connections.** HTTPS is used with a certificate your phone trusts, or with a self-signed certificate whose
fingerprint you confirm once in Setup; after that only that certificate is accepted at that address. Plain HTTP is
refused unless the address is on a private network (for example `192.168.x.x` or a `.local` name).

**On the server.** What your server does with the data is up to the server. Telltype Server decodes audio in
memory and never writes it to disk, and its log has one line per request that never contains audio, transcripts,
prompts, replies or keys (see [server-rs/README.md](server-rs/README.md)). If you use another server, check its own
logging.

## What is stored on your phone

Everything below is in Telltype's private app storage. Android backup is switched off for the app
(`android:allowBackup="false"`).

| Data | Kept for |
|---|---|
| **Recordings** | Deleted as soon as the text is typed, and when you cancel. A recording that could not be sent (server unreachable) stays queued until it is transcribed, then is deleted. A daily job removes any recording that no longer belongs to a queued dictation. |
| **History**: the final text, the raw transcript, the app it was typed into and the timings | **30 days** by default. You can choose another period or turn "Save history" off. A daily job deletes entries older than the period. Entries you **archive** (swipe right) are never deleted automatically; they stay until you delete them. "Clear history now" deletes entries at once (you choose whether archived ones go too). Deleted rows are overwritten in the database file (SQLite `secure_delete`). |
| **Settings**, the bubble's position, the dictionary, snippets, per-app tones and the exclusion list | Until you change or delete them |
| **Your server key** | Until you change it. It is encrypted with AES-256-GCM using a key that is created in the Android Keystore and never leaves it. |
| **The pinned certificate fingerprint** | Until you set up the server again |
| **The app's log** | A size-capped file (about 256 KB, plus one older file). It does not contain what you dictate, unless you switch on verbose debug logging, which switches itself off after one hour. |
| **Misrecognition reports** | Only if you use "Report misrecognition" on a History entry: that entry's raw transcript and final text are added to a report file on the phone. Deleting the entry removes them; clearing History deletes the file. |

Text around the cursor is held in memory for one dictation only and is never stored or logged.

## Sharing a debug bundle

Diagnostics can export a debug bundle (logs, timings and settings) for troubleshooting. It is created only when you
tap Export, and goes only where you choose to share it. It never contains your server key. If you made
misrecognition reports, their text is included, and if verbose debug logging was on, the log may contain dictated
text: look through the bundle before you share it.

## Permissions

| Permission | Why |
|---|---|
| Microphone (`RECORD_AUDIO`, foreground service "microphone") | To record while you dictate. Android shows its microphone indicator while Telltype records. |
| Accessibility service | To see which text field has focus, show the bubble, read the text around the cursor and type the result. |
| Notifications | For the recording notification and for queued dictations. |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`, local network) | To reach your server. |
| Vibration | For haptic feedback on start, stop and problems (can be switched off). |

## Questions

Open an issue at <https://github.com/joeglenn/telltype/issues>. For security problems, see [SECURITY.md](SECURITY.md).
