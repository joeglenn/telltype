#!/usr/bin/env python3
"""Generate the speech clips the feature tests (package V1) play through the debug build's clip microphone.

    ~/telltype/eval/.venv/bin/python android/scripts/make-feature-clips.py \
        [--tts-dir ~/models/dictation-eval/sherpa-onnx-supertonic-3-tts-int8-2026-05-11] \
        [--check-url https://127.0.0.1:8170 --api-key-file ~/.config/telltype/gate.key \
         --cafile ~/.config/telltype/tls/cert.pem]

Each clip in CLIPS is spoken with the offline Supertonic voice the evaluation uses, resampled to 16 kHz mono 16-bit,
trimmed and written to android/app/src/debug/assets/sample/<name>.wav (debug builds only; the release APK has no
clip). The sentences are synthetic and neutral, not anyone's dictation. A test selects a clip by writing its name into
the marker file no_backup/debug/clip-microphone (ClipMicrophone). With --check-url each clip is sent once through the
gate's speech-to-text and the transcript is printed (the key is read from the file and never printed).
"""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parent.parent
OUTPUT_DIR = ANDROID_DIR / "app" / "src" / "debug" / "assets" / "sample"
SPEAKER = 0

# Clip name -> sentence. Names are the marker file's content in the connected tests; keep both in step.
CLIPS = {
    # F1: the same sentence in two tones (formal for email, very casual for personal chat).
    "tone": "yeah so I'm gonna send you the report tomorrow morning okay",
    # F2: a dictionary term the speech model cannot know.
    "dictionary": "I pushed the fix to the Dagster pipeline.",
    # Snippets: a whole-utterance trigger.
    "snippet": "My address.",
    # C1: ten standard Command Mode instructions.
    "command-list": "Make this a bulleted list.",
    "command-shorter": "Make it shorter.",
    "command-polite": "Make it more polite.",
    "command-formal": "Make it more formal.",
    "command-grammar": "Fix the spelling and grammar.",
    "command-casual": "Make it more casual.",
    "command-question": "Turn this into a question.",
    "command-capitals": "Write it in all capital letters.",
    "command-greeting": "Add a friendly greeting at the start.",
    "command-last": "Remove the last sentence.",
    # README and site screenshots: friendly dictations into a chat, an email and a browser search.
    "chat-late": "Hey, um, I'm running about ten minutes late, so uh, save me a seat.",
    "chat-dinner": "Sounds great, let's do dinner on Thursday at seven.",
    "email-draft": "Thanks for the notes from today. I'll send the updated draft on Friday.",
    "search-hikes": "easy hiking trails near the lake that allow dogs",
}


def _selftest_module():
    """The self-test sample generator: its resampling, trimming, WAV writer and gate check are reused."""
    path = ANDROID_DIR / "scripts" / "make-selftest-sample.py"
    spec = importlib.util.spec_from_file_location("make_selftest_sample", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    selftest = _selftest_module()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tts-dir", type=Path, default=selftest.DEFAULT_TTS)
    parser.add_argument("--check-url")
    parser.add_argument("--api-key-file", type=Path)
    parser.add_argument("--cafile", type=Path)
    args = parser.parse_args()

    import sherpa_onnx  # the eval environment's optional 'tts' group

    tts_dir = args.tts_dir.expanduser()
    files = {key: str(tts_dir / name) for key, name in selftest.SUPERTONIC_FILES.items()}
    model = sherpa_onnx.OfflineTtsSupertonicModelConfig(**files)
    tts = sherpa_onnx.OfflineTts(
        sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(supertonic=model, num_threads=4))
    )
    config = sherpa_onnx.GenerationConfig()
    config.sid = SPEAKER
    config.speed = 1.0
    rate = selftest.TARGET_RATE
    for name, sentence in CLIPS.items():
        audio = tts.generate(sentence, config)
        samples = selftest.trim(selftest.resample(list(audio.samples), audio.sample_rate, rate), rate)
        path = OUTPUT_DIR / f"{name}.wav"
        selftest.write_wav(path, samples, rate)
        line = f"{name}: {len(samples) / rate:.2f} s, {path.stat().st_size} bytes; spoken: {sentence}"
        if args.check_url:
            line += f"\n    gate transcript: {selftest.check(path, args.check_url, args.api_key_file, args.cafile)}"
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
