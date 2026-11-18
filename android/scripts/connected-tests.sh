#!/usr/bin/env bash
# Runs every connected test of :app on one device against the server's gate, as one suite (package A7e):
#   1. builds and installs the debug app, its test APK and the test host (skip with --no-build);
#   2. pushes the gate key to /data/local/tmp/owk-gate.key (never printed, never on a command line);
#   3. starts the two stub gates StubGateTest needs (ports 8178 --busy 1 and 8177 --bloat 4);
#   4. clears the app and runs EndToEndTest first (the one test of the real Setup screens), clears it again for
#      DiagnosticsTest (it configures the server through Diagnostics), then every other class in one `am instrument` (each class arranges a configured app itself through DebugTestAccess), then, in its
#      own process, PermissionRowsTest#microphonePermissionMissing after revoking RECORD_AUDIO (revoking kills
#      the app's process, which hosts the tests);
#   5. ProcessDeathTest, from a cleared app each time: the process dies while listening (one run), the next run
#      checks the recovery; then the same while uploading to a third stub gate that holds transcriptions (port 8176,
#      --slow 30). A dying run passes when the process crashed as intended ("DIED"), and fails if it outlived it;
#   6. removes the key, stops the stubs (also on failure or Ctrl-C) and prints a pass/fail table.
# The certificate fingerprint (not a secret) is read from the gate's certificate and passed as an instrumentation
# argument. Usage: android/scripts/connected-tests.sh <serial> [--no-build]
# The key and certificate come from $DICTATION_CONFIG_DIR (the variable the server uses), default ~/.config/telltype;
# the stub gates read the same variable.
# Exit status: 0 when every test passed.
set -euo pipefail

serial=${1:?usage: connected-tests.sh <serial> [--no-build]}
build=yes
[[ ${2:-} == --no-build ]] && build=no
android_dir=$(cd "$(dirname "$0")/.." && pwd)
config=${DICTATION_CONFIG_DIR:-$HOME/.config/telltype}
key_file=$config/gate.key
cert_file=$config/tls/cert.pem
app_id=net.jbglenn.telltype.debug
runner=$app_id.test/androidx.test.runner.AndroidJUnitRunner
tests=net.jbglenn.telltype.e2e
device_key=/data/local/tmp/owk-gate.key
out=$(mktemp -d "${TMPDIR:-/tmp}/owk-connected.XXXXXX")
adb() { "${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb" -s "$serial" "$@"; }
say() { printf '== %s\n' "$*"; }

stubs=()
cleanup() {
    adb shell rm -f "$device_key" >/dev/null 2>&1 || true
    for pid in "${stubs[@]}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

[[ -r $key_file && -r $cert_file ]] || { echo "need $key_file and $cert_file" >&2; exit 2; }
pin=$(openssl x509 -in "$cert_file" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')

if [[ $build == yes ]]; then
    say "building"
    (cd "$android_dir" && systemd-run --user --pipe --wait --collect -q -p MemoryMax=infinity \
        --working-directory="$android_dir" -E ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}" \
        -E JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" \
        ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :testhost:assembleDebug --console=plain -q) \
        > "$out/build.log" 2>&1 || { tail -n 30 "$out/build.log"; exit 1; }
fi
say "installing"
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    testhost/build/outputs/apk/debug/testhost-debug.apk; do
    adb install -r -d -t "$android_dir/$apk" > /dev/null
done

say "starting the stub gates"
python3 "$android_dir/scripts/stub_gate.py" --port 8178 --busy 1 > "$out/stub-8178.log" 2>&1 & stubs+=($!)
python3 "$android_dir/scripts/stub_gate.py" --port 8177 --bloat 4 > "$out/stub-8177.log" 2>&1 & stubs+=($!)
python3 "$android_dir/scripts/stub_gate.py" --port 8176 --slow 30 > "$out/stub-8176.log" 2>&1 & stubs+=($!)
adb push "$key_file" "$device_key" > /dev/null 2>&1

instrument() { # <name> <am instrument -e arguments...>
    local name=$1
    shift
    say "running $name"
    adb shell am instrument -w -r -e "$@" -e owkCertSha256 "$pin" "$runner" > "$out/$name.txt" 2>&1 || true
}

adb shell pm clear "$app_id" > /dev/null
instrument 1-setup class "$tests.EndToEndTest"
# DiagnosticsTest configures the server through the Diagnostics screen, so it starts from a cleared app as well.
adb shell pm clear "$app_id" > /dev/null
instrument 2-diagnostics class "$tests.DiagnosticsTest"
instrument 3-suite package "$tests" \
    -e notClass "$tests.EndToEndTest,$tests.DiagnosticsTest,$tests.ProcessDeathTest,$tests.PermissionRowsTest#microphonePermissionMissing"
adb shell pm revoke "$app_id" android.permission.RECORD_AUDIO
instrument 4-microphone class "$tests.PermissionRowsTest#microphonePermissionMissing"
adb shell pm grant "$app_id" android.permission.RECORD_AUDIO
results=("$out"/1-setup.txt "$out"/2-diagnostics.txt "$out"/3-suite.txt "$out"/4-microphone.txt)
for point in Listening Uploading; do
    phase=$(tr 'A-Z' 'a-z' <<< "$point")
    adb shell pm clear "$app_id" > /dev/null
    instrument "5-die-$phase" class "$tests.ProcessDeathTest#dieWhile$point" -e owkProcessDeath "$phase"
    instrument "6-recover-$phase" class "$tests.ProcessDeathTest#recoverAfterDeath" -e owkProcessDeath recover
    results+=("$out/5-die-$phase.txt" "$out/6-recover-$phase.txt")
done

status=0
python3 - "${results[@]}" <<'EOF' || status=$?
import re, sys
results, failed = [], 0
for path in sys.argv[1:]:
    cls = test = None
    status = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line.strip())
        if m:
            if m.group(1) == "class":
                cls = m.group(2).rsplit(".", 1)[-1]
            else:
                test = m.group(2)
            continue
        m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line.strip())
        if m and cls and test:
            code = int(m.group(1))
            key = f"{cls}#{test}"
            if code == 1:
                status.setdefault(key, "RUN")
            elif status.get(key) != "FAIL":
                status[key] = {0: "PASS", -2: "FAIL", -1: "ERROR", -3: "IGNORED", -4: "SKIPPED"}.get(code, str(code))
    text = open(path, encoding="utf-8", errors="replace").read()
    if "/5-die-" in path:
        # The test kills its own process on purpose: it passes when the process crashed while the test ran.
        # The runner reports the crash switch's exception as the test's failure before the process dies.
        died = "Process crashed" in text and "A7g crash switch" in text
        status = {key: "DIED" if died and value in ("RUN", "FAIL") else value for key, value in status.items()}
        results += status.items() or [(path.rsplit("/", 1)[-1], "DIED" if died else "CRASHED")]
        continue
    if not status or "INSTRUMENTATION_CODE: -1" not in text:
        status[path.rsplit("/", 1)[-1]] = "CRASHED"
    results += status.items()
width = max(len(k) for k, _ in results)
for key, value in results:
    print(f"{key:<{width}}  {value}")
    failed += value not in ("PASS", "DIED")
print(f"{len(results) - failed} passed, {failed} not passed")
sys.exit(1 if failed else 0)
EOF
say "raw output in $out"
exit $status
