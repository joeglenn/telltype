//! What the handlers share.

use std::sync::Arc;

use telltype_speech::SpeechEngine;
use telltype_upstream::Upstreams;

/// Request size limits and the `Retry-After` value.
#[derive(Debug, Clone)]
pub struct HttpLimits {
    /// The `file` part of a transcription upload.
    pub max_upload_bytes: usize,
    /// Allowance for the rest of the multipart body (fields, headers, boundaries).
    pub multipart_overhead_bytes: usize,
    /// Chat request bodies.
    pub max_json_bytes: usize,
    pub retry_after_s: u64,
}

#[derive(Clone)]
pub struct AppState {
    /// `None`: no key is required (`auth.open = true`).
    pub key: Option<Arc<Vec<u8>>>,
    pub speech: Arc<SpeechEngine>,
    pub speech_model: String,
    pub upstreams: Arc<Upstreams>,
    pub limits: HttpLimits,
}
