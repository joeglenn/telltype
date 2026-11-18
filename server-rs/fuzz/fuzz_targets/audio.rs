//! The whole decode path (WAV, Ogg/Opus, WebM/Opus, FLAC, MP3 ... through Symphonia),
//! mixing and resampling, with a 30-second limit.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = telltype_speech::decode_audio(data, 30.0);
});
