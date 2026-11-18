//! Test helpers shared with other crates' tests (feature `test-support`): a WAV writer and fake
//! recognisers. Not compiled into the server binary.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use crate::error::RecognizeError;
use crate::recognizer::Recognizer;

/// A 16-bit PCM WAV with a sine tone.
#[must_use]
pub fn wav_tone(seconds: f64, rate: u32, channels: u16) -> Vec<u8> {
    #[allow(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "test sizes"
    )]
    let frames = (seconds * f64::from(rate)).round() as usize;
    let mut samples = Vec::with_capacity(frames * usize::from(channels));
    for i in 0..frames {
        let t = i as f64 / f64::from(rate);
        #[allow(clippy::cast_possible_truncation, reason = "amplitude below i16::MAX")]
        let value = (8000.0 * (2.0 * std::f64::consts::PI * 440.0 * t).sin()) as i16;
        for _ in 0..channels {
            samples.push(value);
        }
    }
    wav_i16(rate, channels, &samples)
}

/// A 16-bit PCM WAV from interleaved samples.
#[must_use]
pub fn wav_i16(rate: u32, channels: u16, samples: &[i16]) -> Vec<u8> {
    let data: Vec<u8> = samples.iter().flat_map(|s| s.to_le_bytes()).collect();
    wav_raw(1, rate, channels, 16, &data)
}

/// A WAV with any format tag and bit depth around raw sample bytes.
#[must_use]
pub fn wav_raw(tag: u16, rate: u32, channels: u16, bits: u16, data: &[u8]) -> Vec<u8> {
    let block_align = channels * (bits / 8);
    let mut out = Vec::with_capacity(44 + data.len());
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&u32::try_from(36 + data.len()).unwrap_or(u32::MAX).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&tag.to_le_bytes());
    out.extend_from_slice(&channels.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * u32::from(block_align)).to_le_bytes());
    out.extend_from_slice(&block_align.to_le_bytes());
    out.extend_from_slice(&bits.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&u32::try_from(data.len()).unwrap_or(u32::MAX).to_le_bytes());
    out.extend_from_slice(data);
    out
}

/// Records what it was asked and answers with a fixed text, optionally after a delay or a gate.
pub struct FakeRecognizer {
    pub text: String,
    pub delay: Duration,
    pub calls: Mutex<Vec<(usize, Vec<String>)>>,
    pub running: AtomicUsize,
    gate: Option<Arc<(Mutex<bool>, Condvar)>>,
}

impl FakeRecognizer {
    #[must_use]
    pub fn new(text: &str) -> Self {
        Self {
            text: text.to_owned(),
            delay: Duration::ZERO,
            calls: Mutex::new(Vec::new()),
            running: AtomicUsize::new(0),
            gate: None,
        }
    }

    #[must_use]
    pub fn with_delay(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }

    /// Recognitions block until [`open_gate`] is called on the returned handle.
    #[must_use]
    pub fn gated(mut self) -> (Self, Arc<(Mutex<bool>, Condvar)>) {
        let gate = Arc::new((Mutex::new(false), Condvar::new()));
        self.gate = Some(Arc::clone(&gate));
        (self, gate)
    }

    #[must_use]
    pub fn calls(&self) -> Vec<(usize, Vec<String>)> {
        self.calls.lock().map(|c| c.clone()).unwrap_or_default()
    }
}

/// Let every gated recognition finish.
pub fn open_gate(gate: &Arc<(Mutex<bool>, Condvar)>) {
    let (lock, condvar) = &**gate;
    if let Ok(mut open) = lock.lock() {
        *open = true;
        condvar.notify_all();
    }
}

impl Recognizer for FakeRecognizer {
    fn recognize(&self, samples: &[f32], hotwords: &[String]) -> Result<String, RecognizeError> {
        self.running.fetch_add(1, Ordering::AcqRel);
        if let Ok(mut calls) = self.calls.lock() {
            calls.push((samples.len(), hotwords.to_vec()));
        }
        if let Some(gate) = &self.gate {
            let (lock, condvar) = &**gate;
            if let Ok(guard) = lock.lock() {
                let _open = condvar.wait_timeout_while(guard, Duration::from_secs(10), |open| !*open);
            }
        }
        std::thread::sleep(self.delay);
        self.running.fetch_sub(1, Ordering::AcqRel);
        Ok(self.text.clone())
    }
}
