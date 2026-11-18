#![allow(clippy::float_cmp, reason = "16-bit samples convert to f32 exactly")]

use proptest::prelude::*;

use super::{Pcm, WavError, looks_like_wav, parse_wav};
use crate::testing::{wav_i16, wav_raw};

const NO_LIMIT: usize = usize::MAX;

#[test]
fn parses_16_bit_mono_and_stereo() {
    let pcm = parse_wav(&wav_i16(16_000, 1, &[0, 16_384, -32_768]), NO_LIMIT).unwrap();
    assert_eq!(
        pcm,
        Pcm {
            sample_rate: 16_000,
            channels: 1,
            samples: vec![0.0, 0.5, -1.0]
        }
    );
    let stereo = parse_wav(&wav_i16(44_100, 2, &[1, 2, 3, 4]), NO_LIMIT).unwrap();
    assert_eq!((stereo.channels, stereo.frames()), (2, 2));
}

#[test]
fn parses_every_supported_encoding() {
    let u8_pcm = parse_wav(&wav_raw(1, 8000, 1, 8, &[128, 255, 0]), NO_LIMIT).unwrap();
    assert_eq!(u8_pcm.samples, [0.0, 127.0 / 128.0, -1.0]);
    let i24 = parse_wav(&wav_raw(1, 8000, 1, 24, &[0, 0, 0x40, 0, 0, 0x80]), NO_LIMIT).unwrap();
    assert_eq!(i24.samples, [0.5, -1.0]);
    let i32_pcm = parse_wav(&wav_raw(1, 8000, 1, 32, &i32::MIN.to_le_bytes()), NO_LIMIT).unwrap();
    assert_eq!(i32_pcm.samples, [-1.0]);
    let mut f32_data = 0.25f32.to_le_bytes().to_vec();
    f32_data.extend_from_slice(&f32::NAN.to_le_bytes());
    f32_data.extend_from_slice(&7.0f32.to_le_bytes());
    assert_eq!(
        parse_wav(&wav_raw(3, 8000, 1, 32, &f32_data), NO_LIMIT)
            .unwrap()
            .samples,
        [0.25, 0.0, 1.0]
    );
    let f64_pcm = parse_wav(&wav_raw(3, 8000, 1, 64, &(-0.5f64).to_le_bytes()), NO_LIMIT).unwrap();
    assert_eq!(f64_pcm.samples, [-0.5]);
}

#[test]
fn parses_extensible_format_and_skips_other_chunks() {
    let mut file = b"RIFF\0\0\0\0WAVE".to_vec();
    file.extend_from_slice(b"LIST\x03\0\0\0abc\0"); // odd size, padded
    file.extend_from_slice(b"fmt \x28\0\0\0");
    file.extend_from_slice(&0xFFFEu16.to_le_bytes());
    file.extend_from_slice(&1u16.to_le_bytes());
    file.extend_from_slice(&16_000u32.to_le_bytes());
    file.extend_from_slice(&32_000u32.to_le_bytes());
    file.extend_from_slice(&2u16.to_le_bytes());
    file.extend_from_slice(&16u16.to_le_bytes());
    file.extend_from_slice(&22u16.to_le_bytes()); // cbSize
    file.extend_from_slice(&16u16.to_le_bytes()); // valid bits
    file.extend_from_slice(&4u32.to_le_bytes()); // channel mask
    file.extend_from_slice(&1u16.to_le_bytes()); // sub-format PCM ...
    file.extend_from_slice(&[0u8; 14]); // ... rest of the GUID
    file.extend_from_slice(b"data\xff\xff\xff\xff"); // streaming size: read to the end
    file.extend_from_slice(&16_384i16.to_le_bytes());
    file.push(7); // trailing partial sample is ignored
    let pcm = parse_wav(&file, NO_LIMIT).unwrap();
    assert_eq!(pcm.samples, [0.5]);
}

#[test]
fn refuses_what_it_cannot_read() {
    assert_eq!(parse_wav(b"OggS", NO_LIMIT), Err(WavError::NotWav));
    assert!(!looks_like_wav(b"RIFF\0\0\0\0AVI "));
    let only_header = b"RIFF\0\0\0\0WAVE".to_vec();
    assert_eq!(
        parse_wav(&only_header, NO_LIMIT),
        Err(WavError::Malformed("no data chunk"))
    );
    let mut data_first = only_header.clone();
    data_first.extend_from_slice(b"data\0\0\0\0");
    assert_eq!(
        parse_wav(&data_first, NO_LIMIT),
        Err(WavError::Malformed("data chunk before fmt chunk"))
    );
    let mut short_fmt = only_header.clone();
    short_fmt.extend_from_slice(b"fmt \x02\0\0\0\x01\0");
    assert!(matches!(
        parse_wav(&short_fmt, NO_LIMIT),
        Err(WavError::Malformed(_))
    ));
    let mut overrun = only_header;
    overrun.extend_from_slice(b"fmt \x10\0\0\0\x01\0");
    assert!(matches!(
        parse_wav(&overrun, NO_LIMIT),
        Err(WavError::Malformed(_))
    ));
    assert!(matches!(
        parse_wav(&wav_raw(2, 8000, 1, 16, &[0, 0]), NO_LIMIT),
        Err(WavError::Unsupported(_))
    ));
    assert!(matches!(
        parse_wav(&wav_raw(1, 8000, 0, 16, &[]), NO_LIMIT),
        Err(WavError::Unsupported(_))
    ));
    assert!(matches!(
        parse_wav(&wav_raw(1, 10, 1, 16, &[0, 0]), NO_LIMIT),
        Err(WavError::Unsupported(_))
    ));
    let mut bad_align = wav_raw(1, 8000, 1, 16, &[0, 0]);
    bad_align[32] = 3;
    assert!(matches!(
        parse_wav(&bad_align, NO_LIMIT),
        Err(WavError::Malformed(_))
    ));
}

#[test]
fn frame_limit_is_checked_before_conversion() {
    let wav = wav_i16(8000, 1, &[0; 100]);
    assert_eq!(parse_wav(&wav, 99), Err(WavError::TooLong));
    assert_eq!(parse_wav(&wav, 100).unwrap().frames(), 100);
}

proptest! {
    #[test]
    fn any_bytes_never_panic(data in proptest::collection::vec(any::<u8>(), 0..512)) {
        let _ = parse_wav(&data, 10_000);
        let mut riff = b"RIFF\0\0\0\0WAVE".to_vec();
        riff.extend_from_slice(&data);
        let _ = parse_wav(&riff, 10_000);
    }

    #[test]
    fn corrupting_a_valid_header_never_panics(
        samples in proptest::collection::vec(any::<i16>(), 0..64),
        at in 0usize..44,
        byte in any::<u8>(),
    ) {
        let mut wav = wav_i16(16_000, 1, &samples);
        wav[at] = byte;
        let _ = parse_wav(&wav, 10_000);
    }

    #[test]
    fn written_samples_read_back_exactly(
        channels in 1u16..=4,
        rate in 8000u32..=48_000,
        frames in proptest::collection::vec(any::<i16>(), 0..256),
    ) {
        let samples: Vec<i16> = frames.iter().flat_map(|s| std::iter::repeat_n(*s, usize::from(channels))).collect();
        let pcm = parse_wav(&wav_i16(rate, channels, &samples), NO_LIMIT).unwrap();
        prop_assert_eq!(pcm.sample_rate, rate);
        prop_assert_eq!(pcm.channels, channels);
        prop_assert_eq!(pcm.frames(), frames.len());
        for (got, want) in pcm.samples.iter().zip(&samples) {
            prop_assert_eq!(*got, f32::from(*want) / 32_768.0);
        }
    }
}
