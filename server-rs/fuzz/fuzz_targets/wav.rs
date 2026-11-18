//! Our own WAV parser: never a panic, never more frames than allowed.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(pcm) = telltype_speech::parse_wav(data, 16_000 * 30) {
        assert!(pcm.frames() <= 16_000 * 30 + 1);
    }
});
