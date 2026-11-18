#!/usr/bin/env bash
# Builds the debug app and the test host, installs both on one device, grants what adb can grant, switches the
# accessibility service on, starts the app and follows its log. Safe to run again: every step checks or overwrites.
#
#   android/scripts/deploy.sh <adb serial> [--no-build] [--no-tail] [--tail-seconds N] [--clip-microphone]
#
#   <adb serial>       emulator-5560, or ip:port for a phone on wireless debugging (connected first if needed)
#   --no-build         install the APKs already in app/build and testhost/build
#   --no-tail          stop after starting the app
#   --tail-seconds N   follow the log for N seconds instead of until Ctrl-C
#   --clip-microphone  debug builds: the microphone plays the bundled clip (emulators have no microphone)
#
# Environment: ADB (default: $ANDROID_HOME/platform-tools/adb, then adb on PATH). See android/docs/deploy.md.
set -euo pipefail

readonly APP_ID="net.jbglenn.telltype.debug"
readonly HOST_ID="net.jbglenn.telltype.testhost"
readonly SERVICE="$APP_ID/net.jbglenn.telltype.platform.service.TelltypeAccessibilityService"
readonly ACTIVITY="$APP_ID/net.jbglenn.telltype.MainActivity"
readonly ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
readonly HOST_APK="$ANDROID_DIR/testhost/build/outputs/apk/debug/testhost-debug.apk"
readonly PERMISSIONS=(android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS)

say() { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] error: %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

find_adb() {
    if [[ -n "${ADB:-}" ]]; then echo "$ADB"; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
    if [[ -x "$sdk/platform-tools/adb" ]]; then echo "$sdk/platform-tools/adb"; return; fi
    command -v adb || fail "adb not found: set ADB or ANDROID_HOME"
}

SERIAL="${1:-}"
[[ -z "$SERIAL" || "$SERIAL" == -* ]] && usage
shift
BUILD=1 TAIL=1 TAIL_SECONDS=0 CLIP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) BUILD=0 ;;
        --no-tail) TAIL=0 ;;
        --tail-seconds) TAIL_SECONDS="${2:?--tail-seconds needs a number}"; shift ;;
        --clip-microphone) CLIP=1 ;;
        *) usage ;;
    esac
    shift
done
ADB_BIN="$(find_adb)"
adb_s() { "$ADB_BIN" -s "$SERIAL" "$@"; }
sh_s() { adb_s shell "$@" | tr -d '\r'; }

connect_device() {
    if [[ "$SERIAL" == *:* ]] && ! "$ADB_BIN" devices | grep -q "^$SERIAL[[:space:]]*device$"; then
        say "connecting to $SERIAL (wireless debugging)"
        "$ADB_BIN" connect "$SERIAL" >/dev/null || true
    fi
    timeout 30 "$ADB_BIN" -s "$SERIAL" wait-for-device || fail "$SERIAL not reachable within 30 s (paired? see docs/deploy.md)"
    local state; state="$(adb_s get-state 2>/dev/null || true)"
    [[ "$state" == "device" ]] || fail "$SERIAL is '$state', not 'device' (unauthorized: accept the prompt on the phone)"
    say "device $SERIAL: $(sh_s getprop ro.product.model), Android $(sh_s getprop ro.build.version.release) (API $(sh_s getprop ro.build.version.sdk))"
}

build() {
    say "building :app:assembleDebug :testhost:assembleDebug"
    (cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug :testhost:assembleDebug --console=plain -q) \
        || fail "build failed"
}

install_apks() {
    local apk
    for apk in "$APP_APK" "$HOST_APK"; do
        [[ -f "$apk" ]] || fail "missing $apk (run without --no-build)"
        say "installing $(basename "$apk")"
        # -r keeps the app's data (setup, history), -d allows reinstalling an older build, -t test-only APKs.
        adb_s install -r -d -t "$apk" | tail -n 1
    done
}

grant_permissions() {
    local permission
    for permission in "${PERMISSIONS[@]}"; do
        if sh_s pm grant "$APP_ID" "$permission" 2>&1 | grep -q .; then
            say "could not grant $permission (grant it in the app's Setup)"
        else
            say "granted $permission"
        fi
    done
    # Sideloaded apps on Android 13+ may not open "Restricted settings" (accessibility) until this is allowed.
    sh_s appops set "$APP_ID" ACCESS_RESTRICTED_SETTINGS allow
    say "restricted settings: $(sh_s appops get "$APP_ID" ACCESS_RESTRICTED_SETTINGS)"
}

service_bound() {
    # The service of this application id runs in a process (the bound-services line of `dumpsys accessibility` shows
    # only labels, which the debug and release builds share) and the system has not marked it crashed (after the
    # app was stopped it stays listed, and marked crashed, until the setting changes). Output is captured first:
    # `grep -q` closing a pipe early would fail it under pipefail.
    local services accessibility
    services="$(sh_s dumpsys activity services "$APP_ID")"
    accessibility="$(sh_s dumpsys accessibility)"
    [[ "$services" == *"ServiceRecord{"*"$SERVICE"* && "$services" == *"app=ProcessRecord{"* ]] || return 1
    [[ ! "$accessibility" =~ Crashed\ services:[^$'\n']*$APP_ID/ ]]
}

enable_service() {
    sh_s settings put secure accessibility_enabled 1
    if service_bound; then
        say "accessibility service already on and bound"
        return
    fi
    local current others
    current="$(sh_s settings get secure enabled_accessibility_services)"
    [[ "$current" == "null" ]] && current=""
    # The other services the user has on, without ours.
    others="$(tr ':' '\n' <<<"$current" | grep -v -x -F "$SERVICE" | grep -v '^$' | paste -sd: || true)"
    if [[ ":$current:" == *":$SERVICE:"* ]]; then
        # Listed but not bound (the app was stopped): writing the same value changes nothing, so take it off first.
        if [[ -n "$others" ]]; then
            sh_s settings put secure enabled_accessibility_services "$others"
        else
            sh_s settings delete secure enabled_accessibility_services >/dev/null
        fi
    fi
    sh_s settings put secure enabled_accessibility_services "${others:+$others:}$SERVICE"
    local waited=0
    until service_bound; do
        ((waited++ < 20)) || { say "warning: the service is enabled but not bound yet (see the app's Home card)"; return; }
        sleep 0.5
    done
    say "accessibility service on and bound"
}

use_clip_microphone() {
    adb_s shell "run-as $APP_ID sh -c 'mkdir -p no_backup/debug && echo on > no_backup/debug/clip-microphone'"
    say "clip microphone on (debug builds only)"
}

start_app() {
    sh_s am start -n "$ACTIVITY" | grep -v '^Starting' || true
    local pid="" waited=0
    until pid="$(sh_s pidof "$APP_ID")" && [[ -n "$pid" ]]; do
        ((waited++ < 20)) || fail "the app did not start"
        sleep 0.5
    done
    say "app running, pid $pid"
    APP_PID="$pid"
}

tail_log() {
    say "following the app's log (pid $APP_PID)$([[ $TAIL_SECONDS -gt 0 ]] && echo " for $TAIL_SECONDS s" || echo ", Ctrl-C to stop")"
    if [[ $TAIL_SECONDS -gt 0 ]]; then
        timeout "$TAIL_SECONDS" "$ADB_BIN" -s "$SERIAL" logcat -v time --pid="$APP_PID" -T 1 || true
    else
        adb_s logcat -v time --pid="$APP_PID" -T 1
    fi
}

connect_device
((BUILD)) && build
install_apks
grant_permissions
enable_service
((CLIP)) && use_clip_microphone
start_app
((TAIL)) && tail_log
say "done"
