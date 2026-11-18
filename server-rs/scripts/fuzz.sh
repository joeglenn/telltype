#!/usr/bin/env bash
# Fuzz the parsers that read client bytes: multipart, WAV, the audio decoders (Ogg/Opus, WebM,
# FLAC ... through Symphonia) and chat request bodies. Needs a nightly toolchain and cargo-fuzz:
#   rustup toolchain install nightly --profile minimal && cargo +nightly install --locked cargo-fuzz
#
#   scripts/fuzz.sh [seconds per target, default 300] [target ...]
# The corpus grows in fuzz/corpus/<target> (not in git); crashes land in fuzz/artifacts/<target>.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
seconds=${1:-300}
shift || true
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(multipart wav audio chat_json)
bash scripts/native.sh >/dev/null
fixtures=crates/telltype-speech/tests/fixtures
mkdir -p fuzz/corpus/{multipart,wav,audio,chat_json}
cp -n "$fixtures"/* fuzz/corpus/audio/ 2>/dev/null || true
printf 'RIFF$\0\0\0WAVEfmt \20\0\0\0\1\0\1\0\200>\0\0\0}\0\0\2\0\20\0data\0\0\0\0' >fuzz/corpus/wav/seed.wav
printf 'multipart/form-data; boundary=b0undary\n--b0undary\r\nContent-Disposition: form-data; name="file"; filename="a.wav"\r\nContent-Type: audio/wav\r\n\r\nRIFF\r\n--b0undary\r\nContent-Disposition: form-data; name="prompt"\r\n\r\nHyrule\r\n--b0undary--\r\n' >fuzz/corpus/multipart/seed
printf '{"model":"cleanup","messages":[{"role":"user","content":"hi"}],"stream":true,"temperature":0.1}' >fuzz/corpus/chat_json/seed.json
cd fuzz
for target in "${targets[@]}"; do
  echo "== $target: ${seconds} s"
  cargo +nightly fuzz run "$target" "corpus/$target" -- -max_total_time="$seconds" -rss_limit_mb=4096 -max_len=1048576 2>&1 | tail -n 4
done
