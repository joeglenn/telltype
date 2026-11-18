//! What the handlers share.

use std::sync::Arc;
use std::time::Duration;

use telltype_speech::SpeechEngine;
use telltype_upstream::Upstreams;

use crate::keys::KeyStore;
use crate::limits::Guard;

/// Request size limits, body pacing and the `Retry-After` value.
#[derive(Debug, Clone)]
pub struct HttpLimits {
    /// The `file` part of a transcription upload.
    pub max_upload_bytes: usize,
    /// Allowance for the rest of the multipart body (fields, headers, boundaries).
    pub multipart_overhead_bytes: usize,
    /// Chat request bodies.
    pub max_json_bytes: usize,
    pub retry_after_s: u64,
    /// After this grace a body must have arrived at `body_min_bytes_per_s` on average, or the
    /// request ends with 408 and the connection is closed.
    pub body_grace: Duration,
    /// 0: no minimum rate (the connection idle timeout still applies).
    pub body_min_bytes_per_s: u64,
}

impl HttpLimits {
    /// The largest body a path accepts; a larger `Content-Length` is 413 before the body is read.
    #[must_use]
    pub fn body_cap(&self, path: &str) -> usize {
        if path == "/v1/audio/transcriptions" {
            self.max_upload_bytes
                .saturating_add(self.multipart_overhead_bytes)
        } else {
            self.max_json_bytes
        }
    }
}

#[derive(Clone)]
pub struct AppState {
    /// `None`: no key is required (`auth.open = true`).
    pub keys: Option<Arc<KeyStore>>,
    pub guard: Arc<Guard>,
    pub speech: Arc<SpeechEngine>,
    pub speech_model: String,
    pub upstreams: Arc<Upstreams>,
    pub limits: HttpLimits,
}
