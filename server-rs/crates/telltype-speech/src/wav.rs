//! A strict, allocation-bounded WAV (RIFF/WAVE) parser for uploads held in memory.
//!
//! Supports integer PCM (8, 16, 24 and 32 bit), IEEE float (32 and 64 bit) and
//! `WAVE_FORMAT_EXTENSIBLE` wrapping either. A data chunk whose declared size runs past the end of
//! the upload (a recorder that never patched the header, or the streaming sizes 0 and 0xFFFFFFFF) is
//! read up to the end. It never panics on any input (see the property tests).

const FORMAT_PCM: u16 = 1;
const FORMAT_FLOAT: u16 = 3;
const FORMAT_EXTENSIBLE: u16 = 0xFFFE;
const MAX_CHANNELS: u16 = 32;

/// Interleaved samples in `[-1, 1]`.
#[derive(Debug, Clone, PartialEq)]
pub struct Pcm {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples: Vec<f32>,
}

impl Pcm {
    #[must_use]
    pub fn frames(&self) -> usize {
        self.samples.len() / usize::from(self.channels.max(1))
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum WavError {
    #[error("not a RIFF/WAVE file")]
    NotWav,
    #[error("malformed WAV: {0}")]
    Malformed(&'static str),
    #[error("unsupported WAV encoding: {0}")]
    Unsupported(String),
    #[error("WAV audio is longer than the limit")]
    TooLong,
}

#[derive(Debug, Clone, Copy)]
struct Format {
    encoding: Encoding,
    channels: u16,
    sample_rate: u32,
    block_align: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Encoding {
    U8,
    I16,
    I24,
    I32,
    F32,
    F64,
}

impl Encoding {
    fn bytes(self) -> usize {
        match self {
            Self::U8 => 1,
            Self::I16 => 2,
            Self::I24 => 3,
            Self::I32 | Self::F32 => 4,
            Self::F64 => 8,
        }
    }
}

/// True when `data` starts like a WAV file (so other decoders are not tried).
#[must_use]
pub fn looks_like_wav(data: &[u8]) -> bool {
    data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WAVE"
}

/// Parse a whole WAV file. `max_frames` bounds the work and the allocation: a file with more frames
/// per channel is refused with [`WavError::TooLong`] before any sample is converted.
pub fn parse_wav(data: &[u8], max_frames: usize) -> Result<Pcm, WavError> {
    if !looks_like_wav(data) {
        return Err(WavError::NotWav);
    }
    let mut format: Option<Format> = None;
    let mut pos = 12;
    while pos + 8 <= data.len() {
        let id = &data[pos..pos + 4];
        let declared =
            usize::try_from(u32_at(data, pos + 4)).map_err(|_| WavError::Malformed("chunk size"))?;
        let body_start = pos + 8;
        let available = data.len() - body_start;
        if id == b"data" {
            let format = format.ok_or(WavError::Malformed("data chunk before fmt chunk"))?;
            let body = &data[body_start..body_start + declared.min(available)];
            return convert(format, body, max_frames);
        }
        if declared > available {
            return Err(WavError::Malformed("chunk runs past the end of the file"));
        }
        if id == b"fmt " {
            format = Some(parse_fmt(&data[body_start..body_start + declared])?);
        }
        // Chunks are padded to an even size.
        pos = body_start + declared + (declared & 1);
    }
    Err(WavError::Malformed("no data chunk"))
}

fn parse_fmt(body: &[u8]) -> Result<Format, WavError> {
    if body.len() < 16 {
        return Err(WavError::Malformed("fmt chunk is shorter than 16 bytes"));
    }
    let mut tag = u16_at(body, 0);
    let channels = u16_at(body, 2);
    let sample_rate = u32_at(body, 4);
    let block_align = usize::from(u16_at(body, 12));
    let bits = u16_at(body, 14);
    if tag == FORMAT_EXTENSIBLE {
        if body.len() < 40 {
            return Err(WavError::Malformed(
                "extensible fmt chunk is shorter than 40 bytes",
            ));
        }
        // The sub-format GUID starts at byte 24; its first two bytes are the real format tag.
        tag = u16_at(body, 24);
    }
    if channels == 0 || channels > MAX_CHANNELS {
        return Err(WavError::Unsupported(format!("{channels} channels")));
    }
    if !(1000..=384_000).contains(&sample_rate) {
        return Err(WavError::Unsupported(format!("sample rate {sample_rate} Hz")));
    }
    let encoding = match (tag, bits) {
        (FORMAT_PCM, 8) => Encoding::U8,
        (FORMAT_PCM, 16) => Encoding::I16,
        (FORMAT_PCM, 24) => Encoding::I24,
        (FORMAT_PCM, 32) => Encoding::I32,
        (FORMAT_FLOAT, 32) => Encoding::F32,
        (FORMAT_FLOAT, 64) => Encoding::F64,
        _ => {
            return Err(WavError::Unsupported(format!(
                "format tag {tag:#06x} with {bits} bits"
            )));
        }
    };
    if block_align != usize::from(channels) * encoding.bytes() {
        return Err(WavError::Malformed(
            "block align does not match channels and sample size",
        ));
    }
    Ok(Format {
        encoding,
        channels,
        sample_rate,
        block_align,
    })
}

fn convert(format: Format, body: &[u8], max_frames: usize) -> Result<Pcm, WavError> {
    let frames = body.len() / format.block_align;
    if frames > max_frames {
        return Err(WavError::TooLong);
    }
    let width = format.encoding.bytes();
    let usable = &body[..frames * format.block_align];
    let samples = usable
        .chunks_exact(width)
        .map(|s| sample(format.encoding, s))
        .collect();
    Ok(Pcm {
        sample_rate: format.sample_rate,
        channels: format.channels,
        samples,
    })
}

#[allow(
    clippy::cast_possible_truncation,
    reason = "f64 to f32 for samples in [-1, 1] only loses precision"
)]
fn sample(encoding: Encoding, b: &[u8]) -> f32 {
    match encoding {
        Encoding::U8 => (f32::from(b[0]) - 128.0) / 128.0,
        Encoding::I16 => f32::from(i16::from_le_bytes([b[0], b[1]])) / 32_768.0,
        Encoding::I24 => {
            let value = i32::from_le_bytes([0, b[0], b[1], b[2]]) >> 8;
            (f64::from(value) / 8_388_608.0) as f32
        }
        Encoding::I32 => (f64::from(i32::from_le_bytes([b[0], b[1], b[2], b[3]])) / 2_147_483_648.0) as f32,
        Encoding::F32 => clean(f32::from_le_bytes([b[0], b[1], b[2], b[3]])),
        Encoding::F64 => clean(f64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]) as f32),
    }
}

/// Float WAVs may carry NaN or out-of-range values; the recogniser must only see finite samples.
fn clean(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(-1.0, 1.0)
    } else {
        0.0
    }
}

fn u16_at(data: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([data[at], data[at + 1]])
}

fn u32_at(data: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]])
}

#[cfg(test)]
pub(crate) mod tests;
