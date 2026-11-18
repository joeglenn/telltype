#!/usr/bin/env python3
"""Generate the Diagnostics self-test sample bundled in every build type of the app.

    ~/telltype/eval/.venv/bin/python android/scripts/make-selftest-sample.py \
        [--tts-dir ~/models/dictation-eval/sherpa-onnx-supertonic-3-tts-int8-2026-05-11] \
        [--check-url https://127.0.0.1:8170 --api-key-file ~/.config/telltype/gate.key \
         --cafile ~/.config/telltype/tls/cert.pem]

Speaks SENTENCE with the offline Supertonic voice the evaluation uses (eval/asr_inputs.py, sherpa-onnx), resamples
it to 16 kHz mono 16-bit, trims silence and writes android/app/src/main/assets/selftest/sample.wav. The sentence is
synthetic and neutral: it is not test data and not anyone's dictation. The app's expected text is the same
sentence (SelfTestSampleClip.EXPECTED_TEXT); keep the two in step. With --check-url the file is sent once through
the gate's speech-to-text and the transcript is printed (the key is read from the file and never printed).
"""

from __future__ import annotations

import argparse
import json
import math
import ssl
import sys
import urllib.request
import uuid
import wave
from pathlib import Path

SENTENCE = "This is a short test of the dictation service."
TARGET_RATE = 16_000
MAX_BYTES = 150 * 1024
SILENCE_PAD_S = 0.25
SPEAKER = 0
ANDROID_DIR = Path(__file__).resolve().parent.parent
OUTPUT = ANDROID_DIR / "app" / "src" / "main" / "assets" / "selftest" / "sample.wav"
DEFAULT_TTS = Path.home() / "models" / "dictation-eval" / "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
SUPERTONIC_FILES = {
    "duration_predictor": "duration_predictor.int8.onnx",
    "text_encoder": "text_encoder.int8.onnx",
    "vector_estimator": "vector_estimator.int8.onnx",
    "vocoder": "vocoder.int8.onnx",
    "tts_json": "tts.json",
    "unicode_indexer": "unicode_indexer.bin",
    "voice_style": "voice.bin",
}


def synthesize(tts_dir: Path) -> tuple[list[float], int]:
    import sherpa_onnx  # the eval environment's optional 'tts' group

    model = sherpa_onnx.OfflineTtsSupertonicModelConfig(**{k: str(tts_dir / v) for k, v in SUPERTONIC_FILES.items()})
    tts = sherpa_onnx.OfflineTts(
        sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(supertonic=model, num_threads=4))
    )
    config = sherpa_onnx.GenerationConfig()
    config.sid = SPEAKER
    config.speed = 1.0
    audio = tts.generate(SENTENCE, config)
    return list(audio.samples), audio.sample_rate


def resample(samples: list[float], rate: int, target: int, taps: int = 32) -> list[float]:
    """Windowed-sinc resampling (Hann window), low-pass at the lower Nyquist frequency."""
    if rate == target:
        return samples
    ratio = target / rate
    cutoff = min(1.0, ratio)
    out_len = int(len(samples) * ratio)
    out: list[float] = []
    for n in range(out_len):
        centre = n / ratio
        first = int(math.floor(centre)) - taps + 1
        acc = 0.0
        for i in range(first, first + 2 * taps):
            if 0 <= i < len(samples):
                x = centre - i
                window = 0.5 + 0.5 * math.cos(math.pi * x / taps) if abs(x) < taps else 0.0
                sinc = cutoff if x == 0 else math.sin(math.pi * cutoff * x) / (math.pi * x)
                acc += samples[i] * sinc * window
        out.append(acc)
    return out


def trim(samples: list[float], rate: int, threshold: float = 0.01) -> list[float]:
    loud = [i for i, s in enumerate(samples) if abs(s) > threshold]
    if not loud:
        raise SystemExit("the TTS produced silence")
    pad = int(SILENCE_PAD_S * rate)
    return samples[max(0, loud[0] - pad) : loud[-1] + pad]


def write_wav(path: Path, samples: list[float], rate: int) -> None:
    peak = max(abs(s) for s in samples) or 1.0
    gain = min(1.0, 0.9 / peak)
    pcm = bytearray()
    for sample in samples:
        pcm += int(max(-1.0, min(1.0, sample * gain)) * 32767).to_bytes(2, "little", signed=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(bytes(pcm))


def check(path: Path, url: str, key_file: Path, cafile: Path) -> str:
    key = key_file.read_text(encoding="utf-8").strip()
    boundary = f"----owk-sample-{uuid.uuid4().hex}"
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="language"\r\n\r\nen\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="response_format"\r\n\r\njson\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.wav"\r\n'
        "Content-Type: audio/wav\r\n\r\n"
    ).encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        url.rstrip("/") + "/v1/audio/transcriptions",
        data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    context = ssl.create_default_context(cafile=str(cafile))
    context.check_hostname = False
    with urllib.request.urlopen(request, timeout=60, context=context) as response:
        return json.loads(response.read().decode("utf-8"))["text"].strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tts-dir", type=Path, default=DEFAULT_TTS)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--check-url")
    parser.add_argument("--api-key-file", type=Path)
    parser.add_argument("--cafile", type=Path)
    args = parser.parse_args()
    samples, rate = synthesize(args.tts_dir.expanduser())
    samples = trim(resample(samples, rate, TARGET_RATE), TARGET_RATE)
    write_wav(args.output, samples, TARGET_RATE)
    size = args.output.stat().st_size
    print(f"wrote {args.output} ({size} bytes, {len(samples) / TARGET_RATE:.2f} s, TTS rate {rate} Hz)")
    print(f"expected text: {SENTENCE}")
    if size > MAX_BYTES:
        print(f"too large: {size} > {MAX_BYTES} bytes", file=sys.stderr)
        return 1
    if args.check_url:
        print(f"gate transcript: {check(args.output, args.check_url, args.api_key_file, args.cafile)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
