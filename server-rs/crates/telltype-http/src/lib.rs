//! The HTTP API of Telltype Server: auth, request ids, limits, error mapping, `Server-Timing`,
//! `/healthz`, the OpenAI-compatible routes, one log line per request, and the key and TLS material.
//!
//! The contract is `server-rs/docs/contract.md`.

pub mod credentials;
mod error;
pub mod keys;
pub mod limits;
mod middleware;
mod multipart;
mod routes;
mod state;

pub use error::ApiError;
pub use keys::{Identity, KeyStore};
pub use limits::{ClientPolicy, Guard};
pub use multipart::{MultipartError, Part, boundary, parse_parts};
pub use routes::router;
pub use state::{AppState, HttpLimits};

/// Server version reported by `/healthz` and the `Server` header.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
