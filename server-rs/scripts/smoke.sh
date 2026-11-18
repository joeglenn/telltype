#!/usr/bin/env bash
# End-to-end check of a running Telltype Server, verifying its own certificate.
#
#   scripts/smoke.sh [base-url] [audio-file]
#   e.g. scripts/smoke.sh https://192.0.2.10:8170
#
# Checks: 401 without key, /healthz "ok", /v1/models, a transcription (expects "Phoebe" for the
# default clip), and one cleanup chat call. Exits non-zero at the first failure.
# Credentials: CERT (default $TELLTYPE_CONFIG_DIR/tls/cert.pem) and KEY_FILE (default
# $TELLTYPE_CONFIG_DIR/gate.key; TELLTYPE_CONFIG_DIR defaults to ~/.config/telltype). The key goes
# to curl through a pipe, never to a file or the screen. CERT=system uses the system's trust store.
set -euo pipefail

server_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
config_dir="${TELLTYPE_CONFIG_DIR:-$HOME/.config/telltype}"
base="${1:-https://127.0.0.1:8170}"
audio="${2:-$server_dir/../android/app/src/debug/assets/sample/phoebe.wav}"
cert="${CERT:-$config_dir/tls/cert.pem}"
key_file="${KEY_FILE:-$config_dir/gate.key}"

fail() { echo "FAIL: $*" >&2; exit 1; }
[ -r "$key_file" ] || fail "cannot read $key_file"
[ -r "$audio" ] || fail "cannot read $audio"
tls=(--cacert "$cert")
[ "$cert" = system ] && tls=()
case "$base" in http://*) tls=() ;; esac

request_id="smoke-$(date +%s)-$$"
body=$(mktemp)
headers=$(mktemp)
trap 'rm -f -- "$body" "$headers"' EXIT

call() { # with-key(0|1) curl-args... ; prints the HTTP status. The key header arrives on stdin.
  local with_key=$1
  shift
  if [ "$with_key" = 1 ]; then
    printf 'Authorization: Bearer %s\n' "$(tr -d '\n' <"$key_file")" |
      curl -sS "${tls[@]}" --max-time 120 -o "$body" -D "$headers" -w '%{http_code}' \
        -H "X-Request-Id: $request_id" -H @- "$@"
  else
    curl -sS "${tls[@]}" --max-time 120 -o "$body" -D "$headers" -w '%{http_code}' \
      -H "X-Request-Id: $request_id" "$@"
  fi
}
header() { grep -i "^$1:" "$headers" | head -1 | cut -d' ' -f2- | tr -d '\r'; }
json() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print($1)" "$body"; }

status=$(call 0 "$base/healthz")
[ "$status" = 401 ] || fail "healthz without key returned $status, expected 401"
echo "ok   401 without key"

status=$(call 1 "$base/healthz")
[ "$status" = 200 ] || fail "healthz returned $status: $(cat "$body")"
[ "$(json 'd["status"]')" = ok ] || fail "healthz status is not ok: $(cat "$body")"
[ "$(header X-Request-Id)" = "$request_id" ] || fail "X-Request-Id was not echoed"
echo "ok   healthz ok ($(json '", ".join(k + "=" + str(d[k]["ready"]) for k in ("stt", "cleanup", "assistant") if k in d)'))"

status=$(call 1 "$base/v1/models")
[ "$status" = 200 ] || fail "/v1/models returned $status"
echo "ok   models: $(json '" ".join(m["id"] for m in d["data"])')"

start=$(date +%s%N)
status=$(call 1 -F "file=@$audio" -F model=whisper-1 -F language=en "$base/v1/audio/transcriptions")
ms=$(( ($(date +%s%N) - start) / 1000000 ))
[ "$status" = 200 ] || fail "transcription returned $status: $(cat "$body")"
text=$(json 'd["text"]')
[ -n "$text" ] || fail "transcription is empty"
case "$audio" in *phoebe.wav) [[ "$text" == *Phoebe* ]] || fail "unexpected transcript: $text" ;; esac
echo "ok   transcription ${ms} ms, duration $(json 'd["duration"]') s, Server-Timing: $(header Server-Timing)"
echo "     text: $text"

chat='{"model":"cleanup","temperature":0.1,"max_tokens":64,"messages":[{"role":"system","content":"You clean up dictated text. Return only the text the speaker meant to type."},{"role":"user","content":"<transcript>um so lets meet at five pm no actually six pm</transcript>"}]}'
start=$(date +%s%N)
status=$(call 1 -H 'Content-Type: application/json' --data-binary "$chat" "$base/v1/chat/completions")
ms=$(( ($(date +%s%N) - start) / 1000000 ))
[ "$status" = 200 ] || fail "cleanup chat returned $status: $(cat "$body")"
content=$(json 'd["choices"][0]["message"]["content"]')
[ -n "$content" ] || fail "cleanup reply is empty"
echo "ok   cleanup ${ms} ms, Server-Timing: $(header Server-Timing)"
echo "     content: $content"
echo "PASS $base"
