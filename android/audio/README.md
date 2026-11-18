# `:audio`: microphone capture and recording files

Implements the `AudioCapture` and `RecordingStore` ports (`android/docs/contracts.md` page 4; plan sections 7.3,
8.1, 9, 10). Bound in `di/AudioModule.kt`; the graph must also provide `DispatcherProvider`, `Logger`,
`IdGenerator` and `SettingsRepository`. No `Service` lives here: the foreground microphone service is `:platform`'s
and simply drives `AudioCapture`.

| Package | What |
|---|---|
| `capture` | `AudioRecorder` (the port), `CaptureRun` (one recording's loop and verdict), `PcmSource` (the seam `AudioRecord` sits behind), `AudioRecordSourceFactory`, `CaptureFiles`, `CaptureDevices` |
| `wav` | `WavHeader`, `WavFileWriter` (streaming, crash-safe), `WavRepair`, `WavSamples` |
| `analysis` | `LevelMeter` (waveform), `SpeechDetector` (speech presence), `ZeroInputWatch` (microphone in use) |
| `store` | `FileRecordingStore` (the port), `RecordingFiles` (names and location), `StorageSpace` |
| `platform` | Audio focus, screen-off and call monitor, permission check: interfaces and their Android versions |

## Behaviour

- **Capture.** `AudioRecord`, voice-recognition source, 16 kHz mono 16-bit, internal buffer 0.5 s. The blocking read
  loop runs only on `dispatchers.audio`, 20 ms per read, into one reused buffer; each buffer is written to the file
  at once and measured in place (no allocation per buffer). `start` returns after the first buffer is on disk
  (69 ms on the emulator) or fails within 2 s (`START_TIMEOUT`).
- **Files.** `noBackupFilesDir/recordings/<recording>.<session>.wav` (app-private, never backed up). Ids are
  restricted to `[A-Za-z0-9_-]{1,64}`, so no id can name a path elsewhere. Every file starts with a 44-byte header;
  the sizes are written on stop, and `WavRepair` rebuilds them from the file length after a crash
  (`RecordingStore.finalise`, used for orphans). Data is forced to storage every 5 s of audio.
- **Errors.** Permission missing, recorder cannot initialise, microphone held elsewhere (start refused or all-zero
  first half second), no speech, storage full and other file failures are the matching `CaptureError` /
  `StorageError`. A failed or cancelled `start` and `cancel` delete the file; a second `start` while recording is
  `RecorderFailed` and leaves the running recording alone.
- **Interruptions** (on `interruptions`, first one per recording only): screen off, call (audio mode ringing /
  in call / in communication), audio focus lost (only when "Pause other audio while recording" took focus),
  microphone lost (client silenced, read failure, or one second of exact zeros after real input), recording
  failed (write error). Capture stops at once and the microphone, focus and watchers are released; `stop` then
  returns what was captured. If nothing but zeros was ever captured, `stop` reports `MicrophoneInUse`.
- **Storage full while writing.** The partial file is returned as a clip if it holds speech; otherwise `StorageFull`
  and the file is deleted (`StorageFailed` keeps the file, as its `keepsAudio` says). On `create`, below 16 MB free
  the oldest recordings are deleted first; below 1 MB after that, `StorageFull`.
- **Levels.** `LevelMeter` publishes RMS / full scale per buffer in one volatile word; `levels` samples it at most
  60 times a second (17 ms ticks) and only emits a new buffer's level, so it is conflated by construction. Silent
  while idle.

## Speech presence (initial thresholds, to tune on the phone)

20 ms frames, energy after removing the frame mean (a DC offset is not sound), in dBFS of full scale 32768. Android's
CDD calibrates the voice-recognition source to about -22 dBFS for 90 dB SPL, so dBFS is roughly dB SPL - 112.

| Constant | Value | Reasoning |
|---|---|---|
| `SPEECH_FRAME_DBFS` | -50 dBFS (~62 dB SPL) | below voiced frames of normal speech at 10 to 30 cm (65 to 80 dB SPL); a quiet room is 15 dB lower |
| `SIGNAL_FRAME_DBFS` | -65 dBFS (~47 dB SPL) | above a quiet room; whisper or speech across the room |
| `MIN_SPEECH_FRAMES` | 8 (150 ms) | shorter than the voiced part of "yes", longer than a click or a tap |
| `MIN_SIGNAL_FRAMES` | 8 (150 ms) | |

CLEAR: 150 ms of speech frames. FAINT (uploaded, text dropped): not clear, but 150 ms of sound frames. Otherwise
`NoSpeech` (nothing uploaded, file deleted). A recovered orphan with no sound is FAINT, because
`RecordingStore.finalise` can only return a clip. The emulator's input (no microphone hardware: low noise, peak
|sample| 8, 13% exact zeros) is classified as `NoSpeech`.

## Tests

- JVM (`src/test`, scripted `PcmSource` on virtual time): WAV header and length, repair, store, level meter,
  speech presence, zero detection timing, start timeout, double start, focus on every exit path, interruptions,
  storage full, and a 6-minute stream through one buffer.
- Device (`src/androidTest`): `./gradlew :audio:connectedDebugAndroidTest` with `ANDROID_SERIAL` set. Records two
  seconds with the real `AudioRecord`, checks the WAV, the recorder's verdict on the emulator's silence, and that
  cancel leaves no file.
