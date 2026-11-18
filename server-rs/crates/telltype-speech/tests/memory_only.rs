#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! Audio never touches disk: uploads are decoded and recognised from memory only.
//!
//! Two checks. Behaviour: transcriptions of WAV, Ogg Opus, `WebM` Opus and FLAC run with the working
//! directory set to an empty directory, which stays empty. Code: the speech and
//! HTTP crates' sources contain no file-creating or process-spawning call, except the two that write
//! no audio (the hot-word vocabulary derived from `tokens.txt`, and the key and certificate files).

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use telltype_speech::testing::{FakeRecognizer, wav_tone};
use telltype_speech::{EngineLimits, SpeechEngine};

#[tokio::test]
async fn transcriptions_create_no_files() {
    let scratch = std::env::temp_dir().join(format!("telltype-memory-only-{}", std::process::id()));
    std::fs::create_dir_all(&scratch).unwrap();
    // This binary's other test only reads files, so changing the working directory races nothing.
    std::env::set_current_dir(&scratch).unwrap();

    let engine = SpeechEngine::new(EngineLimits {
        max_concurrent: 1,
        max_waiting: 0,
        max_audio_seconds: 360.0,
        recognition_timeout: Duration::from_secs(5),
        max_hotwords: Some(10),
    });
    engine.set_recognizer(Arc::new(FakeRecognizer::new("x")));
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    let uploads = [
        wav_tone(1.0, 44_100, 2),
        std::fs::read(fixtures.join("tone-440hz-1.5s.opus")).unwrap(),
        std::fs::read(fixtures.join("tone-440hz-1.5s-stereo.webm")).unwrap(),
        std::fs::read(fixtures.join("tone-440hz-1.5s-22k.flac")).unwrap(),
    ];
    for upload in uploads {
        let admission = engine.admit().unwrap();
        engine.transcribe(&admission, upload, Some("Term")).await.unwrap();
    }
    let left: Vec<_> = std::fs::read_dir(&scratch)
        .unwrap()
        .map(|e| e.unwrap().path())
        .collect();
    assert!(left.is_empty(), "files appeared: {left:?}");
    std::fs::remove_dir(&scratch).unwrap();
}

const FORBIDDEN: [&str; 7] = [
    "File::create",
    "OpenOptions",
    "fs::write",
    "tempfile",
    "Command::new",
    "temp_dir", // also covers TMPDIR: nothing in the audio path asks for a temporary directory
    "memfd",
];

fn scan(dir: &Path, allowed: &[(&str, &str)]) {
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            if path.file_name().is_some_and(|n| n != "tests") {
                scan(&path, allowed);
            }
            continue;
        }
        let name = path.file_name().unwrap().to_str().unwrap();
        if name == "tests.rs" || name == "testing.rs" {
            continue;
        }
        let text = std::fs::read_to_string(&path).unwrap();
        // Only the code above any `#[cfg(test)]` module counts.
        let code = text.split("#[cfg(test)]").next().unwrap();
        for word in FORBIDDEN {
            assert!(
                !code.contains(word) || allowed.contains(&(name, word)),
                "{} uses {word}",
                path.display()
            );
        }
    }
}

#[test]
fn no_file_writes_or_subprocesses_in_the_audio_path() {
    let speech = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    // recognizer.rs writes bpe.vocab, derived from the model's tokens.txt (no audio), at start.
    scan(&speech, &[("recognizer.rs", "fs::write")]);
    let http = Path::new(env!("CARGO_MANIFEST_DIR")).join("../telltype-http/src");
    // credentials.rs writes the bearer key and the certificate.
    scan(&http, &[("credentials.rs", "OpenOptions")]);
}
