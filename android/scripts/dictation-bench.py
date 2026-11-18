#!/usr/bin/env python3
"""Measure the debug app on a device with adb only (no instrumentation, so LeakCanary stays active).

    android/scripts/dictation-bench.py <serial> dictate  [--count 20] [--listen 8.5] [--out FILE.jsonl]
    android/scripts/dictation-bench.py <serial> reconnect [--cycles 10]
    android/scripts/dictation-bench.py <serial> history  [--last 20]
    android/scripts/dictation-bench.py <serial> meminfo
    android/scripts/dictation-bench.py <serial> coldstart [--runs 10] [--package APP_ID]
    android/scripts/dictation-bench.py <serial> leaks

Needs: the app installed and set up (deploy.sh, then Setup), the test host installed, and on an emulator the
clip microphone (deploy.sh --clip-microphone). `dictate` and `reconnect` read the coordinator's transitions from
logcat, so switch on Settings > Advanced > Verbose debug logging first (debug lines only; never dictated text from
this script). Never runs `uiautomator dump`: a UiAutomation connection suppresses accessibility services, which
unbinds the app's service and removes the bubble. The bubble is found through `dumpsys window` instead.
`history` prints only timings and statuses, never the text of the entries.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

APP_ID = "net.jbglenn.telltype.debug"
HOST_ID = "net.jbglenn.telltype.testhost"
SERVICE = f"{APP_ID}/net.jbglenn.telltype.platform.service.TelltypeAccessibilityService"
BUBBLE_WINDOW = "Telltype bubble}"
FIELD_XY = (540, 400)  # the test host's "Field under test" on a 1080 x 2400 screen
FINISH_INSET = 81  # px from the window's right and bottom edge to the centre of ✓ (and of the round bubble)
STAGES = ["recording_ms", "finalising_ms", "transcription_ms", "cleanup_ms", "insertion_ms", "end_to_end_ms"]


def adb_path() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    sdk = os.environ.get("ANDROID_HOME") or str(Path.home() / "android-sdk")
    candidate = Path(sdk) / "platform-tools" / "adb"
    return str(candidate) if candidate.exists() else "adb"


class Device:
    def __init__(self, serial: str) -> None:
        self.base = [adb_path(), "-s", serial]

    def run(self, *args: str, timeout: float = 60) -> str:
        return subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=timeout).stdout

    def raw(self, *args: str, timeout: float = 60) -> bytes:
        return subprocess.run(self.base + list(args), capture_output=True, timeout=timeout).stdout

    def sh(self, *args: str, timeout: float = 60) -> str:
        return self.run("shell", *args, timeout=timeout).replace("\r", "")

    def device_time(self) -> str:
        # One quoted argument: adb joins the arguments with spaces for the device's shell.
        stamp = self.sh("date", "'+%m-%d %H:%M:%S.000'").strip()
        if not re.fullmatch(r"\d\d-\d\d \d\d:\d\d:\d\d\.000", stamp):
            raise RuntimeError(f"unexpected device time {stamp!r}")
        return stamp

    def logcat_since(self, since: str, *filters: str) -> list[str]:
        return self.run("logcat", "-d", "-v", "time", "-T", since, *filters).splitlines()

    def bubble_frame(self) -> tuple[int, int, int, int] | None:
        windows = self.sh("dumpsys", "window", "windows")
        at = windows.find(BUBBLE_WINDOW)
        if at < 0:
            return None
        match = re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", windows[at : at + 4000])
        return tuple(map(int, match.groups())) if match else None  # type: ignore[return-value]

    def wait_for(self, check, limit: float, poll: float = 0.25):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            time.sleep(poll)
        return None

    def tap(self, x: int, y: int) -> None:
        self.sh("input", "tap", str(x), str(y))


def stamp(line: str) -> float:
    """Seconds within the day of a `logcat -v time` line."""
    match = re.match(r"\d\d-\d\d (\d\d):(\d\d):(\d\d\.\d+)", line)
    if not match:
        raise ValueError(line)
    h, m, s = match.groups()
    return int(h) * 3600 + int(m) * 60 + float(s)


def open_host_field(device: Device) -> tuple[int, int, int, int] | None:
    device.sh("am", "start", "-W", "-n", f"{HOST_ID}/.ViewFieldActivity", "--es", "field_type", "plain", "-f", "0x10008000")
    time.sleep(0.8)
    device.tap(*FIELD_XY)
    return device.wait_for(device.bubble_frame, 15)


def dictate_once(device: Device, listen: float) -> dict:
    since = device.device_time()
    frame = open_host_field(device)
    if not frame:
        return {"ok": False, "why": "no bubble"}
    device.tap((frame[0] + frame[2]) // 2, (frame[1] + frame[3]) // 2)
    pill = device.wait_for(lambda: (f := device.bubble_frame()) and f[2] - f[0] > (frame[2] - frame[0]) * 2 and f, 5)
    if not pill:
        return {"ok": False, "why": "did not start listening"}
    time.sleep(listen)
    pill = device.bubble_frame() or pill
    device.tap(pill[2] - FINISH_INSET, pill[3] - FINISH_INSET)

    def finished() -> list[str] | None:
        lines = device.logcat_since(since, "-s", "SessionCoordinator:D")
        done = [l for l in lines if "Inserted:" in l or "-> Failed" in l or "-> Done" in l]
        return lines if done else None

    lines = device.wait_for(finished, 60, poll=0.5)
    if not lines:
        return {"ok": False, "why": "no result within 60 s"}
    tapped = next((stamp(l) for l in lines if "FinishTapped:" in l), None)
    inserted = next((stamp(l) for l in lines if "Inserted:" in l), None)
    result = {"ok": inserted is not None, "why": "" if inserted else "not inserted"}
    if tapped is not None and inserted is not None:
        result["tap_to_text_ms"] = round((inserted - tapped) * 1000)
    return result


def cmd_dictate(device: Device, args: argparse.Namespace) -> int:
    results = []
    for index in range(1, args.count + 1):
        result = dictate_once(device, args.listen) | {"run": index}
        results.append(result)
        print(json.dumps(result), flush=True)
        if args.out:
            with open(args.out, "a", encoding="utf-8") as out:
                out.write(json.dumps(result) + "\n")
        time.sleep(1.5)
    ok = [r for r in results if r["ok"]]
    taps = sorted(r["tap_to_text_ms"] for r in ok if "tap_to_text_ms" in r)
    print(f"{len(ok)} of {len(results)} dictations typed; tap ✓ to Inserted (logcat): {summary(taps)}")
    return 0 if len(ok) == len(results) else 1


def cmd_reconnect(device: Device, args: argparse.Namespace) -> int:
    if not open_host_field(device):
        print("no bubble before the first cycle")
        return 1
    passed = 0
    for cycle in range(1, args.cycles + 1):
        device.sh("settings", "delete", "secure", "enabled_accessibility_services")
        gone = device.wait_for(lambda: device.bubble_frame() is None, 15)
        device.sh("settings", "put", "secure", "enabled_accessibility_services", SERVICE)
        device.wait_for(lambda: "Telltype" in device.sh("dumpsys", "accessibility").split("Bound services:")[1][:200], 15)
        back = open_host_field(device)
        ok = bool(gone) and back is not None
        passed += ok
        print(json.dumps({"cycle": cycle, "bubble_removed": bool(gone), "bubble_back": back is not None}), flush=True)
    print(f"{passed} of {args.cycles} disconnect/reconnect cycles removed and restored the bubble")
    return 0 if passed == args.cycles else 1


def pull_database(device: Device, into: Path) -> Path:
    for suffix in ("", "-wal", "-shm"):
        data = device.raw("exec-out", "run-as", APP_ID, "cat", f"databases/telltype.db{suffix}")
        if data and not data.startswith(b"cat:"):
            (into / f"telltype.db{suffix}").write_bytes(data)
    return into / "telltype.db"


def millis(value) -> float | None:
    """A kotlinx.serialization Duration (ISO-8601 string such as 'PT0.293S') or a number of ms."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    match = re.fullmatch(r"-?PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", str(value))
    if not match:
        return None
    h, m, s = match.groups()
    return (int(h or 0) * 3600 + int(m or 0) * 60 + float(s or 0)) * 1000


def summary(values: list[float]) -> str:
    if not values:
        return "no values"
    ordered = sorted(values)
    p95 = ordered[min(len(ordered) - 1, max(0, round(0.95 * len(ordered)) - 1))]
    return f"n={len(ordered)} median {statistics.median(ordered):.0f} ms, p95 {p95:.0f} ms, max {ordered[-1]:.0f} ms"


def cmd_history(device: Device, args: argparse.Namespace) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        db = sqlite3.connect(pull_database(device, Path(tmp)))
        rows = db.execute(
            "SELECT created_at, app_package, cleanup_status, inserted_via, error_code, timings "
            "FROM history ORDER BY created_at DESC LIMIT ?",
            (args.last,),
        ).fetchall()
        db.close()
    print(f"{len(rows)} newest History entries (text not read)")
    per_stage: dict[str, list[float]] = {stage: [] for stage in STAGES}
    server: dict[str, list[float]] = {}
    statuses: dict[str, int] = {}
    for _created, app, cleanup, via, error, timings in rows:
        key = f"{app} cleanup={cleanup} via={via} error={error}"
        statuses[key] = statuses.get(key, 0) + 1
        parsed = json.loads(timings)
        for stage in STAGES:
            value = millis(parsed.get(stage))
            if value is not None:
                per_stage[stage].append(value)
        seen: set[str] = set()
        for part in parsed.get("server", []):
            value = millis(next((v for k, v in part.items() if k != "name"), None))
            name = part.get("name", "?")
            # The gate reports "gate" once for transcription and once for cleanup, in that order.
            label = f"{name} (cleanup)" if name in seen else name
            seen.add(name)
            if value is not None:
                server.setdefault(label, []).append(value)
    for key, count in statuses.items():
        print(f"  {count} x {key}")
    print("| Stage | n | Median | p95 | Max |\n|---|---|---|---|---|")
    for name, values in list(per_stage.items()) + [(f"server {k}", v) for k, v in server.items()]:
        if values:
            ordered = sorted(values)
            p95 = ordered[min(len(ordered) - 1, max(0, round(0.95 * len(ordered)) - 1))]
            print(f"| {name} | {len(ordered)} | {statistics.median(ordered):.0f} ms | {p95:.0f} ms | {ordered[-1]:.0f} ms |")
    if args.raw_keys and rows:
        first = json.loads(rows[0][5])
        print("timings keys:", sorted(first.keys()), "server part:", (first.get("server") or [None])[0])
    return 0


def cmd_meminfo(device: Device, args: argparse.Namespace) -> int:
    text = device.sh("dumpsys", "meminfo", args.package)
    for label in ("Java Heap:", "Native Heap:", "Code:", "Graphics:", "TOTAL PSS:", "TOTAL RSS:"):
        match = re.search(re.escape(label) + r"\s+(\d+)", text)
        if match:
            print(f"{label} {int(match.group(1)) / 1024:.1f} MB")
    match = re.search(r"TOTAL\s+(\d+)\s+(\d+)", text)
    if match:
        print(f"TOTAL row: pss {int(match.group(1)) / 1024:.1f} MB")
    return 0


def cmd_coldstart(device: Device, args: argparse.Namespace) -> int:
    """Stops the app and starts its launcher activity N times. Stopping a package switches its accessibility
    service off, so the service is taken off first and put back at the end (it was on for the debug app)."""
    enabled = device.sh("settings", "get", "secure", "enabled_accessibility_services").strip()
    device.sh("settings", "delete", "secure", "enabled_accessibility_services")
    # Explicit: the debug build has a second launcher activity (LeakCanary's "Leaks").
    activity = f"{args.package}/{args.activity}"
    times = []
    for _ in range(args.runs):
        device.sh("am", "force-stop", args.package)
        time.sleep(2)
        out = device.sh("am", "start", "-W", "-n", activity)
        match = re.search(r"TotalTime: (\d+)", out)
        if match:
            times.append(int(match.group(1)))
        time.sleep(3)
    device.sh("input", "keyevent", "KEYCODE_HOME")
    if enabled and enabled != "null":
        device.sh("settings", "put", "secure", "enabled_accessibility_services", enabled)
    print(f"cold start of {activity} (am start -W TotalTime): {times}; {summary(times)}")
    return 0 if times else 1


def cmd_leaks(device: Device, args: argparse.Namespace) -> int:
    lines = device.run("logcat", "-d", "-v", "raw", "-s", "LeakCanary:D").splitlines()
    results = [i for i, l in enumerate(lines) if "HEAP ANALYSIS RESULT" in l]
    dumps = [l for l in lines if "dumping heap" in l.lower() or "Heap dump reason" in l]
    print(f"LeakCanary in logcat: {len(dumps)} heap-dump lines, {len(results)} analysis results")
    for i in results:
        block = lines[i : i + 40]
        print("\n".join(l for l in block[:3] if l))
        for l in block:
            if re.search(r"LEAKS|Signature|╰→|mContext|Leaking: YES|retained by leaking", l):
                print("   ", l.strip())
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("serial")
    sub = parser.add_subparsers(dest="command", required=True)
    d = sub.add_parser("dictate")
    d.add_argument("--count", type=int, default=20)
    d.add_argument("--listen", type=float, default=8.5, help="seconds between the start tap and ✓")
    d.add_argument("--out")
    r = sub.add_parser("reconnect")
    r.add_argument("--cycles", type=int, default=10)
    h = sub.add_parser("history")
    h.add_argument("--last", type=int, default=20)
    h.add_argument("--raw-keys", action="store_true")
    m = sub.add_parser("meminfo")
    m.add_argument("--package", default=APP_ID)
    c = sub.add_parser("coldstart")
    c.add_argument("--runs", type=int, default=10)
    c.add_argument("--package", default=APP_ID)
    c.add_argument("--activity", default="net.jbglenn.telltype.MainActivity")
    sub.add_parser("leaks")
    args = parser.parse_args()
    device = Device(args.serial)
    handlers = {
        "dictate": cmd_dictate,
        "reconnect": cmd_reconnect,
        "history": cmd_history,
        "meminfo": cmd_meminfo,
        "coldstart": cmd_coldstart,
        "leaks": cmd_leaks,
    }
    return handlers[args.command](device, args)


if __name__ == "__main__":
    sys.exit(main())
