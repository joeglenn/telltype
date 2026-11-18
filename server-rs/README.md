# Telltype Server

One program, `telltype-server`, that gives the Telltype dictation app everything it needs from a
server: speech to text (Parakeet through sherpa-onnx, on the CPU) and the text models for cleanup
and Command Mode (served by `llama-server`, which Telltype Server can start and watch for you). It
speaks the OpenAI API (`/v1/audio/transcriptions`, `/v1/chat/completions`, `/v1/models`), so the app
also works with any other OpenAI-compatible server, and other OpenAI clients work with this one.

- HTTPS with your own certificate or a self-signed one generated on first start (clients pin it),
  plain HTTP only when you ask for it.
- An optional bearer key, compared in constant time.
- Audio is decoded in memory (WAV, Ogg/WebM Opus, FLAC, MP3, AAC/M4A, Vorbis) and never written to
  disk: no temporary files, no ffmpeg.
- `GET /healthz` says what is ready; requests get `Server-Timing` with stage timings; one JSON log
  line per request that never contains audio, transcripts, prompts, replies or keys.
- Graceful shutdown: running requests finish, supervised `llama-server` processes are stopped.

The HTTP contract (routes, fields, errors, health) is [`docs/contract.md`](docs/contract.md).

## Contents

1. [Install](#install)
2. [Configure](#configure)
3. [Run as a systemd user unit](#run-as-a-systemd-user-unit)
4. [Check it](#check-it)
5. [Configuration reference](#configuration-reference)
6. [How it works](#how-it-works)
7. [Development](#development)
8. [Troubleshooting](#troubleshooting)

## Install

Needs Linux on x86_64 (tested; sherpa-onnx also publishes aarch64 libraries, untested), Rust 1.94 or newer (<https://rustup.rs>), `curl`, and for the text
models a `llama-server` binary from [llama.cpp](https://github.com/ggml-org/llama.cpp) (any build:
CPU, Vulkan, CUDA, ROCm, Metal). No root is needed anywhere.

```sh
cd server-rs
TELLTYPE_MODEL_DIR=~/models/telltype scripts/download_models.sh   # once; verifies SHA-256
scripts/install.sh                                                # builds, installs, first run writes the config
$EDITOR ~/.config/telltype/server.toml                            # paths to the models and llama-server
scripts/install.sh                                                # credentials, unit, start
```

`install.sh` builds the release binary (`cargo build --release --locked`; the first build downloads
sherpa-onnx's prebuilt static library, about 23 MB), installs it as `~/.local/bin/telltype-server`,
copies [`examples/server.toml`](examples/server.toml) to `~/.config/telltype/server.toml` on the
first run, then on later runs validates the configuration, creates the key and certificate if
missing, installs the unit and starts it (or restarts it when the binary or unit changed). It refuses
to touch a port that something else holds. `PREFIX`, `TELLTYPE_CONFIG_DIR` and `UNIT_DIR` change
the locations; `--no-start` stops before starting.

`download_models.sh` fetches Parakeet Unified EN 0.6B int8 (sherpa-onnx export, 501 MB), Gemma 4
E4B QAT q4_0 (cleanup, 5.2 GB) and Qwen3.5 4B Q4_K_M (Command Mode, 3.0 GB), resumes partial
downloads and checks every file against the publisher's SHA-256 (the hashes are in the script).

## Configure

One TOML file. Every key has a default except the model paths; unknown keys are errors, and every
error names its key:

```text
$ telltype-server check-config --config server.toml
telltype-server: configuration: text.cleanup.url: must be an http:// URL (upstreams normally run on this machine; https is not supported)
```

Start from [`examples/server.toml`](examples/server.toml). [`examples/home-server.toml`](examples/home-server.toml)
is a real machine's configuration, kept as an example of a complete setup (its paths are that
machine's). Relative paths are relative to the configuration file's directory; `~/` means `$HOME`
(also in `supervise.args`).

The configuration file is passed with `--config <file>` or `TELLTYPE_CONFIG`. Commands:

| Command | What |
|---|---|
| `telltype-server serve --config F` | Run (the default command) |
| `telltype-server check-config --config F` | Validate and exit; exit code 2 on an error |
| `telltype-server credentials --config F [--rotate-key] [--rotate-cert]` | Create the key (32 random bytes, URL-safe base64, mode 0600) and, in `self-signed` mode, the certificate (ECDSA P-256, ten years) if missing; or replace them. Prints the certificate's fingerprints, never the key |
| `telltype-server fingerprint --config F` | Print the certificate's SHA-256 and its SPKI pin (`sha256/…`, the form the app and OkHttp pin) |

After `--rotate-cert` every client must pin the new certificate; after `--rotate-key` every client
needs the new key. Restart the server after either.

## Run as a systemd user unit

[`systemd/telltype-server.service`](systemd/telltype-server.service) is the unit `install.sh`
installs (with your paths filled in). By hand:

```sh
install -m 0644 systemd/telltype-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now telltype-server
loginctl enable-linger "$USER"          # keep it running without a login session
journalctl --user -u telltype-server -f # one JSON object per line
systemctl --user restart telltype-server
```

The unit restarts the server on failure, except after exit code 2 (a configuration or credential
error, which a restart cannot fix). When `supervise` is configured the server starts its
`llama-server` processes itself, so this is the only unit you need.

## Check it

```sh
TELLTYPE_CONFIG_DIR=~/.config/telltype scripts/smoke.sh https://127.0.0.1:8170
```

checks 401 without the key, `/healthz` "ok", `/v1/models`, a transcription of the app's sample clip
and one cleanup request, over HTTPS against the server's own certificate. Real output (a test
instance on port 18170, Parakeet on the CPU, Qwen3.5 2B through a supervised `llama-server` in router mode;
recorded with 0.2.0, which still listed the `cleanup` and `assistant` aliases that 1.0.0 no longer lists):

```text
ok   401 without key
ok   healthz ok (stt=True, cleanup=True, assistant=True)
ok   models: parakeet-unified-en-0.6b-int8 qwen_qwen3.5-2b-q4_k_m cleanup assistant
ok   transcription 179 ms, duration 7.435 s, Server-Timing: decode;dur=0.6, queue;dur=0.0, stt;dur=167.9, gate;dur=168.8
ok   cleanup 143 ms, Server-Timing: llm;dur=133.2, gate;dur=133.3
PASS https://127.0.0.1:18170
```

Start-up with that configuration: the port answers (503 `not_ready`) after 66 ms, the recogniser is
loaded and `/healthz` is 200 after 1.2 s. Peak resident memory of `telltype-server` with Parakeet
loaded, after transcriptions: 980 MB (`/usr/bin/time -v`); the model weights of `llama-server` live
in GPU memory.

## Configuration reference

Durations are seconds (decimals allowed, above 0, at most 86400). Sizes are bytes.

### `[server]`

| Key | Default | Meaning |
|---|---|---|
| `listen` | `"0.0.0.0:8170"` | Address and port to listen on |
| `max_upload_bytes` | `26214400` (25 MiB) | Largest audio `file` part. Larger: 413 `too_large` |
| `multipart_overhead_bytes` | `1048576` | Allowance for the rest of a transcription upload (fields, boundaries) |
| `max_json_bytes` | `1048576` | Largest chat request body. Larger: 413 `too_large` |
| `retry_after_s` | `5` | `Retry-After` on 503 `not_ready` (whole seconds, at least 1) |
| `health_timeout_s` | `2` | Time allowed for each upstream health probe and model list in `/healthz` |
| `shutdown_grace_s` | `10` | On SIGTERM/SIGINT: time running requests get to finish |

### `[tls]`

| Key | Default | Meaning |
|---|---|---|
| `mode` | `"self-signed"` | `self-signed`: use `cert_file`/`key_file`, generating both on first start when neither exists. `files`: both must exist. `off`: plain HTTP |
| `cert_file`, `key_file` | `"tls/cert.pem"`, `"tls/key.pem"` | PEM certificate (chain) and private key. The key file must be mode 0600 |
| `subject_alt_names` | `["localhost", "127.0.0.1"]` | Names and IP addresses in a generated certificate: list every address clients use |
| `common_name` | `"telltype-server"` | Subject CN of a generated certificate |
| `allow_plain_http` | `false` | Must be `true` with `mode = "off"` (an explicit confirmation) |

TLS 1.2 and 1.3 (rustls). The app pins the certificate it was shown once and does not check host
names, so one certificate serves every address.

### `[auth]`

| Key | Default | Meaning |
|---|---|---|
| `key_file` | required unless `open` | File with the bearer key (at least 32 characters, mode 0600 or stricter, or the server refuses to start) |
| `open` | `false` | `true`: no key at all (only for trusted networks); then `key_file` must be absent |

### `[speech]`

| Key | Default | Meaning |
|---|---|---|
| `model_dir` | required | Directory with `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt` (the sherpa-onnx Parakeet export) |
| `model_name` | `"parakeet-unified-en-0.6b-int8"` | Name in `/healthz` and `/v1/models` |
| `threads` | `8` | CPU threads per recognition |
| `max_concurrent` | `2` | Recognitions at once |
| `max_waiting` | `4` | Requests that may wait for a slot; beyond `max_concurrent + max_waiting`: 429 `busy` |
| `max_audio_seconds` | `360` | Longer audio: 413 `too_large` (decoding stops just after the limit) |
| `recognition_timeout_s` | `120` | Longer: 504 `backend_timeout` (the slot stays held until the recognition really ends) |
| `hotwords` | `false` | `true`: the request's `prompt` terms become hot words (modified beam search). See "Hot words" |
| `hotwords_score` | `1.0` | Hot-word bonus |
| `max_hotwords` | `100` | Terms used per request |
| `vocab_dir` | required with `hotwords` | Where the derived `bpe.vocab` (made from `tokens.txt`, never audio) is written at start |

### `[text.cleanup]` and `[text.commands]`

Two OpenAI-compatible upstreams, normally `llama-server`: `cleanup` (required; the model that tidies
dictated text) and `commands` (optional; Command Mode). Both have the same keys:

| Key | Default | Meaning |
|---|---|---|
| `url` | `http://127.0.0.1:8172` / `:8173` | Base URL (`http://` only; upstreams normally run on the same machine) |
| `model` | required | The role's real model name: `/healthz` reports it, and `/v1/models` lists it in place of an upstream id that is one of `aliases` (an upstream still started with `--alias cleanup`) |
| `aliases` | `{}` | Deprecated request names, kept so older apps keep working: request model name → upstream model name. `cleanup = "gemma-4-e4b-it-qat-q4_0"` routes `cleanup` here and rewrites `model`. Alias names are never listed in `/v1/models` |
| `timeout_s` | `60` / `120` | Time for the upstream to answer (for streams: the longest silence between chunks) |
| `max_in_flight` | `4` / `2` | Requests at once to this upstream; more: 429 `busy` |
| `health_path` | `"/health"` | Route that answers 200 when the upstream can serve |

A chat request goes to the upstream that has its `model` as an alias, or else to the one whose own
`/v1/models` lists it (asked again when a name is new). Clients should send the real ids from
`/v1/models`; the `cleanup` and `assistant` names are deprecated. An unknown name is 400 `bad_request`.

#### `[text.<role>.supervise]` (optional): Telltype Server runs the upstream

| Key | Default | Meaning |
|---|---|---|
| `binary` | required | Path to `llama-server` |
| `args` | `[]` | Its arguments, without `--host`/`--port` (taken from `url`, which must then be `http://127.0.0.1:<port>` or `http://localhost:<port>`) and without `--models-preset`/`--models-max` (from `router`) |
| `restart_min_s`, `restart_max_s` | `1`, `60` | Back-off after an exit: doubles from min to max; a run of 60 s resets it |
| `stop_timeout_s` | `20` | On shutdown: SIGTERM to the process group, SIGKILL after this |

The process is started at once and watched: until its health route answers it is "not ready";
afterwards three failed checks in a row (every 10 s) count as hung and it is restarted. Its output
goes to the server's journal.

#### `[text.<role>.supervise.router]` (optional): `llama-server` in router mode

| Key | Default | Meaning |
|---|---|---|
| `model_dirs` | `[]` | Directories whose `*.gguf` become presets, named by the lower-cased file name without `.gguf` (projectors `mmproj*` and later shards of split files are skipped) |
| `models_ini` | none | An existing presets file (for example another llama-server router's `models.ini`) whose sections are offered too; on a name clash its section wins |
| `presets_file` | required | Where the generated presets are written at every start (mode 0600) |
| `models_max` | `1` | Models loaded at once (`--models-max`); `llama-server` switches models itself |
| `defaults` | `{}` | `key = "value"` lines added to every preset from `model_dirs`, e.g. `n-gpu-layers = "999"`, `ctx-size = "8192"` |

## How it works

```text
           HTTPS (rustls), bearer key, limits, Server-Timing, request log
client ──► telltype-server ─┬─ /v1/audio/transcriptions ─► decode in memory ─► Parakeet (sherpa-onnx, CPU)
                            ├─ /v1/chat/completions ─────► cleanup llama-server  ┐ started, watched and
                            ├─ /v1/models (merged) ──────► commands llama-server ┘ stopped by the server
                            └─ /healthz
```

The workspace is split by responsibility: `telltype-config` (the TOML schema and validation),
`telltype-speech` (WAV parser, Symphonia decoding, resampling, the recogniser and its limits),
`telltype-upstream` (routing, proxying, streaming, model lists), `telltype-supervisor` (processes and
presets), `telltype-http` (the API, errors, auth, logs, credentials) and `telltype-server` (the
binary). Errors are typed in each crate and mapped to statuses and codes in one place
(`telltype-http/src/error.rs`). `unsafe` is forbidden in all of them; `unwrap`, `expect` and `panic!`
are denied outside tests.

### Speech engine

Parakeet Unified EN 0.6B int8 runs through the `sherpa-onnx` crate 2.0.1, greedy search, 16 kHz
mono. Uploads are decoded by our WAV parser (integer and float PCM, `WAVE_FORMAT_EXTENSIBLE`) or by
Symphonia with a pure-Rust Opus decoder (Ogg Opus, WebM Opus, FLAC, MP3, AAC/M4A, Vorbis), mixed to
mono and resampled with sherpa-onnx's windowed-sinc resampler. Nothing is written to disk and no
subprocess is started; `crates/telltype-speech/tests/memory_only.rs` checks both.

By default the native library is linked statically (one self-contained binary). sherpa-onnx 2.0.1's
native libraries differ in one place on the sample clip: the crate's prebuilt builds (static and
shared) write "…turning away her eyes it is certainly…" where the shared libraries in sherpa-onnx's
wheel package of the same version write "…her eyes. It is certainly…"; all words agree, threads do
not matter, and the Opus version of the clip gives the same words. To link the wheel's libraries:
`cargo build --release --no-default-features --features telltype-speech/shared` with
`SHERPA_ONNX_LIB_DIR` pointing at the wheel's `sherpa_onnx/lib`, and those libraries next to the
binary; the default stays static.

### Hot words

`hotwords = true` turns the request's comma-separated `prompt` terms into per-request hot words
(modified beam search, a `bpe.vocab` derived from `tokens.txt`). It is off by default: in sherpa-onnx
2.0.1 beam search with hot words repeats the hot word ("observed Phoebe Phoebe") and drops sentence
boundaries; upstream fixed this after 2.0.1. With it off the `prompt` field is accepted and
ignored.

## Development

```sh
scripts/gate.sh                  # fmt, clippy (pedantic, -D warnings), tests + line coverage (floor 85 %), cargo deny
SLOW=1 TELLTYPE_PARAKEET_DIR=<parakeet dir> scripts/gate.sh   # plus the real-model test
```

The gate needs `cargo-llvm-cov` and `cargo-deny` (`cargo install --locked cargo-llvm-cov
cargo-deny`) and the `llvm-tools-preview` component. Pedantic lints are configured in
`[workspace.lints]` with a short, reasoned allow-list; `gate.sh` therefore runs clippy without a
command-line `-W clippy::pedantic`, which would override that list. Each step's log is in
`target/gate/`.

Tests need no model and no GPU, except the one slow test (`#[ignore]`d):

```sh
TELLTYPE_PARAKEET_DIR=<parakeet dir> cargo test -p telltype-speech --release --test parakeet -- --ignored --nocapture
```

It loads Parakeet, transcribes `android/app/src/debug/assets/sample/phoebe.wav` (and an Ogg Opus copy
made with ffmpeg, if ffmpeg is installed) and compares with the expected transcript: the words
must match; with `TELLTYPE_EXPECT_EXACT=1` (a build against the wheel's libraries, see "Speech
engine") the text must match exactly.

`scripts/contract.py` runs the same black-box checks against two servers (for example two versions
of this server, or this server and another implementation of the contract) and prints a table; see [`docs/contract.md`](docs/contract.md).

## Troubleshooting

- **Exit code 2 at start**: a configuration or credential problem; the log line `"event":"fatal"`
  and stderr name the key or file. `telltype-server check-config` shows it without starting.
- **`/healthz` is 503 `not_ready`**: the recogniser is still loading (about a second) or failed (the
  server then exits with code 1; the log names the missing model file).
- **`degraded`**: speech works, cleanup does not answer its health route. With `supervise`, look for
  `"process":"cleanup"` lines (`started`, `exited`, `restarting after a delay`) in the journal.
- **429 `busy`**: more requests than `max_concurrent + max_waiting` (speech) or `max_in_flight`
  (text) at once.
- **415 `unsupported_audio`**: the upload is not a format the decoders know, or is cut off before
  any audio.
- **The client says the certificate is untrusted**: it has to trust or pin this certificate
  (`telltype-server fingerprint`); public CAs never will. For curl: `--cacert <cert_file>`.
