//! Text model upstreams: OpenAI-compatible servers (normally `llama-server`) that Telltype Server
//! proxies `POST /v1/chat/completions` and `GET /v1/models` to.
//!
//! A request's `model` selects the upstream: first the configured aliases (`cleanup`, `assistant`),
//! then the model names the upstreams list in their own `/v1/models`. Bodies pass through byte for
//! byte unless an alias names a different upstream model, in which case only `model` is rewritten.
//! Streaming replies (`"stream": true`) pass through unchanged as they arrive.

mod chat;
mod error;
mod set;

pub use chat::{ChatReply, ReplyBody};
pub use error::UpstreamError;
pub use set::{ModelList, Probe, UpstreamSpec, Upstreams};

#[cfg(any(test, feature = "test-support"))]
pub mod testing;
