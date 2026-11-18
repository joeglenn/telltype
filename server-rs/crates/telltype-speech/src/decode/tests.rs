use super::{SAMPLE_RATE, decode_audio};
use crate::error::DecodeError;
use crate::testing::{wav_i16, wav_tone};

const OPUS: &[u8] = include_bytes!("../../tests/fixtures/tone-440hz-1.5s.opus");
const WEBM: &[u8] = include_bytes!("../../tests/fixtures/tone-440hz-1.5s-stereo.webm");
const FLAC: &[u8] = include_bytes!("../../tests/fixtures/tone-440hz-1.5s-22k.flac");

fn near(value: f64, expected: f64, tolerance: f64) -> bool {
    (value - expected).abs() <= tolerance
}

#[test]
fn wav_at_16k_is_passed_through_with_duration() {
    let audio = decode_audio(&wav_tone(1.5, 16_000, 1), 360.0).unwrap();
    assert_eq!(audio.samples.len(), 24_000);
    assert!(near(audio.duration_s, 1.5, 1e-9));
}

#[test]
fn stereo_44k_wav_becomes_16k_mono() {
    let audio = decode_audio(&wav_tone(1.0, 44_100, 2), 360.0).unwrap();
    assert!(
        near(audio.samples.len() as f64, f64::from(SAMPLE_RATE), 60.0),
        "{}",
        audio.samples.len()
    );
    assert!(near(audio.duration_s, 1.0, 1e-9));
}

#[test]
fn stereo_is_averaged() {
    let audio = decode_audio(&wav_i16(16_000, 2, &[16_384, 0, -16_384, -16_384]), 360.0).unwrap();
    assert_eq!(audio.samples, [0.25, -0.5]);
}

#[test]
fn ogg_opus_webm_opus_and_flac_decode_in_memory() {
    for (name, data) in [("ogg opus", OPUS), ("webm opus", WEBM), ("flac", FLAC)] {
        let audio = decode_audio(data, 360.0).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert!(near(audio.duration_s, 1.5, 0.05), "{name}: {}", audio.duration_s);
        assert!(
            near(audio.samples.len() as f64, 24_000.0, 800.0),
            "{name}: {}",
            audio.samples.len()
        );
        let peak = audio.samples.iter().fold(0f32, |m, s| m.max(s.abs()));
        assert!(peak > 0.05, "{name}: the tone is missing (peak {peak})");
    }
}

#[test]
fn too_long_audio_is_refused() {
    let err = decode_audio(&wav_tone(2.0, 8000, 1), 1.5).unwrap_err();
    assert_eq!(
        err,
        DecodeError::TooLong {
            minutes: "0.03".to_owned()
        }
    );
    assert_eq!(err.to_string(), "audio is longer than 0.03 minutes");
    assert_eq!(
        decode_audio(OPUS, 1.0).unwrap_err().to_string(),
        "audio is longer than 0.02 minutes"
    );
    assert_eq!(
        decode_audio(&wav_tone(61.0, 1000, 1), 60.0).unwrap_err(),
        DecodeError::TooLong {
            minutes: "1".to_owned()
        }
    );
}

#[test]
fn garbage_empty_and_truncated_uploads_are_typed_errors() {
    assert!(matches!(
        decode_audio(b"definitely not audio", 360.0),
        Err(DecodeError::Unsupported(_))
    ));
    assert!(matches!(
        decode_audio(&[], 360.0),
        Err(DecodeError::Unsupported(_))
    ));
    assert_eq!(
        decode_audio(&wav_i16(16_000, 1, &[]), 360.0),
        Err(DecodeError::Empty)
    );
    // A cut-off upload either decodes up to the cut or is refused; it never panics.
    for data in [&OPUS[..OPUS.len() / 2], &FLAC[..FLAC.len() / 2]] {
        match decode_audio(data, 360.0) {
            Ok(audio) => assert!(audio.duration_s < 1.5),
            Err(e) => assert!(matches!(e, DecodeError::Unsupported(_)), "{e:?}"),
        }
    }
    assert!(matches!(
        decode_audio(&OPUS[..60], 360.0),
        Err(DecodeError::Unsupported(_) | DecodeError::Empty)
    ));
}

proptest::proptest! {
    #[test]
    fn decode_never_panics_on_corrupted_containers(at in 0usize..3463, byte in proptest::prelude::any::<u8>()) {
        let mut data = OPUS.to_vec();
        let index = at % data.len();
        data[index] = byte;
        let _ = decode_audio(&data, 360.0);
    }
}
