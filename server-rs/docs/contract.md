# Telltype Server: HTTP contract

What a client can rely on. It is a superset of the OpenAI API for the three routes the Telltype app
uses, plus `/healthz`. This describes version 1.1.0.

## Common

- Base URL: `https://<address>:<port>` (TLS 1.2 or 1.3 with the server's certificate), or `http://`
  when the operator turned TLS off.
- **Authentication**: when the server has a key, every route (unknown ones included) needs
  `Authorization: Bearer <key>`. The scheme is case-insensitive; the key is compared in constant
  time. Missing or wrong: 401 `unauthorized` with `WWW-Authenticate: Bearer`, and nothing else
  happens.
- **Limits per client address** (since 1.1; off for loopback, private LAN, Tailscale and Docker
  addresses by default, see the README's `[limits]`): too many requests, or too many failed keys,
  give 429 `rate_limited` with `Retry-After`; while blocked after failed keys no key is checked at
  all. A request rejected before its body is read (rate limit, key, declared `Content-Length` over
  the limit) is answered with `Connection: close` and the body is not read. A body that arrives
  more slowly than the configured minimum rate gives 408 `request_timeout`.
- **Keys limited to roles** (since 1.1): a key without the `speech` role gets 403 `forbidden` on
  transcriptions; a chat model of a role the key lacks is 404 `model_not_found`; `/v1/models`
  lists only the key's roles.
- **Request id**: `X-Request-Id` (1 to 128 of `A-Za-z0-9._:-`) is echoed in the response and
  logged. A missing or malformed one is replaced by a random 32-hex-digit id, which is echoed.
- **`Server-Timing`** on every response: stage durations in milliseconds, then `gate` (total time in
  the server). Transcriptions: `decode, queue, stt, gate`. Chat: `llm, gate`. `/healthz`:
  `probe, gate`. Errors: `gate` (plus any stage that ran).
- `Server: telltype-server/<version>`.
- Unknown routes: 404 `not_found`. Known routes with another method: 405 `method_not_allowed`
  (both after authentication).

## `GET /healthz`

```json
{
  "status": "ok",
  "version": "1.1.0",
  "stt":       { "ready": true, "model": "parakeet-unified-en-0.6b-int8", "engine": "sherpa-onnx" },
  "cleanup":   { "ready": true, "model": "gemma-4-e4b-it-qat-q4_0" },
  "assistant": { "ready": true, "model": "qwen_qwen3.5-4b-q4_k_m" },
  "models": {
    "speech":   ["parakeet-unified-en-0.6b-int8"],
    "cleanup":  ["gemma-4-e4b-it-qat-q4_0"],
    "commands": ["gemma-4-e4b-it-qat-q4_0", "qwen_qwen3.5-4b-q4_k_m", "qwen_qwen3.5-9b-q4_k_m"]
  }
}
```

- `status`: `ok` when speech and cleanup are ready, `degraded` when only speech is (200 in both
  cases), `not_ready` when speech is not ready: then the status is **503**, with `Retry-After` and
  the error fields (`"error": {"code": "not_ready", ...}`) added to the same object.
- `stt.ready` is false while the recogniser loads (about a second after start).
- `cleanup` and `assistant` (the commands upstream; the name is the one today's app reads) are ready
  when their health route answered 200 within the probe timeout. Without a commands upstream,
  `assistant` is `{"ready": false, "model": ""}`. `model` is the configured name.
- `models`: the model ids each upstream offers, as `GET /v1/models` lists them (a model file that an
  earlier upstream already serves appears under that upstream's name), or `null` when the upstream
  could not be asked; `speech` is the recogniser's name. Clients pick cleanup and Command Mode
  models from these lists.

## `GET /v1/models`

Only allowed models are listed (each text role's `allow` patterns, since 1.1).

OpenAI format: `{"object": "list", "data": [{"id": ..., "object": "model", ...}, ...]}`. First the
speech model (`owned_by: "telltype"`), then every upstream's entries as the upstream sent them
(first id wins). Only real model ids are listed: configured aliases are not, and an upstream entry
whose id is one of its role's aliases (llama-server started with `--alias cleanup`) is listed under
the role's configured `model` name, without its `aliases` field.

Every entry has one field that stock OpenAI clients ignore (since 1.0.1):

```json
{"id": "gemma-4-e4b-it-qat-q4_0", "object": "model", "owned_by": "llamacpp",
 "telltype": {"roles": ["cleanup", "commands"]}}
```

`telltype.roles` lists the roles that can run the model: `speech` (the recogniser), and the name of
each text upstream that offers it (`cleanup`, `commands`). A client shows a model only in the lists
of its roles.

**One entry per model file.** The server finds the file behind an entry from the `--model` argument
a router-mode llama-server reports for each preset (`status.args`), or from `/props` (`model_path`)
of an upstream that lists one model. When a later upstream offers a file an earlier one already
serves (the commands router's preset of the cleanup model), that entry is not listed: the earlier
entry gains the role, and a request naming the dropped id is sent to the earlier upstream under the
listed name, so the file is never loaded twice. Upstreams are taken in the order cleanup, commands.
Two presets of the same upstream that share a file (deliberately different settings) both stay.

## `POST /v1/audio/transcriptions`

`multipart/form-data`, as OpenAI's:

| Field | Required | Meaning |
|---|---|---|
| `file` | yes | The audio. WAV (any rate, channels, 8/16/24/32-bit integer or 32/64-bit float), Ogg or WebM Opus, FLAC, MP3, AAC/M4A, Vorbis. At most `max_upload_bytes` (25 MiB) and `max_audio_seconds` (6 min) |
| `model` | no | Ignored (accepted so OpenAI clients work) |
| `language` | no | `en` or empty; anything else is 400 |
| `prompt` | no | Comma-separated dictionary terms; hot words when the server enables them, else ignored |
| `response_format` | no | `json` (default), `verbose_json` or `text` |

Other fields are ignored. Text fields are UTF-8, at most 64 KiB each, trimmed.

Replies: `json` → `{"text": "...", "duration": 7.435}` (seconds, three decimals); `verbose_json` →
the same plus `"language": "en"` (the engine gives no segments); `text` → the transcript as
`text/plain; charset=utf-8`.

## `POST /v1/chat/completions`

OpenAI chat format, at most `max_json_bytes` (1 MiB).

- `model` (non-empty string) selects the upstream: any id `GET /v1/models` lists, or an id it
  leaves out as a duplicate of a listed model file (routed as that listed id). Unknown or not
  allowed: 404 `model_not_found` (since 1.1; 400 before), and no upstream sees the request.
- **Deprecated:** the alias names `cleanup` and `assistant` (whatever `aliases` configures) are still
  accepted, so installed apps from before 1.0.0 keep working, and route to the configured model.
  They are not listed and may be removed in a later version; send a listed id instead.
- `messages` must be a non-empty list; `stream` absent, `null`, `true` or `false`.
- The body is forwarded byte for byte. Only when the name differs from the upstream's own id (an
  alias, or a real name listed in place of an upstream's alias id) is `model` rewritten (the other
  fields and their order are kept).
- The reply is the upstream's, unchanged, with its content type. With `"stream": true` the
  server-sent events pass through as they arrive.

## Errors

JSON: `{"error": {"code": "...", "message": "..."}}`. Messages are for people and may change; codes
and statuses are the contract.

| Status | Code | When |
|---|---|---|
| 400 | `bad_request` | Missing or malformed fields, not multipart, malformed multipart, bad JSON |
| 401 | `unauthorized` | Missing or wrong key (`Connection: close`) |
| 403 | `forbidden` | The key may not use speech recognition (since 1.1) |
| 404 | `not_found` | No such route |
| 404 | `model_not_found` | Chat model unknown, not allowed, or of a role the key lacks (since 1.1) |
| 408 | `request_timeout` | The body arrived too slowly (since 1.1; `Connection: close`) |
| 405 | `method_not_allowed` | Wrong method for a route |
| 413 | `too_large` | Upload over 25 MiB, audio over 6 minutes, chat body over 1 MiB |
| 415 | `unsupported_audio` | The file cannot be decoded, or contains no audio |
| 429 | `rate_limited` | Too many requests or failed keys from the client's address; has `Retry-After` (since 1.1) |
| 429 | `busy` | Too many transcriptions (`max_concurrent + max_waiting`) or chat requests for one upstream (`max_in_flight`) in flight |
| 500 | `internal_error` | The recogniser failed (should not happen) |
| 502 | `backend_error` | An upstream answered with an error other than 503, or broke off |
| 503 | `not_ready` | Recogniser loading, upstream unreachable or loading (llama-server's 503); has `Retry-After` |
| 504 | `backend_timeout` | Recognition or an upstream took longer than its timeout |

An over-limit body is not read (since 1.1; before, up to 64 MiB were read and discarded): the 413
is sent with `Connection: close` and the connection closed. A client that sent
`Expect: 100-continue` always receives it; one that keeps streaming the body may see the connection
reset before it reads the 413.

## Comparing two servers

`scripts/contract.py` sends the same black-box checks (authentication, request ids, timings,
health shape, transcription formats and limits, chat validation and limits, one real cleanup
request) to two servers and prints a table side by side. It exits 1 when a check differs that
should agree; the checks it marks "intended" are the ones that differ between server versions
(`GET /v1/models` and `stream: true`, both added in 0.2.0, the alias names no longer listed since
1.0.0, and `version`).
