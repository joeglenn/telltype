#!/usr/bin/env python3
"""Contract comparison: the same black-box checks against two servers, side by side.

    scripts/contract.py \\
        --a https://127.0.0.1:8170  --a-key <a>/gate.key --a-cert <a>/tls/cert.pem \\
        --b https://127.0.0.1:18170 --b-key <b>/gate.key --b-cert <b>/tls/cert.pem

The table's columns are named a and b; --a-name and --b-name label them.

Every request is read-only. Exactly one real chat completion goes to each server (the cleanup
check); the other chat checks are refused before any model runs. Keys are read from their files
and sent in the Authorization header only; they are never printed. Prints a table and exits 1 when
a check that should agree does not (differences marked "intended" are the ones expected between
server versions, see docs/contract.md "Comparing two servers").
"""

from __future__ import annotations

import argparse
import json
import re
import ssl
import sys
import urllib.error
import urllib.request
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SAMPLE = Path(__file__).resolve().parents[2] / "android/app/src/debug/assets/sample/phoebe.wav"


@dataclass
class Server:
    name: str
    base: str
    key: str
    context: ssl.SSLContext | None


@dataclass
class Reply:
    status: int
    headers: dict[str, str]
    body: bytes

    def json(self) -> Any:
        try:
            return json.loads(self.body)
        except ValueError:
            return None

    def code(self) -> str | None:
        body = self.json()
        if isinstance(body, dict) and isinstance(body.get("error"), dict):
            return body["error"].get("code")
        return None


def request(server: Server, method: str, path: str, *, body: bytes | None = None,
            headers: dict[str, str] | None = None, key: str | None = "default") -> Reply:
    all_headers = dict(headers or {})
    token = server.key if key == "default" else key
    if token is not None:
        all_headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(server.base + path, data=body, method=method, headers=all_headers)
    try:
        with urllib.request.urlopen(req, context=server.context, timeout=120) as resp:
            return Reply(resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read())
    except urllib.error.HTTPError as err:
        return Reply(err.code, {k.lower(): v for k, v in err.headers.items()}, err.read())


def multipart(fields: dict[str, str], file: bytes | None) -> tuple[bytes, str]:
    boundary = "contract" + uuid.uuid4().hex
    out = b""
    if file is not None:
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n"
                "Content-Type: audio/wav\r\n\r\n").encode() + file + b"\r\n"
    for name, value in fields.items():
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode()
    out += f"--{boundary}--\r\n".encode()
    return out, f"multipart/form-data; boundary={boundary}"


def upload(server: Server, fields: dict[str, str], file: bytes | None) -> Reply:
    body, content_type = multipart(fields, file)
    return request(server, "POST", "/v1/audio/transcriptions", body=body, headers={"Content-Type": content_type})


def chat(server: Server, payload: object) -> Reply:
    raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    return request(server, "POST", "/v1/chat/completions", body=raw, headers={"Content-Type": "application/json"})


def timing_names(reply: Reply) -> str:
    return ",".join(e.split(";")[0].strip() for e in reply.headers.get("server-timing", "").split(",") if e.strip())


def status_code(reply: Reply) -> str:
    return f"{reply.status} {reply.code() or ''}".strip()


WAV = SAMPLE.read_bytes()
USER = [{"role": "user", "content": "hi"}]


def checks() -> list[tuple[str, str | None, Callable[[Server], object]]]:
    """(name, intended difference or None, function(server) -> comparable value)."""
    def health_shape(s: Server) -> str:
        r = request(s, "GET", "/healthz")
        body = r.json() if isinstance(r.json(), dict) else {}
        shape = {k: sorted(v) if isinstance(v, dict) else type(v).__name__
                 for k, v in body.items() if k in ("status", "stt", "cleanup", "assistant")}
        return f"{r.status} status={body.get('status')} {json.dumps(shape, sort_keys=True)}"

    def request_id(s: Server) -> str:
        good = request(s, "GET", "/healthz", headers={"X-Request-Id": "contract-42"}).headers.get("x-request-id")
        bad = request(s, "GET", "/healthz", headers={"X-Request-Id": "bad id"}).headers.get("x-request-id", "")
        return f"echo={good} replaced={bool(re.fullmatch('[0-9a-f]{32}', bad))}"

    def transcription(s: Server) -> str:
        r = upload(s, {"model": "whisper-1", "language": "en"}, WAV)
        body = r.json() or {}
        return f"{r.status} {json.dumps(body, sort_keys=True)}"

    def cleanup(s: Server) -> str:
        r = chat(s, {"model": "cleanup", "temperature": 0, "max_tokens": 32, "messages": [
            {"role": "system", "content": "Repeat the user's text exactly."},
            {"role": "user", "content": "Six pm."}]})
        body = r.json() or {}
        has_content = bool(body.get("choices", [{}])[0].get("message", {}).get("content"))
        return f"{r.status} object={body.get('object')} content={has_content} timing={timing_names(r)}"

    too_big = b"x" * (26 * 1024 * 1024 + 1)
    return [
        ("healthz without key", None, lambda s: f"{status_code(request(s, 'GET', '/healthz', key=None))} "
                                              f"www-auth={request(s, 'GET', '/healthz', key=None).headers.get('www-authenticate')}"),
        ("healthz wrong key", None, lambda s: status_code(request(s, "GET", "/healthz", key="wrong-key"))),
        ("unknown route without key", None, lambda s: status_code(request(s, "GET", "/nope", key=None))),
        ("healthz shape", None, health_shape),
        ("healthz Server-Timing", None, lambda s: timing_names(request(s, "GET", "/healthz"))),
        ("X-Request-Id", None, request_id),
        ("unknown route", None, lambda s: status_code(request(s, "GET", "/nope"))),
        ("wrong method", None, lambda s: status_code(request(s, "GET", "/v1/chat/completions"))),
        ("transcription phoebe.wav", None, transcription),
        ("transcription Server-Timing", None,
         lambda s: timing_names(upload(s, {}, WAV))),
        ("verbose_json", None, lambda s: sorted((upload(s, {"response_format": "verbose_json"}, WAV).json() or {}).keys())),
        ("response_format srt", None, lambda s: status_code(upload(s, {"response_format": "srt"}, WAV))),
        ("language fr", None, lambda s: status_code(upload(s, {"language": "fr"}, WAV))),
        ("missing file", None, lambda s: status_code(upload(s, {"language": "en"}, None))),
        ("not multipart", None, lambda s: status_code(request(s, "POST", "/v1/audio/transcriptions", body=b"{}",
                                                              headers={"Content-Type": "application/json"}))),
        ("garbage audio", None, lambda s: status_code(upload(s, {}, b"definitely not audio"))),
        ("upload over 25 MB", None, lambda s: status_code(upload(s, {}, too_big))),
        ("chat not JSON", None, lambda s: status_code(chat(s, b"not json"))),
        ("chat unknown model", None, lambda s: status_code(chat(s, {"model": "gpt-4", "messages": USER}))),
        ("chat empty messages", None, lambda s: status_code(chat(s, {"model": "cleanup", "messages": []}))),
        ("chat over 1 MB", None, lambda s: status_code(chat(s, {"model": "cleanup", "messages": [
            {"role": "user", "content": "x" * (1024 * 1024)}]}))),
        ("chat stream:true with a bad body", "intended: streaming allowed since 0.2.0, so only messages is wrong",
         lambda s: status_code(chat(s, {"model": "cleanup", "stream": True, "messages": []}))),
        ("chat cleanup (one real request)", None, cleanup),
        ("GET /v1/models", "intended: route added in 0.2.0", lambda s: status_code(request(s, "GET", "/v1/models"))),
        ("healthz version", "intended: server version",
         lambda s: (request(s, "GET", "/healthz").json() or {}).get("version")),
    ]


def server(name: str, base: str, key_file: str, cert: str | None) -> Server:
    key = Path(key_file).expanduser().read_text(encoding="utf-8").strip()
    context = None
    if base.startswith("https://"):
        context = ssl.create_default_context(cafile=str(Path(cert).expanduser())) if cert else ssl.create_default_context()
    return Server(name, base.rstrip("/"), key, context)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for side in ("a", "b"):
        parser.add_argument(f"--{side}", required=True, help="base URL")
        parser.add_argument(f"--{side}-key", required=True, help="file holding the bearer key")
        parser.add_argument(f"--{side}-cert", help="certificate to trust (PEM)")
        parser.add_argument(f"--{side}-name", default=side)
    args = parser.parse_args()
    a = server(args.a_name, args.a, args.a_key, args.a_cert)
    b = server(args.b_name, args.b, args.b_key, args.b_cert)

    rows, failures = [], 0
    for name, intended, check in checks():
        results = []
        for target in (a, b):
            try:
                results.append(str(check(target)))
            except Exception as exc:  # report, do not stop: the table is the point
                results.append(f"error: {type(exc).__name__}: {exc}")
        same = results[0] == results[1]
        verdict = "same" if same else ("differs (" + intended + ")" if intended else "DIFFERS")
        failures += 0 if same or intended else 1
        rows.append((name, results[0], results[1], verdict))

    width = max(len(r[0]) for r in rows)
    print(f"| {'check'.ljust(width)} | {a.name} | {b.name} | verdict |")
    print(f"|{'-' * (width + 2)}|---|---|---|")
    for name, ra, rb, verdict in rows:
        print(f"| {name.ljust(width)} | {ra} | {rb} | {verdict} |")
    print(f"\n{len(rows)} checks, {sum(r[3] == 'same' for r in rows)} same, "
          f"{sum(r[3].startswith('differs (') for r in rows)} intended differences, {failures} unexpected")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
