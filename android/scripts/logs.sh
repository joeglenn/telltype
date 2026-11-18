#!/usr/bin/env bash
# Pulls the app's log files, crash reports and recent logcat lines from one device into a new directory.
# Safe to run again: each run writes a fresh timestamped directory and changes nothing on the device.
#
#   android/scripts/logs.sh <adb serial> [--out DIR] [--package APP_ID]
#
#   --out DIR          where to write (default: android/build/device-logs/<serial>/<timestamp>, git-ignored)
#   --package APP_ID   default net.jbglenn.telltype.debug. Release builds are not debuggable, so run-as
#                      cannot read their files: use Diagnostics > Debug bundle > Export on the phone instead.
#
# The files hold no dictated text and no key (the app never logs them); they may hold app package names.
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="net.jbglenn.telltype.debug"
OUT=""

say() { printf '[logs] %s\n' "$*"; }
fail() { printf '[logs] error: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

find_adb() {
    if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
    if [[ -x "$sdk/platform-tools/adb" ]]; then echo "$sdk/platform-tools/adb"; return; fi
    command -v adb || fail "adb not found: set ADB or ANDROID_HOME"
}

SERIAL="${1:-}"
[[ -z "$SERIAL" || "$SERIAL" == -* ]] && usage
shift
while [[ $# -gt 0 ]]; do
    case "$1" in
        --out) OUT="${2:?--out needs a directory}"; shift ;;
        --package) APP_ID="${2:?--package needs an application id}"; shift ;;
        *) usage ;;
    esac
    shift
done
ADB_BIN="$(find_adb)"
adb_s() { "$ADB_BIN" -s "$SERIAL" "$@"; }

if [[ "$SERIAL" == *:* ]] && ! "$ADB_BIN" devices | grep -q "^$SERIAL[[:space:]]*device$"; then
    "$ADB_BIN" connect "$SERIAL" >/dev/null || true
fi
timeout 30 "$ADB_BIN" -s "$SERIAL" wait-for-device || fail "$SERIAL not reachable within 30 s"
adb_s shell pm path "$APP_ID" >/dev/null 2>&1 || fail "$APP_ID is not installed on $SERIAL"

OUT="${OUT:-$ANDROID_DIR/build/device-logs/${SERIAL//[:\/]/_}/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT/logs" "$OUT/crash"

if ! adb_s shell run-as "$APP_ID" true >/dev/null 2>&1; then
    fail "$APP_ID is not debuggable: export the debug bundle from Diagnostics instead"
fi

# pull_dir <device dir relative to the app's data dir> <local dir>: copies each regular file by name.
pull_dir() {
    local name count=0
    while IFS= read -r name; do
        [[ -z "$name" ]] && continue
        adb_s exec-out run-as "$APP_ID" cat "$1/$name" >"$2/$name"
        count=$((count + 1))
    done < <(adb_s shell "run-as $APP_ID ls $1 2>/dev/null" | tr -d '\r')
    echo "$count"
}

say "app log files: $(pull_dir files/logs "$OUT/logs")"
say "crash reports: $(pull_dir files/crash "$OUT/crash")"
PID="$(adb_s shell pidof "$APP_ID" | tr -d '\r' || true)"
if [[ -n "$PID" ]]; then
    adb_s logcat -d -v time --pid="$PID" >"$OUT/logcat-app.txt"
    say "logcat of the running app (pid $PID): $(wc -l <"$OUT/logcat-app.txt") lines"
else
    say "the app is not running: no logcat of its process"
fi
adb_s logcat -d -v time -s AndroidRuntime:E LeakCanary:D ActivityManager:I >"$OUT/logcat-system.txt" || true
adb_s shell dumpsys package "$APP_ID" | grep -m 2 -E 'versionName|versionCode' | sed 's/^ *//' >"$OUT/version.txt" || true
say "version: $(tr '\n' ' ' <"$OUT/version.txt")"
say "written to $OUT"
ls -la "$OUT" "$OUT/logs" "$OUT/crash" | sed 's/^/[logs]   /'
