#!/usr/bin/env python3
"""A stand-in for the server's gate in front of the real one, for the error-table tests on the emulator.

It listens with the gate's own certificate (so the app's pinned fingerprint matches) and forwards every request to
the real gate, except what it is told to break:

  --busy N        answer the first N transcription requests with 503 `not_ready` and Retry-After: 1
                  ("The server is warming up", then success on the app's retry)
  --bloat N       repeat the cleanup model's reply N times (the length guard rejects it: "cleanup skipped")
  --slow S        hold every transcription request S seconds before forwarding it (an upload in flight, for the
                  process-death tests)

With --openai-speech TRANSCRIPT it is instead a plain stock speech server (for stock-servers-test.sh): plain HTTP,
no key, no certificate, nothing forwarded; it serves only POST /v1/audio/transcriptions, answering every upload
with TRANSCRIPT (the known transcript of the clip the test plays) in OpenAI's format, and 404 for everything else
(no /v1/models, no /healthz, no gate extras such as Server-Timing or the gate's error JSON).

Usage (on the build machine; the emulator reaches it at 10.0.2.2:<port>):
  python3 android/scripts/stub_gate.py --port 8178 --busy 1
  python3 android/scripts/stub_gate.py --port 18201 --openai-speech "Well, I don't wish to see it any more"
Stop it with Ctrl-C. It never logs headers or bodies (they carry the key and dictated text).
"""

import argparse
import http.server
import json
import os
import ssl
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

TLS = Path(os.environ.get("DICTATION_CONFIG_DIR") or Path.home() / ".config/telltype") / "tls"
UPSTREAM = "https://127.0.0.1:8170"
FORWARDED = ("Authorization", "Content-Type", "X-Session-Id", "Accept")


def upstream_context() -> ssl.SSLContext:
    context = ssl.create_default_context(cafile=str(TLS / "cert.pem"))
    context.check_hostname = False
    return context


class Stub(http.server.BaseHTTPRequestHandler):
    # Keep-alive like the real gate: the app's client reuses connections and does not retry a lost one.
    protocol_version = "HTTP/1.1"
    busy_left = 0
    bloat = 1
    slow = 0.0
    lock = threading.Lock()

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002 - signature of the base class
        print(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}", flush=True)

    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        self.forward(None)

    def do_POST(self) -> None:  # noqa: N802 - http.server naming
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.startswith("/v1/audio/transcriptions"):
            with Stub.lock:
                busy = Stub.busy_left > 0
                Stub.busy_left -= 1 if busy else 0
            if Stub.slow > 0:
                time.sleep(Stub.slow)
            if busy:
                self.reply(503, json.dumps({"error": {"code": "not_ready", "message": "warming up"}}).encode(),
                           {"Content-Type": "application/json", "Retry-After": "1"})
                return
        self.forward(body)

    def forward(self, body: bytes | None) -> None:
        headers = {name: self.headers[name] for name in FORWARDED if self.headers.get(name)}
        request = urllib.request.Request(UPSTREAM + self.path, data=body, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(request, context=upstream_context(), timeout=60) as response:
                status, payload, kept = response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            status, payload, kept = error.code, error.read(), dict(error.headers)
        if self.path.startswith("/v1/chat/completions") and status == 200 and Stub.bloat > 1:
            reply = json.loads(payload)
            for choice in reply.get("choices", []):
                content = choice["message"]["content"]
                choice["message"]["content"] = " ".join([content] * Stub.bloat)
            payload = json.dumps(reply).encode()
        passed = {k: v for k, v in kept.items() if k.lower() in ("content-type", "server-timing", "retry-after")}
        self.reply(status, payload, passed)

    def reply(self, status: int, payload: bytes, headers: dict[str, str]) -> None:
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


class StockSpeech(http.server.BaseHTTPRequestHandler):
    """A stock OpenAI-style speech server: only POST /v1/audio/transcriptions, with a fixed transcript."""

    protocol_version = "HTTP/1.1"
    transcript = ""

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002 - signature of the base class
        print(f"{self.command} {self.path.split('?')[0]} -> {args[1] if len(args) > 1 else ''}", flush=True)

    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        self.answer(404, b"Not Found", "text/plain")

    def do_POST(self) -> None:  # noqa: N802 - http.server naming
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.split("?")[0] != "/v1/audio/transcriptions":
            self.answer(404, b"Not Found", "text/plain")
        elif b'name="response_format"\r\n\r\ntext' in body:
            self.answer(200, StockSpeech.transcript.encode(), "text/plain; charset=utf-8")
        else:
            self.answer(200, json.dumps({"text": StockSpeech.transcript}).encode(), "application/json")

    def answer(self, status: int, payload: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8178)
    parser.add_argument("--busy", type=int, default=0)
    parser.add_argument("--bloat", type=int, default=1)
    parser.add_argument("--slow", type=float, default=0.0)
    parser.add_argument("--openai-speech", metavar="TRANSCRIPT", default=None)
    options = parser.parse_args()
    if options.openai_speech is not None:
        StockSpeech.transcript = options.openai_speech
        stock = http.server.ThreadingHTTPServer(("0.0.0.0", options.port), StockSpeech)
        print(f"stock speech server on :{options.port} (plain HTTP, transcriptions only)", flush=True)
        stock.serve_forever()
        return
    Stub.busy_left, Stub.bloat, Stub.slow = options.busy, options.bloat, options.slow
    server = http.server.ThreadingHTTPServer(("0.0.0.0", options.port), Stub)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(TLS / "cert.pem"), str(TLS / "key.pem"))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"stub gate on :{options.port}, busy={options.busy}, bloat={options.bloat}, slow={options.slow}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
