//! From upload bytes to 16 kHz mono samples, in memory.

use std::io::Cursor;
use std::sync::LazyLock;

use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::codecs::registry::CodecRegistry;
use symphonia::core::common::Tier;
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, TrackType};
use symphonia::core::io::{MediaSourceStream, MediaSourceStreamOptions};
use symphonia::core::meta::MetadataOptions;
use symphonia_opus::OpusDecoder;

use crate::error::DecodeError;
use crate::resample::to_target_rate;
use crate::wav::{Pcm, WavError, looks_like_wav, parse_wav};

/// The recogniser's sample rate.
pub const SAMPLE_RATE: u32 = 16_000;

/// Mono samples at [`SAMPLE_RATE`] and the audio's duration in seconds.
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedAudio {
    pub samples: Vec<f32>,
    pub duration_s: f64,
}

static CODECS: LazyLock<CodecRegistry> = LazyLock::new(|| {
    let mut registry = CodecRegistry::new();
    symphonia::default::register_enabled_codecs(&mut registry);
    registry.register_audio_decoder_at_tier::<OpusDecoder>(Tier::Fallback);
    registry
});

/// Decode an upload. WAV goes through our parser; everything else through Symphonia (Ogg and `WebM`
/// Opus, FLAC, MP3, AAC/M4A, Vorbis). Audio longer than `max_seconds` is refused, and decoding stops
/// shortly after the limit, so an over-long file costs little.
pub fn decode_audio(data: &[u8], max_seconds: f64) -> Result<DecodedAudio, DecodeError> {
    let too_long = || DecodeError::TooLong {
        minutes: format_minutes(max_seconds),
    };
    let pcm = if looks_like_wav(data) {
        let max_frames = max_frames(max_seconds, 384_000);
        parse_wav(data, max_frames).map_err(|e| match e {
            WavError::TooLong => too_long(),
            other => DecodeError::Unsupported(other.to_string()),
        })?
    } else {
        decode_with_symphonia(data, max_seconds)?
    };
    if pcm.samples.is_empty() {
        return Err(DecodeError::Empty);
    }
    let duration_s = pcm.frames() as f64 / f64::from(pcm.sample_rate);
    if duration_s > max_seconds {
        return Err(too_long());
    }
    let mono = downmix(&pcm);
    let samples = to_target_rate(&mono, pcm.sample_rate, SAMPLE_RATE)?;
    if samples.is_empty() {
        return Err(DecodeError::Empty);
    }
    Ok(DecodedAudio {
        samples,
        duration_s: (duration_s * 1000.0).round() / 1000.0,
    })
}

/// Frames at `rate` that correspond to `max_seconds` plus one second of slack.
fn max_frames(max_seconds: f64, rate: u32) -> usize {
    let frames = (max_seconds + 1.0) * f64::from(rate);
    // `max_seconds` is validated to at most two hours, so this is far below usize::MAX.
    #[allow(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "bounded positive value"
    )]
    let frames = frames.ceil() as usize;
    frames
}

fn format_minutes(seconds: f64) -> String {
    let minutes = seconds / 60.0;
    if (minutes - minutes.round()).abs() < 1e-9 {
        format!("{minutes:.0}")
    } else {
        format!("{minutes:.2}")
    }
}

fn downmix(pcm: &Pcm) -> Vec<f32> {
    let channels = usize::from(pcm.channels.max(1));
    if channels == 1 {
        return pcm.samples.clone();
    }
    let scale = 1.0 / f32::from(pcm.channels);
    pcm.samples
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() * scale)
        .collect()
}

fn decode_with_symphonia(data: &[u8], max_seconds: f64) -> Result<Pcm, DecodeError> {
    let unsupported = |e: &SymphoniaError| DecodeError::Unsupported(e.to_string());
    let source = MediaSourceStream::new(
        Box::new(Cursor::new(data.to_vec())),
        MediaSourceStreamOptions::default(),
    );
    let mut format = symphonia::default::get_probe()
        .probe(
            &Hint::new(),
            source,
            FormatOptions::default(),
            MetadataOptions::default(),
        )
        .map_err(|e| unsupported(&e))?;
    let track = format
        .default_track(TrackType::Audio)
        .ok_or_else(|| DecodeError::Unsupported("no audio track".to_owned()))?;
    let track_id = track.id;
    let params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .ok_or_else(|| DecodeError::Unsupported("the audio track has no codec parameters".to_owned()))?
        .clone();
    let mut decoder = CODECS
        .make_audio_decoder(&params, &AudioDecoderOptions::default())
        .map_err(|e| unsupported(&e))?;
    read_packets(format.as_mut(), decoder.as_mut(), track_id, max_seconds)
}

fn read_packets(
    format: &mut dyn FormatReader,
    decoder: &mut dyn AudioDecoder,
    track_id: u32,
    max_seconds: f64,
) -> Result<Pcm, DecodeError> {
    let mut samples: Vec<f32> = Vec::new();
    let mut spec: Option<(u32, u16)> = None;
    let mut chunk: Vec<f32> = Vec::new();
    loop {
        let packet = match format.next_packet() {
            Ok(Some(packet)) => packet,
            Ok(None) => break,
            // A truncated upload ends where the data ends.
            Err(SymphoniaError::IoError(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(DecodeError::Unsupported(e.to_string())),
        };
        if packet.track_id != track_id {
            continue;
        }
        let buffer = match decoder.decode(&packet) {
            Ok(buffer) => buffer,
            // A corrupt packet is skipped, as players do; a stream of only corrupt packets ends empty.
            Err(SymphoniaError::DecodeError(_)) => continue,
            Err(e) => return Err(DecodeError::Unsupported(e.to_string())),
        };
        let rate = buffer.spec().rate();
        let channels = u16::try_from(buffer.spec().channels().count())
            .map_err(|_| DecodeError::Unsupported("too many channels".to_owned()))?;
        if channels == 0 {
            continue;
        }
        match spec {
            None => spec = Some((rate, channels)),
            Some(known) if known != (rate, channels) => {
                return Err(DecodeError::Unsupported(
                    "the stream changes rate or channels".to_owned(),
                ));
            }
            Some(_) => {}
        }
        chunk.clear();
        buffer.copy_to_vec_interleaved(&mut chunk);
        samples.extend_from_slice(&chunk);
        if samples.len() / usize::from(channels) > max_frames(max_seconds, rate) {
            return Err(DecodeError::TooLong {
                minutes: format_minutes(max_seconds),
            });
        }
    }
    let (sample_rate, channels) = spec.ok_or(DecodeError::Empty)?;
    for s in &mut samples {
        if !s.is_finite() {
            *s = 0.0;
        }
    }
    Ok(Pcm {
        sample_rate,
        channels,
        samples,
    })
}

#[cfg(test)]
mod tests;
