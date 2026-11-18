//! Speech to text for Telltype Server, entirely in memory.
//!
//! An upload is decoded from its bytes (WAV by our own parser, anything else through Symphonia,
//! Ogg/`WebM` Opus included), mixed down to mono, resampled to 16 kHz and handed to the recogniser
//! (sherpa-onnx with Parakeet). No step writes audio to a file: there is no temporary file and no
//! subprocess, and `tests/memory_only.rs` checks that.

mod decode;
mod engine;
mod error;
mod hotwords;
mod recognizer;
mod resample;
mod wav;

pub use decode::{DecodedAudio, SAMPLE_RATE, decode_audio};
pub use engine::{Admission, EngineLimits, SpeechEngine, Timings, Transcript};
pub use error::{DecodeError, LoadError, RecognizeError, SpeechError};
pub use hotwords::{bpe_vocab_from_tokens, parse_hotwords};
pub use recognizer::{Recognizer, SherpaOptions, SherpaRecognizer};
pub use wav::{Pcm, WavError, parse_wav};

#[cfg(any(test, feature = "test-support"))]
pub mod testing;
