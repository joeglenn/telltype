#!/usr/bin/env bash
# G1 (D60): runs StockServerTest on one device against stock OpenAI-compatible servers started here, not our gate:
#   - text: llama.cpp's llama-server with a small model, plain HTTP, no key, on 127.0.0.1:$TEXT_PORT;
#   - speech: stub_gate.py --openai-speech, a plain stock speech server (only POST /v1/audio/transcriptions, no
#     /v1/models, no /healthz, no gate extras), plain HTTP, no key, on 127.0.0.1:$SPEECH_PORT, answering every upload
#     with the sample clip's known transcript, so Setup proves it with a one-second silent transcription and the
#     dictation needs no speech model here.
# Both run as transient user units (g1-stock-text, g1-stock-stt) and are stopped on every exit. The emulator reaches
# them at 10.0.2.2. The ports are fixed (default 18200, 18201): run one instance of this script at a time on a host.
# Expects the APKs installed (connected-tests.sh or deploy.sh did it) or builds nothing itself.
# Usage: android/scripts/stock-servers-test.sh <serial>
set -euo pipefail

serial=${1:?usage: stock-servers-test.sh <serial>}
repo=$(cd "$(dirname "$0")/../.." && pwd)
llama=${LLAMA_SERVER:-$HOME/llama.cpp/build/bin/llama-server}
model=${STOCK_TEXT_MODEL:-$HOME/models/dictation/Qwen_Qwen3.5-2B-Q4_K_M.gguf}
# The transcript of app/src/debug/assets/sample/phoebe.wav (SampleClip.TRANSCRIPT), the clip the test plays.
transcript="Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait"
TEXT_PORT=${TEXT_PORT:-18200}
SPEECH_PORT=${SPEECH_PORT:-18201}
app_id=net.jbglenn.telltype.debug
runner=$app_id.test/androidx.test.runner.AndroidJUnitRunner
out=$(mktemp -d "${TMPDIR:-/tmp}/g1-stock.XXXXXX")
adb() { "${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb" -s "$serial" "$@"; }
say() { printf '== %s\n' "$*"; }

cleanup() {
    systemctl --user stop g1-stock-text.service g1-stock-stt.service >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

say "starting llama-server on 127.0.0.1:$TEXT_PORT ($(basename "$model"))"
systemd-run --user --unit=g1-stock-text --collect -q -p MemoryMax=infinity \
    "$llama" -m "$model" --host 127.0.0.1 --port "$TEXT_PORT" -c 4096 -np 1 -ngl 99 --jinja
say "starting the stock speech stand-in on 127.0.0.1:$SPEECH_PORT"
systemd-run --user --unit=g1-stock-stt --collect -q -E PYTHONUNBUFFERED=1 \
    /usr/bin/python3 "$repo/android/scripts/stub_gate.py" --port "$SPEECH_PORT" --openai-speech "$transcript"
for _ in $(seq 1 90); do
    text_ok=$(curl -fsS "http://127.0.0.1:$TEXT_PORT/v1/models" >/dev/null 2>&1 && echo yes || echo no)
    stt_ok=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$SPEECH_PORT/v1/models" | grep -qx 404 && echo yes || echo no)
    [[ $text_ok == yes && $stt_ok == yes ]] && break
    sleep 2
done
say "text /v1/models: $(curl -fsS "http://127.0.0.1:$TEXT_PORT/v1/models" | head -c 300)"
say "speech /v1/models: HTTP $(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$SPEECH_PORT/v1/models")"

adb shell pm clear "$app_id" > /dev/null
adb logcat -c
say "running StockServerTest"
adb shell am instrument -w -r -e class net.jbglenn.telltype.stock.StockServerTest \
    -e owkStockSpeech "http://10.0.2.2:$SPEECH_PORT" -e owkStockText "http://10.0.2.2:$TEXT_PORT" \
    "$runner" > "$out/instrument.txt" 2>&1 || true
adb logcat -d -s StockServerTest:I ServerCheck:I ServerHealth:I RemoteStt:I RemoteCleaner:I > "$out/logcat.txt" || true
grep -E "^(INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_CODE|OK \(|FAILURES)" "$out/instrument.txt" | head -20
say "log lines"
cat "$out/logcat.txt"
say "raw output in $out"
grep -q "^OK (1 test)" "$out/instrument.txt"
