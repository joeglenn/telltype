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
4. [Running on the internet](#running-on-the-internet)
5. [Check it](#check-it)
6. [Configuration reference](#configuration-reference)
7. [How it works](#how-it-works)
8. [Development](#development)
9. [Troubleshooting](#troubleshooting)

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

The unit is sandboxed: the file system is read-only except `~/.local/state/telltype` and the
certificate directory `~/.config/telltype/tls` (run `telltype-server credentials` once before the
first start, so a self-signed certificate exists), no new privileges, a private `/tmp`, only
IPv4/IPv6/Unix sockets, the `@system-service` system calls, `UMask=0077`, memory and task caps.
`systemd-analyze --user security` rated a copy 5.3 with `MemoryDenyWriteExecute=yes` enabled (the
unit of 1.0: 9.2); the speech engine works with that option, see the unit file for when to enable it. It still listens on
every interface and reaches the `llama-server`s on localhost; the GPU stays visible to supervised
`llama-server`s. Options a user unit cannot apply, and what a system unit could add, are listed in
the unit file. `systemctl --user reload telltype-server` re-reads the key files at once.

## Running on the internet

The defaults suit a home network: nothing changes for clients on loopback, the private LAN ranges,
Tailscale or Docker bridges (`[limits] exempt`). For a server reachable from the internet, change
these settings and read what each protection does.

What is protected, always:

- **Slow and idle connections.** Headers (and the TLS handshake) must arrive within
  `header_timeout_s` (10 s); an idle HTTP/1 keep-alive connection is closed after the same time; any
  connection with no byte in either direction for `idle_timeout_s` (300 s) is closed. A request
  body must keep arriving at `body_min_bytes_per_s` (4 KiB/s) after `body_grace_s` (10 s), or the
  request ends with 408 and the connection closes. At most `max_connections` (1024) connections,
  `max_connections_per_ip` (32) per address (IPv6: per /64); over the cap a connection is closed
  right after `accept`. HTTP/2: at most `http2_max_streams` (32) streams per connection, reset
  floods are cut off.
- **Early rejection.** The rate limits, the key and the declared `Content-Length` are checked
  before any byte of the body is read; a rejection is answered and the connection closed
  (`Connection: close`) instead of reading the body. Clients that send `Expect: 100-continue` (curl
  does for large uploads) always see the 413/401; a client that streams the body regardless may see
  the connection reset before it reads the answer. The Telltype app uploads a few hundred KB and is
  not affected.
- **Rate limits per client address.** All requests: `requests_per_minute` (600). Failed keys:
  `auth_failures_per_minute` (10); after that every request from the address gets 429
  `rate_limited` with `Retry-After`, and no key is even checked until the bucket refills. At most
  `rate_limit_clients` (10 000) addresses are tracked. Behind a reverse proxy, list it in
  `trusted_proxies` so the address in `X-Forwarded-For` counts, not the proxy's.
- **Allowed models only.** Each text role serves the models its `allow` patterns match (default
  `["*"]`: everything the upstream lists, as in 1.0). Any other name is 404 `model_not_found`
  before an upstream sees the request, so a `llama-server` router is never asked to load it, and
  `/v1/models` lists only allowed models.
- **Named keys.** One key per person or device, each optionally limited to roles; revoked without
  a restart.

What to set for a public server:

```toml
[limits]
exempt = []                       # nobody is exempt from the limits
trusted_proxies = ["127.0.0.1"]   # only when a reverse proxy on this machine forwards to the server

[auth]
keys_dir = "keys"                 # named keys (key_file may stay for the owner's own key)

[text.commands]
allow = ["qwen_qwen3.5-4b-q4_k_m", "gemma-4-*"]   # only what you want to offer
```

Keep `[limits] exempt` empty (or only the ranges you trust) when a reverse proxy runs on the same
machine: without `trusted_proxies` every request then comes from 127.0.0.1, and loopback is exempt
by default.

Keys:

```sh
telltype-server keys add anna --config server.toml                  # all roles
telltype-server keys add tablet --roles speech,cleanup --config server.toml
telltype-server keys add anna2 --show --config server.toml          # also print the key once
telltype-server keys list --config server.toml                      # names and roles, never keys
telltype-server keys revoke tablet --config server.toml             # refused within 2 s
```

A key file is `<keys_dir>/<name>.key`, mode 0600 in a directory of mode 0700: the key on the first
line, optionally `roles = speech, cleanup`. The server checks the directory every 2 s and on
SIGHUP; a file with wrong permissions is left out (its key stops working) and logged by name. Logs
carry the key's name (`"key": "anna"`), never the key. Roles: `speech` (transcriptions), `cleanup`
and `commands` (chat on that upstream; other models are 404 for that key, and `/v1/models` lists
only the key's roles).

A reverse proxy with a Let's Encrypt certificate ([Caddy](https://caddyserver.com)), forwarding to
the server on loopback with plain HTTP (`listen = "127.0.0.1:8170"`, `tls.mode = "off"`,
`allow_plain_http = true`, `trusted_proxies = ["127.0.0.1"]`, `exempt = []`):

```caddyfile
{
	servers {
		timeouts {
			read_header 10s
			read_body 120s
			idle 60s
		}
	}
}

dictation.example.org {
	request_body {
		max_size 27MB
	}
	# Caddy sets X-Forwarded-For; the server believes it only from trusted_proxies.
	reverse_proxy 127.0.0.1:8170 {
		transport http {
			dial_timeout 5s
			response_header_timeout 180s
		}
	}
}
```

The app then connects to `https://dictation.example.org` with the system
trust store, no pinning needed. Without a proxy the server's own TLS (`tls.mode = "files"` with a
Let's Encrypt certificate, or `self-signed` with the app's pinning) is just as good; the
protections above apply either way.

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

### `[limits]`

Connection handling and rate limits ("Running on the internet" explains each).

| Key | Default | Meaning |
|---|---|---|
| `header_timeout_s` | `10` | TLS handshake and each request's headers (HTTP/2: the first request); also how long an idle HTTP/1 keep-alive connection is kept |
| `idle_timeout_s` | `300` | A connection with no byte read or written for this long is closed. Must be longer than `recognition_timeout_s` and every text `timeout_s` |
| `body_grace_s`, `body_min_bytes_per_s` | `10`, `4096` | After the grace a body must have arrived at this average rate, or 408 `request_timeout` (`0`: no minimum rate) |
| `max_connections` | `1024` | Open connections in total |
| `max_connections_per_ip` | `32` | Open connections per client address (IPv6: per /64); `exempt` and `trusted_proxies` are not capped |
| `http2_max_streams` | `32` | Concurrent HTTP/2 streams per connection |
| `requests_per_minute` | `600` | Requests per client address and minute (also the burst); more: 429 `rate_limited` |
| `auth_failures_per_minute` | `10` | Failed keys per client address and minute; then 429 `rate_limited` without a key check until a token is back |
| `rate_limit_clients` | `10000` | Client addresses tracked at most (memory bound; idle ones are forgotten first) |
| `trusted_proxies` | `[]` | Addresses or ranges (`127.0.0.1`, `10.0.0.0/8`) whose `X-Forwarded-For` names the client |
| `exempt` | loopback, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `fd7a:115c:a1e0::/48`, `fe80::/10`, `fc00::/7` | Clients never rate-limited or capped per address: this machine, the LAN, Tailscale, Docker bridges. `[]` for a public server |

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
| `key_file` | required unless `keys_dir` or `open` | File with the bearer key (at least 32 characters, mode 0600 or stricter, or the server refuses to start); its name in logs is `default` |
| `keys_dir` | none | Directory (mode 0700) of named keys, `<name>.key` each; managed with `telltype-server keys` and re-read without a restart. Works together with `key_file` |
| `open` | `false` | `true`: no key at all (only for trusted networks); then `key_file` and `keys_dir` must be absent |

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
| `allow` | `["*"]` | Model names clients may use (`*` and `?` patterns), besides `model` and the aliases. `[]`: the configured model only. Others are 404 `model_not_found` without reaching the upstream, and are not listed |

A chat request goes to the upstream that has its `model` as an alias, or else to the one whose own
`/v1/models` lists it (asked again when a name is new). Clients should send the real ids from
`/v1/models`; the `cleanup` and `assistant` names are deprecated. An unknown or not allowed name is
404 `model_not_found` (400 before 1.1).

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

The sherpa-onnx native library is used only from `target/native/sherpa-onnx`, which
`scripts/native.sh` downloads and checks against the SHA-256 pinned in
[`native/sherpa-onnx.sha256`](native/sherpa-onnx.sha256) (the `sherpa-onnx-sys` crate downloads
without any check); `.cargo/config.toml` points the build there (`SHERPA_ONNX_LIB_DIR`). The gate
and `install.sh` run it first; for a plain `cargo build` run `scripts/native.sh` once. A changed or
corrupted archive stops it with "SHA-256 mismatch".

`scripts/fuzz.sh [seconds]` fuzzes the parsers that read client bytes (multipart, WAV, the audio
decoders with Ogg/Opus and WebM, chat request bodies) with `cargo fuzz` (nightly toolchain and
`cargo install cargo-fuzz`); the corpus stays in `fuzz/corpus`, outside git.

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
