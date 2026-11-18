//! Typed errors of the upstream crate; the HTTP layer maps them to statuses and codes.

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum UpstreamError {
    /// The request itself is wrong (400).
    #[error("{0}")]
    BadRequest(String),
    /// Too many requests in flight for this upstream (429).
    #[error("{role} has {limit} requests in flight, limit {limit}")]
    Busy { role: String, limit: usize },
    /// Not reachable, or loading (503 with `Retry-After`).
    #[error("{0}")]
    NotReady(String),
    /// No answer within the configured timeout (504).
    #[error("{role} did not answer within {seconds} s")]
    Timeout { role: String, seconds: u64 },
    /// Any other failure of the upstream (502).
    #[error("{0}")]
    Backend(String),
    /// The HTTP client could not be built (at start only).
    #[error("cannot create the HTTP client: {0}")]
    Client(String),
}
