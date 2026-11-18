//! The speech engine: readiness, admission limits, decoding and recognition on worker threads.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use tokio::sync::Semaphore;

use crate::decode::decode_audio;
use crate::error::{RecognizeError, SpeechError};
use crate::hotwords::parse_hotwords;
use crate::recognizer::Recognizer;

/// Limits of the engine, from the `[speech]` configuration.
#[derive(Debug, Clone)]
pub struct EngineLimits {
    /// Recognitions running at once (each uses `threads` CPU threads).
    pub max_concurrent: usize,
    /// Requests allowed to wait for a slot; above `max_concurrent + max_waiting` requests get `Busy`.
    pub max_waiting: usize,
    pub max_audio_seconds: f64,
    pub recognition_timeout: Duration,
    /// `Some(n)`: the prompt's first `n` terms become hot words. `None`: the prompt is ignored.
    pub max_hotwords: Option<usize>,
}

/// Stage timings in milliseconds, as reported in `Server-Timing`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Timings {
    pub decode_ms: f64,
    pub queue_ms: f64,
    pub stt_ms: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Transcript {
    pub text: String,
    pub duration_s: f64,
    pub timings: Timings,
}

/// Holds one place among the requests in flight; released on drop.
#[derive(Debug)]
pub struct Admission {
    counter: Arc<AtomicUsize>,
}

impl Drop for Admission {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::AcqRel);
    }
}

pub struct SpeechEngine {
    recognizer: OnceLock<Arc<dyn Recognizer>>,
    slots: Arc<Semaphore>,
    in_flight: Arc<AtomicUsize>,
    limits: EngineLimits,
}

impl SpeechEngine {
    #[must_use]
    pub fn new(limits: EngineLimits) -> Self {
        Self {
            recognizer: OnceLock::new(),
            slots: Arc::new(Semaphore::new(limits.max_concurrent)),
            in_flight: Arc::new(AtomicUsize::new(0)),
            limits,
        }
    }

    /// Make the engine ready. Later calls are ignored (the first recogniser stays).
    pub fn set_recognizer(&self, recognizer: Arc<dyn Recognizer>) {
        let _already_set = self.recognizer.set(recognizer);
    }

    #[must_use]
    pub fn ready(&self) -> bool {
        self.recognizer.get().is_some()
    }

    #[must_use]
    pub fn in_flight(&self) -> usize {
        self.in_flight.load(Ordering::Acquire)
    }

    /// Take a place among the requests in flight before the upload is read, so a busy server
    /// refuses at once. Fails with `Busy` above the limit and `NotReady` while the model loads.
    pub fn admit(&self) -> Result<Admission, SpeechError> {
        let limit = self.limits.max_concurrent + self.limits.max_waiting;
        let previous = self.in_flight.fetch_add(1, Ordering::AcqRel);
        let admission = Admission {
            counter: Arc::clone(&self.in_flight),
        };
        if previous >= limit {
            return Err(SpeechError::Busy {
                in_flight: previous,
                limit,
            });
        }
        if !self.ready() {
            return Err(SpeechError::NotReady);
        }
        Ok(admission)
    }

    /// Decode and recognise one upload. The recognition slot stays held until the worker thread
    /// really finishes, even after a timeout, so a slow recognition never oversubscribes the CPU.
    pub async fn transcribe<A: AsRef<[u8]> + Send + 'static>(
        &self,
        _admission: &Admission,
        audio: A,
        prompt: Option<&str>,
    ) -> Result<Transcript, SpeechError> {
        let recognizer = Arc::clone(self.recognizer.get().ok_or(SpeechError::NotReady)?);
        let hotwords = self
            .limits
            .max_hotwords
            .map(|n| parse_hotwords(prompt, n))
            .unwrap_or_default();

        let started = Instant::now();
        let max_seconds = self.limits.max_audio_seconds;
        let decoded = tokio::task::spawn_blocking(move || decode_audio(audio.as_ref(), max_seconds))
            .await
            .map_err(|_| RecognizeError::TaskFailed)??;
        let decode_ms = ms(started.elapsed());

        let waiting = Instant::now();
        let permit = Arc::clone(&self.slots)
            .acquire_owned()
            .await
            .map_err(|_| RecognizeError::TaskFailed)?;
        let queue_ms = ms(waiting.elapsed());

        let recognising = Instant::now();
        let samples = decoded.samples;
        let task = tokio::task::spawn_blocking(move || {
            let _slot = permit;
            recognizer.recognize(&samples, &hotwords)
        });
        let timeout = self.limits.recognition_timeout;
        let text = match tokio::time::timeout(timeout, task).await {
            Ok(joined) => joined.map_err(|_| RecognizeError::TaskFailed)??,
            Err(_) => {
                return Err(SpeechError::Timeout {
                    seconds: timeout.as_secs(),
                });
            }
        };
        let stt_ms = ms(recognising.elapsed());
        Ok(Transcript {
            text,
            duration_s: decoded.duration_s,
            timings: Timings {
                decode_ms,
                queue_ms,
                stt_ms,
            },
        })
    }
}

fn ms(elapsed: Duration) -> f64 {
    elapsed.as_secs_f64() * 1000.0
}

#[cfg(test)]
mod tests;
