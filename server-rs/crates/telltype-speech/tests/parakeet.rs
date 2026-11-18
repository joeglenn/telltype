#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, reason = "test code")]

//! Slow test with the real model: Parakeet Unified EN 0.6B (int8) through sherpa-onnx transcribes the
//! app's sample clip into the expected transcript.
//!
//! Ignored by default (it loads 630 MB of model files); run it with
//!
//! ```sh
//! TELLTYPE_PARAKEET_DIR=/path/to/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming \
//!   cargo test -p telltype-speech --release --test parakeet -- --ignored --nocapture
//! ```
//!
//! `TELLTYPE_THREADS` sets the recogniser threads (default 8); `TELLTYPE_SAMPLE_WAV` overrides the clip (default: the repository's
//! `android/app/src/debug/assets/sample/phoebe.wav`).

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::Instant;

use telltype_speech::{Recognizer, SherpaOptions, SherpaRecognizer, decode_audio};

/// The expected transcript of `phoebe.wav` (7.435 s), as sherpa-onnx 2.0.1 writes it with the shared
/// libraries of its wheel package. The crate's prebuilt static and shared archives give the same words
/// without the sentence boundary ("…her eyes it is certainly…"); see README "Speech engine".
const EXPECTED_TRANSCRIPT: &str = "Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. It is certainly very like the old portrait";

fn sample() -> PathBuf {
    std::env::var_os("TELLTYPE_SAMPLE_WAV").map_or_else(
        || {
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../../android/app/src/debug/assets/sample/phoebe.wav")
        },
        PathBuf::from,
    )
}

#[test]
#[ignore = "slow: loads the real Parakeet model (set TELLTYPE_PARAKEET_DIR)"]
fn parakeet_transcribes_the_sample_as_expected() {
    let model_dir =
        PathBuf::from(std::env::var_os("TELLTYPE_PARAKEET_DIR").expect("set TELLTYPE_PARAKEET_DIR"));
    let started = Instant::now();
    let recognizer = SherpaRecognizer::load(&SherpaOptions {
        model_dir,
        threads: 8,
        hotwords: None,
    })
    .unwrap();
    println!("model loaded in {} ms", started.elapsed().as_millis());

    let wav = std::fs::read(sample()).unwrap();
    let started = Instant::now();
    let audio = decode_audio(&wav, 360.0).unwrap();
    let decoded = started.elapsed();
    let text = recognizer.recognize(&audio.samples, &[]).unwrap();
    println!(
        "wav:    {:.3} s audio, decode {} ms, total {} ms",
        audio.duration_s,
        decoded.as_millis(),
        started.elapsed().as_millis()
    );
    println!("got:      {text}");
    println!("expected: {EXPECTED_TRANSCRIPT}");
    assert!((audio.duration_s - 7.435).abs() < 1e-6, "{}", audio.duration_s);
    // The words always match. The exact text (one sentence boundary, "eyes. It" against "eyes it")
    // depends on the native build: the wheel's libsherpa-onnx gives the expected text exactly
    // (build with --no-default-features --features shared and SHERPA_ONNX_LIB_DIR pointing at the
    // wheel's sherpa_onnx/lib, then set TELLTYPE_EXPECT_EXACT=1); the crate's prebuilt static and
    // shared archives give the boundary-free variant. See README "Speech engine".
    assert_eq!(words(&text), words(EXPECTED_TRANSCRIPT));
    println!("exact match: {}", text == EXPECTED_TRANSCRIPT);
    if std::env::var_os("TELLTYPE_EXPECT_EXACT").is_some() {
        assert_eq!(text, EXPECTED_TRANSCRIPT);
    }

    // The app's other upload format: Ogg Opus, made from the same clip by ffmpeg (test only).
    if let Some(opus) = to_ogg_opus(&wav) {
        let audio = decode_audio(&opus, 360.0).unwrap();
        let text = recognizer.recognize(&audio.samples, &[]).unwrap();
        println!("opus:   {text}");
        assert_eq!(
            words(&text),
            words(EXPECTED_TRANSCRIPT),
            "Opus at 24 kbit/s should give the same words"
        );
    } else {
        println!("opus:   skipped (no ffmpeg with libopus)");
    }
}

fn words(text: &str) -> Vec<String> {
    text.split_whitespace()
        .map(|w| w.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase())
        .collect()
}

fn to_ogg_opus(wav: &[u8]) -> Option<Vec<u8>> {
    use std::io::Write;
    let mut child = Command::new("ffmpeg")
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            "pipe:0",
            "-c:a",
            "libopus",
            "-b:a",
            "24k",
            "-f",
            "ogg",
            "pipe:1",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .ok()?;
    let mut stdin = child.stdin.take()?;
    let input = wav.to_vec();
    let writer = std::thread::spawn(move || stdin.write_all(&input));
    let output = child.wait_with_output().ok()?;
    writer.join().ok()?.ok()?;
    output.status.success().then_some(output.stdout)
}
